package com.brudre.streaming.sources

import android.util.Log
import com.brudre.streaming.data.AppSettings
import com.brudre.streaming.data.CatalogException
import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TitleDetails
import com.brudre.streaming.live.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    private val store: SourceStore,
) {
    /** Até [MEMORY_FEEDS] catálogos em memória (LRU); o resto fica só em disco (Mi Stick: 1 GB de RAM). */
    private val memory = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, List<FeedItem>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FeedItem>>?) = size > MEMORY_FEEDS
    })

    init { refreshRegistry() }

    /** Catálogos cadastrados (JSON/RSS) + o catálogo virtual "Minhas fontes" com os links únicos, na ordem de prioridade. */
    fun feeds(): List<FeedInfo> {
        val catalogs = store.current.filter { it.type.isCatalog && UrlGuard.isAllowed(it.url) }.map { FeedInfo(it.id, it.name, it.url) }
        val links = if (store.current.any { it.type.isSingle }) listOf(FeedInfo(LINKS_ID, "Minhas fontes (links)", "")) else emptyList()
        return links + catalogs
    }

    fun isEnabled(id: String) = if (id == LINKS_ID) store.current.any { it.type.isSingle && it.enabled } else store.get(id)?.enabled == true

    fun enabledFeeds() = feeds().filter { isEnabled(it.id) }

    /** Links únicos (magnet, .torrent, vídeo, HLS) viram títulos do catálogo virtual. */
    private fun linkItems(): List<FeedItem> = store.current.filter { it.type.isSingle && it.enabled }.mapNotNull { s ->
        val t = when (s.type) {
            SourceType.VIDEO -> FeedSource.Type.HTTP
            SourceType.HLS -> FeedSource.Type.HLS
            SourceType.MAGNET -> FeedSource.Type.MAGNET
            else -> FeedSource.Type.TORRENT
        }
        val src = if (t == FeedSource.Type.MAGNET) FeedSource(t, s.url).takeIf { FeedParser.isValidMagnet(s.url) } else FeedParser.source(t.name, s.url)
        src?.let {
            FeedItem(
                com.brudre.streaming.data.Title(
                    id = "${FeedParser.PREFIX}links:${s.id}", kind = com.brudre.streaming.data.Kind.MOVIE, name = s.name,
                    synopsis = "${s.type.label} cadastrado em Gerenciar fontes.", license = "Fonte cadastrada por você",
                ),
                listOf(it),
            )
        }
    }

    fun refreshRegistry() {
        val list = feeds()
        list.filter { it.id != LINKS_ID }.forEach { f -> board.register(f.id, "Catálogo: ${f.name}", f.url, isEnabled(f.id), removable = true) }
        board.all.value.filter { it.id.startsWith(PREFIX) && list.none { f -> f.id == it.id } }.forEach { board.unregister(it.id) }
    }

    suspend fun items(feedId: String, force: Boolean = false): List<FeedItem> = withContext(Dispatchers.IO) {
        if (feedId == LINKS_ID) return@withContext linkItems().also { memory[LINKS_ID] = it }
        val f = feeds().firstOrNull { it.id == feedId } ?: throw CatalogException("Catálogo não encontrado")
        if (!isEnabled(f.id)) throw CatalogException("Catálogo desativado em Gerenciar fontes")
        if (!force) memory[f.id]?.let { return@withContext it }
        val file = File(dir.apply { mkdirs() }, "${f.id}.json")
        if (!force && file.exists() && System.currentTimeMillis() - file.lastModified() < TTL_MS) {
            runCatching { FeedParser.itemsFromJson(file.readText()) }.getOrNull()?.let {
                memory[f.id] = it
                if (!indexFile(f.id).exists()) runCatching { indexFile(f.id).writeText(FeedParser.indexOf(it)) }
                return@withContext it
            }
        }
        val t0 = System.currentTimeMillis()
        try {
            val feed = download(f.url, f.id)
            saveNext(f.id, feed.next)
            board.success(f.id, System.currentTimeMillis() - t0)
            memory[f.id] = feed.items
            runCatching { file.writeText(com.brudre.streaming.sources.Secrets.protectKnown(FeedParser.itemsToJson(feed.items))); indexFile(f.id).writeText(FeedParser.indexOf(feed.items)) }
            Log.i(TAG, "Catálogo ${f.name}: ${feed.items.size} títulos (${feed.rejected} descartados)")
            feed.items
        } catch (e: Exception) {
            board.failure(f.id, e.message ?: "falha")
            runCatching { FeedParser.itemsFromJson(file.readText()) }.getOrNull()?.let { memory[f.id] = it; return@withContext it }
            throw if (e is CatalogException) e else CatalogException(e.message ?: "Falha ao ler o catálogo", e)
        }
    }

    /** Próxima página de cada catálogo paginado ("next" no JSON, rel="next" no RSS/Atom). */
    private val nextPages = ConcurrentHashMap<String, String>()
    fun hasMore(id: String) = nextPages.containsKey(id) || File(dir, "$id.next").exists()

    private fun nextOf(id: String): String? = nextPages[id] ?: runCatching { File(dir, "$id.next").readText().trim() }.getOrNull()?.takeIf { it.startsWith("https://") }

    private fun saveNext(id: String, next: String?) {
        val f = File(dir.apply { mkdirs() }, "$id.next")
        if (next == null) { nextPages.remove(id); f.delete() } else { nextPages[id] = next; runCatching { f.writeText(next) } }
    }

    /**
     * Carrega a PRÓXIMA página de um catálogo paginado (sob demanda, sem baixar tudo de uma vez).
     * Até [MAX_TOTAL] títulos por catálogo; páginas repetidas e títulos repetidos são ignorados.
     */
    suspend fun loadMore(feedId: String): List<FeedItem> = withContext(Dispatchers.IO) {
        val current = items(feedId)
        val next = nextOf(feedId) ?: return@withContext current
        if (current.size >= MAX_TOTAL) { saveNext(feedId, null); return@withContext current }
        val page = download(next, feedId)
        val merged = (current + page.items).distinctBy { it.title.id }.take(MAX_TOTAL)
        memory[feedId] = merged
        saveNext(feedId, page.next?.takeIf { it != next && merged.size > current.size })
        runCatching { File(dir, "$feedId.json").writeText(com.brudre.streaming.sources.Secrets.protectKnown(FeedParser.itemsToJson(merged))); indexFile(feedId).writeText(FeedParser.indexOf(merged)) }
        Log.i(TAG, "Catálogo $feedId: +${merged.size - current.size} títulos (página seguinte)")
        merged
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
        val type = if (clean.substringBefore('?').lowercase().let { it.endsWith(".rss") || it.endsWith(".xml") }) SourceType.RSS else SourceType.CATALOG_JSON
        val id = UserSource.idFor(type, clean)
        store.put(UserSource(id, name.trim().ifBlank { feed.name }.take(60), clean, type, priority = store.current.size,
            lastTest = SourceTest(true, System.currentTimeMillis(), "${feed.items.size} títulos", feed.items.size)))
        invalidate(id)
        refreshRegistry()
        feed.items.size
    }

    /** Descarta o cache de um catálogo (após editar, atualizar ou excluir a fonte). */
    fun invalidate(id: String) {
        memory.remove(id)
        memory.remove(LINKS_ID)
        File(dir, "$id.json").delete()
        indexFile(id).delete()
        File(dir, "$id.next").delete(); nextPages.remove(id)
    }

    private fun indexFile(id: String) = File(dir.apply { mkdirs() }, "$id.idx")

    fun remove(id: String) {
        store.remove(id)
        invalidate(id)
        refreshRegistry()
    }

    fun setEnabled(id: String, on: Boolean) {
        store.setEnabled(id, on)
        invalidate(id)
        refreshRegistry()
    }

    /**
     * Itens das fontes ativas que correspondem à obra, na ordem de prioridade das fontes.
     * 1º pelo identificador TMDB (exato); 2º por nome original/alternativo + ano (±1).
     * Lê só o índice de cada catálogo; o catálogo é aberto apenas se houver correspondência.
     */
    suspend fun matches(names: List<String>, year: String?, tmdbId: String? = null, series: Boolean? = null): List<Pair<FeedInfo, FeedItem>> = withContext(Dispatchers.IO) {
        if (names.isEmpty() && tmdbId == null) return@withContext emptyList()
        val out = ArrayList<Pair<FeedInfo, FeedItem>>()
        for (f in enabledFeeds()) {
            ensureActive()
            if (f.id == LINKS_ID) {
                linkItems().filter { matchesItem(it, names, year, tmdbId, series) }.forEach { out += f to it }
                continue
            }
            val idx = indexFile(f.id)
            if (!idx.exists() || System.currentTimeMillis() - idx.lastModified() > TTL_MS) runCatching { items(f.id) }
            val hits = runCatching {
                idx.useLines { lines -> lines.mapNotNull { FeedParser.parseIndexLine(it) }.filter { e -> indexMatches(e, names, year, tmdbId, series) }.map { it.position }.toList() }
            }.getOrDefault(emptyList())
            if (hits.isEmpty()) continue
            val items = runCatching { items(f.id) }.getOrDefault(emptyList())
            hits.mapNotNull { items.getOrNull(it) }.filter { matchesItem(it, names, year, tmdbId, series) }.forEach { out += f to it }
        }
        out
    }

    private fun indexMatches(e: FeedParser.IndexEntry, names: List<String>, year: String?, tmdbId: String?, series: Boolean?): Boolean {
        if (tmdbId != null && e.tmdbId != null) return e.tmdbId == tmdbId
        // Filme nunca casa com série (e vice-versa), mesmo com nome igual.
        if (series != null && e.series != series) return false
        return TitleMatcher.matches(com.brudre.streaming.data.Title("x", com.brudre.streaming.data.Kind.MOVIE, e.name, e.year), names, year)
    }

    private fun matchesItem(it: FeedItem, names: List<String>, year: String?, tmdbId: String?, series: Boolean? = null): Boolean =
        TitleMatcher.matchesItem(it, names, year, tmdbId, series)


    fun find(titleId: String): FeedItem? = synchronized(memory) { memory.values.toList() }.asSequence().flatten().firstOrNull { it.title.id == titleId }

    fun feedIdOf(titleId: String): String? = titleId.removePrefix(FeedParser.PREFIX).substringBefore(':').let { PREFIX + it }.takeIf { titleId.startsWith(FeedParser.PREFIX) }

    fun clearCache() { memory.clear(); dir.listFiles()?.forEach { it.delete() } }

    companion object {
        private const val TAG = "BruDreSources"
        /** Id de provedor do catálogo (também usado no id dos títulos: "feed:<hash>:<item>"). */
        const val PREFIX = "feed_"
        /** Catálogo virtual com os links únicos cadastrados. */
        const val LINKS_ID = "feed_links"
        private const val TTL_MS = 6 * 60 * 60 * 1000L
        private const val MAX_BYTES = 2L * 1024 * 1024
        private const val MEMORY_FEEDS = 4
        /** Limite de títulos por catálogo paginado (memória do Mi Stick). */
        const val MAX_TOTAL = 1500
    }
}

/** Provedor do Source Manager que reúne todos os catálogos externos ativos. */
class FeedProvider(private val repo: FeedRepository, private val search: SearchRepository? = null) : ContentProvider {
    override val id = "feeds"
    override val name = "Catálogos externos"
    override val description = "Fontes cadastradas por você (HTTP, HLS, magnet e .torrent autorizados)"
    override val capabilities = setOf(Capability.CATALOG, Capability.DIRECT, Capability.HLS, Capability.TORRENT)

    override fun handles(title: Title) = title.id.startsWith(FeedParser.PREFIX)

    override suspend fun search(query: String): List<Title> {
        val words = M3uParser.normalizeName(query).split(' ').filter { it.length > 1 }
        if (words.isEmpty()) return emptyList()
        // Catálogos: um por vez (a memória guarda só os mais recentes). APIs de pesquisa: consulta dinâmica.
        val fromCatalogs = repo.enabledFeeds().flatMap { f ->
            runCatching { repo.items(f.id) }.getOrDefault(emptyList()).map { it.title }
                .filter { t -> val n = M3uParser.normalizeName(t.name + " " + (t.synopsis ?: "")); words.all { n.contains(it) } }
        }
        val fromApis = search?.let { s -> runCatching { s.search(SearchTemplate.Query(query)) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; emptyList() } }
            .orEmpty().map { it.item.title }
        return (fromCatalogs + fromApis).distinctBy { it.id }
    }

    override suspend fun getDetails(title: Title): TitleDetails {
        val feedId = repo.feedIdOf(title.id) ?: throw CatalogException("Título inválido")
        val item = repo.find(title.id) ?: search?.find(title.id) ?: repo.items(feedId).firstOrNull { it.title.id == title.id }
            ?: throw CatalogException("Título não está mais no catálogo")
        val playables = item.playables()
        val kinds = item.sources.map { it.type }.toSet() + item.episodes.flatMap { e -> e.sources.map { it.type } }
        val label = buildString {
            append("Catálogo externo")
            if (FeedSource.Type.HTTP in kinds) append(" • HTTP")
            if (FeedSource.Type.HLS in kinds) append(" • HLS")
            if (FeedSource.Type.MAGNET in kinds || FeedSource.Type.TORRENT in kinds) append(" • BitTorrent (seu IP fica visível aos outros participantes)")
        }
        return TitleDetails(item.title, SourceRanker.tag(playables, feedId), label, providerId = feedId)
    }
}
