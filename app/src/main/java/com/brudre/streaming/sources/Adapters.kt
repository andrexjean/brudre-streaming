package com.brudre.streaming.sources

import org.json.JSONArray
import org.json.JSONObject

/**
 * Source Engine 3.0 — adaptadores de fontes autorizadas que oferecem pesquisa.
 *
 * Cada adaptador transforma a resposta do provedor no formato de catálogo do BruDre e passa pelo MESMO
 * parser e pelas mesmas validações (UrlGuard, magnet, conteúdo adulto). Nada de "raspar" páginas HTML:
 * só formatos declarados (JSON do BruDre, RSS/Atom, OpenSearch, REST descrita por um descritor JSON).
 */
interface SearchAdapter {
    val capabilities: SourceCapabilities
    /** Cabeçalhos exigidos pela fonte (autenticação declarada no descritor). */
    val headers: Map<String, String> get() = emptyMap()
    /** "GET" ou "POST" (GraphQL). */
    val method: String get() = "GET"
    /** Corpo JSON do POST para a consulta, ou null. */
    fun body(q: SearchTemplate.Query, page: Int): String? = null
    /** Endereço da página [page] (1, 2, …) para a consulta, ou null se a fonte não pagina e page > 1. */
    fun url(q: SearchTemplate.Query, page: Int): String?
    /** Converte a resposta em itens + se há mais páginas. */
    fun parse(body: String, sourceId: String): AdapterPage
}

/** [raw] = itens que o protocolo devolveu antes dos filtros (0 = "sem resultados"; >0 sem [items] = nada reproduzível). */
data class AdapterPage(val items: List<FeedItem>, val hasMore: Boolean, val raw: Int = items.size)

object Adapters {
    const val PAGE_SIZE = 50

    fun forSource(s: UserSource, key: String? = null): SearchAdapter = when (s.type) {
        SourceType.SEARCH -> BruDreTemplateAdapter(s.url)
        SourceType.OPENSEARCH -> OpenSearchAdapter(OpenSearch.fromConfig(s.config ?: throw ClassifiedException(ErrorKind.CATALOG, "Descrição OpenSearch não carregada: use Atualizar")))
        SourceType.REST -> RestAdapter(RestDescriptor.parse(s.config ?: throw ClassifiedException(ErrorKind.CATALOG, "Descritor não carregado: use Atualizar")), key)
        SourceType.TORZNAB -> TorznabAdapter(TorznabCaps.fromConfig(s.config ?: throw ClassifiedException(ErrorKind.CATALOG, "Capacidades do indexador não carregadas: use Atualizar")), key)
        else -> throw ClassifiedException(ErrorKind.FORMAT, "${s.type.label} não oferece pesquisa")
    }

    fun paged(template: String) = template.contains("{page}") || template.contains("{offset}")
}

/** API de pesquisa do BruDre: modelo com {query}/{slug}; resposta = catálogo JSON (com "more"/"next" opcionais). */
class BruDreTemplateAdapter(private val template: String) : SearchAdapter {
    override val capabilities = SourceCapabilities(true, template.contains("{tmdb}"), true, Adapters.paged(template), listOf("HTTP", "HLS", "magnet", ".torrent"))

    override fun url(q: SearchTemplate.Query, page: Int): String? =
        if (page > 1 && !capabilities.pagination) null else SearchTemplate.expand(template, q.copy(page = page), Adapters.PAGE_SIZE)

    override fun parse(body: String, sourceId: String): AdapterPage {
        val feed = SearchRepository.parse(body, sourceId)
        val more = feed.more ?: (feed.next != null || (capabilities.pagination && feed.items.size >= Adapters.PAGE_SIZE))
        return AdapterPage(feed.items, more && capabilities.pagination)
    }
}

/**
 * OpenSearch 1.1: a descrição (XML) traz o modelo <Url template="…{searchTerms}…" type="application/atom+xml|rss+xml">.
 * Os marcadores do padrão viram os do BruDre: {searchTerms}→{query}, {startPage?}→{page}, {startIndex?}→{offset}, {count?}→{limit}.
 */
data class OpenSearch(val name: String, val template: String, val responseType: String) {
    fun toConfig(): String = JSONObject().put("name", name).put("template", template).put("type", responseType).toString()

    companion object {
        fun fromConfig(json: String): OpenSearch = JSONObject(json).let { OpenSearch(it.optString("name"), it.getString("template"), it.optString("type")) }

        /** Lê a descrição; aceita só respostas Atom ou RSS (resultados de vídeo com enclosure/magnet). */
        fun parseDescription(xml: String): OpenSearch {
            if (!xml.contains("<OpenSearchDescription", true)) throw ClassifiedException(ErrorKind.FORMAT, "Não é uma descrição OpenSearch")
            val name = Regex("<ShortName>(.*?)</ShortName>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(xml)?.groupValues?.get(1)?.trim() ?: "OpenSearch"
            val urls = Regex("<Url\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(xml).map { it.groupValues[1] }.mapNotNull { a ->
                val type = Regex("type=\"([^\"]+)\"").find(a)?.groupValues?.get(1)?.lowercase() ?: return@mapNotNull null
                val t = Regex("template=\"([^\"]+)\"").find(a)?.groupValues?.get(1)?.replace("&amp;", "&") ?: return@mapNotNull null
                type to t
            }.toList()
            val pick = urls.firstOrNull { it.first.contains("atom") } ?: urls.firstOrNull { it.first.contains("rss") }
                ?: throw ClassifiedException(ErrorKind.FORMAT, "A descrição OpenSearch não oferece resultados em Atom ou RSS (${urls.joinToString { it.first }})")
            val template = convert(pick.second)
            SearchTemplate.check(template)?.let { throw ClassifiedException(ErrorKind.BLOCKED, it) }
            return OpenSearch(name.take(60), template, pick.first)
        }

        fun convert(t: String): String = t
            .replace("{searchTerms}", "{query}")
            .replace(Regex("\\{startPage\\??\\}"), "{page}")
            .replace(Regex("\\{startIndex\\??\\}"), "{offset}")
            .replace(Regex("\\{count\\??\\}"), "{limit}")
            .replace(Regex("\\{language\\??\\}"), "pt-BR")
            .replace(Regex("\\{(inputEncoding|outputEncoding)\\??\\}"), "UTF-8")
            .replace(Regex("\\{[a-zA-Z:]+\\?\\}"), "") // demais parâmetros opcionais ficam vazios
    }
}

class OpenSearchAdapter(private val os: OpenSearch) : SearchAdapter {
    override val capabilities = SourceCapabilities(true, false, false, Adapters.paged(os.template), listOf("HTTP", "HLS", "magnet", ".torrent"))

    override fun url(q: SearchTemplate.Query, page: Int): String? =
        if (page > 1 && !capabilities.pagination) null else SearchTemplate.expand(os.template, q.copy(page = page), Adapters.PAGE_SIZE)

    override fun parse(body: String, sourceId: String): AdapterPage {
        val t = body.trimStart('﻿', ' ', '\n', '\r', '\t')
        if (!t.startsWith("<")) throw ClassifiedException(ErrorKind.FORMAT, "A fonte OpenSearch deveria responder Atom/RSS")
        if (t.take(2048).lowercase().contains("<html")) throw ClassifiedException(ErrorKind.FORMAT, "A fonte respondeu uma página web, não Atom/RSS")
        val feed = try { FeedParser.parse(t, sourceId.replace('_', '-')) } catch (e: IllegalArgumentException) { throw ClassifiedException(ErrorKind.CATALOG, e.message ?: "Resposta inválida") }
        val total = Regex("<(?:opensearch:)?totalResults>(\\d+)<", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.toIntOrNull()
        val more = feed.next != null || (capabilities.pagination && feed.items.isNotEmpty() && (total == null || total > feed.items.size))
        return AdapterPage(feed.items, more && capabilities.pagination, feed.items.size + feed.rejected)
    }
}

/**
 * Descritor de API REST (formato "brudre-adapter" v1), cadastrado pelo usuário como uma fonte:
 * ```
 * {"format": "brudre-adapter", "version": 1, "name": "Meu servidor",
 *  "search": {"url": "https://host/api/busca?q={query}&p={page}", "items": "data.results", "hasMore": "has_more"},
 *  "lookup": {"url": "https://host/api/tmdb/{tmdb}"},
 *  "fields": {"id": "uid", "title": "name", "year": "released", "tmdb": "ids.tmdb", "kind": "type",
 *             "season": "season", "episode": "episode", "series": "show", "language": "lang", "poster": "image"},
 *  "kinds": {"movie": "MOVIE", "show": "SERIES", "episode": "SERIES"},
 *  "media": [{"type": "http", "url": "{files.mp4}"}, {"type": "magnet", "url": "{magnet}"}],
 *  "headers": {"X-Api-Key": "…"}, "rateLimitPerMinute": 30}
 * ```
 * Campos aceitam caminhos com ponto; "{campo}" nos modelos é trocado pelo valor do item.
 */
data class RestDescriptor(
    val name: String,
    val searchUrl: String,
    val itemsPath: String,
    val hasMorePath: String?,
    val lookupUrl: String?,
    val fields: Map<String, String>,
    val kinds: Map<String, String>,
    val media: List<Pair<String, String>>,
    val headers: Map<String, String>,
    val ratePerMinute: Int?,
    /** "GET" (REST) ou "POST" (GraphQL e APIs que pesquisam por corpo JSON). */
    val method: String = "GET",
    /** Corpo do POST (JSON). Marcadores {query} etc. são escapados como texto JSON. */
    val body: String? = null,
    /** Limpa títulos de arquivo ("File:Nome_do_video.webm" → "Nome do video"). */
    val titleCleanup: Boolean = false,
    /** Categorias declaradas (filmes, series, documentarios, infantil, livre): quais títulos consultar nesta fonte. */
    val categories: Set<String> = emptySet(),
    /** Restrições de uso declaradas pelo provedor (exibidas ao usuário). */
    val restrictions: String? = null,
    /** Texto usado no teste de cadastro (o exemplo padrão pode não existir na fonte). */
    val sampleQuery: String? = null,
) {
    val capabilities: SourceCapabilities get() = SourceCapabilities(
        textSearch = true, idLookup = lookupUrl != null, episodes = fields.containsKey("episode"), pagination = Adapters.paged(searchUrl + (body ?: "")),
        mediaFormats = media.map { it.first.uppercase() }.distinct(), auth = headers.isNotEmpty() || needsKey, ratePerMinute = ratePerMinute,
    )
    /** O descritor usa {key}: a chave fica guardada cifrada no aparelho (nunca no descritor nem na exportação). */
    val needsKey: Boolean get() = searchUrl.contains("{key}") || (body?.contains("{key}") == true) || headers.values.any { it.contains("{key}") } || media.any { it.second.contains("{key}") }

    companion object {
        /** Só estes cabeçalhos podem ser enviados (autenticação); nada de Cookie, Host etc. */
        val ALLOWED_HEADERS = setOf("authorization", "x-api-key", "x-emby-token", "x-mediabrowser-token", "x-plex-token")

        fun parse(json: String): RestDescriptor {
            val o = try { JSONObject(json) } catch (e: Exception) { throw ClassifiedException(ErrorKind.CATALOG, "Descritor JSON inválido") }
            if (o.optString("format") != "brudre-adapter") throw ClassifiedException(ErrorKind.FORMAT, "Não é um descritor \"brudre-adapter\"")
            if (o.optInt("version", 1) != 1) throw ClassifiedException(ErrorKind.FORMAT, "Versão do descritor não suportada")
            val search = o.optJSONObject("search") ?: throw ClassifiedException(ErrorKind.CATALOG, "Descritor sem \"search\"")
            val url = search.optString("url")
            val method = search.optString("method", "GET").uppercase()
            if (method != "GET" && method != "POST") throw ClassifiedException(ErrorKind.FORMAT, "Método não suportado: $method")
            val body = search.optString("body").ifBlank { null }?.take(8192)
            if (body != null && method != "POST") throw ClassifiedException(ErrorKind.FORMAT, "\"body\" exige \"method\": \"POST\"")
            // GraphQL/POST: o texto pesquisado vai no corpo; o endereço precisa ser https e seguro.
            if (method == "POST") {
                if (body == null || !(body.contains("{query}") || body.contains("{slug}"))) throw ClassifiedException(ErrorKind.CATALOG, "POST precisa de \"body\" com {query}")
                if (!url.startsWith("https://")) throw ClassifiedException(ErrorKind.BLOCKED, "Use um endereço https://")
                UrlGuard.check(SearchTemplate.expand(url, SearchTemplate.Query("x")))?.let { throw ClassifiedException(ErrorKind.BLOCKED, it) }
            } else SearchTemplate.check(url.replace("{key}", "chave"))?.let { throw ClassifiedException(ErrorKind.BLOCKED, it) }
            val lookup = o.optJSONObject("lookup")?.optString("url")?.takeIf { it.isNotBlank() }
            if (lookup != null && (!lookup.contains("{tmdb}") || !lookup.startsWith("https://"))) throw ClassifiedException(ErrorKind.BLOCKED, "\"lookup\" precisa ser https:// com {tmdb}")
            val fields = o.optJSONObject("fields")?.let { f -> f.keys().asSequence().associateWith { f.getString(it) } } ?: emptyMap()
            if ("title" !in fields) throw ClassifiedException(ErrorKind.CATALOG, "Descritor sem o campo \"title\"")
            val media = o.optJSONArray("media")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("type") to it.optString("url") } } }.orEmpty()
            if (media.isEmpty()) throw ClassifiedException(ErrorKind.CATALOG, "Descritor sem \"media\" (endereço do vídeo)")
            val headers = o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } }.orEmpty()
            headers.keys.firstOrNull { it.lowercase() !in ALLOWED_HEADERS }?.let { throw ClassifiedException(ErrorKind.BLOCKED, "Cabeçalho não permitido no descritor: $it") }
            if (headers.values.any { it.length > 512 || it.any { c -> c == '\r' || c == '\n' } }) throw ClassifiedException(ErrorKind.BLOCKED, "Valor de cabeçalho inválido")
            return RestDescriptor(
                o.optString("name", "API REST").take(60), url, search.optString("items", "items"), search.optString("hasMore").ifBlank { null },
                lookup, fields, o.optJSONObject("kinds")?.let { k -> k.keys().asSequence().associate { it.lowercase() to k.getString(it) } }.orEmpty(),
                media, headers, o.optInt("rateLimitPerMinute", 0).takeIf { it > 0 },
                method, body, o.optBoolean("titleCleanup", false),
                o.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.getString(it).lowercase() }.toSet() }.orEmpty(),
                o.optString("restrictions").ifBlank { null }?.take(300),
                o.optString("sampleQuery").ifBlank { null }?.take(80),
            )
        }

        /** Valor por caminho com pontos ("ids.tmdb"); null se ausente. */
        fun at(o: Any?, path: String): Any? {
            var cur: Any? = o
            for (k in path.split('.')) {
                val c = cur
                cur = when (c) {
                    is JSONObject -> if (c.has(k) && !c.isNull(k)) c.get(k) else null
                    is JSONArray -> k.toIntOrNull()?.let { i -> if (i in 0 until c.length()) c.get(i) else null }
                    else -> null
                } ?: return null
            }
            return cur
        }

        fun fill(template: String, item: JSONObject): String? {
            var missing = false
            val out = Regex("\\{([A-Za-z0-9_.]+)\\}").replace(template) { m -> at(item, m.groupValues[1])?.toString() ?: run { missing = true; "" } }
            return if (missing || out.isBlank()) null else out
        }
    }
}

class RestAdapter(private val d: RestDescriptor, private val key: String? = null) : SearchAdapter {
    override val capabilities = d.capabilities
    override val headers get() = d.headers.mapValues { (_, v) -> v.replace("{key}", key ?: "") }
    override val method get() = d.method

    override fun body(q: SearchTemplate.Query, page: Int): String? = d.body?.let { b ->
        fun js(v: String?) = org.json.JSONObject.quote(v ?: "").removeSurrounding("\"")
        b.replace("{query}", js(q.text)).replace("{slug}", js(SearchTemplate.slug(q.text))).replace("{tmdb}", js(q.tmdbId))
            .replace("{year}", js(q.year)).replace("{type}", js(q.type)).replace("{season}", js(q.season?.toString()))
            .replace("{page}", page.toString()).replace("{offset}", ((page - 1) * Adapters.PAGE_SIZE).toString()).replace("{limit}", Adapters.PAGE_SIZE.toString())
            .replace("{key}", js(key))
    }

    override fun url(q: SearchTemplate.Query, page: Int): String? {
        if (page == 1 && q.tmdbId != null && d.lookupUrl != null) return withKey(SearchTemplate.expand(d.lookupUrl, q, Adapters.PAGE_SIZE))
        if (page > 1 && !capabilities.pagination) return null
        return withKey(SearchTemplate.expand(d.searchUrl, q.copy(page = page), Adapters.PAGE_SIZE))
    }

    private fun withKey(url: String) = url.replace("{key}", java.net.URLEncoder.encode(key ?: "", "UTF-8"))

    /** "File:Nome_do_video.webm" → "Nome do video". */
    private fun clean(t: String) = if (!d.titleCleanup) t else t.substringAfter(':').substringBeforeLast('.').replace('_', ' ').trim()

    /** Mapeia cada item para o catálogo JSON do BruDre e usa o parser comum (com todas as validações). */
    override fun parse(body: String, sourceId: String): AdapterPage {
        val t = body.trimStart('﻿', ' ', '\n', '\r', '\t')
        if (!t.startsWith("{") && !t.startsWith("[")) throw ClassifiedException(if (t.startsWith("<")) ErrorKind.FORMAT else ErrorKind.CATALOG, "A API deveria responder JSON")
        val root: Any = try { if (t.startsWith("[")) JSONArray(t) else JSONObject(t) } catch (e: Exception) { throw ClassifiedException(ErrorKind.CATALOG, "JSON inválido na resposta da API") }
        val found = if (root is JSONArray) root else RestDescriptor.at(root, d.itemsPath)
        val arr = when (found) {
            is JSONArray -> found
            // Mapa de itens (ex.: MediaWiki "query.pages": {"123": {...}}) → lista
            is JSONObject -> JSONArray().apply { found.keys().forEach { k -> found.optJSONObject(k)?.let { put(it) } } }
            else -> (root as? JSONObject)?.takeIf { d.lookupUrl != null }?.let { JSONArray().put(it) } ?: JSONArray() // "lookup" devolve um item
        }
        val f = d.fields
        val items = JSONArray()
        for (i in 0 until minOf(arr.length(), FeedParser.MAX_ITEMS)) {
            val item = arr.optJSONObject(i) ?: continue
            fun v(key: String): String? = f[key]?.let { path -> RestDescriptor.at(item, path) }?.toString()?.takeIf { s -> s.isNotBlank() }
            val sources = JSONArray()
            d.media.forEach { (type, tpl) -> RestDescriptor.fill(tpl, item)?.let { u -> sources.put(JSONObject().put("type", type).put("url", u)) } }
            if (sources.length() == 0) continue
            val kind = v("kind")?.lowercase()?.let { d.kinds[it] ?: it.uppercase() }
            val season = v("season")?.toIntOrNull()
            val episode = v("episode")?.toIntOrNull()
            val title = ((if (episode != null) v("series") else null) ?: v("title"))?.let { clean(it) }?.ifBlank { null } ?: continue
            val o = JSONObject().put("id", (v("id") ?: title) + (if (episode != null) "-s${season ?: 1}e$episode" else "")).put("title", title)
            v("year")?.let { y -> o.put("year", y.take(4)) }
            v("synopsis")?.let { x -> o.put("synopsis", x) }
            v("poster")?.let { x -> o.put("poster", x) }
            v("language")?.let { x -> o.put("language", x) }
            if (kind != null) o.put("kind", if (kind == "EPISODE") "SERIES" else kind)
            v("tmdb")?.let { tm -> o.put("tmdb", tm) }
            if (episode != null) {
                o.put("kind", "SERIES")
                o.put("episodes", JSONArray().put(JSONObject().put("season", season ?: 1).put("number", episode).put("title", v("title") ?: "Episódio $episode").put("sources", sources)))
            } else o.put("sources", sources)
            items.put(o)
        }
        val feed = SearchRepository.parse(JSONObject().put("name", d.name).put("items", items).toString(), sourceId)
        // hasMore: booleano, ou presença de um objeto de continuação (ex.: MediaWiki "continue")
        val more = d.hasMorePath?.let { p -> RestDescriptor.at(root, p).let { v -> if (v is Boolean) v else v != null } } ?: (capabilities.pagination && arr.length() >= Adapters.PAGE_SIZE)
        return AdapterPage(feed.items, more && capabilities.pagination, arr.length())
    }

}
