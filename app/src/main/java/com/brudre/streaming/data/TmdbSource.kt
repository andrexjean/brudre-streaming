package com.brudre.streaming.data

import java.net.URLEncoder

/**
 * Metadados do TMDB (capas, sinopses em português, classificação indicativa).
 * Não informa nem redireciona para serviços pagos: a reprodução depende de uma fonte
 * gratuita encontrada pelo BruDre Source Manager (ex.: a mesma obra no Internet Archive).
 *
 * As linhas do catálogo trazem obras publicadas até [PUBLIC_DOMAIN_YEAR], período que já
 * está em domínio público nos EUA e por isso costuma ter cópia autorizada no acervo público.
 */
class TmdbSource(
    private val apiKey: String,
    private val settings: AppSettings,
    private val fetch: (String) -> String,
) {
    val enabled: Boolean get() = apiKey.isNotBlank()

    fun cacheKey(rowKey: String): String = "$rowKey|r${settings.current.maxRating}|v2"

    fun row(key: String): List<Title> {
        val spec = ROWS[key] ?: throw CatalogException("Seção desconhecida: $key")
        val s = settings.current
        val p = LinkedHashMap<String, String>()
        p["sort_by"] = spec.sort
        spec.genres?.let { p["with_genres"] = it }
        p["vote_count.gte"] = spec.minVotes.toString()
        if (spec.media == "movie") {
            p["primary_release_date.lte"] = "$PUBLIC_DOMAIN_YEAR-12-31"
            if (s.maxRating != "18") {
                p["certification_country"] = REGION
                p["certification.lte"] = s.maxRating
            }
        } else {
            p["first_air_date.lte"] = "$PUBLIC_DOMAIN_YEAR-12-31"
        }
        val json = fetch(url("discover/${spec.media}", p))
        return TmdbParser.parseList(json, spec.media).let { list ->
            if (spec.kidsOnly) list.filter { it.kind == Kind.KIDS } else list
        }
    }

    fun search(term: String): List<Title> = TmdbParser.parseList(fetch(url("search/multi", mapOf("query" to term))), null)

    /** Metadados completos + títulos original/inglês para procurar a obra em fontes gratuitas. */
    fun details(title: Title): TmdbDetails {
        val (media, id) = TmdbParser.parseId(title.id) ?: throw CatalogException("Título inválido")
        val append = if (media == "movie") "release_dates,translations" else "content_ratings,translations"
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

    data class Spec(
        val label: String,
        val media: String,
        val sort: String = "popularity.desc",
        val genres: String? = null,
        val minVotes: Int = 20,
        val kidsOnly: Boolean = false,
    )

    companion object {
        const val BASE = "https://api.themoviedb.org/3"
        const val REGION = "BR"
        /** Obras publicadas até este ano estão em domínio público nos EUA (regra de 95 anos, em 2026). */
        const val PUBLIC_DOMAIN_YEAR = 1930

        val ROWS: Map<String, Spec> = mapOf(
            "tmdb_pd_classics" to Spec("Grandes Clássicos do Cinema", "movie", minVotes = 150),
            "tmdb_pd_top" to Spec("Clássicos Mais Bem Avaliados", "movie", sort = "vote_average.desc", minVotes = 400),
            "tmdb_pd_horror" to Spec("Terror e Fantasia Clássicos", "movie", genres = "27|14|878", minVotes = 40),
            "tmdb_pd_comedy" to Spec("Comédias de Chaplin, Keaton e Lloyd", "movie", genres = "35", minVotes = 60),
            "tmdb_pd_anim" to Spec("Animações Pioneiras", "movie", genres = "16", minVotes = 8),
            "tmdb_pd_docs" to Spec("Documentários Históricos", "movie", genres = "99", minVotes = 8),
        )
    }
}
