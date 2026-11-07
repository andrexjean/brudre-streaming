package com.brudre.streaming.sources

import android.util.Log
import com.brudre.streaming.data.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Modelo de endereço de uma API de pesquisa autorizada (documentada em docs/FONTES.md).
 *
 * Ex.: `https://minha-fonte.org/api/busca?q={query}&ano={year}&tmdb={tmdb}`
 * Marcadores: {query} (texto), {slug} (texto-com-hifens), {tmdb} (movie:603), {year}, {type} (movie|tv), {season},
 * {page} (1, 2, …), {offset} (0, 50, …) e {limit} (itens por página).
 * A resposta deve ser JSON no formato de catálogo do BruDre (`{"items": [...]}`), mesmo vazio.
 */
object SearchTemplate {
    private val MARKERS = listOf("{query}", "{slug}", "{tmdb}", "{year}", "{type}", "{season}", "{page}", "{offset}", "{limit}")

    fun isTemplate(url: String) = url.contains("{query}") || url.contains("{slug}")

    data class Query(val text: String, val tmdbId: String? = null, val year: String? = null, val type: String? = null, val season: Int? = null, val page: Int = 1)

    fun slug(text: String) = TitleMatcher.normalize(text).replace(' ', '-')

    fun expand(template: String, q: Query, limit: Int = 50): String {
        fun enc(v: String?) = URLEncoder.encode(v ?: "", "UTF-8").replace("+", "%20")
        return template
            .replace("{query}", enc(q.text))
            .replace("{slug}", enc(slug(q.text)))
            .replace("{tmdb}", enc(q.tmdbId))
            .replace("{year}", enc(q.year))
            .replace("{type}", enc(q.type))
            .replace("{season}", enc(q.season?.toString()))
            .replace("{page}", q.page.toString())
            .replace("{offset}", ((q.page - 1).coerceAtLeast(0) * limit).toString())
            .replace("{limit}", limit.toString())
    }

    /** Motivo de bloqueio do modelo (validado com valores de exemplo), ou null. */
    fun check(template: String): String? {
        if (!isTemplate(template)) return "O endereço precisa ter {query} ou {slug} no lugar do texto pesquisado"
        if (!template.startsWith("https://", true)) return "Use um endereço https://"
        val host = template.substringAfter("://").substringBefore('/').substringBefore('?')
        if (MARKERS.any { host.contains(it) }) return "O servidor não pode depender do texto pesquisado"
        return UrlGuard.check(expand(template, Query("big buck bunny", "movie:10378", "2008", "movie", 1)))
    }
}

/** Resultado de uma fonte de pesquisa. */
data class SearchHit(val source: UserSource, val item: FeedItem)

/** Uma página de resultados; [moreFrom] = fontes que têm mais páginas. */
data class SearchPage(val hits: List<SearchHit>, val moreFrom: Set<String>)

/**
 * Pesquisa dinâmica nas fontes autorizadas que oferecem busca (API BruDre, OpenSearch, REST por descritor).
 * - Página a página, sob demanda; cada página é consultada uma vez (cache de 10 min, no máximo [CACHE_QUERIES]).
 * - No máximo [MAX_PARALLEL] fontes ao mesmo tempo, tempo-limite por fonte e respeito ao limite por minuto declarado.
 * - Falha de uma fonte não afeta as outras (vira histórico de falha da própria fonte).
 * - Cancelável: ao sair da tela, a coroutine é cancelada e as chamadas HTTP são abortadas.
 */
class SearchRepository(
    guarded: OkHttpClient,
    private val store: SourceStore,
) {
    private val client = guarded.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(PER_SOURCE_MS, TimeUnit.MILLISECONDS).build()
    /** Fontes com autenticação: sem seguir redirecionamentos (o token nunca vai para outro servidor). */
    private val authClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val semaphore = Semaphore(MAX_PARALLEL)
    private val lastCall = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val cache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Pair<Long, SearchPage>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, SearchPage>>?) = size > CACHE_QUERIES
    })

    fun sources(): List<UserSource> = store.current.filter { it.type.isSearchable && it.enabled }

    /** Primeira página de todas as fontes de pesquisa (compatível com a 9.0). */
    suspend fun search(q: SearchTemplate.Query): List<SearchHit> = page(q, 1).hits

    /** Página [page] (1, 2…). Com [only], consulta só as fontes indicadas (as que ainda têm resultados). */
    suspend fun page(q: SearchTemplate.Query, page: Int, only: Set<String>? = null): SearchPage = coroutineScope {
        val list = sources().filter { only == null || it.id in only }
        if (list.isEmpty() || q.text.isBlank()) return@coroutineScope SearchPage(emptyList(), emptySet())
        val key = listOf(q.text, q.tmdbId, q.year, q.type, q.season, page, list.joinToString { it.id }).joinToString("|")
        cache[key]?.let { (at, p) -> if (System.currentTimeMillis() - at < CACHE_MS) return@coroutineScope p }
        val results = list.map { src ->
            async {
                semaphore.withPermit {
                    try {
                        val adapter = Adapters.forSource(src)
                        val r = withTimeout(PER_SOURCE_MS + 2_000) { query(src, adapter, q, page) } ?: return@withPermit null
                        store.recordResult(src.id, true)
                        src to r
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val f = ErrorClassifier.classify(e)
                        Log.w(TAG, "Pesquisa em ${src.name} falhou: ${f.technical}")
                        store.recordResult(src.id, false, f.summary)
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
        val hits = results.flatMap { (src, r) -> r.items.take(MAX_RESULTS).map { SearchHit(src, it) } }
        // Mesma obra vinda de duas fontes fica nas duas (são alternativas); duplicatas da MESMA fonte saem.
        val dedup = hits.distinctBy { it.source.id + "|" + (it.item.tmdbId ?: TitleMatcher.normalize(it.item.title.name)) + "|" + it.item.title.year + "|" + it.item.episodes.firstOrNull()?.let { e -> "${e.season}x${e.number}" } }
        val p = SearchPage(dedup, results.filter { it.second.hasMore }.map { it.first.id }.toSet())
        cache[key] = System.currentTimeMillis() to p
        p
    }

    /** Uma consulta HTTP (cancelável) a uma fonte, com limite por minuto e resposta limitada a [MAX_BYTES]. */
    suspend fun query(src: UserSource, adapter: SearchAdapter, q: SearchTemplate.Query, page: Int): AdapterPage? {
        val url = adapter.url(q, page) ?: return null
        UrlGuard.check(url)?.let { throw BlockedUrlException(it) }
        adapter.capabilities.ratePerMinute?.let { rate ->
            val interval = 60_000L / rate
            val wait = (lastCall[src.id] ?: 0L) + interval - System.currentTimeMillis()
            if (wait > 0) kotlinx.coroutines.delay(wait.coerceAtMost(PER_SOURCE_MS / 2))
        }
        lastCall[src.id] = System.currentTimeMillis()
        val text = fetch(url, adapter.headers)
        return adapter.parse(text, src.id)
    }

    /** Chamada HTTP cancelável de verdade: ao cancelar a coroutine (saiu da tela), a conexão é abortada. */
    suspend fun fetch(url: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).header("User-Agent", CatalogRepository.USER_AGENT)
            .header("Accept", "application/json, application/atom+xml, application/rss+xml;q=0.9, */*;q=0.5")
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val call: Call = (if (headers.isEmpty()) client else authClient).newCall(req)
        val response = kotlinx.coroutines.suspendCancellableCoroutine<okhttp3.Response> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: java.io.IOException) { if (cont.isActive) cont.resumeWith(Result.failure(e)) }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    if (cont.isActive) cont.resumeWith(Result.success(response)) else response.close()
                }
            })
        }
        return withContext(Dispatchers.IO) {
            response.use { r ->
                if (r.isRedirect) throw ClassifiedException(ErrorKind.BLOCKED, "Fonte com autenticação tentou redirecionar para ${r.header("Location")?.let { UrlGuard.hostOf(it) } ?: "outro endereço"}: bloqueado")
                if (!r.isSuccessful) throw HttpStatusException(r.code)
                val src = r.body?.source() ?: throw ClassifiedException(ErrorKind.CATALOG, "Resposta vazia da fonte de pesquisa")
                src.request(MAX_BYTES)
                src.buffer.snapshot().utf8().take(MAX_BYTES.toInt())
            }
        }
    }

    fun find(titleId: String): FeedItem? = synchronized(cache) { cache.values.toList() }.asSequence()
        .flatMap { it.second.hits.asSequence() }.map { it.item }.firstOrNull { it.title.id == titleId }

    fun clear() = cache.clear()

    companion object {
        private const val TAG = "BruDreSearch"
        const val MAX_PARALLEL = 3
        const val PER_SOURCE_MS = 12_000L
        /** Por fonte e por página; mais resultados vêm em "Carregar mais". */
        const val MAX_RESULTS = 50
        private const val MAX_BYTES = 512L * 1024
        private const val CACHE_MS = 10 * 60_000L
        private const val CACHE_QUERIES = 20

        /** Resposta da API: obrigatoriamente JSON com "items" (HTML ou RSS são recusados). */
        fun parse(text: String, sourceId: String): Feed {
            val t = text.trimStart('﻿', ' ', '\n', '\r', '\t')
            if (!t.startsWith("{")) {
                throw ClassifiedException(
                    if (t.startsWith("<")) ErrorKind.FORMAT else ErrorKind.CATALOG,
                    if (t.lowercase().contains("<html")) "A API respondeu uma página web, não JSON" else "A API de pesquisa deve responder JSON no formato do BruDre",
                )
            }
            return try {
                FeedParser.parse(t, sourceId.replace('_', '-'))
            } catch (e: IllegalArgumentException) {
                throw ClassifiedException(ErrorKind.CATALOG, e.message ?: "JSON sem \"items\"")
            } catch (e: org.json.JSONException) {
                throw ClassifiedException(ErrorKind.CATALOG, "JSON inválido na resposta da API")
            }
        }
    }
}
