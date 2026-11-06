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
 * Marcadores: {query} (texto), {slug} (texto-com-hifens), {tmdb} (movie:603), {year}, {type} (movie|tv), {season}, {page}.
 * A resposta deve ser JSON no formato de catálogo do BruDre (`{"items": [...]}`), mesmo vazio.
 */
object SearchTemplate {
    private val MARKERS = listOf("{query}", "{slug}", "{tmdb}", "{year}", "{type}", "{season}", "{page}")

    fun isTemplate(url: String) = url.contains("{query}") || url.contains("{slug}")

    data class Query(val text: String, val tmdbId: String? = null, val year: String? = null, val type: String? = null, val season: Int? = null, val page: Int = 1)

    fun slug(text: String) = TitleMatcher.normalize(text).replace(' ', '-')

    fun expand(template: String, q: Query): String {
        fun enc(v: String?) = URLEncoder.encode(v ?: "", "UTF-8").replace("+", "%20")
        return template
            .replace("{query}", enc(q.text))
            .replace("{slug}", enc(slug(q.text)))
            .replace("{tmdb}", enc(q.tmdbId))
            .replace("{year}", enc(q.year))
            .replace("{type}", enc(q.type))
            .replace("{season}", enc(q.season?.toString()))
            .replace("{page}", q.page.toString())
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

/**
 * Pesquisa dinâmica nas APIs autorizadas cadastradas (Gerenciar fontes → API de pesquisa).
 * - Consultas em paralelo com no máximo [MAX_PARALLEL] ao mesmo tempo e tempo-limite por fonte.
 * - Falha de uma fonte não afeta as outras (vira histórico de falha da própria fonte).
 * - Cancelável: ao sair da tela, a coroutine é cancelada e as chamadas HTTP são abortadas.
 * - Resultados recentes ficam em memória por 10 min (no máximo [CACHE_QUERIES] pesquisas).
 */
class SearchRepository(
    guarded: OkHttpClient,
    private val store: SourceStore,
) {
    private val client = guarded.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(PER_SOURCE_MS, TimeUnit.MILLISECONDS).build()
    private val semaphore = Semaphore(MAX_PARALLEL)
    private val cache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Pair<Long, List<SearchHit>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<SearchHit>>>?) = size > CACHE_QUERIES
    })

    fun sources(): List<UserSource> = store.current.filter { it.type == SourceType.SEARCH && it.enabled }

    suspend fun search(q: SearchTemplate.Query): List<SearchHit> = coroutineScope {
        val list = sources()
        if (list.isEmpty() || q.text.isBlank()) return@coroutineScope emptyList()
        val key = listOf(q.text, q.tmdbId, q.year, q.type, q.season, q.page, list.joinToString { it.id }).joinToString("|")
        cache[key]?.let { (at, hits) -> if (System.currentTimeMillis() - at < CACHE_MS) return@coroutineScope hits }
        val results = list.map { src ->
            async {
                semaphore.withPermit {
                    try {
                        val items = withTimeout(PER_SOURCE_MS + 2_000) { query(src, q) }
                        store.recordResult(src.id, true)
                        items.map { SearchHit(src, it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val f = ErrorClassifier.classify(e)
                        Log.w(TAG, "Pesquisa em ${src.name} falhou: ${f.technical}")
                        store.recordResult(src.id, false, f.summary)
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten()
        // Mesma obra vinda de duas fontes fica nas duas (são fontes alternativas); duplicatas da MESMA fonte saem.
        val dedup = results.distinctBy { it.source.id + "|" + (it.item.tmdbId ?: TitleMatcher.normalize(it.item.title.name)) + "|" + it.item.title.year }
        cache[key] = System.currentTimeMillis() to dedup
        dedup
    }

    /** Uma consulta HTTP (cancelável) a uma fonte; resposta limitada a [MAX_BYTES] e só JSON do BruDre. */
    suspend fun query(src: UserSource, q: SearchTemplate.Query): List<FeedItem> {
        val url = SearchTemplate.expand(src.url, q)
        UrlGuard.check(url)?.let { throw BlockedUrlException(it) }
        val text = fetch(url)
        return parse(text, src.id).items.take(MAX_RESULTS)
    }

    /** Chamada HTTP cancelável de verdade: ao cancelar a coroutine (saiu da tela), a conexão é abortada. */
    suspend fun fetch(url: String): String {
        val call: Call = client.newCall(Request.Builder().url(url).header("User-Agent", CatalogRepository.USER_AGENT).header("Accept", "application/json").build())
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
                if (!r.isSuccessful) throw HttpStatusException(r.code)
                val src = r.body?.source() ?: throw ClassifiedException(ErrorKind.CATALOG, "Resposta vazia da API de pesquisa")
                src.request(MAX_BYTES)
                src.buffer.snapshot().utf8().take(MAX_BYTES.toInt())
            }
        }
    }

    fun find(titleId: String): FeedItem? = synchronized(cache) { cache.values.toList() }.asSequence()
        .flatMap { it.second.asSequence() }.map { it.item }.firstOrNull { it.title.id == titleId }

    fun clear() = cache.clear()

    companion object {
        private const val TAG = "BruDreSearch"
        const val MAX_PARALLEL = 3
        const val PER_SOURCE_MS = 12_000L
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
