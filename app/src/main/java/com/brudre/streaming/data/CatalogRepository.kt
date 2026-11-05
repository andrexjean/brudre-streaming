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
    tmdbApiKey: String = "",
) {

    /** Metadados do TMDB (só obras com chance de fonte gratuita). Desligado se o app foi compilado sem a chave. */
    val tmdb = TmdbSource(tmdbApiKey, settings) { url -> get(url) }

    data class RowSpec(val key: String, val label: String, val query: String?, val sort: String, val kind: Kind, val rows: Int = 30)

    private val memory = ConcurrentHashMap<String, List<Title>>()
    private val metadata = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 12
    }

    // ------------------------------------------------------------------ linhas

    suspend fun row(key: String, force: Boolean = false): List<Title> {
        if (key.startsWith("tmdb_")) return tmdbRow(key, force)
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
                val list = parseOrThrow { ArchiveParser.parseSearch(get(searchUrl(spec.query, spec.sort, spec.rows)), spec.kind) }.filter { ArchiveParser.isFamilySafe(it) }
                memory[key] = list
                db.catalogCache().put(CatalogCacheEntity(key, Title.listToJson(list), System.currentTimeMillis()))
                list
            } catch (e: IOException) {
                // Rede caiu: usa a última versão salva, mesmo vencida.
                cached?.let { Title.listFromJson(it.json).also { l -> memory[key] = l } } ?: throw e
            }
        }
    }

    private suspend fun tmdbRow(key: String, force: Boolean): List<Title> {
        if (!tmdb.enabled) throw CatalogException("Catálogo TMDB desativado (app compilado sem a chave do TMDB)")
        val cacheKey = tmdb.cacheKey(key)
        if (!force) memory[cacheKey]?.let { return it }
        return withContext(Dispatchers.IO) {
            val cached = db.catalogCache().get(cacheKey)
            if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS) {
                return@withContext Title.listFromJson(cached.json).also { memory[cacheKey] = it }
            }
            try {
                val list = parseOrThrow { tmdb.row(key) }
                memory[cacheKey] = list
                db.catalogCache().put(CatalogCacheEntity(cacheKey, Title.listToJson(list), System.currentTimeMillis()))
                list
            } catch (e: IOException) {
                cached?.let { Title.listFromJson(it.json).also { l -> memory[cacheKey] = l } } ?: throw e
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
    // Usadas pelos provedores do BruDre Source Manager (sources/Providers.kt).

    fun cleanQuery(term: String): String =
        term.replace(Regex("[^\\p{L}\\p{N} '-]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Busca no acervo público com os filtros de licença e segurança. */
    suspend fun searchArchive(clean: String, page: Int = 1): List<Title> = withContext(Dispatchers.IO) {
        val terms = clean.split(' ').filter { it.length > 1 }.ifEmpty { listOf(clean) }
        val byTitle = terms.joinToString(" AND ") { "title:($it*)" }
        val bySubject = terms.joinToString(" AND ") { "subject:($it)" }
        val q = "(($byTitle) OR ($bySubject)) AND collection:($SEARCH_COLLECTIONS)$NOT_RESTRICTED"
        parseOrThrow { ArchiveParser.parseSearch(get(searchUrl(q, "downloads desc", 40, page)), Kind.MOVIE) }.filter { ArchiveParser.isFamilySafe(it) }
    }

    /** Procura no acervo a mesma obra catalogada em outra fonte (título + ano). */
    suspend fun matchArchive(names: List<String>, year: String?, kind: Kind): Title? = withContext(Dispatchers.IO) {
        if (names.isEmpty()) return@withContext null
        val collections = if (kind == Kind.SERIES) "classic_tv" else "$SEARCH_COLLECTIONS OR prelinger"
        val q = "(${com.brudre.streaming.sources.TitleMatcher.archiveQuery(names)}) AND collection:($collections)$NOT_RESTRICTED"
        val found = parseOrThrow { ArchiveParser.parseSearch(get(searchUrl(q, "downloads desc", 25)), kind) }.filter { ArchiveParser.isFamilySafe(it) }
        com.brudre.streaming.sources.TitleMatcher.best(found, names, year)
    }

    // --------------------------------------------------------------- detalhes

    suspend fun curatedDetails(title: Title): TitleDetails = withContext(Dispatchers.IO) {
        val entry = curated.find(title.id) ?: return@withContext TitleDetails(title, emptyList(), "Sem fonte autorizada cadastrada")
        when {
            entry.playable != null -> TitleDetails(
                title = entry.title,
                playables = listOf(entry.playable),
                sourceLabel = "Fonte oficial do titular • streaming direto + torrent oficial",
                providerId = "curated",
            )
            entry.archiveId != null -> {
                val json = metadataJson(entry.archiveId)
                val playables = ArchiveParser.buildPlayables(entry.archiveId, json, settings.current.quality, entry.title.kind)
                TitleDetails(entry.title, playables, sourceLabelFor(playables), providerId = "curated")
            }
            else -> TitleDetails(entry.title, emptyList(), "Sem fonte autorizada cadastrada")
        }
    }

    suspend fun archiveDetails(title: Title, archiveId: String = title.archiveId!!): TitleDetails = withContext(Dispatchers.IO) {
        val json = metadataJson(archiveId)
        if (ArchiveParser.isRestricted(json)) throw CatalogException("Item com acesso restrito no Internet Archive")
        val enriched = parseOrThrow { ArchiveParser.enrichTitle(title, json) }
        val playables = parseOrThrow { ArchiveParser.buildPlayables(archiveId, json, settings.current.quality, title.kind) }
        TitleDetails(enriched, playables, sourceLabelFor(playables), providerId = "archive")
    }

    private fun sourceLabelFor(playables: List<Playable>): String = when {
        playables.isEmpty() -> "Nenhum vídeo compatível (MP4 H.264) encontrado neste item"
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

    private fun searchUrl(query: String, sort: String, rows: Int, page: Int = 1): String {
        val fields = listOf("identifier", "title", "year", "date", "description", "subject", "runtime", "collection")
            .joinToString("") { "&fl%5B%5D=$it" }
        return "${ArchiveParser.BASE}/advancedsearch.php?q=${URLEncoder.encode(query, "UTF-8")}" +
            fields + "&sort%5B%5D=${URLEncoder.encode(sort, "UTF-8")}&rows=$rows&page=$page&output=json"
    }

    /** GET com uma nova tentativa em falha de rede (Wi-Fi instável ou recém-reconectado). */
    internal fun get(url: String): String {
        if (!isOnline()) throw CatalogException("Sem conexão com a internet")
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        var last: IOException? = null
        repeat(3) { attempt ->
            if (attempt > 0) Thread.sleep(1500L * attempt)
            try {
                http.newCall(req).execute().use { resp ->
                    if (resp.code == 401) throw CatalogException("Chave do catálogo (TMDB) inválida ou revogada")
                    if (!resp.isSuccessful) throw CatalogException("Servidor do catálogo respondeu ${resp.code}")
                    return resp.body?.string() ?: throw CatalogException("Resposta vazia do catálogo")
                }
            } catch (e: CatalogException) {
                throw e
            } catch (e: IOException) {
                last = e
            }
        }
        throw CatalogException(if (isOnline()) "Falha de rede ao consultar o catálogo" else "Sem conexão com a internet", last)
    }

    internal inline fun <T> parseOrThrow(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: BlockedContentException) {
        throw CatalogException("Conteúdo bloqueado pelo BruDre", e)
    } catch (e: Exception) {
        throw CatalogException("Resposta inesperada do catálogo", e)
    }

    companion object {
        const val USER_AGENT = "BruDreStreaming/8.0 (Android TV; uso domestico)"
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        /**
         * Filtros de segurança aplicados a toda consulta ao Internet Archive:
         * só itens com licença declarada (domínio público ou Creative Commons),
         * sem acesso restrito e sem conteúdo adulto/explícito.
         */
        const val NOT_RESTRICTED =
            " AND mediatype:(movies)" +
                " AND licenseurl:(*publicdomain* OR *creativecommons*)" +
                " AND -access-restricted-item:(true)" +
                " AND -title:(sex OR sexy OR nude OR nudist OR naked OR erotic OR erotica OR porn OR xxx OR madness OR bait OR orgy OR lust OR striptease OR burlesque OR concentration)" +
                " AND -subject:(sex OR sexploitation OR nudity OR erotic OR erotica OR adult OR porn OR exploitation OR atrocities)"
        const val SEARCH_COLLECTIONS =
            "feature_films OR classic_tv OR classic_cartoons OR animationandcartoons OR SciFi_Horror OR Film_Noir OR Comedy_Films OR silent_films"

        private fun ia(collection: String, extra: String = "") = "collection:($collection)$extra$NOT_RESTRICTED"

        val ROWS: Map<String, RowSpec> = listOf(
            RowSpec("curated_featured", "Destaques", null, "", Kind.MOVIE),
            RowSpec("curated_torrent", "Filmes abertos via torrent", null, "", Kind.MOVIE),
            RowSpec("curated_kids", "Animações abertas", null, "", Kind.KIDS),
            RowSpec("featured", "Clássicos Grátis — tocam no BruDre", ia("feature_films"), "downloads desc", Kind.MOVIE),
            RowSpec("recent", "Adicionados Recentemente", ia("feature_films", " AND year:[1910 TO 1963]"), "publicdate desc", Kind.MOVIE),
            RowSpec("scifi", "Ficção Científica e Terror", ia("SciFi_Horror"), "downloads desc", Kind.MOVIE),
            RowSpec("noir", "Film Noir", ia("Film_Noir"), "downloads desc", Kind.MOVIE),
            RowSpec("comedy", "Comédia", ia("Comedy_Films"), "downloads desc", Kind.MOVIE),
            RowSpec("silent", "Cinema Mudo", ia("silent_films"), "downloads desc", Kind.MOVIE),
            RowSpec("series", "Séries Clássicas Grátis", ia("classic_tv"), "downloads desc", Kind.SERIES),
            RowSpec("series_recent", "Séries Adicionadas Recentemente", ia("classic_tv"), "publicdate desc", Kind.SERIES),
            RowSpec("kids", "Desenhos Clássicos Grátis", ia("classic_cartoons"), "downloads desc", Kind.KIDS),
            RowSpec("kids_animation", "Animação", ia("animationandcartoons"), "downloads desc", Kind.KIDS),
            RowSpec("westerns", "Faroeste", ia("feature_films", " AND subject:(western)"), "downloads desc", Kind.MOVIE),
            RowSpec("adventure", "Aventura e Ação", ia("feature_films", " AND subject:(adventure OR action OR swashbuckler)"), "downloads desc", Kind.MOVIE),
            RowSpec("docs_featured", "Documentários Grátis", ia("feature_films OR prelinger", " AND subject:(documentary OR documentaries OR documentario)"), "downloads desc", Kind.MOVIE),
            RowSpec("docs_prelinger", "Arquivo Prelinger — Filmes Históricos", ia("prelinger"), "downloads desc", Kind.MOVIE),
            RowSpec("docs_science", "Ciência e Natureza", ia("prelinger", " AND subject:(science OR nature OR space OR animals OR biology)"), "downloads desc", Kind.MOVIE),
            RowSpec("docs_recent", "Documentários Adicionados Recentemente", ia("prelinger"), "publicdate desc", Kind.MOVIE),
        ).associateBy { it.key }

        fun label(key: String) = ROWS[key]?.label ?: TmdbSource.ROWS[key]?.label ?: key
    }
}
