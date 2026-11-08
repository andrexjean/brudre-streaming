package com.brudre.streaming.sources

import android.content.Context
import com.brudre.streaming.data.AppSettings
import com.brudre.streaming.data.CatalogException
import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.live.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Fontes cadastradas pelo usuário (Configurações → Gerenciar fontes), salvas no aparelho:
 * continuam após reiniciar e podem ser exportadas/importadas em JSON.
 */
class SourceStore(context: Context, settings: AppSettings) {
    private val prefs = context.getSharedPreferences("brudre_sources", Context.MODE_PRIVATE)
    private val _sources = MutableStateFlow(load())
    val sources: StateFlow<List<UserSource>> = _sources.asStateFlow()
    val current: List<UserSource> get() = _sources.value

    init {
        syncLocal(current)
        // Migração da 8.0: catálogos e listas M3U guardados nas configurações antigas.
        val s = settings.current
        if (s.feeds.isNotEmpty() || s.customLists.isNotEmpty()) {
            val migrated = s.feeds.map { it to SourceType.CATALOG_JSON } + s.customLists.map { it to SourceType.M3U }
            migrated.forEach { (entry, type) ->
                val url = entry.substringAfter('|').trim()
                val name = entry.substringBefore('|').trim().ifBlank { "Fonte" }
                if (current.none { it.url == url }) put(UserSource(UserSource.idFor(type, url), name, url, type, enabled = UserSource.idFor(type, url) !in s.disabledSources, priority = current.size))
            }
            settings.update { it.copy(feeds = emptyList(), customLists = emptyList()) }
        }
    }

    private fun load(): List<UserSource> = prefs.getString(KEY, null)?.let { UserSourcesJson.listFromJson(it) }.orEmpty().sortedBy { it.priority }

    @Synchronized
    private fun save(list: List<UserSource>) {
        val normalized = list.mapIndexed { i, s -> s.copy(priority = i) }
        prefs.edit().putString(KEY, UserSourcesJson.listToJson(normalized)).commit()
        _sources.value = normalized
        syncLocal(normalized)
    }

    /** Servidores locais confirmados passam a valer para o guarda de rede (e deixam de valer ao excluir a fonte). */
    private fun syncLocal(list: List<UserSource>) = LocalServers.sync(
        list.mapNotNull { s -> s.localPin?.let { pin -> LocalServers.keyOf(s.url)?.let { it to pin } } }.toMap(),
    )

    fun get(id: String) = current.firstOrNull { it.id == id }
    fun ofType(vararg types: SourceType) = current.filter { it.type in types }

    fun put(source: UserSource) {
        val list = current.toMutableList()
        val i = list.indexOfFirst { it.id == source.id }
        if (i >= 0) list[i] = source.copy(priority = list[i].priority) else {
            if (list.size >= UserSourcesJson.MAX_SOURCES) throw CatalogException("Limite de ${UserSourcesJson.MAX_SOURCES} fontes atingido")
            list += source
        }
        save(list.sortedBy { it.priority })
    }

    /** Troca a fonte [oldId] (edição de nome/endereço), mantendo a posição na prioridade. */
    fun replace(oldId: String, source: UserSource) {
        val list = current.toMutableList()
        val i = list.indexOfFirst { it.id == oldId }
        if (i < 0) { put(source); return }
        list.removeAll { it.id == source.id && it.id != oldId }
        val j = list.indexOfFirst { it.id == oldId }
        list[j] = source
        save(list)
    }

    fun remove(id: String) = save(current.filterNot { it.id == id })
    fun setEnabled(id: String, on: Boolean) = save(current.map { if (it.id == id) it.copy(enabled = on) else it })
    /** Grava o teste. NUNCA ativa a fonte sozinho: fonte salva sem validação exige [markValidated] + ação do usuário. */
    fun saveTest(id: String, test: SourceTest, config: String? = null) = save(current.map {
        if (it.id != id) it else it.copy(lastTest = test, config = config ?: it.config)
    })

    /**
     * Fonte salva sem validação passou na validação completa (formato redetectado igual, segurança e estrutura):
     * deixa de ser "pendente", mas continua DESATIVADA até o usuário ativar.
     */
    fun markValidated(id: String, test: SourceTest, config: String?) = save(current.map {
        if (it.id != id) it else it.copy(lastTest = test, pending = false, validatedAt = test.at, config = config ?: it.config)
    })

    /** Outra fonte com o mesmo endereço (normalizado), ou null. */
    fun duplicateOf(url: String, exceptId: String? = null): UserSource? {
        val n = UserSource.normalizedUrl(url)
        return current.firstOrNull { it.id != exceptId && UserSource.normalizedUrl(it.url) == n }
    }

    /** Ids das fontes que repetem o endereço de outra já cadastrada (mantém a primeira). */
    fun duplicates(): Set<String> = current.groupBy { UserSource.normalizedUrl(it.url) }.values.filter { it.size > 1 }.flatMap { it.drop(1) }.map { it.id }.toSet()

    /** Resultado real de uso (reprodução/pesquisa). Gravado sem bloquear: chamado de várias threads. */
    @Synchronized
    fun recordResult(id: String, ok: Boolean, error: String? = null, latencyMs: Int? = null) {
        if (current.none { it.id == id }) return
        save(current.map { if (it.id == id) it.copy(stats = it.stats.record(ok, error, latencyMs = latencyMs)) else it })
    }

    /** Sobe (delta = -1) ou desce (+1) na prioridade. */
    fun move(id: String, delta: Int) {
        val list = current.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        save(list)
    }

    fun export(): String = UserSourcesJson.export(current)

    /** Junta as fontes importadas às atuais (as já existentes são atualizadas). Devolve quantas entraram. */
    fun import(text: String): Int {
        val incoming = UserSourcesJson.import(text)
        val list = current.toMutableList()
        incoming.forEach { s -> val i = list.indexOfFirst { it.id == s.id }; if (i >= 0) list[i] = s.copy(lastTest = list[i].lastTest, stats = list[i].stats) else list += s }
        save(list.take(UserSourcesJson.MAX_SOURCES))
        return incoming.size
    }

    companion object { private const val KEY = "sources" }
}

/** Identifica o formato e testa a fonte (estrutura + conectividade) antes de salvar. */
class SourceInspector(
    guarded: OkHttpClient,
    private val feeds: () -> FeedRepository,
    private val live: () -> com.brudre.streaming.live.LiveRepository,
    private val validator: StreamValidator,
    private val search: () -> SearchRepository,
) {
    @Volatile private var key: String? = null
    @Volatile private var auth: AuthMethod = AuthMethod.NONE
    private val client = guarded.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    /** [config] = configuração resolvida no teste (modelo OpenSearch, descritor REST), gravada com a fonte. */
    data class Inspection(val type: SourceType?, val test: SourceTest, val suggestedName: String?, val config: String? = null)

    suspend fun inspect(url: String, forcedType: SourceType? = null, key: String? = null, auth: AuthMethod = AuthMethod.NONE): Inspection = withContext(Dispatchers.IO) {
        this@SourceInspector.key = key
        this@SourceInspector.auth = auth
        val u = url.trim()
        val now = System.currentTimeMillis()
        fun fail(kind: ErrorKind, msg: String, detail: String? = null) = Inspection(null, SourceTest(false, now, "${kind.title}: $msg", 0, kind.name, detail ?: msg), null)
        fun failWith(e: Throwable, type: SourceType? = null) = ErrorClassifier.classify(e).let { Inspection(type, SourceTest.failure(it, now), null) }
        if (u.isEmpty()) return@withContext fail(ErrorKind.FORMAT, "Informe um endereço")
        val type: SourceType
        if (forcedType != null) type = forcedType else {
            if (SearchTemplate.isTemplate(u)) {
                SearchTemplate.check(u.replace("{key}", "chave"))?.let { return@withContext fail(ErrorKind.BLOCKED, it) }
            } else if (!u.startsWith("magnet:", true)) {
                UrlGuard.check(u)?.let { return@withContext fail(ErrorKind.BLOCKED, it) }
                if (!u.startsWith("https://", true)) return@withContext fail(ErrorKind.BLOCKED, "Use um endereço https:// (conexões sem criptografia são bloqueadas)")
            }
            val det = try {
                if (u.startsWith("magnet:", true) || SearchTemplate.isTemplate(u)) SourceDetector.classify(u, null, ByteArray(0)) else head(u)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Indexadores costumam recusar o endereço "puro" (400/401/404); só respondem a t=caps.
                if (e is HttpStatusException && probeTorznab(u) != null) SourceDetector.Detection(SourceType.TORZNAB, "Torznab/Newznab")
                else return@withContext failWith(e)
            }
            val torznab = if (det.type == null && !u.startsWith("magnet:", true) && !SearchTemplate.isTemplate(u) && !det.reason.contains("executável", true)) probeTorznab(u) else null
            type = det.type ?: torznab ?: return@withContext fail(
                when {
                    u.startsWith("magnet:", true) -> ErrorKind.MAGNET
                    det.reason.contains("executável", true) -> ErrorKind.BLOCKED
                    det.reason.contains("JSON", true) -> ErrorKind.CATALOG
                    else -> ErrorKind.FORMAT
                },
                det.reason,
            )
        }
        try {
            test(u, type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failWith(e, type)
        }
    }

    /** Endereço que não se identificou sozinho: é um endpoint Torznab/Newznab? (responde só com t=caps) */
    private suspend fun probeTorznab(url: String): SourceType? = Torznab.candidates(url).firstNotNullOfOrNull { c ->
        // Credencial recusada também identifica o protocolo (o teste mostra a mensagem do indexador).
        runCatching { Torznab.parseCaps(search().fetch(Torznab.capsUrl(c, key)), c); SourceType.TORZNAB }
            .getOrElse { e -> if (e is ClassifiedException && e.kind == ErrorKind.HTTP && e.message?.contains("Credencial") == true) SourceType.TORZNAB else null }
    }

    private fun head(url: String): SourceDetector.Detection {
        val req = Request.Builder().url(url).header("User-Agent", CatalogRepository.USER_AGENT).header("Range", "bytes=0-65535").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful && r.code != 206) throw HttpStatusException(r.code)
            val src = r.body?.source() ?: throw IOException("resposta vazia")
            src.request(65536)
            val bytes = src.buffer.snapshot().toByteArray().copyOf(minOf(65536, src.buffer.size.toInt()))
            return SourceDetector.classify(r.request.url.toString(), r.header("Content-Type"), bytes)
        }
    }

    private suspend fun test(url: String, type: SourceType): Inspection {
        val now = System.currentTimeMillis()
        return when (type) {
            SourceType.OPENSEARCH -> {
                val os = OpenSearch.parseDescription(fetchText(url))
                val adapter = OpenSearchAdapter(os)
                val sample = SearchTemplate.Query("big buck bunny", null, "2008", "movie", 1)
                val pg = adapter.parse(search().fetch(adapter.url(sample, 1)!!), "teste")
                Inspection(type, SourceTest(true, now, "OpenSearch (${os.responseType.substringAfter('/')}) respondeu: ${pg.items.size} resultado(s) para \"${sample.text}\"", pg.items.size), os.name, os.toConfig())
            }
            SourceType.REST -> {
                val text = fetchText(url)
                val d = RestDescriptor.parse(text)
                if (d.needsKey && key.isNullOrEmpty()) throw ClassifiedException(ErrorKind.HTTP, "Esta API exige uma chave: informe em \"Credencial\" (fica cifrada no aparelho)")
                val adapter = RestAdapter(d, key)
                val sample = SearchTemplate.Query(d.sampleQuery ?: "big buck bunny", null, null, "movie", 1)
                val pg = adapter.parse(search().fetch(adapter.url(sample, 1)!!, adapter.headers + listOfNotNull(auth.header(key)).toMap(), adapter.body(sample, 1)), "teste")
                Inspection(type, SourceTest(true, now, "API REST respondeu: ${pg.items.size} resultado(s) reproduzíveis para \"${sample.text}\"", pg.items.size), d.name, text.take(UserSource.MAX_CONFIG))
            }
            SourceType.TORZNAB -> {
                // 1) capacidades (no endereço digitado ou em …/api); 2) pesquisa real com a amostra; 3) segunda página
                var lastError: Exception? = null
                val caps = Torznab.candidates(url).firstNotNullOfOrNull { c ->
                    UrlGuard.check(c)?.let { throw BlockedUrlException(it) }
                    try { Torznab.parseCaps(search().fetch(Torznab.capsUrl(c, key)), c) } catch (e: ClassifiedException) { if (e.kind == ErrorKind.HTTP) throw e; lastError = e; null } catch (e: Exception) { lastError = e; null }
                } ?: throw (lastError ?: ClassifiedException(ErrorKind.FORMAT, "Não respondeu às capacidades Torznab/Newznab"))
                val adapter = TorznabAdapter(caps, key)
                val sample = SearchTemplate.Query("night of the living dead", null, "1968", "movie", 1)
                val pg = adapter.parse(search().fetch(adapter.url(sample, 1)), "teste")
                Inspection(type, SourceTest(true, now, "${caps.summary}. Pesquisa de teste: ${pg.items.size} resultado(s) reproduzíveis (magnet/.torrent)" + if (pg.hasMore) ", há mais páginas" else "", pg.items.size), caps.server ?: UrlGuard.hostOf(caps.base), caps.toConfig())
            }
            SourceType.SEARCH -> {
                SearchTemplate.check(url)?.let { throw BlockedUrlException(it) }
                val sample = SearchTemplate.Query("big buck bunny", "movie:10378", "2008", "movie", 1)
                val text = search().fetch(SearchTemplate.expand(url, sample).replace("{key}", java.net.URLEncoder.encode(key ?: "", "UTF-8")), listOfNotNull(auth.header(key)).toMap())
                val feed = SearchRepository.parse(text, "teste")
                Inspection(type, SourceTest(true, now, "API respondeu: ${feed.items.size} resultado(s) para \"${sample.text}\"", feed.items.size), UrlGuard.hostOf(url.substringBefore('{')))
            }
            SourceType.CATALOG_JSON, SourceType.RSS -> {
                val feed = feeds().download(url, M3uParser.stableId("feed", url))
                if (feed.items.isEmpty()) Inspection(type, SourceTest(false, now, "${ErrorKind.CATALOG.title}: nenhum título com fonte válida (HTTPS, HLS, magnet ou .torrent)", 0, ErrorKind.CATALOG.name), feed.name)
                else Inspection(type, SourceTest(true, now, "${feed.items.size} títulos" + if (feed.rejected > 0) " (${feed.rejected} descartados)" else "", feed.items.size), feed.name)
            }
            SourceType.M3U -> {
                val r = try { live().fetch(url, "teste") } catch (e: IllegalArgumentException) { throw IOException(e.message) }
                if (r.channels.isEmpty()) Inspection(type, SourceTest(false, now, "${ErrorKind.CATALOG.title}: nenhum canal HLS seguro (${r.total} analisados)", 0, ErrorKind.CATALOG.name), null)
                else {
                    // Amostra de sinais: testa até 5 canais para mostrar se a lista está viva.
                    val sample = r.channels.take(5)
                    val alive = sample.count { live().health.probe(it.url, it.headers) == null }
                    Inspection(type, SourceTest(alive > 0, now, "${r.channels.size} canais • $alive de ${sample.size} testados no ar", r.channels.size), null)
                }
            }
            SourceType.HLS -> {
                try {
                    val why = live().health.probeOrThrow(url, emptyMap())
                    if (why == null) Inspection(type, SourceTest(true, now, "Transmissão no ar", 1), null)
                    else Inspection(type, SourceTest(false, now, "${ErrorKind.FORMAT.title}: $why", 0, ErrorKind.FORMAT.name, why), null)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Inspection(type, SourceTest.failure(ErrorClassifier.classify(e), now), null)
                }
            }
            SourceType.VIDEO -> {
                head(url) // classifica DNS, HTTP, TLS etc. com o erro original
                Inspection(type, SourceTest(true, now, "Vídeo responde", 1), url.substringBefore('?').substringAfterLast('/'))
            }
            SourceType.MAGNET -> {
                val ok = FeedParser.isValidMagnet(url)
                val dn = Regex("[?&]dn=([^&]+)").find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                if (!ok) Inspection(type, SourceTest(false, now, "${ErrorKind.MAGNET.title}: ${ErrorKind.MAGNET.hint}", 0, ErrorKind.MAGNET.name), dn)
                else Inspection(type, SourceTest(true, now, "Magnet válido (arquivos listados ao abrir, via DHT/trackers)", 1), dn)
            }
            SourceType.TORRENT -> {
                // Baixa e lê o .torrent de verdade: confere a estrutura e se há vídeo.
                val bytes = fetchBytes(url, com.brudre.streaming.torrent.TorrentContent.MAX_TORRENT_BYTES.toLong())
                val meta = com.brudre.streaming.torrent.TorrentContent.parse(bytes)
                val videos = meta.videos
                if (videos.isEmpty()) Inspection(type, SourceTest(false, now, "${ErrorKind.NO_VIDEO.title}: nenhum vídeo entre ${meta.files.size} arquivos", 0, ErrorKind.NO_VIDEO.name), meta.name)
                else Inspection(type, SourceTest(true, now, ".torrent válido: ${videos.size} vídeo(s), ${com.brudre.streaming.torrent.CacheManager.formatBytes(meta.totalSize)}", videos.size), meta.name)
            }
        }
    }

    /** Arquivo de exportação: pasta do app no armazenamento (acessível por gerenciadores de arquivos). */
    fun exportFile(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "brudre-fontes.json")

    fun fetchBytes(url: String, max: Long): ByteArray {
        UrlGuard.check(url)?.let { throw BlockedUrlException(it) }
        client.newCall(Request.Builder().url(url).header("User-Agent", CatalogRepository.USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code)
            val body = r.body ?: throw ClassifiedException(ErrorKind.FORMAT, "Resposta vazia")
            if (body.contentLength() > max) throw ClassifiedException(ErrorKind.FORMAT, "Arquivo grande demais")
            val src = body.source()
            src.request(max + 1)
            if (src.buffer.size > max) throw ClassifiedException(ErrorKind.FORMAT, "Arquivo grande demais")
            return src.buffer.readByteArray()
        }
    }

    fun fetchText(url: String): String {
        UrlGuard.check(url)?.let { throw CatalogException(it) }
        client.newCall(Request.Builder().url(url).header("User-Agent", CatalogRepository.USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("servidor respondeu ${r.code}")
            val src = r.body?.source() ?: throw IOException("vazio")
            src.request(512L * 1024)
            return src.buffer.snapshot().utf8().take(512 * 1024)
        }
    }
}
