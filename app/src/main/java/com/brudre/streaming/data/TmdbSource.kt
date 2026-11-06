package com.brudre.streaming.data

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Catálogo do TMDB: capas, sinopses em português e classificação indicativa.
 *
 * - Linhas "grátis": só títulos com oferta GRATUITA no Brasil (Pluto TV, YouTube, Plex, Mercado Play…),
 *   nunca assinatura, aluguel ou compra.
 * - Linhas de lançamentos/em alta: catálogo completo; quem não tem fonte gratuita aparece só como catálogo.
 * - Linhas de clássicos: obras até [PUBLIC_DOMAIN_YEAR] (domínio público), procuradas no Internet Archive.
 */
class TmdbSource(
    private val apiKey: String,
    private val settings: AppSettings,
    private val fetch: (String) -> String,
) {
    val enabled: Boolean get() = apiKey.isNotBlank()

    @Volatile private var freeIds: Map<String, String>? = null

    fun cacheKey(rowKey: String): String = "$rowKey|r${settings.current.maxRating}|v3"

    /** Ids TMDB dos serviços gratuitos conhecidos no Brasil (para não misturar planos pagos com anúncios). */
    private fun freeProviderIds(media: String): String? {
        freeIds?.get(media)?.let { return it.ifEmpty { null } }
        val ids = runCatching { TmdbParser.parseFreeCatalog(fetch(url("watch/providers/$media", mapOf("watch_region" to REGION)))) }
            .getOrDefault(emptyList()).distinct().joinToString("|")
        freeIds = (freeIds ?: emptyMap()) + (media to ids)
        return ids.ifEmpty { null }
    }

    fun row(key: String): List<Title> {
        val spec = ROWS[key] ?: throw CatalogException("Seção desconhecida: $key")
        val s = settings.current
        val restricted = s.maxRating != "18"
        // "Em alta" puro não filtra classificação: com controle dos pais ativo usa a lista filtrada por popularidade.
        if (spec.trending && !(restricted && spec.media == "movie")) {
            val list = TmdbParser.parseList(fetch(url("trending/${spec.media}/week", emptyMap())), spec.media)
            return finish(spec, list)
        }
        val p = LinkedHashMap<String, String>()
        p["sort_by"] = spec.sort
        spec.genres?.let { p["with_genres"] = it }
        p["vote_count.gte"] = spec.minVotes.toString()
        val dateKey = if (spec.media == "movie") "primary_release_date" else "first_air_date"
        when {
            spec.classic -> p["$dateKey.lte"] = "$PUBLIC_DOMAIN_YEAR-12-31"
            else -> {
                p["$dateKey.lte"] = day(0)
                spec.recentDays?.let { p["$dateKey.gte"] = day(-it) }
                spec.minYear?.let { p["$dateKey.gte"] = "$it-01-01" }
            }
        }
        if (spec.free) {
            p["watch_region"] = REGION
            p["with_watch_monetization_types"] = "free|ads"
            freeProviderIds(spec.media)?.let { p["with_watch_providers"] = it }
        }
        if (spec.media == "movie" && restricted) {
            p["certification_country"] = REGION
            p["certification.lte"] = s.maxRating
        }
        return finish(spec, TmdbParser.parseList(fetch(url("discover/${spec.media}", p)), spec.media))
    }

    private fun finish(spec: Spec, list: List<Title>): List<Title> {
        val l = if (spec.kidsOnly) list.filter { it.kind == Kind.KIDS } else list
        return if (spec.free) l.map { it.copy(license = FREE_LICENSE) } else l
    }

    fun search(term: String): List<Title> = TmdbParser.parseList(fetch(url("search/multi", mapOf("query" to term))), null)

    /** Metadados completos, serviços gratuitos e títulos original/inglês para procurar a obra no acervo. */
    fun details(title: Title): TmdbDetails {
        val (media, id) = TmdbParser.parseId(title.id) ?: throw CatalogException("Título inválido")
        val append = if (media == "movie") "release_dates,translations,watch/providers" else "content_ratings,translations,watch/providers"
        return TmdbParser.parseDetails(fetch(url("$media/$id", mapOf("append_to_response" to append))), media, title, REGION)
    }

    private fun url(path: String, params: Map<String, String>): String {
        val all = LinkedHashMap<String, String>()
        all["api_key"] = apiKey
        all["language"] = "pt-BR"
        // Sem "region": com ela o TMDB troca a data pela do relançamento no Brasil (ex.: Metrópolis "2010").
        all["include_adult"] = "false"
        all.putAll(params)
        return "$BASE/$path?" + all.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
    }

    private fun day(offset: Int): String {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(c.timeInMillis))
    }

    data class Spec(
        val label: String,
        val media: String,
        val sort: String = "popularity.desc",
        val genres: String? = null,
        val minVotes: Int = 20,
        val kidsOnly: Boolean = false,
        val free: Boolean = false,
        val classic: Boolean = false,
        val trending: Boolean = false,
        val recentDays: Int? = null,
        val minYear: Int? = null,
    )

    companion object {
        const val BASE = "https://api.themoviedb.org/3"
        const val REGION = "BR"
        /** Obras publicadas até este ano estão em domínio público nos EUA (regra de 95 anos, em 2026). */
        const val PUBLIC_DOMAIN_YEAR = 1930
        /** Marca nos títulos das linhas grátis (selo GRÁTIS no card). */
        const val FREE_LICENSE = "TMDB • grátis"

        val ROWS: Map<String, Spec> = mapOf(
            // Grátis no Brasil (apps oficiais com anúncios)
            "tmdb_free_movies" to Spec("Filmes Recentes Grátis", "movie", free = true, minYear = 2000, minVotes = 40),
            "tmdb_free_new" to Spec("Lançamentos Grátis", "movie", sort = "primary_release_date.desc", free = true, minVotes = 10),
            "tmdb_free_trending" to Spec("Filmes em Alta Grátis", "movie", free = true, recentDays = 1100, minVotes = 15),
            "tmdb_free_action" to Spec("Ação e Aventura Grátis", "movie", genres = "28|12|53", free = true, minVotes = 30),
            "tmdb_free_comedy" to Spec("Comédia Grátis", "movie", genres = "35", free = true, minVotes = 30),
            "tmdb_free_horror" to Spec("Terror Grátis", "movie", genres = "27", free = true, minVotes = 30),
            "tmdb_free_family" to Spec("Filmes para a Família Grátis", "movie", genres = "10751|16", free = true, minVotes = 20),
            "tmdb_free_docs" to Spec("Documentários Grátis (apps)", "movie", genres = "99", free = true, minVotes = 5),
            "tmdb_free_series" to Spec("Séries Grátis", "tv", free = true, minVotes = 20),
            "tmdb_free_new_series" to Spec("Séries Novas Grátis", "tv", sort = "first_air_date.desc", free = true, minVotes = 5),
            "tmdb_free_kids" to Spec("Desenhos Grátis", "tv", genres = "10762", free = true, minVotes = 5),
            // Lançamentos e em alta (catálogo completo)
            "tmdb_new_movies" to Spec("Filmes Lançados Recentemente", "movie", recentDays = 120, minVotes = 15),
            "tmdb_upcoming_streaming" to Spec("Lançamentos do Ano", "movie", recentDays = 365, minVotes = 100),
            "tmdb_trending_movies" to Spec("Filmes em Alta na Semana", "movie", trending = true, recentDays = 730, minVotes = 50),
            "tmdb_popular_movies" to Spec("Filmes Mais Populares", "movie", minYear = 2015, minVotes = 500),
            "tmdb_new_series" to Spec("Séries que Estrearam Recentemente", "tv", recentDays = 180, minVotes = 10),
            "tmdb_trending_series" to Spec("Séries em Alta na Semana", "tv", trending = true, recentDays = 730, minVotes = 50),
            "tmdb_popular_series" to Spec("Séries Mais Populares", "tv", minYear = 2015, minVotes = 300),
            // Clássicos em domínio público (procurados no Internet Archive)
            "tmdb_pd_classics" to Spec("Grandes Clássicos do Cinema", "movie", classic = true, minVotes = 150),
            "tmdb_pd_top" to Spec("Clássicos Mais Bem Avaliados", "movie", sort = "vote_average.desc", classic = true, minVotes = 400),
            "tmdb_pd_horror" to Spec("Terror e Fantasia Clássicos", "movie", genres = "27|14|878", classic = true, minVotes = 40),
            "tmdb_pd_comedy" to Spec("Comédias de Chaplin, Keaton e Lloyd", "movie", genres = "35", classic = true, minVotes = 60),
            "tmdb_pd_anim" to Spec("Animações Pioneiras", "movie", genres = "16", classic = true, minVotes = 8),
            "tmdb_pd_docs" to Spec("Documentários Históricos", "movie", genres = "99", classic = true, minVotes = 8),
        )
    }
}
