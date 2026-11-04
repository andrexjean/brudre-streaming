package com.brudre.streaming.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Conversão das respostas JSON do Internet Archive em [Title] e [Playable].
 * Funções puras (sem rede) para permitir testes unitários.
 */
object ArchiveParser {

    const val BASE = "https://archive.org"

    data class IaFile(
        val name: String,
        val format: String,
        val source: String,
        val size: Long?,
        val lengthSec: Int?,
        val height: Int?,
        val title: String?,
    )

    // ---------------------------------------------------------------- busca

    fun parseSearch(json: String, defaultKind: Kind): List<Title> {
        val root = JSONObject(json)
        if (root.has("error")) throw IllegalStateException("Internet Archive: ${root.optString("error")}")
        val docs = root.optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        val out = ArrayList<Title>(docs.length())
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val id = d.optString("identifier").takeIf { it.isNotBlank() } ?: continue
            val collections = values(d.opt("collection"))
            out += Title(
                id = Title.ARCHIVE_PREFIX + id,
                kind = kindFor(collections, defaultKind),
                name = cleanText(first(d.opt("title")) ?: id).take(120),
                year = yearOf(d),
                synopsis = first(d.opt("description"))?.let { cleanText(it).take(420) }?.takeIf { it.isNotBlank() },
                durationMin = parseDuration(first(d.opt("runtime")))?.let { (it + 59) / 60 },
                genres = genresOf(d.opt("subject")),
                rating = null,
                posterUrl = thumb(id),
                bannerUrl = thumb(id),
                license = "Internet Archive",
            )
        }
        return out
    }

    fun kindFor(collections: List<String>, fallback: Kind): Kind {
        val c = collections.map { it.lowercase() }
        return when {
            c.any { it == "classic_cartoons" || it == "animationandcartoons" } -> Kind.KIDS
            c.any { it == "classic_tv" || it == "television" } -> Kind.SERIES
            c.any { it == "feature_films" || it == "scifi_horror" || it == "film_noir" || it == "comedy_films" || it == "silent_films" } -> Kind.MOVIE
            else -> fallback
        }
    }

    // ------------------------------------------------------------- metadados

    /** Atualiza [base] com os metadados completos do item (sinopse, duração, gêneros). */
    fun enrichTitle(base: Title, json: String): Title {
        val meta = JSONObject(json).optJSONObject("metadata") ?: return base
        val synopsis = first(meta.opt("description"))?.let { cleanText(it).take(900) }?.takeIf { it.isNotBlank() }
        return base.copy(
            name = first(meta.opt("title"))?.let { cleanText(it).take(120) } ?: base.name,
            year = yearOf(meta) ?: base.year,
            synopsis = synopsis ?: base.synopsis,
            durationMin = parseDuration(first(meta.opt("runtime")))?.let { (it + 59) / 60 } ?: base.durationMin,
            genres = genresOf(meta.opt("subject")).ifEmpty { base.genres },
            license = licenseLabel(first(meta.opt("licenseurl"))),
        )
    }

    fun isRestricted(json: String): Boolean {
        val meta = JSONObject(json).optJSONObject("metadata") ?: return false
        return first(meta.opt("access-restricted-item"))?.equals("true", true) == true
    }

    fun parseFiles(json: String): List<IaFile> {
        val files = JSONObject(json).optJSONArray("files") ?: return emptyList()
        val out = ArrayList<IaFile>(files.length())
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            out += IaFile(
                name = f.optString("name"),
                format = f.optString("format"),
                source = f.optString("source"),
                size = f.optString("size").toLongOrNull(),
                lengthSec = parseDuration(f.optString("length")),
                height = f.optString("height").toIntOrNull(),
                title = f.optStringOrNull("title"),
            )
        }
        return out
    }

    /**
     * Monta a lista de episódios/partes reproduzíveis de um item.
     * Só considera MP4/M4V (H.264/AAC na prática), adequados ao Mi TV Stick.
     */
    fun buildPlayables(identifier: String, json: String, quality: Quality, kind: Kind = Kind.SERIES): List<Playable> {
        if (isRestricted(json)) return emptyList()
        val files = parseFiles(json)
        val torrentFile = files.firstOrNull { it.format.equals("Archive BitTorrent", true) || it.name == "${identifier}_archive.torrent" }
        val torrentUrl = torrentFile?.let { downloadUrl(identifier, it.name) }

        val videos = files.filter { isPlayableVideo(it) }
        if (videos.isEmpty()) return emptyList()

        val groups = videos.groupBy { groupKey(it.name) }
        var chosen = groups.map { (key, list) -> key to list.minWith(compareBy<IaFile>({ rank(it, quality) }, { it.size ?: Long.MAX_VALUE })) }
            .sortedWith { a, b -> naturalCompare(a.first, b.first) }
            .take(300)
        if (kind != Kind.SERIES && chosen.size > 1) chosen = collapseMovieVariants(chosen, quality)

        val subtitles = files.filter { it.name.endsWith(".srt", true) || it.name.endsWith(".vtt", true) }

        return chosen.mapIndexed { index, (key, file) ->
            val (season, number) = seasonEpisode(file.name) ?: (1 to index + 1)
            val single = chosen.size == 1
            val subs = subtitles
                .filter { single || it.name.startsWith(key, ignoreCase = true) }
                .map { sub ->
                    val lang = languageOf(sub.name)
                    val auto = isAutoSubtitle(sub.name)
                    SubtitleRef(downloadUrl(identifier, sub.name), lang, languageLabel(lang) + if (auto) " (automática)" else "")
                }
            Playable(
                key = if (chosen.size == 1) "main" else "f:$key",
                label = file.title?.let { cleanText(it) } ?: prettify(key),
                season = season,
                number = number,
                directUrl = downloadUrl(identifier, file.name),
                torrent = torrentUrl?.let { TorrentRef(it, null, file.name) },
                subtitles = subs,
                durationSec = file.lengthSec,
                preferTorrent = false,
            )
        }.sortedWith(compareBy({ it.season }, { it.number }))
    }

    /**
     * Filmes costumam ter versões duplicadas (ex.: "ed_hd" e "ed_1024") ou o filme inteiro
     * junto com as partes do DVD. Mantém só uma versão quando os arquivos são equivalentes.
     */
    fun collapseMovieVariants(chosen: List<Pair<String, IaFile>>, quality: Quality): List<Pair<String, IaFile>> {
        // 1) versões com a mesma duração (±2%) são o mesmo vídeo
        val distinct = ArrayList<Pair<String, IaFile>>()
        for (item in chosen.sortedBy { rank(it.second, quality) }) {
            val len = item.second.lengthSec
            val dup = len != null && distinct.any { d -> d.second.lengthSec?.let { kotlin.math.abs(it - len) <= maxOf(3, len / 50) } == true }
            if (!dup) distinct += item
        }
        val ordered = chosen.filter { c -> distinct.any { it.first == c.first } }
        // 2) descarta trechos curtíssimos (menus de DVD, vinhetas) quando há vídeos longos
        val longest = ordered.maxOfOrNull { it.second.lengthSec ?: 0 } ?: 0
        val meaningful = if (longest >= 600) ordered.filter { (it.second.lengthSec ?: longest) >= 120 } else ordered
        // 3) se um arquivo é o filme inteiro (>= 2x o segundo maior), usa só ele
        val byLen = meaningful.sortedByDescending { it.second.lengthSec ?: 0 }
        if (byLen.size > 1) {
            val first = byLen[0].second.lengthSec ?: 0
            val second = byLen[1].second.lengthSec ?: 0
            if (first > 0 && first >= second * 2) return listOf(byLen[0])
        }
        return meaningful
    }

    fun isAutoSubtitle(name: String): Boolean {
        val n = name.lowercase()
        return n.contains(".asr.") || n.contains("autogenerated")
    }

    private val UNSAFE = Regex("(?i)\\b(sex|sexy|nude|nudist|naked|erotic|erotica|porn|xxx|orgy|striptease|lust)\\b")

    /** Filtro local de conteúdo adulto (o catálogo é para a família). */
    fun isFamilySafe(t: Title): Boolean =
        !UNSAFE.containsMatchIn(t.name) && t.genres.none { UNSAFE.containsMatchIn(it) }

    fun isPlayableVideo(f: IaFile): Boolean {
        val n = f.name.lowercase()
        if (!(n.endsWith(".mp4") || n.endsWith(".m4v"))) return false
        if (n.contains("thumb") || n.contains("sample") || n.contains("trailer")) return false
        if ((f.height ?: 0) > 1088) return false
        return true
    }

    fun groupKey(name: String): String {
        val base = name.substringAfterLast('/').substringBeforeLast('.')
        return base.replace(Regex("(?i)(_512kb|\\.ia|_hd|_h264|\\.h264)$"), "")
    }

    fun rank(f: IaFile, quality: Quality): Int {
        val fmt = f.format.lowercase()
        val isLight = fmt.contains("512kb") || f.name.lowercase().contains("_512kb")
        val base = when (quality) {
            Quality.BEST -> when {
                fmt == "h.264 hd" -> 0
                fmt == "h.264" -> 1
                fmt == "h.264 ia" -> 2
                fmt == "mpeg4" -> 3
                isLight -> 4
                else -> 5
            }
            Quality.LIGHT -> when {
                isLight -> 0
                fmt == "h.264 ia" -> 1
                fmt == "h.264" -> 2
                fmt == "mpeg4" -> 3
                fmt == "h.264 hd" -> 4
                else -> 5
            }
        }
        return base * 10 + if (f.source.equals("original", true)) 1 else 0
    }

    fun seasonEpisode(name: String): Pair<Int, Int>? {
        Regex("(?i)s(\\d{1,2})[ ._-]?e(\\d{1,3})").find(name)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        Regex("(?<![0-9])(\\d{1,2})x(\\d{2})(?![0-9])").find(name)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        return null
    }

    fun languageOf(name: String): String {
        val n = name.lowercase()
        return when {
            Regex("[._-](pt|por|pt-br|ptbr|portuguese|portugues)[._-]").containsMatchIn(n) -> "pt"
            Regex("[._-](es|spa|spanish|espanol)[._-]").containsMatchIn(n) -> "es"
            Regex("[._-](en|eng|english)[._-]").containsMatchIn(n) -> "en"
            isAutoSubtitle(n) -> "en"
            else -> "und"
        }
    }

    fun languageLabel(code: String) = when (code) {
        "pt" -> "Português"; "en" -> "English"; "es" -> "Español"; else -> "Legenda"
    }

    // ---------------------------------------------------------------- URLs

    fun thumb(id: String) = "$BASE/services/img/${enc(id)}"

    fun downloadUrl(id: String, fileName: String): String =
        "$BASE/download/${enc(id)}/" + fileName.split('/').joinToString("/") { enc(it) }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // -------------------------------------------------------------- helpers

    fun values(v: Any?): List<String> = when (v) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it)?.toString() }
        else -> listOf(v.toString())
    }

    fun first(v: Any?): String? = values(v).firstOrNull()?.takeIf { it.isNotBlank() }

    private fun yearOf(o: JSONObject): String? {
        first(o.opt("year"))?.let { y -> Regex("\\d{4}").find(y)?.let { return it.value } }
        first(o.opt("date"))?.let { d -> Regex("\\d{4}").find(d)?.let { return it.value } }
        return null
    }

    fun genresOf(v: Any?): List<String> = values(v)
        .flatMap { it.split(';', ',') }
        .map { it.trim() }
        .filter { it.length in 3..24 && !it.contains("http") && !it.equals("movies", true) && !it.equals("feature films", true) }
        .map { it.replaceFirstChar { c -> c.uppercase() } }
        .distinctBy { it.lowercase() }
        .take(3)

    /** Aceita "5400.12", "90", "1:30:00", "90:00". Retorna segundos. */
    fun parseDuration(v: String?): Int? {
        if (v.isNullOrBlank()) return null
        val s = v.trim()
        s.toDoubleOrNull()?.let { return if (it > 0) it.toInt() else null }
        val parts = s.split(':').map { it.trim().toDoubleOrNull() ?: return null }
        val sec = when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> return null
        }
        return sec.toInt().takeIf { it > 0 }
    }

    fun cleanText(raw: String): String = raw
        .replace(Regex("(?i)<br\\s*/?>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun prettify(key: String): String = key.replace('_', ' ').replace('.', ' ').replace(Regex("\\s+"), " ").trim()

    private fun licenseLabel(url: String?): String = when {
        url == null -> "Internet Archive"
        url.contains("publicdomain", true) -> "Domínio público — Internet Archive"
        url.contains("creativecommons", true) -> "Creative Commons — Internet Archive"
        else -> "Internet Archive"
    }

    fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                (x.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO).compareTo(y.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }
}
