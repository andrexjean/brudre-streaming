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
    }

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
    /** Grava o teste. Fonte salva sem validação é ativada automaticamente no primeiro teste bem-sucedido. */
    fun saveTest(id: String, test: SourceTest) = save(current.map {
        if (it.id != id) it
        else if (it.pending && test.ok) it.copy(lastTest = test, pending = false, enabled = true)
        else it.copy(lastTest = test)
    })

    /** Resultado real de uso (reprodução/pesquisa). Gravado sem bloquear: chamado de várias threads. */
    @Synchronized
    fun recordResult(id: String, ok: Boolean, error: String? = null) {
        if (current.none { it.id == id }) return
        save(current.map { if (it.id == id) it.copy(stats = it.stats.record(ok, error)) else it })
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
    private val client = guarded.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    data class Inspection(val type: SourceType?, val test: SourceTest, val suggestedName: String?)

    suspend fun inspect(url: String, forcedType: SourceType? = null): Inspection = withContext(Dispatchers.IO) {
        val u = url.trim()
        val now = System.currentTimeMillis()
        fun fail(kind: ErrorKind, msg: String, detail: String? = null) = Inspection(null, SourceTest(false, now, "${kind.title}: $msg", 0, kind.name, detail ?: msg), null)
        fun failWith(e: Throwable, type: SourceType? = null) = ErrorClassifier.classify(e).let { Inspection(type, SourceTest.failure(it, now), null) }
        if (u.isEmpty()) return@withContext fail(ErrorKind.FORMAT, "Informe um endereço")
        val type: SourceType
        if (forcedType != null) type = forcedType else {
            if (SearchTemplate.isTemplate(u)) {
                SearchTemplate.check(u)?.let { return@withContext fail(ErrorKind.BLOCKED, it) }
            } else if (!u.startsWith("magnet:", true)) {
                UrlGuard.check(u)?.let { return@withContext fail(ErrorKind.BLOCKED, it) }
                if (!u.startsWith("https://", true)) return@withContext fail(ErrorKind.BLOCKED, "Use um endereço https:// (conexões sem criptografia são bloqueadas)")
            }
            val det = try {
                if (u.startsWith("magnet:", true) || SearchTemplate.isTemplate(u)) SourceDetector.classify(u, null, ByteArray(0)) else head(u)
            } catch (e: Exception) {
                return@withContext failWith(e)
            }
            type = det.type ?: return@withContext fail(
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
            SourceType.SEARCH -> {
                SearchTemplate.check(url)?.let { throw BlockedUrlException(it) }
                val sample = SearchTemplate.Query("big buck bunny", "movie:10378", "2008", "movie", 1)
                val text = search().fetch(SearchTemplate.expand(url, sample))
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
