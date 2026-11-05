package com.brudre.streaming.data

import org.json.JSONArray
import org.json.JSONObject

/** Tipo de oferta de um streaming para um título (dados JustWatch via TMDB). */
enum class Offer(val label: String, val order: Int) {
    SUBSCRIPTION("Assinatura", 0),
    FREE("Grátis", 1),
    ADS("Grátis com anúncios", 2),
    RENT("Aluguel", 3),
    BUY("Compra", 4),
}

data class WatchProvider(
    val id: Int,
    val name: String,
    val logoUrl: String?,
    val offer: Offer,
    val priority: Int = 999,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("logo", logoUrl ?: JSONObject.NULL)
        .put("offer", offer.name).put("priority", priority)

    companion object {
        fun fromJson(o: JSONObject) = WatchProvider(
            o.getInt("id"), o.getString("name"), o.optStringOrNull("logo"),
            runCatching { Offer.valueOf(o.getString("offer")) }.getOrDefault(Offer.SUBSCRIPTION), o.optInt("priority", 999),
        )
    }
}

/**
 * Leitura das respostas da API do TMDB (The Movie Database).
 * Funções puras para testes unitários. Imagens servidas por image.tmdb.org.
 */
object TmdbParser {
    const val PREFIX = "tmdb:"
    private const val IMG = "https://image.tmdb.org/t/p/"

    fun poster(path: String?) = path?.takeIf { it.isNotBlank() }?.let { "${IMG}w342$it" }
    fun backdrop(path: String?) = path?.takeIf { it.isNotBlank() }?.let { "${IMG}w780$it" }
    fun logo(path: String?) = path?.takeIf { it.isNotBlank() }?.let { "${IMG}w92$it" }

    fun id(media: String, tmdbId: Int) = "$PREFIX$media:$tmdbId"

    /** "tmdb:movie:123" → ("movie", 123) */
    fun parseId(id: String): Pair<String, Int>? {
        if (!id.startsWith(PREFIX)) return null
        val parts = id.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return null
        val n = parts[1].toIntOrNull() ?: return null
        return if (parts[0] == "movie" || parts[0] == "tv") parts[0] to n else null
    }

    private val KIDS_GENRES = setOf(16, 10762, 10751)

    /** Lista paginada (/trending, /discover, /search/multi, /movie/popular...). */
    fun parseList(json: String, defaultMedia: String?): List<Title> {
        val root = JSONObject(json)
        if (root.has("status_message") && !root.has("results")) throw IllegalStateException("TMDB: ${root.optString("status_message")}")
        val arr = root.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Title>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val media = o.optStringOrNull("media_type") ?: defaultMedia ?: continue
            if (media != "movie" && media != "tv") continue // ignora pessoas na busca
            if (o.optBoolean("adult", false)) continue
            val name = (if (media == "movie") o.optStringOrNull("title") else o.optStringOrNull("name")) ?: continue
            val date = if (media == "movie") o.optStringOrNull("release_date") else o.optStringOrNull("first_air_date")
            val genres = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()
            val poster = poster(o.optStringOrNull("poster_path"))
            if (poster == null && o.optStringOrNull("backdrop_path") == null) continue // sem imagem = catálogo pobre
            out += Title(
                id = id(media, o.getInt("id")),
                kind = when {
                    genres.any { it in KIDS_GENRES } && (genres.contains(16) || genres.contains(10762)) -> Kind.KIDS
                    media == "tv" -> Kind.SERIES
                    else -> Kind.MOVIE
                },
                name = name,
                year = date?.take(4)?.takeIf { it.length == 4 },
                synopsis = o.optStringOrNull("overview"),
                genres = genres.mapNotNull { GENRES[it] }.take(2),
                posterUrl = backdrop(o.optStringOrNull("backdrop_path")) ?: poster,
                bannerUrl = backdrop(o.optStringOrNull("backdrop_path")) ?: poster,
                license = "TMDB",
            )
        }
        return out
    }

    /** Detalhes completos com append_to_response=watch/providers,release_dates|content_ratings. */
    fun parseDetails(json: String, media: String, base: Title?, region: String = "BR"): Pair<Title, List<WatchProvider>> {
        val o = JSONObject(json)
        if (o.has("status_message") && !o.has("id")) throw IllegalStateException("TMDB: ${o.optString("status_message")}")
        val tmdbId = o.getInt("id")
        val name = (if (media == "movie") o.optStringOrNull("title") else o.optStringOrNull("name")) ?: base?.name ?: "?"
        val date = if (media == "movie") o.optStringOrNull("release_date") else o.optStringOrNull("first_air_date")
        val runtime = if (media == "movie") o.optInt("runtime", 0).takeIf { it > 0 }
        else o.optJSONArray("episode_run_time")?.let { a -> if (a.length() > 0) a.optInt(0) else null }?.takeIf { it > 0 }
        val genreList = o.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } } ?: emptyList()
        val genreIds = o.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("id") } } ?: emptyList()
        val seasons = if (media == "tv") o.optInt("number_of_seasons", 0) else 0
        val synopsis = o.optStringOrNull("overview")?.let { s ->
            if (seasons > 0) "$s\n${seasons} temporada${if (seasons > 1) "s" else ""}." else s
        }
        val title = Title(
            id = id(media, tmdbId),
            kind = when {
                genreIds.contains(16) || genreIds.contains(10762) -> Kind.KIDS
                media == "tv" -> Kind.SERIES
                else -> Kind.MOVIE
            },
            name = name,
            year = date?.take(4)?.takeIf { it.length == 4 } ?: base?.year,
            synopsis = synopsis ?: base?.synopsis,
            durationMin = runtime,
            genres = genreList.take(3),
            rating = certification(o, media, region),
            posterUrl = backdrop(o.optStringOrNull("backdrop_path")) ?: poster(o.optStringOrNull("poster_path")) ?: base?.posterUrl,
            bannerUrl = backdrop(o.optStringOrNull("backdrop_path")) ?: base?.bannerUrl,
            license = "Dados: TMDB • Onde assistir: JustWatch",
        )
        val providers = o.optJSONObject("watch/providers")?.let { parseProviders(it, region) } ?: emptyList()
        return title to providers
    }

    fun parseProviders(wp: JSONObject, region: String = "BR"): List<WatchProvider> {
        val r = wp.optJSONObject("results")?.optJSONObject(region) ?: return emptyList()
        val out = ArrayList<WatchProvider>()
        fun read(key: String, offer: Offer) {
            val a = r.optJSONArray(key) ?: return
            for (i in 0 until a.length()) {
                val p = a.optJSONObject(i) ?: continue
                out += WatchProvider(p.optInt("provider_id"), p.optString("provider_name"), logo(p.optStringOrNull("logo_path")), offer, p.optInt("display_priority", 999))
            }
        }
        read("flatrate", Offer.SUBSCRIPTION)
        read("free", Offer.FREE)
        read("ads", Offer.ADS)
        read("rent", Offer.RENT)
        read("buy", Offer.BUY)
        // Um provedor aparece uma vez, na melhor oferta (assinatura > grátis > anúncios > aluguel > compra).
        return out.sortedWith(compareBy({ it.offer.order }, { it.priority }))
            .distinctBy { StreamingApps.canonical(it.name) }
    }

    /** Classificação indicativa brasileira (L, 10, 12, 14, 16, 18). */
    fun certification(o: JSONObject, media: String, region: String): String? {
        if (media == "movie") {
            val arr = o.optJSONObject("release_dates")?.optJSONArray("results") ?: return null
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (c.optString("iso_3166_1") != region) continue
                val dates = c.optJSONArray("release_dates") ?: continue
                for (j in 0 until dates.length()) {
                    dates.optJSONObject(j)?.optStringOrNull("certification")?.let { return normalizeRating(it) }
                }
            }
        } else {
            val arr = o.optJSONObject("content_ratings")?.optJSONArray("results") ?: return null
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (c.optString("iso_3166_1") == region) c.optStringOrNull("rating")?.let { return normalizeRating(it) }
            }
        }
        return null
    }

    private fun normalizeRating(r: String): String? = when (val v = r.trim().uppercase()) {
        "", "NR" -> null
        "AL", "LIVRE" -> "L"
        else -> v.removePrefix("A").takeIf { it.isNotBlank() }
    }

    /** Catálogo de provedores do país (/watch/providers/movie). */
    fun parseProviderCatalog(json: String): List<WatchProvider> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val prio = p.optJSONObject("display_priorities")?.optInt("BR", 999) ?: p.optInt("display_priority", 999)
            WatchProvider(p.optInt("provider_id"), p.optString("provider_name"), logo(p.optStringOrNull("logo_path")), Offer.SUBSCRIPTION, prio)
        }.sortedBy { it.priority }
    }

    fun providersToJson(list: List<WatchProvider>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    fun providersFromJson(json: String): List<WatchProvider> {
        val a = JSONArray(json)
        return (0 until a.length()).map { WatchProvider.fromJson(a.getJSONObject(it)) }
    }

    /** Gêneros do TMDB em português (ids estáveis). */
    val GENRES: Map<Int, String> = mapOf(
        28 to "Ação", 12 to "Aventura", 16 to "Animação", 35 to "Comédia", 80 to "Crime", 99 to "Documentário",
        18 to "Drama", 10751 to "Família", 14 to "Fantasia", 36 to "História", 27 to "Terror", 10402 to "Música",
        9648 to "Mistério", 10749 to "Romance", 878 to "Ficção científica", 10770 to "Filme de TV", 53 to "Thriller",
        10752 to "Guerra", 37 to "Faroeste", 10759 to "Ação e Aventura", 10762 to "Infantil", 10763 to "Notícias",
        10764 to "Reality", 10765 to "Ficção e Fantasia", 10766 to "Novela", 10767 to "Talk show", 10768 to "Guerra e Política",
    )
}
