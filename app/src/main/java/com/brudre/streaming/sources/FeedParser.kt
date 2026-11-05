package com.brudre.streaming.sources

import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Parental
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.SubtitleRef
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TorrentRef
import com.brudre.streaming.data.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Fonte de vídeo de um item de catálogo externo. */
data class FeedSource(val type: Type, val url: String, val file: String? = null) {
    enum class Type { HTTP, HLS, MAGNET, TORRENT }
}

data class FeedEpisode(val season: Int, val number: Int, val label: String, val sources: List<FeedSource>)

data class FeedItem(val title: Title, val sources: List<FeedSource>, val episodes: List<FeedEpisode> = emptyList(), val subtitles: List<SubtitleRef> = emptyList()) {
    /** Uma unidade reproduzível por filme/episódio, juntando HTTP/HLS (diretas) e torrent. */
    fun playables(): List<Playable> {
        if (episodes.isNotEmpty()) {
            return episodes.mapNotNull { e -> FeedParser.playable("${title.id}:s${e.season}e${e.number}", e.label, e.season, e.number, e.sources, subtitles) }
        }
        return listOfNotNull(FeedParser.playable(title.id, title.name, 1, 1, sources, subtitles))
    }
}

data class Feed(val name: String, val items: List<FeedItem>, val rejected: Int)

/**
 * Catálogos externos cadastrados pelo usuário: feed JSON do BruDre ou RSS com magnet/.torrent/vídeo.
 * Toda URL passa pelo [UrlGuard]; magnets são validados; conteúdo adulto é descartado.
 *
 * Formato JSON:
 * ```
 * {"name": "Minha fonte", "items": [{"id": "x", "title": "Nome", "year": "2010", "kind": "MOVIE",
 *   "synopsis": "...", "poster": "https://...", "license": "CC BY",
 *   "sources": [{"type": "magnet", "url": "magnet:?xt=..."}, {"type": "http", "url": "https://.../x.mp4"}],
 *   "episodes": [{"season": 1, "number": 1, "title": "Ep. 1", "sources": [...]}]}]}
 * ```
 */
object FeedParser {
    const val PREFIX = "feed:"
    const val MAX_ITEMS = 500

    fun parse(text: String, feedId: String): Feed {
        val t = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            t.startsWith("{") -> parseJson(t, feedId)
            t.startsWith("<") -> parseRss(t, feedId)
            else -> throw IllegalArgumentException("Formato não reconhecido (use JSON do BruDre ou RSS)")
        }
    }

    // ------------------------------------------------------------------ JSON

    private fun parseJson(text: String, feedId: String): Feed {
        val root = JSONObject(text)
        val arr = root.optJSONArray("items") ?: throw IllegalArgumentException("Feed sem \"items\"")
        val out = ArrayList<FeedItem>()
        var rejected = 0
        for (i in 0 until minOf(arr.length(), MAX_ITEMS)) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optStringOrNull("title") ?: o.optStringOrNull("name") ?: run { rejected++; continue }
            val synopsis = o.optStringOrNull("synopsis") ?: o.optStringOrNull("description")
            if (Parental.isExplicit(name, synopsis)) { rejected++; continue }
            val sources = sources(o.optJSONArray("sources"))
            val episodes = o.optJSONArray("episodes")?.let { ea ->
                (0 until ea.length()).mapNotNull { j ->
                    val e = ea.optJSONObject(j) ?: return@mapNotNull null
                    val s = sources(e.optJSONArray("sources"))
                    if (s.isEmpty()) null else FeedEpisode(e.optInt("season", 1), e.optInt("number", j + 1), e.optString("title", "Episódio ${j + 1}"), s)
                }
            } ?: emptyList()
            if (sources.isEmpty() && episodes.isEmpty()) { rejected++; continue }
            val itemId = o.optStringOrNull("id") ?: com.brudre.streaming.live.M3uParser.stableId(feedId, name)
            val kind = Kind.parse(o.optStringOrNull("kind")?.uppercase()).let { if (it == Kind.LIVE) Kind.MOVIE else it }
            val poster = o.optStringOrNull("poster")?.takeIf { UrlGuard.isAllowed(it) }
            out += FeedItem(
                Title(
                    id = "$PREFIX$feedId:${itemId.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)}",
                    kind = if (episodes.isNotEmpty() && kind == Kind.MOVIE) Kind.SERIES else kind,
                    name = name.take(120),
                    year = o.optStringOrNull("year")?.take(4),
                    synopsis = synopsis?.take(900),
                    durationMin = o.optInt("durationMin", 0).takeIf { it > 0 },
                    genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) }.take(3) } ?: emptyList(),
                    rating = o.optStringOrNull("rating"),
                    posterUrl = poster,
                    bannerUrl = o.optStringOrNull("banner")?.takeIf { UrlGuard.isAllowed(it) } ?: poster,
                    license = o.optStringOrNull("license") ?: "Fonte externa",
                ),
                sources, episodes,
                subtitles = o.optJSONArray("subtitles")?.let { sa ->
                    (0 until sa.length()).mapNotNull { k ->
                        sa.optJSONObject(k)?.let { s -> s.optStringOrNull("url")?.takeIf { UrlGuard.isAllowed(it) }?.let { SubtitleRef(it, s.optString("lang", "und"), s.optString("label", s.optString("lang"))) } }
                    }
                } ?: emptyList(),
            )
        }
        return Feed(root.optString("name", "Fonte externa"), out.distinctBy { it.title.id }, rejected)
    }

    private fun sources(arr: JSONArray?): List<FeedSource> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            source(o.optString("type"), o.optString("url"), o.optStringOrNull("file"))
        }
    }

    /** Valida e classifica uma fonte; null se insegura ou inválida. */
    fun source(type: String?, url: String?, file: String? = null): FeedSource? {
        val u = url?.trim() ?: return null
        if (u.startsWith("magnet:", true)) return if (isValidMagnet(u)) FeedSource(FeedSource.Type.MAGNET, u, file) else null
        if (!UrlGuard.isAllowed(u)) return null
        val path = u.substringBefore('?').lowercase()
        val t = when {
            type.equals("torrent", true) || path.endsWith(".torrent") -> FeedSource.Type.TORRENT
            type.equals("hls", true) || path.endsWith(".m3u8") -> FeedSource.Type.HLS
            type.equals("http", true) || type.equals("https", true) || type.equals("video", true) ||
                path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm") || path.endsWith(".mkv") -> FeedSource.Type.HTTP
            else -> return null
        }
        // Só HTTPS para mídia (o app não permite tráfego sem criptografia).
        if (!u.startsWith("https://", true)) return null
        return FeedSource(t, u, file)
    }

    private val BTIH = Regex("(?i)[?&]xt=urn:(btih:([a-f0-9]{40}|[a-z2-7]{32})|btmh:1220[a-f0-9]{64})(&|$)")

    /** Magnet com hash de torrent válido (v1 hex/base32 ou v2), sem caracteres de controle. */
    fun isValidMagnet(u: String): Boolean = u.length <= 4096 && u.none { it.isWhitespace() || it.code < 0x20 } && BTIH.containsMatchIn(u)

    fun playable(key: String, label: String, season: Int, number: Int, sources: List<FeedSource>, subtitles: List<SubtitleRef>): Playable? {
        val direct = sources.filter { it.type == FeedSource.Type.HTTP || it.type == FeedSource.Type.HLS }.map { it.url }.distinct()
        val magnet = sources.firstOrNull { it.type == FeedSource.Type.MAGNET }
        val torrentFile = sources.firstOrNull { it.type == FeedSource.Type.TORRENT }
        val torrent = if (magnet != null || torrentFile != null) TorrentRef(torrentFile?.url, magnet?.url, magnet?.file ?: torrentFile?.file) else null
        if (direct.isEmpty() && torrent == null) return null
        return Playable(key, label, season, number, direct.firstOrNull(), torrent, subtitles, null, preferTorrent = false, mirrors = direct.drop(1))
    }

    // ------------------------------------------------------------------- RSS

    private val ITEM = Regex("<item\\b[^>]*>(.*?)</item>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private fun tag(xml: String, name: String): String? =
        Regex("<$name\\b[^>]*>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(xml)?.groupValues?.get(1)?.let { unescape(it).trim() }

    private fun unescape(s: String) = s.replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    private fun parseRss(xml: String, feedId: String): Feed {
        if (!xml.contains("<rss", true) && !xml.contains("<channel", true)) throw IllegalArgumentException("XML não é um feed RSS")
        val channelTitle = tag(xml.substringBefore("<item"), "title") ?: "Feed RSS"
        val out = ArrayList<FeedItem>()
        var rejected = 0
        for (m in ITEM.findAll(xml).take(MAX_ITEMS)) {
            val body = m.groupValues[1]
            val name = tag(body, "title") ?: run { rejected++; continue }
            val desc = tag(body, "description")?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
            if (Parental.isExplicit(name, desc)) { rejected++; continue }
            val candidates = ArrayList<Pair<String?, String>>()
            Regex("<enclosure\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(body).forEach { e ->
                val attrs = e.groupValues[1]
                val url = Regex("url=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)?.let(::unescape)
                val type = Regex("type=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                if (url != null) candidates += (if (type?.contains("bittorrent") == true) "torrent" else if (type?.contains("mpegurl") == true) "hls" else "http") to url
            }
            Regex("name=\"magneturl\"\\s+value=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(body)?.let { candidates += "magnet" to unescape(it.groupValues[1]) }
            tag(body, "link")?.let { candidates += null to it }
            val sources = candidates.mapNotNull { (t, u) -> source(t, u) }.distinctBy { it.url }
            if (sources.isEmpty()) { rejected++; continue }
            val guid = tag(body, "guid") ?: name
            out += FeedItem(
                Title(
                    id = "$PREFIX$feedId:${com.brudre.streaming.live.M3uParser.stableId(feedId, guid)}",
                    kind = Kind.MOVIE, name = name.take(120), synopsis = desc?.take(900),
                    year = Regex("\\b(19|20)\\d{2}\\b").find(name)?.value,
                    license = "Fonte externa (RSS)",
                ),
                sources,
            )
        }
        return Feed(channelTitle, out.distinctBy { it.title.id }, rejected)
    }

    fun itemsToJson(items: List<FeedItem>): String = JSONArray().apply {
        items.forEach { it ->
            put(JSONObject().put("t", it.title.toJson())
                .put("s", JSONArray().apply { it.sources.forEach { s -> put(JSONObject().put("type", s.type.name).put("url", s.url).put("file", s.file ?: JSONObject.NULL)) } })
                .put("e", JSONArray().apply { it.episodes.forEach { e -> put(JSONObject().put("season", e.season).put("number", e.number).put("label", e.label)
                    .put("s", JSONArray().apply { e.sources.forEach { s -> put(JSONObject().put("type", s.type.name).put("url", s.url).put("file", s.file ?: JSONObject.NULL)) } })) } })
                .put("sub", JSONArray().apply { it.subtitles.forEach { sb -> put(sb.toJson()) } }))
        }
    }.toString()

    fun itemsFromJson(json: String): List<FeedItem> {
        fun src(a: JSONArray) = (0 until a.length()).map { i -> a.getJSONObject(i).let { FeedSource(FeedSource.Type.valueOf(it.getString("type")), it.getString("url"), it.optStringOrNull("file")) } }
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            FeedItem(
                Title.fromJson(o.getJSONObject("t")),
                src(o.getJSONArray("s")),
                o.optJSONArray("e")?.let { ea -> (0 until ea.length()).map { j -> ea.getJSONObject(j).let { e -> FeedEpisode(e.getInt("season"), e.getInt("number"), e.getString("label"), src(e.getJSONArray("s"))) } } } ?: emptyList(),
                o.optJSONArray("sub")?.let { sa -> (0 until sa.length()).map { SubtitleRef.fromJson(sa.getJSONObject(it)) } } ?: emptyList(),
            )
        }
    }
}
