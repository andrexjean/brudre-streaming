package com.brudre.streaming.data

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catálogo completo (filmes, séries, desenhos, lançamentos) via API do TMDB,
 * sempre restrito ao que está disponível em streaming no Brasil.
 * A reprodução acontece no app oficial de cada serviço.
 */
class TmdbSource(
    private val apiKey: String,
    private val settings: AppSettings,
    private val fetch: (String) -> String,
) {
    val enabled: Boolean get() = apiKey.isNotBlank()

    @Volatile private var providerCatalog: List<WatchProvider>? = null

    /** Provedores do Brasil (ids do TMDB), para filtrar por "meus streamings". */
    fun providers(): List<WatchProvider> {
        providerCatalog?.let { return it }
        val movie = TmdbParser.parseProviderCatalog(fetch(url("watch/providers/movie", mapOf("watch_region" to REGION))))
        val tv = runCatching { TmdbParser.parseProviderCatalog(fetch(url("watch/providers/tv", mapOf("watch_region" to REGION)))) }.getOrDefault(emptyList())
        return (movie + tv).distinctBy { it.id }.also { providerCatalog = it }
    }

    /** Ids TMDB dos serviços que o usuário marcou como assinados. */
    fun myProviderIds(): Set<Int> {
        val mine = settings.current.myServices
        if (mine.isEmpty()) return emptySet()
        return providers().filter { StreamingApps.canonical(it.name) in mine }.map { it.id }.toSet()
    }

    /** Chave de cache: as linhas mudam quando o filtro "só os meus" muda. */
    fun cacheKey(rowKey: String): String {
        val s = settings.current
        return if (s.onlyMine && s.myServices.isNotEmpty() && rowKey !in FREE_ROWS) "$rowKey|${s.myServices.sorted().joinToString(",")}" else rowKey
    }

    fun row(key: String): List<Title> {
        val spec = ROWS[key] ?: throw CatalogException("Seção desconhecida: $key")
        val s = settings.current
        val useMine = (key in MINE_ROWS) || (s.onlyMine && s.myServices.isNotEmpty() && key !in FREE_ROWS)
        val ids = if (useMine) myProviderIds() else emptySet()
        if (key in MINE_ROWS && ids.isEmpty()) return emptyList()

        val (path, media, params) = if (spec.trending && ids.isEmpty()) {
            Triple<String, String?, Map<String, String>>("trending/all/week", null, emptyMap())
        } else {
            val media = spec.media
            val p = LinkedHashMap<String, String>()
            p["sort_by"] = spec.sort
            p["watch_region"] = REGION
            p["with_watch_monetization_types"] = spec.monetization
            spec.genres?.let { p["with_genres"] = it }
            spec.minVotes?.let { p["vote_count.gte"] = it.toString() }
            if (spec.sort.contains("release_date") || spec.sort.contains("first_air_date")) {
                p[if (media == "movie") "primary_release_date.lte" else "first_air_date.lte"] = today()
            }
            if (ids.isNotEmpty()) p["with_watch_providers"] = ids.joinToString("|")
            Triple<String, String?, Map<String, String>>("discover/$media", media, p)
        }
        val json = fetch(url(path, params))
        return TmdbParser.parseList(json, media).let { list ->
            if (spec.kidsOnly) list.filter { it.kind == Kind.KIDS } else list
        }
    }

    fun search(term: String): List<Title> {
        val json = fetch(url("search/multi", mapOf("query" to term)))
        return TmdbParser.parseList(json, null)
    }

    fun details(title: Title): TitleDetails {
        val (media, id) = TmdbParser.parseId(title.id) ?: throw CatalogException("Título inválido")
        val append = if (media == "movie") "watch/providers,release_dates" else "watch/providers,content_ratings"
        val json = fetch(url("$media/$id", mapOf("append_to_response" to append)))
        val (full, providers) = TmdbParser.parseDetails(json, media, title, REGION)
        val label = when {
            providers.isEmpty() -> "Não está em nenhum streaming no Brasil no momento (pode estar só no cinema)"
            providers.any { it.offer == Offer.FREE || it.offer == Offer.ADS } -> "Disponível grátis em pelo menos um serviço"
            else -> "Disponível nos serviços abaixo"
        }
        return TitleDetails(full, emptyList(), label, providers)
    }

    private fun url(path: String, params: Map<String, String>): String {
        val all = LinkedHashMap<String, String>()
        all["api_key"] = apiKey
        all["language"] = "pt-BR"
        all["region"] = REGION
        all["include_adult"] = "false"
        all.putAll(params)
        return "$BASE/$path?" + all.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    data class Spec(
        val label: String,
        val media: String,
        val sort: String = "popularity.desc",
        val genres: String? = null,
        val minVotes: Int? = null,
        val monetization: String = "flatrate|free|ads",
        val trending: Boolean = false,
        val kidsOnly: Boolean = false,
    )

    companion object {
        const val BASE = "https://api.themoviedb.org/3"
        const val REGION = "BR"

        val ROWS: Map<String, Spec> = mapOf(
            "tmdb_trending" to Spec("Em Alta", "movie", trending = true),
            "tmdb_movies" to Spec("Filmes Populares", "movie"),
            "tmdb_new_movies" to Spec("Lançamentos no Streaming", "movie", sort = "primary_release_date.desc", minVotes = 40),
            "tmdb_top_movies" to Spec("Filmes Mais Bem Avaliados", "movie", sort = "vote_average.desc", minVotes = 3000),
            "tmdb_action" to Spec("Ação e Aventura", "movie", genres = "28|12"),
            "tmdb_comedy" to Spec("Comédia", "movie", genres = "35"),
            "tmdb_horror" to Spec("Terror e Suspense", "movie", genres = "27|53"),
            "tmdb_series" to Spec("Séries Populares", "tv"),
            "tmdb_new_series" to Spec("Séries Novas", "tv", sort = "first_air_date.desc", minVotes = 20),
            "tmdb_top_series" to Spec("Séries Mais Bem Avaliadas", "tv", sort = "vote_average.desc", minVotes = 1500),
            "tmdb_anim_movies" to Spec("Filmes de Animação", "movie", genres = "16"),
            "tmdb_kids_series" to Spec("Desenhos", "tv", genres = "16|10762"),
            "tmdb_family" to Spec("Para a Família", "movie", genres = "10751"),
            "tmdb_free" to Spec("Grátis (com anúncios)", "movie", monetization = "free|ads"),
            "tmdb_free_series" to Spec("Séries Grátis", "tv", monetization = "free|ads"),
            "tmdb_mine" to Spec("Nos Meus Streamings", "movie"),
            "tmdb_mine_series" to Spec("Séries nos Meus Streamings", "tv"),
        )
        private val FREE_ROWS = setOf("tmdb_free", "tmdb_free_series")
        private val MINE_ROWS = setOf("tmdb_mine", "tmdb_mine_series")
    }
}
