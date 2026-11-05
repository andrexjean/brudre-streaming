package com.brudre.streaming.sources

import android.util.Log
import com.brudre.streaming.data.AppSettings
import com.brudre.streaming.data.CatalogException
import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TitleDetails
import com.brudre.streaming.live.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Catálogo externo cadastrado pelo usuário. */
data class FeedInfo(val id: String, val name: String, val url: String)

/**
 * Fontes externas de vídeo (comunitárias, independentes ou do próprio usuário) com HTTP, HLS,
 * magnet ou .torrent. Cada catálogo é um provedor do Source Manager: pode ser ativado, desativado
 * ou removido sem mexer no resto do app. Cache em disco de 6 h; última versão usada se a rede cair.
 */
class FeedRepository(
    private val guardedHttp: OkHttpClient,
    private val settings: AppSettings,
    private val board: StatusBoard,
    private val dir: File,
) {
    private val memory = ConcurrentHashMap<String, List<com.brudre.streaming.sources.FeedItem>>()

    init { refreshRegistry() }

    fun feeds(): List<FeedInfo> = settings.current.feeds.mapNotNull { e ->
        val url = e.substringAfter('|').trim()
        if (!UrlGuard.isAllowed(url)) null else FeedInfo(PREFIX + M3uParser.stableId("feed", url), e.substringBefore('|').trim().ifBlank { "Fonte externa" }, url)
    }

    fun isEnabled(id: String) = id !in settings.current.disabledSources

    fun enabledFeeds() = feeds().filter { isEnabled(it.id) }

    fun refreshRegistry() {
        val list = feeds()
        list.forEach { f -> board.register(f.id, "Catálogo: ${f.name}", f.url, isEnabled(f.id), removable = true) }
        board.all.value.filter { it.id.startsWith(PREFIX) && list.none { f -> f.id == it.id } }.forEach { board.unregister(it.id) }
    }

    suspend fun items(feedId: String, force: Boolean = false): List<FeedItem> = withContext(Dispatchers.IO) {
        val f = feeds().firstOrNull { it.id == feedId } ?: throw CatalogException("Catálogo não encontrado")
        if (!isEnabled(f.id)) throw CatalogException("Catálogo desativado em Configurações")
        if (!force) memory[f.id]?.let { return@withContext it }
        val file = File(dir.apply { mkdirs() }, "${f.id}.json")
        if (!force && file.exists() && System.currentTimeMillis() - file.lastModified() < TTL_MS) {
            runCatching { FeedParser.itemsFromJson(file.readText()) }.getOrNull()?.let { memory[f.id] = it; return@withContext it }
        }
        val t0 = System.currentTimeMillis()
        try {
            val feed = download(f.url, f.id)
            board.success(f.id, System.currentTimeMillis() - t0)
            memory[f.id] = feed.items
            runCatching { file.writeText(FeedParser.itemsToJson(feed.items)) }
            Log.i(TAG, "Catálogo ${f.name}: ${feed.items.size} títulos (${feed.rejected} descartados)")
            feed.items
        } catch (e: Exception) {
            board.failure(f.id, e.message ?: "falha")
            runCatching { FeedParser.itemsFromJson(file.readText()) }.getOrNull()?.let { memory[f.id] = it; return@withContext it }
            throw if (e is CatalogException) e else CatalogException(e.message ?: "Falha ao ler o catálogo", e)
        }
    }

    fun download(url: String, feedId: String): Feed {
        UrlGuard.check(url)?.let { throw CatalogException(it) }
        val req = Request.Builder().url(url).header("User-Agent", CatalogRepository.USER_AGENT).build()
        guardedHttp.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Servidor do catálogo respondeu ${r.code}")
            val body = r.body ?: throw IOException("Catálogo vazio")
            if (body.contentLength() > MAX_BYTES) throw CatalogException("Catálogo grande demais para o aparelho")
            val src = body.source()
            src.request(MAX_BYTES)
            val text = src.buffer.snapshot().utf8().take(MAX_BYTES.toInt())
            return try { FeedParser.parse(text, feedId.removePrefix(PREFIX)) } catch (e: IllegalArgumentException) {
                throw CatalogException(e.message ?: "Formato inválido")
            } catch (e: org.json.JSONException) {
                throw CatalogException("JSON inválido no catálogo")
            }
        }
    }

    /** Confere o catálogo antes de salvar: endereço seguro e ao menos um título com fonte válida. */
    suspend fun add(name: String, url: String): Int = withContext(Dispatchers.IO) {
        val clean = url.trim()
        val feed = download(clean, M3uParser.stableId("feed", clean))
        if (feed.items.isEmpty()) throw CatalogException("Nenhum título com fonte válida (HTTPS, HLS, magnet ou .torrent) nesse catálogo")
        val label = name.replace('|', ' ').trim().ifBlank { feed.name }
        settings.update { s -> s.copy(feeds = (s.feeds.filterNot { it.substringAfter('|').trim() == clean } + "$label|$clean").takeLast(MAX_FEEDS)) }
        memory.remove(PREFIX + M3uParser.stableId("feed", clean))
        refreshRegistry()
        feed.items.size
    }

    fun remove(id: String) {
        val f = feeds().firstOrNull { it.id == id } ?: return
        settings.update { s -> s.copy(feeds = s.feeds.filterNot { it.substringAfter('|').trim() == f.url }, disabledSources = s.disabledSources - id) }
        memory.remove(id)
        File(dir, "$id.json").delete()
        refreshRegistry()
    }

    fun setEnabled(id: String, on: Boolean) {
        settings.update { s -> s.copy(disabledSources = if (on) s.disabledSources - id else s.disabledSources + id) }
        refreshRegistry()
    }

    fun find(titleId: String): FeedItem? = memory.values.asSequence().flatten().firstOrNull { it.title.id == titleId }

    fun feedIdOf(titleId: String): String? = titleId.removePrefix(FeedParser.PREFIX).substringBefore(':').let { PREFIX + it }.takeIf { titleId.startsWith(FeedParser.PREFIX) }

    fun clearCache() { memory.clear(); dir.listFiles()?.forEach { it.delete() } }

    companion object {
        private const val TAG = "BruDreSources"
        /** Id de provedor do catálogo (também usado no id dos títulos: "feed:<hash>:<item>"). */
        const val PREFIX = "feed_"
        private const val TTL_MS = 6 * 60 * 60 * 1000L
        private const val MAX_BYTES = 2L * 1024 * 1024
        private const val MAX_FEEDS = 10
    }
}

/** Provedor do Source Manager que reúne todos os catálogos externos ativos. */
class FeedProvider(private val repo: FeedRepository) : ContentProvider {
    override val id = "feeds"
    override val name = "Catálogos externos"
    override val description = "Fontes cadastradas por você (HTTP, HLS, magnet e .torrent autorizados)"
    override val capabilities = setOf(Capability.CATALOG, Capability.DIRECT, Capability.HLS, Capability.TORRENT)

    override fun handles(title: Title) = title.id.startsWith(FeedParser.PREFIX)

    override suspend fun search(query: String): List<Title> {
        val words = M3uParser.normalizeName(query).split(' ').filter { it.length > 1 }
        if (words.isEmpty()) return emptyList()
        return repo.enabledFeeds().flatMap { f -> runCatching { repo.items(f.id) }.getOrDefault(emptyList()) }
            .map { it.title }
            .filter { t -> val n = M3uParser.normalizeName(t.name + " " + (t.synopsis ?: "")); words.all { n.contains(it) } }
    }

    override suspend fun getDetails(title: Title): TitleDetails {
        val feedId = repo.feedIdOf(title.id) ?: throw CatalogException("Título inválido")
        val item = repo.find(title.id) ?: repo.items(feedId).firstOrNull { it.title.id == title.id }
            ?: throw CatalogException("Título não está mais no catálogo")
        val playables = item.playables()
        val kinds = item.sources.map { it.type }.toSet() + item.episodes.flatMap { e -> e.sources.map { it.type } }
        val label = buildString {
            append("Catálogo externo")
            if (FeedSource.Type.HTTP in kinds) append(" • HTTP")
            if (FeedSource.Type.HLS in kinds) append(" • HLS")
            if (FeedSource.Type.MAGNET in kinds || FeedSource.Type.TORRENT in kinds) append(" • BitTorrent (seu IP fica visível aos outros participantes)")
        }
        return TitleDetails(item.title, playables, label, providerId = feedId)
    }
}
