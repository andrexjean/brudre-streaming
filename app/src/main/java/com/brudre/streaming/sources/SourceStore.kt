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
    fun saveTest(id: String, test: SourceTest) = save(current.map { if (it.id == id) it.copy(lastTest = test) else it })

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
        incoming.forEach { s -> val i = list.indexOfFirst { it.id == s.id }; if (i >= 0) list[i] = s.copy(lastTest = list[i].lastTest) else list += s }
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
) {
    private val client = guarded.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    data class Inspection(val type: SourceType?, val test: SourceTest, val suggestedName: String?)

    suspend fun inspect(url: String, forcedType: SourceType? = null): Inspection = withContext(Dispatchers.IO) {
        val u = url.trim()
        val now = System.currentTimeMillis()
        fun fail(msg: String) = Inspection(null, SourceTest(false, now, msg), null)
        if (u.isEmpty()) return@withContext fail("Informe um endereço")
        val type: SourceType
        if (forcedType != null) type = forcedType else {
            if (!u.startsWith("magnet:", true)) {
                UrlGuard.check(u)?.let { return@withContext fail(it) }
                if (!u.startsWith("https://", true)) return@withContext fail("Use um endereço https:// (conexões sem criptografia são bloqueadas)")
            }
            val det = try {
                if (u.startsWith("magnet:", true)) SourceDetector.classify(u, null, ByteArray(0)) else head(u)
            } catch (e: BlockedUrlException) {
                return@withContext fail(e.message ?: "Endereço bloqueado")
            } catch (e: IOException) {
                return@withContext fail("Sem conexão com a fonte: ${e.message ?: "falha de rede"}")
            }
            type = det.type ?: return@withContext fail(det.reason)
        }
        try {
            test(u, type)
        } catch (e: BlockedUrlException) {
            fail(e.message ?: "Endereço bloqueado")
        } catch (e: IOException) {
            Inspection(type, SourceTest(false, now, e.message ?: "falha de rede"), null)
        } catch (e: Exception) {
            Inspection(type, SourceTest(false, now, e.message ?: e.javaClass.simpleName), null)
        }
    }

    private fun head(url: String): SourceDetector.Detection {
        val req = Request.Builder().url(url).header("User-Agent", CatalogRepository.USER_AGENT).header("Range", "bytes=0-65535").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful && r.code != 206) throw IOException("servidor respondeu ${r.code}")
            val src = r.body?.source() ?: throw IOException("resposta vazia")
            src.request(65536)
            val bytes = src.buffer.snapshot().toByteArray().copyOf(minOf(65536, src.buffer.size.toInt()))
            return SourceDetector.classify(r.request.url.toString(), r.header("Content-Type"), bytes)
        }
    }

    private suspend fun test(url: String, type: SourceType): Inspection {
        val now = System.currentTimeMillis()
        return when (type) {
            SourceType.CATALOG_JSON, SourceType.RSS -> {
                val feed = feeds().download(url, M3uParser.stableId("feed", url))
                if (feed.items.isEmpty()) Inspection(type, SourceTest(false, now, "Nenhum título com fonte válida (HTTPS, HLS, magnet ou .torrent)"), feed.name)
                else Inspection(type, SourceTest(true, now, "${feed.items.size} títulos" + if (feed.rejected > 0) " (${feed.rejected} descartados)" else "", feed.items.size), feed.name)
            }
            SourceType.M3U -> {
                val r = try { live().fetch(url, "teste") } catch (e: IllegalArgumentException) { throw IOException(e.message) }
                if (r.channels.isEmpty()) Inspection(type, SourceTest(false, now, "Nenhum canal HLS seguro (${r.total} analisados)"), null)
                else {
                    // Amostra de sinais: testa até 5 canais para mostrar se a lista está viva.
                    val sample = r.channels.take(5)
                    val alive = sample.count { live().health.probe(it.url, it.headers) == null }
                    Inspection(type, SourceTest(alive > 0, now, "${r.channels.size} canais • $alive de ${sample.size} testados no ar", r.channels.size), null)
                }
            }
            SourceType.HLS -> {
                val why = live().health.probe(url, emptyMap())
                Inspection(type, SourceTest(why == null, now, why ?: "Transmissão no ar", 1), null)
            }
            SourceType.VIDEO -> {
                val r = validator.check(url, emptyMap())
                Inspection(type, SourceTest(r == StreamValidator.Result.OK, now, if (r == StreamValidator.Result.OK) "Vídeo responde" else "Vídeo não respondeu ($r)", 1), url.substringBefore('?').substringAfterLast('/'))
            }
            SourceType.MAGNET -> {
                val ok = FeedParser.isValidMagnet(url)
                val dn = Regex("[?&]dn=([^&]+)").find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                Inspection(type, SourceTest(ok, now, if (ok) "Magnet válido (metadados obtidos ao tocar, via DHT/trackers)" else "Magnet inválido", 1), dn)
            }
            SourceType.TORRENT -> {
                val ok = validator.check(url, emptyMap()) == StreamValidator.Result.OK
                Inspection(type, SourceTest(ok, now, if (ok) "Arquivo .torrent disponível" else "Arquivo .torrent não respondeu", 1), url.substringBefore('?').substringAfterLast('/').removeSuffix(".torrent"))
            }
        }
    }

    /** Arquivo de exportação: pasta do app no armazenamento (acessível por gerenciadores de arquivos). */
    fun exportFile(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "brudre-fontes.json")

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
