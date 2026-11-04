package com.brudre.streaming.data

import org.json.JSONObject

/**
 * Índice local de fontes autorizadas (assets/authorized_sources.json).
 * Liga títulos do catálogo a .torrent/magnet oficiais publicados pelos titulares
 * ou a itens do Internet Archive. Não consulta indexadores de terceiros.
 */
class CuratedCatalog(json: String) {

    data class Entry(
        val title: Title,
        val featured: Boolean,
        val kidsFriendly: Boolean,
        val archiveId: String?,
        val playable: Playable?,
    )

    val entries: List<Entry> = parse(json)

    fun find(titleId: String): Entry? = entries.firstOrNull { it.title.id == titleId }
    fun featured(): List<Title> = entries.filter { it.featured }.map { it.title }
    fun kids(): List<Title> = entries.filter { it.kidsFriendly }.map { it.title }
    fun torrentTitles(): List<Title> = entries.filter { it.playable?.torrent != null }.map { it.title }
    fun all(): List<Title> = entries.map { it.title }

    fun search(term: String): List<Title> {
        val t = term.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        return entries.map { it.title }.filter { title ->
            title.name.lowercase().contains(t) || title.genres.any { it.lowercase().contains(t) }
        }
    }

    companion object {
        fun parse(json: String): List<Entry> {
            val arr = JSONObject(json).getJSONArray("titles")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val slug = o.getString("slug")
                val title = Title(
                    id = Title.CURATED_PREFIX + slug,
                    kind = Kind.parse(o.optString("kind")),
                    name = o.getString("name"),
                    year = o.optStringOrNull("year"),
                    synopsis = o.optStringOrNull("synopsis"),
                    durationMin = if (o.isNull("durationMin")) null else o.optInt("durationMin"),
                    genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    rating = o.optStringOrNull("rating"),
                    posterUrl = o.optStringOrNull("poster"),
                    bannerUrl = o.optStringOrNull("poster"),
                    license = o.optStringOrNull("license"),
                )
                val torrentUrl = o.optStringOrNull("torrentUrl")
                val magnet = o.optStringOrNull("magnet")
                val direct = o.optStringOrNull("direct")
                val subs = o.optJSONArray("subtitles")?.let { a ->
                    (0 until a.length()).map { SubtitleRef.fromJson(a.getJSONObject(it).let { s -> JSONObject().put("url", s.getString("url")).put("lang", s.optString("lang")).put("label", s.optString("label")) }) }
                } ?: emptyList()
                val playable = if (torrentUrl != null || magnet != null || direct != null) {
                    Playable(
                        key = "main",
                        label = title.name,
                        season = 1,
                        number = 1,
                        directUrl = direct,
                        torrent = if (torrentUrl != null || magnet != null) TorrentRef(torrentUrl, magnet, o.optStringOrNull("file")) else null,
                        subtitles = subs,
                        durationSec = title.durationMin?.times(60),
                        preferTorrent = true,
                    )
                } else null
                Entry(
                    title = title,
                    featured = o.optBoolean("featured", false),
                    kidsFriendly = o.optBoolean("kidsFriendly", false),
                    archiveId = o.optStringOrNull("archiveId"),
                    playable = playable,
                )
            }
        }
    }
}
