package com.brudre.streaming.live

import com.brudre.streaming.data.Parental
import com.brudre.streaming.data.optStringOrNull
import com.brudre.streaming.sources.UrlGuard
import org.json.JSONArray
import org.json.JSONObject

/** Canal ao vivo (HLS). [mirrors] são endereços alternativos do mesmo canal, usados na reconexão. */
data class LiveChannel(
    val id: String,
    val name: String,
    val url: String,
    val mirrors: List<String> = emptyList(),
    val logo: String? = null,
    val groups: List<String> = emptyList(),
    val country: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val quality: String? = null,
    val notAlways: Boolean = false,
    val geoBlocked: Boolean = false,
    val source: String = "",
) {
    val titleId: String get() = "$PREFIX$id"
    val headers: Map<String, String>
        get() = buildMap {
            referrer?.let { put("Referer", it) }
            userAgent?.let { put("User-Agent", it) }
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url)
        .put("mirrors", JSONArray(mirrors))
        .put("logo", logo ?: JSONObject.NULL)
        .put("groups", JSONArray(groups))
        .put("country", country ?: JSONObject.NULL)
        .put("ua", userAgent ?: JSONObject.NULL)
        .put("ref", referrer ?: JSONObject.NULL)
        .put("q", quality ?: JSONObject.NULL)
        .put("na", notAlways)
        .put("geo", geoBlocked)
        .put("src", source)

    companion object {
        const val PREFIX = "live:"

        fun fromJson(o: JSONObject) = LiveChannel(
            id = o.getString("id"),
            name = o.getString("name"),
            url = o.getString("url"),
            mirrors = o.optJSONArray("mirrors")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            logo = o.optStringOrNull("logo"),
            groups = o.optJSONArray("groups")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            country = o.optStringOrNull("country"),
            userAgent = o.optStringOrNull("ua"),
            referrer = o.optStringOrNull("ref"),
            quality = o.optStringOrNull("q"),
            notAlways = o.optBoolean("na"),
            geoBlocked = o.optBoolean("geo"),
            source = o.optString("src"),
        )

        fun listToJson(list: List<LiveChannel>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
        fun listFromJson(json: String): List<LiveChannel> {
            val a = JSONArray(json)
            return (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }
    }
}

/**
 * Regras de segurança e de distribuição aplicadas a toda lista M3U:
 * sem conteúdo adulto (sempre), sem canais de TV por assinatura, só HLS por http(s).
 */
object ChannelPolicy {
    /** Marcas de TV paga no Brasil: retransmissões delas em listas abertas não são autorizadas. */
    private val PAY_TV = Regex(
        "(?i)\\b(premiere|sportv|telecine|hbo|cinemax|megapix|universal tv|universal channel|studio universal|warner|tnt|space|axn|" +
            "sony channel|sony movies|fox|fx|espn|combate|globonews|globoplay|gloob|gloobinho|discovery|history|a&e|amc|lifetime|tlc|" +
            "cartoon network|cartoonito|adult swim|disney|nickelodeon|nick jr|nicktoons|multishow|gnt|canal brasil|canal off|bis|" +
            "band ?sports|bandnews|arte ?1|curta!|prime box|woohoo|tcm|film ?& ?arts|dazn|paramount|star channel|national geographic|" +
            "nat ?geo|animal planet|comedy central|mtv|vh1|food network|hgtv|boomerang|tooncast|zoomoo|babyfirst|sexy ?hot|playboy|" +
            "venus|hustler|private tv|cnn international|telemundo|universal\\+|tv5monde|eleven sports|sky sports|bein|dsports|" +
            "nba tv|nfl network|tooncast|cine ?brasil tv|canal sony)\\b",
    )

    /** Canais FAST oficiais (gratuitos com anúncios, distribuídos pelos próprios serviços). */
    private val FAST_HOSTS = listOf(
        "pluto.tv", "jmp2.uk", "plex.tv", "samsung.wurl.tv", "wurl.tv", "wurl.com", "amagi.tv", "tubi.video", "tubi.io",
        "roku.com", "stirr.com", "xumo.com", "rakuten.tv", "distro.tv", "redbull.tv", "lg.com", "vizio.com",
    )

    private val ADULT_GROUPS = setOf("xxx", "adult", "adults", "18+")

    fun isFastHost(url: String): Boolean {
        val host = UrlGuard.hostOf(url) ?: return false
        return FAST_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun isAdult(name: String, groups: List<String>): Boolean =
        groups.any { it.lowercase() in ADULT_GROUPS } || Parental.isExplicit(name) ||
            Regex("(?i)\\b(adult|adulto|hot ?club|18\\+)\\b").containsMatchIn(name)

    fun isPayTv(name: String, url: String): Boolean = !isFastHost(url) && PAY_TV.containsMatchIn(name)

    /** Só HLS: os demais formatos de listas abertas costumam ser retransmissões cruas e instáveis. */
    fun isHls(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u") || path.contains(".m3u8/")
    }

    /** null = permitido; senão, o motivo da exclusão. */
    /**
     * null = permitido; senão, o motivo da exclusão. Só HTTPS: sinais em http puro podem ser
     * adulterados no caminho e o app mantém o tráfego sem criptografia bloqueado.
     */
    fun rejectReason(name: String, url: String, groups: List<String>): String? = when {
        isAdult(name, groups) -> "adulto"
        UrlGuard.check(url) != null -> "url"
        isPayTv(name, url) -> "pago"
        !isHls(url) -> "formato"
        !url.startsWith("https://", ignoreCase = true) -> "inseguro"
        else -> null
    }
}

object M3uParser {
    data class Result(val channels: List<LiveChannel>, val rejected: Map<String, Int>, val total: Int)

    private val ATTR = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
    private val QUALITY = Regex("\\((\\d{3,4}p)\\)")
    private val TAGS = Regex("\\[([^\\]]+)\\]")

    fun parse(text: String, source: String, limit: Int = DEFAULT_LIMIT): Result = parse(text.lineSequence(), source, limit)

    /** Lê linha a linha (sem carregar a lista inteira em memória quando vem da rede). */
    fun parse(lines: Sequence<String>, source: String, limit: Int = DEFAULT_LIMIT): Result {
        val byKey = LinkedHashMap<String, LiveChannel>()
        val seenUrls = HashSet<String>()
        val rejected = HashMap<String, Int>()
        var total = 0
        var pending: Pending? = null
        var first = true
        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            if (first) {
                first = false
                if (!line.startsWith("#EXTM3U") && !line.startsWith("#EXTINF")) throw IllegalArgumentException("O arquivo não é uma lista M3U")
                if (line.startsWith("#EXTM3U")) continue
            }
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> pending = parseExtInf(line)
                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> pending?.let { p ->
                    val opt = line.substringAfter(':')
                    when {
                        opt.startsWith("http-user-agent=", true) -> p.userAgent = opt.substringAfter('=').trim().ifBlank { null }
                        opt.startsWith("http-referrer=", true) || opt.startsWith("http-referer=", true) ->
                            p.referrer = opt.substringAfter('=').trim().ifBlank { null }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val p = pending ?: continue
                    pending = null
                    total++
                    val url = line
                    val reason = ChannelPolicy.rejectReason(p.rawName, url, p.groups)
                    if (reason != null) { rejected[reason] = (rejected[reason] ?: 0) + 1; continue }
                    if (!seenUrls.add(url)) continue
                    if (p.referrer != null && !UrlGuard.isAllowed(p.referrer)) p.referrer = null
                    val key = p.tvgId?.lowercase()?.ifBlank { null } ?: normalizeName(p.name)
                    val existing = byKey[key]
                    if (existing != null) {
                        // Mesmo canal, outro endereço: vira alternativa para reconexão.
                        if (existing.mirrors.size < MAX_MIRRORS && existing.headers == p.headers()) {
                            byKey[key] = existing.copy(mirrors = existing.mirrors + url)
                        }
                        continue
                    }
                    if (byKey.size >= limit) continue
                    byKey[key] = LiveChannel(
                        id = stableId(source, key),
                        name = p.name,
                        url = url,
                        logo = p.logo?.takeIf { UrlGuard.isAllowed(it) },
                        groups = p.groups,
                        country = p.country,
                        userAgent = p.userAgent,
                        referrer = p.referrer,
                        quality = p.quality,
                        notAlways = p.notAlways,
                        geoBlocked = p.geoBlocked,
                        source = source,
                    )
                }
            }
        }
        return Result(byKey.values.toList(), rejected, total)
    }

    private class Pending(
        val rawName: String,
        val name: String,
        val tvgId: String?,
        val logo: String?,
        val groups: List<String>,
        val country: String?,
        var userAgent: String?,
        var referrer: String?,
        val quality: String?,
        val notAlways: Boolean,
        val geoBlocked: Boolean,
    ) {
        fun headers(): Map<String, String> = buildMap {
            referrer?.let { put("Referer", it) }
            userAgent?.let { put("User-Agent", it) }
        }
    }

    private fun parseExtInf(line: String): Pending {
        val body = line.substringAfter(':')
        // A vírgula que separa atributos do nome é a primeira fora de aspas (user agents têm vírgulas).
        var inQuotes = false
        var split = -1
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) { split = i; break }
        }
        val attrPart = if (split >= 0) body.substring(0, split) else body
        val rawName = (if (split >= 0) body.substring(split + 1) else "").trim()
        val attrs = ATTR.findAll(attrPart).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val tags = TAGS.findAll(rawName).map { it.groupValues[1].lowercase() }.toList()
        val quality = QUALITY.find(rawName)?.groupValues?.get(1)
        val clean = rawName.replace(QUALITY, "").replace(TAGS, "").replace(Regex("\\s+"), " ").trim()
            .ifBlank { attrs["tvg-name"]?.trim().orEmpty() }.ifBlank { "Canal sem nome" }
        val tvgId = attrs["tvg-id"]?.trim()
        return Pending(
            rawName = rawName,
            name = clean.take(80),
            tvgId = tvgId,
            logo = attrs["tvg-logo"]?.trim()?.ifBlank { null },
            groups = attrs["group-title"]?.split(';', '|')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            country = countryOf(tvgId) ?: attrs["tvg-country"]?.take(2)?.lowercase()?.ifBlank { null },
            userAgent = attrs["http-user-agent"]?.ifBlank { null },
            referrer = (attrs["http-referrer"] ?: attrs["http-referer"])?.ifBlank { null },
            quality = quality,
            notAlways = tags.any { it.contains("not 24/7") },
            geoBlocked = tags.any { it.contains("geo-blocked") },
        )
    }

    /** "Globo.br@SD" → "br". */
    fun countryOf(tvgId: String?): String? {
        val base = tvgId?.substringBefore('@') ?: return null
        val code = base.substringAfterLast('.', "").lowercase()
        return code.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }
    }

    fun normalizeName(name: String): String =
        java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Hash FNV-1a de 64 bits: id curto e estável entre recargas da lista. */
    fun stableId(source: String, key: String): String {
        var h = -0x340d631b7bdddcdbL
        for (ch in "$source|$key") {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h)
    }

    const val DEFAULT_LIMIT = 3000
    private const val MAX_MIRRORS = 4
}

/** Organização das listas em linhas da tela TV ao Vivo. */
object LiveCategories {
    data class Category(val key: String, val label: String, val groups: Set<String>)

    /** Ordem de prioridade: um canal entra só na primeira categoria que casar. */
    val ALL = listOf(
        Category("news", "Notícias", setOf("news", "business", "weather")),
        Category("kids", "Infantil", setOf("kids", "animation", "family")),
        Category("docs", "Documentários e Educação", setOf("documentary", "education", "science", "culture", "outdoor", "travel")),
        Category("ent", "Filmes, Séries e Entretenimento", setOf("movies", "series", "entertainment", "comedy", "classic", "lifestyle", "cooking", "auto")),
        Category("sports", "Esportes", setOf("sports")),
        Category("music", "Música", setOf("music")),
        Category("religious", "Religiosos", setOf("religious")),
        Category("general", "Canais Abertos e Gerais", emptySet()),
    )

    /** Animação adulta (South Park, Adult Swim...) nunca entra em Infantil. */
    private val NOT_FOR_KIDS = Regex("(?i)\\b(south park|adult swim|family guy|simpsons|futurama|beavis|rick and morty|anime adult)\\b")

    fun categoryOf(ch: LiveChannel): Category {
        val g = ch.groups.map { it.lowercase() }.toSet()
        val kidsOk = !NOT_FOR_KIDS.containsMatchIn(ch.name) && ("kids" in g || ("animation" in g && "comedy" !in g))
        return ALL.firstOrNull { c -> if (c.key == "kids") kidsOk else c.groups.any { it in g } }
            ?: ALL.firstOrNull { c -> c.key == "ent" && "animation" in g } ?: ALL.last()
    }

    fun group(list: List<LiveChannel>): List<Pair<Category, List<LiveChannel>>> {
        val by = list.groupBy { categoryOf(it).key }
        return ALL.mapNotNull { c -> by[c.key]?.takeIf { it.isNotEmpty() }?.let { c to it } }
    }

    fun inCategory(list: List<LiveChannel>, key: String): List<LiveChannel> = list.filter { categoryOf(it).key == key }

    fun search(list: List<LiveChannel>, term: String): List<LiveChannel> {
        val q = M3uParser.normalizeName(term)
        if (q.length < 2) return emptyList()
        val words = q.split(' ')
        return list.filter { ch ->
            val n = M3uParser.normalizeName(ch.name)
            words.all { n.contains(it) }
        }.sortedBy { if (M3uParser.normalizeName(it.name).startsWith(q)) 0 else 1 }
    }
}
