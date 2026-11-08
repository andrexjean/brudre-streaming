package com.brudre.streaming.sources

import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Parental
import com.brudre.streaming.data.Title
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Torznab 1.3 e Newznab — APIs de indexação baseadas em RSS 2.0 com atributos (`torznab:attr`/`newznab:attr`).
 *
 * Adaptador genérico: não conhece nenhum indexador; o endpoint é cadastrado pelo usuário.
 * - Capacidades: `t=caps` (funções de pesquisa, parâmetros aceitos, categorias, limites).
 * - Pesquisa: `t=search` (texto), `t=movie` (imdbid/tmdbid), `t=tvsearch` (season/ep/tmdbid/imdbid/tvdbid).
 * - Paginação: `offset` + `limit` (limite máximo declarado em caps); total em `<torznab:response total>`.
 * - Autenticação: `apikey` na consulta (padrão do protocolo), guardada cifrada no [CredentialStore].
 * - Erros do protocolo: `<error code="…" description="…"/>` (100–103 = credenciais, 200–203 = parâmetros, 900+ = servidor).
 *
 * Só entra no BruDre o que é reproduzível: magnet válido, .torrent HTTPS, ou hash (infohash → magnet).
 * Resultados NZB (Usenet) são descartados: o app não tem cliente NNTP. Categoria 6000 (adulto) é sempre bloqueada;
 * categorias fora de filmes (2000) e TV (5000) são ignoradas.
 */
data class TorznabCaps(
    /** Endpoint sem `t`/`apikey` (pode manter outros parâmetros do servidor). */
    val base: String,
    val flavor: String,
    val server: String?,
    val search: Set<String>,
    val tv: Set<String>?,
    val movie: Set<String>?,
    val categories: List<Pair<Int, String>>,
    val limitMax: Int,
    val limitDefault: Int,
) {
    val hasCategories get() = categories.isNotEmpty()

    fun toConfig(): String = JSONObject()
        .put("base", base).put("flavor", flavor).put("server", server ?: "")
        .put("search", JSONArray(search.toList()))
        .put("tv", tv?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
        .put("movie", movie?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
        .put("categories", JSONArray().apply { categories.forEach { (id, n) -> put(JSONObject().put("id", id).put("name", n)) } })
        .put("limitMax", limitMax).put("limitDefault", limitDefault)
        .toString()

    /** Resumo para a tela de diagnóstico. */
    val summary: String get() = listOfNotNull(
        flavor.replaceFirstChar { it.uppercase() } + (server?.let { " ($it)" } ?: ""),
        "texto",
        movie?.let { "filmes" + if (it.any { p -> p == "imdbid" || p == "tmdbid" }) " por id" else "" },
        tv?.let { "séries" + if ("season" in it) " por temporada/episódio" else "" },
        "${categories.size} categorias",
        "até $limitMax por página",
    ).joinToString(" • ")

    companion object {
        fun fromConfig(json: String): TorznabCaps = JSONObject(json).let { o ->
            fun set(k: String): Set<String>? = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val cats = o.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { c -> c.getInt("id") to c.optString("name") } } } ?: emptyList()
            TorznabCaps(o.getString("base"), o.optString("flavor", "torznab"), o.optString("server").ifBlank { null }, set("search") ?: setOf("q"), set("tv"), set("movie"), cats, o.optInt("limitMax", 100), o.optInt("limitDefault", 50))
        }
    }
}

object Torznab {
    const val CAT_MOVIES = 2000
    const val CAT_TV = 5000
    const val CAT_TV_DOCUMENTARY = 5080
    const val CAT_ADULT = 6000

    /** Separa `apikey` (vai para o armazenamento cifrado) e `t` do endereço digitado. */
    fun splitKey(url: String): Pair<String, String?> {
        val u = url.trim()
        val q = u.substringAfter('?', "")
        if (q.isEmpty()) return u to null
        var key: String? = null
        val keep = q.split('&').filter { p ->
            val name = p.substringBefore('=').lowercase()
            when (name) {
                "apikey" -> { key = java.net.URLDecoder.decode(p.substringAfter('=', ""), "UTF-8").ifBlank { null }; false }
                "t", "q", "cat", "offset", "limit", "extended" -> false
                else -> p.isNotBlank()
            }
        }
        val base = u.substringBefore('?') + if (keep.isEmpty()) "" else "?" + keep.joinToString("&")
        return base to key
    }

    /** Candidatos de endpoint: como digitado e, se não termina em /api, com /api (Jackett: …/torznab/ → …/torznab/api). */
    fun candidates(base: String): List<String> {
        val path = base.substringBefore('?').trimEnd('/')
        val query = base.substringAfter('?', "")
        val withApi = if (path.endsWith("/api", true)) null else "$path/api" + if (query.isEmpty()) "" else "?$query"
        return listOfNotNull(base, withApi).distinct()
    }

    fun url(base: String, params: List<Pair<String, String?>>, key: String?): String {
        val all = params.filter { !it.second.isNullOrEmpty() } + listOfNotNull(key?.let { "apikey" to it })
        val sep = if (base.contains('?')) "&" else "?"
        return base + sep + all.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
    }

    fun capsUrl(base: String, key: String?) = url(base, listOf("t" to "caps"), key)

    private val ERROR = Regex("<error\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
    private fun attr(attrs: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)?.let(::unescape)

    private fun unescape(s: String) = s.replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    /** Erro do protocolo → falha classificada (sem a apikey na mensagem). */
    fun checkError(xml: String) {
        val m = ERROR.find(xml.take(4096)) ?: return
        val code = attr(m.groupValues[1], "code")?.toIntOrNull() ?: 0
        val desc = attr(m.groupValues[1], "description")?.replace(Regex("(?i)apikey=[^&\\s\"]+"), "apikey=***")?.take(200) ?: "sem descrição"
        throw when (code) {
            in 100..199 -> ClassifiedException(ErrorKind.AUTH, "Credencial recusada pelo indexador (erro $code: $desc). Confira a chave em \"Credencial\".")
            in 200..299 -> ClassifiedException(ErrorKind.FORMAT, "O indexador recusou os parâmetros (erro $code: $desc)")
            in 500..599 -> ClassifiedException(ErrorKind.HTTP, "Limite de consultas do indexador atingido (erro $code: $desc)")
            else -> ClassifiedException(ErrorKind.HTTP, "O indexador respondeu erro $code: $desc")
        }
    }

    private fun supported(block: String?): Set<String>? {
        if (block == null) return null
        if (attr(block, "available")?.equals("yes", true) != true) return null
        return (attr(block, "supportedParams") ?: "q").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Lê `<caps>`; [base] = endpoint que respondeu. */
    fun parseCaps(xml: String, base: String): TorznabCaps {
        val t = xml.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        checkError(t)
        if (!Regex("<caps\\b", RegexOption.IGNORE_CASE).containsMatchIn(t.take(8192))) throw ClassifiedException(ErrorKind.FORMAT, "O endereço não respondeu às capacidades Torznab/Newznab (t=caps)")
        val server = Regex("<server\\b([^>]*)/?>", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
        val serverName = server?.let { attr(it, "title") ?: attr(it, "appname") }?.take(60)
        val limits = Regex("<limits\\b([^>]*)/?>", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
        val max = limits?.let { attr(it, "max")?.toIntOrNull() } ?: 100
        val def = limits?.let { attr(it, "default")?.toIntOrNull() } ?: minOf(max, 50)
        fun fn(name: String) = Regex("<$name\\b([^>]*)/?>", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
        val search = supported(fn("search")) ?: setOf("q")
        val tv = supported(fn("tv-search"))
        val movie = supported(fn("movie-search"))
        val cats = Regex("<category\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(t).mapNotNull { m ->
            val id = attr(m.groupValues[1], "id")?.toIntOrNull() ?: return@mapNotNull null
            id to (attr(m.groupValues[1], "name") ?: id.toString())
        }.filter { it.first / 1000 != CAT_ADULT / 1000 }.distinctBy { it.first }.toList()
        val flavor = if (t.contains("torznab", true) || cats.isEmpty() || !t.contains("newznab", true)) "torznab" else "newznab"
        return TorznabCaps(base, flavor, serverName, search, tv, movie, cats, max.coerceIn(1, 500), def.coerceIn(1, 500))
    }

    data class Release(val name: String, val year: String?, val season: Int?, val episode: Int?, val quality: String?)

    private val SE = Regex("(?i)\\bS(\\d{1,2})[ ._-]?E(\\d{1,3})\\b")
    private val X = Regex("(?i)\\b(\\d{1,2})x(\\d{2,3})\\b")
    private val SEASON = Regex("(?i)\\b(?:S(\\d{1,2})|Season[ ._-]?(\\d{1,2})|Temporada[ ._-]?(\\d{1,2}))\\b")
    private val YEAR = Regex("\\b(19[2-9]\\d|20\\d{2})\\b")
    private val TAGS = Regex("(?i)\\b(2160p|1080p|720p|576p|480p|4k|uhd|web-?dl|web-?rip|webrip|bluray|blu-ray|brrip|bdrip|hdtv|dvdrip|x26[45]|h\\.?26[45]|hevc|avc|aac|ac3|dts|remux|proper|repack|complete|dual|legendado|dublado)\\b")

    /** "Night.of.the.Living.Dead.1968.1080p.WEB" → nome, ano, temporada/episódio, qualidade. */
    fun release(raw: String): Release {
        val t = raw.replace('_', ' ').replace(Regex("(?<=[^\\s.])\\.(?=[^\\s.])"), " ").replace(Regex("\\s+"), " ").trim()
        val se = SE.find(t) ?: X.find(t)
        val seasonOnly = if (se == null) SEASON.find(t) else null
        // Ano: entre parênteses/colchetes se houver; senão o último ano plausível do nome ("Blade Runner 2049 2017" → 2017).
        val maxYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1
        val years = YEAR.findAll(t).filter { it.range.first > 0 && it.value.toInt() <= maxYear }.toList()
        val year = years.firstOrNull { m -> m.range.first > 0 && t[m.range.first - 1] in "([" } ?: years.lastOrNull { m -> TAGS.find(t)?.let { m.range.first < it.range.first } ?: true }
        val tag = TAGS.find(t)?.takeIf { it.range.first > 0 }
        val cut = listOfNotNull(se?.range?.first, seasonOnly?.range?.first, year?.range?.first, tag?.range?.first).filter { it > 0 }.minOrNull() ?: t.length
        val name = t.substring(0, cut).replace(Regex("[\\[(\\-–:]+\\s*$"), "").trim().ifBlank { t }
        val season = se?.groupValues?.get(1)?.toIntOrNull() ?: seasonOnly?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
        return Release(name.take(120), year?.value, season, se?.groupValues?.get(2)?.toIntOrNull(), FeedParser.qualityOf(t))
    }

    data class Parsed(val items: List<FeedItem>, val total: Int?, val offset: Int?, val rawCount: Int, val rejected: Map<String, Int>)

    private val ITEM = Regex("<item\\b[^>]*>(.*?)</item>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private fun tag(xml: String, name: String): String? =
        Regex("<$name\\b[^>]*>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(xml)?.groupValues?.get(1)?.let { unescape(it).trim() }

    /** Interpreta a resposta RSS de uma pesquisa. [sourceId] compõe os ids dos títulos. */
    fun parseResults(xml: String, sourceId: String): Parsed {
        val t = xml.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        checkError(t)
        if (!t.startsWith("<") || !(t.contains("<rss", true) || t.contains("<channel", true))) throw ClassifiedException(ErrorKind.FORMAT, "O indexador deveria responder RSS (Torznab/Newznab)")
        val resp = Regex("<(?:torznab|newznab):response\\b([^>]*)/?>", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
        val total = resp?.let { attr(it, "total")?.toIntOrNull() }
        val offset = resp?.let { attr(it, "offset")?.toIntOrNull() }
        val rejected = LinkedHashMap<String, Int>()
        fun reject(why: String) { rejected[why] = (rejected[why] ?: 0) + 1 }
        val feedId = sourceId.replace('_', '-')
        val out = ArrayList<Pair<FeedItem, Int>>()
        var raw = 0
        for (m in ITEM.findAll(t).take(FeedParser.MAX_ITEMS)) {
            raw++
            val body = m.groupValues[1]
            val title = tag(body, "title")?.takeIf { it.isNotBlank() } ?: run { reject("sem título"); null } ?: continue
            val attrs = HashMap<String, MutableList<String>>()
            Regex("<(?:torznab|newznab):attr\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(body).forEach { a ->
                val n = attr(a.groupValues[1], "name")?.lowercase() ?: return@forEach
                val v = attr(a.groupValues[1], "value") ?: return@forEach
                attrs.getOrPut(n) { ArrayList() } += v
            }
            fun a(n: String) = attrs[n]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val cats = (attrs["category"].orEmpty() + Regex("<category>(\\d+)</category>", RegexOption.IGNORE_CASE).findAll(body).map { it.groupValues[1] }).mapNotNull { it.toIntOrNull() }
            val desc = tag(body, "description")?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
            if (cats.any { it / 1000 == CAT_ADULT / 1000 } || Parental.isExplicit(title, desc)) { reject("conteúdo adulto"); continue }
            if (cats.isNotEmpty() && cats.none { it / 1000 == CAT_MOVIES / 1000 || it / 1000 == CAT_TV / 1000 }) { reject("categoria não é vídeo"); continue }
            val seeders = a("seeders")?.toIntOrNull()
            if (seeders == 0 && a("peers")?.toIntOrNull()?.let { it > 0 } != true) { reject("sem semeadores"); continue }

            val enclosure = Regex("<enclosure\\b([^>]*)/?>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
            val encUrl = enclosure?.let { attr(it, "url") }
            val encType = enclosure?.let { attr(it, "type") }?.lowercase()
            val link = tag(body, "link")
            val candidates = ArrayList<Pair<String?, String>>()
            a("magneturl")?.let { candidates += "magnet" to it }
            listOfNotNull(encUrl, link).forEach { u ->
                when {
                    u.startsWith("magnet:", true) -> candidates += "magnet" to u
                    encType?.contains("nzb") == true || u.substringBefore('?').endsWith(".nzb", true) -> Unit
                    else -> candidates += "torrent" to u
                }
            }
            val hash = a("infohash")?.lowercase()?.takeIf { Regex("[a-f0-9]{40}").matches(it) }
            if (candidates.none { it.first == "magnet" } && hash != null) {
                candidates += "magnet" to "magnet:?xt=urn:btih:$hash&dn=" + URLEncoder.encode(title, "UTF-8").replace("+", "%20")
            }
            val sources = candidates.mapNotNull { (type, u) -> FeedParser.source(type, u) }.distinctBy { it.url }
            if (sources.isEmpty()) {
                reject(if (encType?.contains("nzb") == true || listOfNotNull(encUrl, link).any { it.substringBefore('?').endsWith(".nzb", true) }) "NZB (Usenet) não reproduzível" else "sem magnet/.torrent HTTPS válido")
                continue
            }
            val rel = release(title)
            val season = a("season")?.filter { it.isDigit() }?.toIntOrNull() ?: rel.season
            val episode = a("episode")?.filter { it.isDigit() }?.toIntOrNull() ?: rel.episode
            val series = episode != null || season != null || cats.any { it / 1000 == CAT_TV / 1000 && it != CAT_TV_DOCUMENTARY } && cats.none { it / 1000 == CAT_MOVIES / 1000 }
            val tmdb = a("tmdbid")?.takeIf { it.all(Char::isDigit) && it != "0" }?.let { (if (series) "tv:" else "movie:") + it }
            val year = a("year")?.take(4) ?: rel.year
            val size = a("size")?.toLongOrNull() ?: enclosure?.let { attr(it, "length")?.toLongOrNull() }
            val guid = tag(body, "guid") ?: sources.first().url
            val name = rel.name + (rel.quality?.let { " ($it)" } ?: "")
            val info = listOfNotNull(
                "Indexador Torznab/Newznab",
                seeders?.let { "$it semeadores" },
                size?.takeIf { it > 0 }?.let { com.brudre.streaming.torrent.CacheManager.formatBytes(it) },
            ).joinToString(" • ")
            val id = "${FeedParser.PREFIX}$feedId:${com.brudre.streaming.live.M3uParser.stableId(feedId, guid)}"
            val epSources = if (episode != null) sources.map { if (it.type == FeedSource.Type.HTTP || it.type == FeedSource.Type.HLS) it else it.copy(file = "S%02dE%02d".format(season ?: 1, episode)) } else sources
            val item = if (episode != null) FeedItem(
                Title(id, Kind.SERIES, name, synopsis = desc?.take(600), year = year, license = info),
                emptyList(), listOf(FeedEpisode(season ?: 1, episode, title.take(120), epSources)), tmdbId = tmdb,
            ) else FeedItem(
                Title(id, if (series) Kind.SERIES else Kind.MOVIE, name, synopsis = desc?.take(600), year = year, license = info),
                sources, tmdbId = tmdb,
            )
            out += item to (seeders ?: -1)
        }
        // Mais semeadores primeiro (disponibilidade observada pelo próprio indexador)
        return Parsed(out.sortedByDescending { it.second }.map { it.first }, total, offset, raw, rejected)
    }
}

class TorznabAdapter(val caps: TorznabCaps, private val key: String?) : SearchAdapter {
    override val capabilities = SourceCapabilities(
        textSearch = true,
        idLookup = caps.movie?.any { it == "tmdbid" || it == "imdbid" } == true || caps.tv?.any { it == "tmdbid" || it == "imdbid" || it == "tvdbid" } == true,
        episodes = caps.tv?.contains("season") == true,
        pagination = true,
        mediaFormats = listOf("magnet", ".torrent"),
        auth = key != null,
    )

    private val limit get() = minOf(Adapters.PAGE_SIZE, caps.limitMax).coerceAtLeast(1)

    /** Categorias pedidas, quando o indexador declara categorias (senão, nenhum filtro na consulta). */
    private fun cats(q: SearchTemplate.Query, series: Boolean): String? {
        if (!caps.hasCategories) return null
        val ids = caps.categories.map { it.first }
        val wanted = when {
            q.category == "documentarios" -> listOf(Torznab.CAT_TV_DOCUMENTARY, Torznab.CAT_MOVIES)
            series -> listOf(Torznab.CAT_TV)
            q.type == "movie" -> listOf(Torznab.CAT_MOVIES)
            else -> listOf(Torznab.CAT_MOVIES, Torznab.CAT_TV)
        }.filter { it in ids }
        return wanted.joinToString(",").ifEmpty { null }
    }

    override fun url(q: SearchTemplate.Query, page: Int): String {
        val series = q.type == "tv" || q.season != null
        val tmdb = q.tmdbId?.substringAfter(':')?.takeIf { it.all(Char::isDigit) }
        val fn: String
        val params = ArrayList<Pair<String, String?>>()
        when {
            series && caps.tv != null -> {
                fn = "tvsearch"
                params += "q" to q.text
                if (q.season != null && "season" in caps.tv) params += "season" to q.season.toString()
                if (tmdb != null && "tmdbid" in caps.tv) params += "tmdbid" to tmdb
            }
            !series && caps.movie != null && (q.type == "movie" || tmdb != null) -> {
                fn = "movie"
                params += "q" to q.text
                if (tmdb != null && "tmdbid" in caps.movie) params += "tmdbid" to tmdb
            }
            else -> { fn = "search"; params += "q" to q.text }
        }
        params += "cat" to cats(q, series)
        params += "extended" to "1"
        params += "offset" to ((page - 1) * limit).toString()
        params += "limit" to limit.toString()
        return Torznab.url(caps.base, listOf("t" to fn) + params, key)
    }

    override fun parse(body: String, sourceId: String): AdapterPage {
        val p = Torznab.parseResults(body, sourceId)
        if (p.rejected.isNotEmpty()) android.util.Log.i("BruDreSearch", "Torznab $sourceId: ${p.items.size} de ${p.rawCount} aceitos; descartados ${p.rejected}")
        val more = if (p.total != null) (p.offset ?: 0) + p.rawCount < p.total else p.rawCount >= limit
        // Links do próprio indexador (/dl/…?apikey=…): a chave vira o marcador {cred}, resolvido só na hora da requisição.
        val items = p.items.map { it.copy(sources = it.sources.map { s -> s.copy(url = Secrets.referenceKnown(s.url, key)) },
            episodes = it.episodes.map { e -> e.copy(sources = e.sources.map { s -> s.copy(url = Secrets.referenceKnown(s.url, key)) }) }) }
        return AdapterPage(items, more && p.rawCount > 0, p.rawCount)
    }
}
