package com.brudre.streaming.data

import org.json.JSONArray
import org.json.JSONObject

enum class Kind(val label: String) {
    MOVIE("Filme"), SERIES("Série"), KIDS("Infantil");

    companion object {
        fun parse(value: String?): Kind = entries.firstOrNull { it.name == value } ?: MOVIE
    }
}

/** Item do catálogo. Ter capa e sinopse NÃO significa estar disponível: ver [TitleDetails.playables]. */
data class Title(
    val id: String,
    val kind: Kind,
    val name: String,
    val year: String? = null,
    val synopsis: String? = null,
    val durationMin: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: String? = null,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val license: String? = null,
) {
    val isArchive: Boolean get() = id.startsWith(ARCHIVE_PREFIX)
    val isCurated: Boolean get() = id.startsWith(CURATED_PREFIX)
    val isTmdb: Boolean get() = id.startsWith(TmdbParser.PREFIX)
    val archiveId: String? get() = if (isArchive) id.removePrefix(ARCHIVE_PREFIX) else null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.name).put("name", name)
        .put("year", year ?: JSONObject.NULL)
        .put("synopsis", synopsis ?: JSONObject.NULL)
        .put("durationMin", durationMin ?: JSONObject.NULL)
        .put("genres", JSONArray(genres))
        .put("rating", rating ?: JSONObject.NULL)
        .put("poster", posterUrl ?: JSONObject.NULL)
        .put("banner", bannerUrl ?: JSONObject.NULL)
        .put("license", license ?: JSONObject.NULL)

    companion object {
        const val ARCHIVE_PREFIX = "ia:"
        const val CURATED_PREFIX = "cur:"

        fun fromJson(o: JSONObject): Title = Title(
            id = o.getString("id"),
            kind = Kind.parse(o.optString("kind")),
            name = o.optString("name"),
            year = o.optStringOrNull("year"),
            synopsis = o.optStringOrNull("synopsis"),
            durationMin = if (o.isNull("durationMin")) null else o.optInt("durationMin"),
            genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            rating = o.optStringOrNull("rating"),
            posterUrl = o.optStringOrNull("poster"),
            bannerUrl = o.optStringOrNull("banner"),
            license = o.optStringOrNull("license"),
        )

        fun listToJson(list: List<Title>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
        fun listFromJson(json: String): List<Title> {
            val a = JSONArray(json)
            return (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }
    }
}

data class SubtitleRef(val url: String, val language: String, val label: String) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("lang", language).put("label", label)

    companion object {
        fun fromJson(o: JSONObject) = SubtitleRef(o.getString("url"), o.optString("lang", "und"), o.optString("label", o.optString("lang")))
    }
}

/** Torrent autorizado: .torrent oficial e/ou magnet, mais o arquivo de vídeo a reproduzir. */
data class TorrentRef(val torrentUrl: String?, val magnet: String?, val filePathHint: String?) {
    fun toJson(): JSONObject = JSONObject()
        .put("torrentUrl", torrentUrl ?: JSONObject.NULL)
        .put("magnet", magnet ?: JSONObject.NULL)
        .put("file", filePathHint ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = TorrentRef(o.optStringOrNull("torrentUrl"), o.optStringOrNull("magnet"), o.optStringOrNull("file"))
    }
}

/** Unidade reproduzível (filme ou episódio) com as fontes autorizadas encontradas. */
data class Playable(
    val key: String,
    val label: String,
    val season: Int,
    val number: Int,
    val directUrl: String?,
    val torrent: TorrentRef?,
    val subtitles: List<SubtitleRef>,
    val durationSec: Int?,
    val preferTorrent: Boolean,
) {
    val hasSource: Boolean get() = directUrl != null || torrent != null

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("label", label).put("season", season).put("number", number)
        .put("direct", directUrl ?: JSONObject.NULL)
        .put("torrent", torrent?.toJson() ?: JSONObject.NULL)
        .put("subtitles", JSONArray().apply { subtitles.forEach { put(it.toJson()) } })
        .put("durationSec", durationSec ?: JSONObject.NULL)
        .put("preferTorrent", preferTorrent)

    companion object {
        fun fromJson(o: JSONObject): Playable = Playable(
            key = o.getString("key"),
            label = o.optString("label"),
            season = o.optInt("season", 1),
            number = o.optInt("number", 1),
            directUrl = o.optStringOrNull("direct"),
            torrent = o.optJSONObject("torrent")?.let { TorrentRef.fromJson(it) },
            subtitles = o.optJSONArray("subtitles")?.let { a -> (0 until a.length()).map { SubtitleRef.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            durationSec = if (o.isNull("durationSec")) null else o.optInt("durationSec"),
            preferTorrent = o.optBoolean("preferTorrent", false),
        )
    }
}

data class TitleDetails(
    val title: Title,
    val playables: List<Playable>,
    val sourceLabel: String,
    /** Streamings onde o título está no Brasil (catálogo TMDB). Reproduzidos pelo app oficial. */
    val providers: List<WatchProvider> = emptyList(),
) {
    val isExternal: Boolean get() = title.isTmdb
    val available: Boolean get() = playables.any { it.hasSource }
    val seasons: List<Int> get() = playables.map { it.season }.distinct().sorted()
    val isEpisodic: Boolean get() = playables.size > 1
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
