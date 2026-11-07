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
    /** Endereço da página [page] (1, 2, …) para a consulta, ou null se a fonte não pagina e page > 1. */
    fun url(q: SearchTemplate.Query, page: Int): String?
    /** Converte a resposta em itens + se há mais páginas. */
    fun parse(body: String, sourceId: String): AdapterPage
}

data class AdapterPage(val items: List<FeedItem>, val hasMore: Boolean)

object Adapters {
    const val PAGE_SIZE = 50

    fun forSource(s: UserSource): SearchAdapter = when (s.type) {
        SourceType.SEARCH -> BruDreTemplateAdapter(s.url)
        SourceType.OPENSEARCH -> OpenSearchAdapter(OpenSearch.fromConfig(s.config ?: throw ClassifiedException(ErrorKind.CATALOG, "Descrição OpenSearch não carregada: use Atualizar")))
        SourceType.REST -> RestAdapter(RestDescriptor.parse(s.config ?: throw ClassifiedException(ErrorKind.CATALOG, "Descritor não carregado: use Atualizar")))
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
        return AdapterPage(feed.items, more && capabilities.pagination)
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
) {
    val capabilities: SourceCapabilities get() = SourceCapabilities(
        textSearch = true, idLookup = lookupUrl != null, episodes = fields.containsKey("episode"), pagination = Adapters.paged(searchUrl),
        mediaFormats = media.map { it.first.uppercase() }.distinct(), auth = headers.isNotEmpty(), ratePerMinute = ratePerMinute,
    )

    companion object {
        /** Só estes cabeçalhos podem ser enviados (autenticação); nada de Cookie, Host etc. */
        val ALLOWED_HEADERS = setOf("authorization", "x-api-key", "x-emby-token", "x-mediabrowser-token", "x-plex-token")

        fun parse(json: String): RestDescriptor {
            val o = try { JSONObject(json) } catch (e: Exception) { throw ClassifiedException(ErrorKind.CATALOG, "Descritor JSON inválido") }
            if (o.optString("format") != "brudre-adapter") throw ClassifiedException(ErrorKind.FORMAT, "Não é um descritor \"brudre-adapter\"")
            if (o.optInt("version", 1) != 1) throw ClassifiedException(ErrorKind.FORMAT, "Versão do descritor não suportada")
            val search = o.optJSONObject("search") ?: throw ClassifiedException(ErrorKind.CATALOG, "Descritor sem \"search\"")
            val url = search.optString("url")
            SearchTemplate.check(url)?.let { throw ClassifiedException(ErrorKind.BLOCKED, it) }
            val lookup = o.optJSONObject("lookup")?.optString("url")?.takeIf { it.isNotBlank() }
            if (lookup != null && (!lookup.contains("{tmdb}") || !lookup.startsWith("https://"))) throw ClassifiedException(ErrorKind.BLOCKED, "\"lookup\" precisa ser https:// com {tmdb}")
            val fields = o.optJSONObject("fields")?.let { f -> f.keys().asSequence().associateWith { f.getString(it) } } ?: emptyMap()
            if ("title" !in fields) throw ClassifiedException(ErrorKind.CATALOG, "Descritor sem o campo \"title\"")
            val media = o.optJSONArray("media")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("type") to it.optString("url") } } }.orEmpty()
            if (media.isEmpty()) throw ClassifiedException(ErrorKind.CATALOG, "Descritor sem \"media\" (endereço do vídeo)")
            val headers = o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } }.orEmpty()
            headers.keys.firstOrNull { it.lowercase() !in ALLOWED_HEADERS }?.let { throw ClassifiedException(ErrorKind.BLOCKED, "Cabeçalho não permitido no descritor: $it") }
            return RestDescriptor(
                o.optString("name", "API REST").take(60), url, search.optString("items", "items"), search.optString("hasMore").ifBlank { null },
                lookup, fields, o.optJSONObject("kinds")?.let { k -> k.keys().asSequence().associate { it.lowercase() to k.getString(it) } }.orEmpty(),
                media, headers, o.optInt("rateLimitPerMinute", 0).takeIf { it > 0 },
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

class RestAdapter(private val d: RestDescriptor) : SearchAdapter {
    override val capabilities = d.capabilities
    override val headers get() = d.headers

    override fun url(q: SearchTemplate.Query, page: Int): String? {
        if (page == 1 && q.tmdbId != null && d.lookupUrl != null) return SearchTemplate.expand(d.lookupUrl, q, Adapters.PAGE_SIZE)
        if (page > 1 && !capabilities.pagination) return null
        return SearchTemplate.expand(d.searchUrl, q.copy(page = page), Adapters.PAGE_SIZE)
    }

    /** Mapeia cada item para o catálogo JSON do BruDre e usa o parser comum (com todas as validações). */
    override fun parse(body: String, sourceId: String): AdapterPage {
        val t = body.trimStart('﻿', ' ', '\n', '\r', '\t')
        if (!t.startsWith("{") && !t.startsWith("[")) throw ClassifiedException(if (t.startsWith("<")) ErrorKind.FORMAT else ErrorKind.CATALOG, "A API deveria responder JSON")
        val root: Any = try { if (t.startsWith("[")) JSONArray(t) else JSONObject(t) } catch (e: Exception) { throw ClassifiedException(ErrorKind.CATALOG, "JSON inválido na resposta da API") }
        val arr = (if (root is JSONArray) root else RestDescriptor.at(root, d.itemsPath)) as? JSONArray
            ?: (root as? JSONObject)?.let { JSONArray().put(it) } // "lookup" pode devolver um item só
            ?: JSONArray()
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
            val title = (if (episode != null) v("series") else null) ?: v("title") ?: continue
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
        val more = d.hasMorePath?.let { (RestDescriptor.at(root, it) as? Boolean) } ?: (capabilities.pagination && arr.length() >= Adapters.PAGE_SIZE)
        return AdapterPage(feed.items, more && capabilities.pagination)
    }

}
