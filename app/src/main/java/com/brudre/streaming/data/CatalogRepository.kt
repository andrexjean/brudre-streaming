package com.brudre.streaming.data

import com.brudre.streaming.data.db.BruDreDatabase
import com.brudre.streaming.data.db.CatalogCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class CatalogException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Catálogo automático: Internet Archive (acervo público) + índice curado de fontes autorizadas.
 * Cache em memória + Room (6 h) para abrir rápido no Mi Stick e funcionar com rede instável.
 */
class CatalogRepository(
    private val http: OkHttpClient,
    private val db: BruDreDatabase,
    val curated: CuratedCatalog,
    private val settings: AppSettings,
    private val isOnline: () -> Boolean = { true },
) {

    data class RowSpec(val key: String, val label: String, val query: String?, val sort: String, val kind: Kind, val rows: Int = 30)

    private val memory = ConcurrentHashMap<String, List<Title>>()
    private val metadata = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 12
    }

    // ------------------------------------------------------------------ linhas

    suspend fun row(key: String, force: Boolean = false): List<Title> {
        val spec = ROWS[key] ?: throw CatalogException("Seção desconhecida: $key")
        if (spec.query == null) return curatedRow(key)
        if (!force) memory[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val cached = db.catalogCache().get(key)
            val fresh = cached != null && System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS
            if (!force && fresh) {
                return@withContext Title.listFromJson(cached!!.json).also { memory[key] = it }
            }
            try {
                val list = parseOrThrow { ArchiveParser.parseSearch(get(searchUrl(spec.query, spec.sort, spec.rows)), spec.kind) }
                memory[key] = list
                db.catalogCache().put(CatalogCacheEntity(key, Title.listToJson(list), System.currentTimeMillis()))
                list
            } catch (e: IOException) {
                // Rede caiu: usa a última versão salva, mesmo vencida.
                cached?.let { Title.listFromJson(it.json).also { l -> memory[key] = l } } ?: throw e
            }
        }
    }

    private fun curatedRow(key: String): List<Title> = when (key) {
        "curated_featured" -> curated.featured()
        "curated_torrent" -> curated.torrentTitles()
        "curated_kids" -> curated.kids()
        else -> curated.all()
    }

    // ----------------------------------------------------------------- busca

    suspend fun search(term: String): List<Title> {
        val clean = term.replace(Regex("[^\\p{L}\\p{N} '-]"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.length < 2) return emptyList()
        val local = curated.search(clean)
        val terms = clean.split(' ').filter { it.length > 1 }.ifEmpty { listOf(clean) }
        val byTitle = terms.joinToString(" AND ") { "title:($it*)" }
        val bySubject = terms.joinToString(" AND ") { "subject:($it)" }
        val q = "(($byTitle) OR ($bySubject)) AND mediatype:(movies) AND collection:($SEARCH_COLLECTIONS)$NOT_RESTRICTED"
        val remote = withContext(Dispatchers.IO) {
            parseOrThrow { ArchiveParser.parseSearch(get(searchUrl(q, "downloads desc", 40)), Kind.MOVIE) }
        }
        return (local + remote).distinctBy { it.id }
    }

    // --------------------------------------------------------------- detalhes

    suspend fun details(title: Title): TitleDetails = withContext(Dispatchers.IO) {
        val entry = if (title.isCurated) curated.find(title.id) else null
        when {
            entry?.playable != null -> TitleDetails(
                title = entry.title,
                playables = listOf(entry.playable),
                sourceLabel = "Fonte oficial do titular • torrent + streaming direto",
            )
            entry?.archiveId != null -> {
                val json = metadataJson(entry.archiveId)
                val playables = ArchiveParser.buildPlayables(entry.archiveId, json, settings.current.quality)
                TitleDetails(entry.title, playables, sourceLabelFor(playables))
            }
            entry != null -> TitleDetails(entry.title, emptyList(), "Sem fonte autorizada cadastrada")
            title.isArchive -> {
                val id = title.archiveId!!
                val json = metadataJson(id)
                val enriched = parseOrThrow { ArchiveParser.enrichTitle(title, json) }
                val playables = parseOrThrow { ArchiveParser.buildPlayables(id, json, settings.current.quality) }
                TitleDetails(enriched, playables, sourceLabelFor(playables))
            }
            else -> TitleDetails(title, emptyList(), "Sem fonte autorizada cadastrada")
        }
    }

    private fun sourceLabelFor(playables: List<Playable>): String = when {
        playables.isEmpty() -> "Nenhum vídeo compatível encontrado neste item"
        playables.any { it.torrent != null } -> "Internet Archive • streaming direto + torrent oficial"
        else -> "Internet Archive • streaming direto"
    }

    private fun metadataJson(id: String): String {
        synchronized(metadata) { metadata[id]?.let { return it } }
        val json = get("${ArchiveParser.BASE}/metadata/${ArchiveParser.enc(id)}")
        if (json.trim() == "{}") throw CatalogException("Item não encontrado no Internet Archive")
        synchronized(metadata) { metadata[id] = json }
        return json
    }

    fun trimMemory() {
        memory.clear()
        synchronized(metadata) { metadata.clear() }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        trimMemory()
        db.catalogCache().clear()
    }

    // ------------------------------------------------------------------ rede

    private fun searchUrl(query: String, sort: String, rows: Int): String {
        val fields = listOf("identifier", "title", "year", "date", "description", "subject", "runtime", "collection")
            .joinToString("") { "&fl%5B%5D=$it" }
        return "${ArchiveParser.BASE}/advancedsearch.php?q=${URLEncoder.encode(query, "UTF-8")}" +
            fields + "&sort%5B%5D=${URLEncoder.encode(sort, "UTF-8")}&rows=$rows&page=1&output=json"
    }

    private fun get(url: String): String {
        if (!isOnline()) throw CatalogException("Sem conexão com a internet")
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw CatalogException("Servidor do catálogo respondeu ${resp.code}")
                return resp.body?.string() ?: throw CatalogException("Resposta vazia do catálogo")
            }
        } catch (e: CatalogException) {
            throw e
        } catch (e: IOException) {
            throw CatalogException(if (isOnline()) "Falha de rede ao consultar o catálogo" else "Sem conexão com a internet", e)
        }
    }

    private inline fun <T> parseOrThrow(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw CatalogException("Resposta inesperada do catálogo", e)
    }

    companion object {
        const val USER_AGENT = "BruDreStreaming/6.0 (Android TV; uso domestico)"
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        private const val NOT_RESTRICTED = " AND -access-restricted-item:(true)"
        const val SEARCH_COLLECTIONS =
            "feature_films OR classic_tv OR classic_cartoons OR animationandcartoons OR SciFi_Horror OR film_noir OR comedy_films OR silent_films"

        private fun ia(collection: String) = "collection:($collection) AND mediatype:(movies)$NOT_RESTRICTED"

        val ROWS: Map<String, RowSpec> = listOf(
            RowSpec("curated_featured", "Destaques", null, "", Kind.MOVIE),
            RowSpec("curated_torrent", "Filmes abertos via torrent", null, "", Kind.MOVIE),
            RowSpec("curated_kids", "Animações abertas", null, "", Kind.KIDS),
            RowSpec("featured", "Filmes em Destaque", ia("feature_films"), "downloads desc", Kind.MOVIE),
            RowSpec("recent", "Adicionados Recentemente", ia("feature_films"), "addeddate desc", Kind.MOVIE),
            RowSpec("scifi", "Ficção Científica e Terror", ia("SciFi_Horror"), "downloads desc", Kind.MOVIE),
            RowSpec("noir", "Film Noir", ia("film_noir"), "downloads desc", Kind.MOVIE),
            RowSpec("comedy", "Comédia", ia("comedy_films"), "downloads desc", Kind.MOVIE),
            RowSpec("silent", "Cinema Mudo", ia("silent_films"), "downloads desc", Kind.MOVIE),
            RowSpec("series", "Séries Clássicas", ia("classic_tv"), "downloads desc", Kind.SERIES),
            RowSpec("series_recent", "Séries Adicionadas Recentemente", ia("classic_tv"), "addeddate desc", Kind.SERIES),
            RowSpec("kids", "Desenhos Clássicos", ia("classic_cartoons"), "downloads desc", Kind.KIDS),
            RowSpec("kids_animation", "Animação", ia("animationandcartoons"), "downloads desc", Kind.KIDS),
        ).associateBy { it.key }

        fun label(key: String) = ROWS[key]?.label ?: key
    }
}
