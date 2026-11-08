package com.brudre.streaming.sources

import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.data.Playable
import com.brudre.streaming.torrent.MagnetLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Requisição recusada pelas regras de segurança (não é falha de rede: não adianta tentar de novo). */
class BlockedUrlException(reason: String) : IOException(reason)

/** Valida cada salto de redirecionamento e recusa respostas que sejam executáveis. */
class GuardInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url.toString()
        UrlGuard.check(url)?.let { throw BlockedUrlException("$it: ${chain.request().url.host}") }
        val response = chain.proceed(chain.request())
        if (UrlGuard.isExecutableResponse(response.header("Content-Type"), url)) {
            response.close()
            throw BlockedUrlException("Arquivo executável bloqueado")
        }
        return response
    }
}

/** DNS que não resolve para loopback/link-local (evita que listas externas apontem para o próprio aparelho). */
object GuardDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        // Nomes que resolvem para a rede privada só valem para servidores locais cadastrados (anti-SSRF / DNS rebinding).
        val local = LocalServers.isRegisteredHost(hostname)
        val ok = all.filterNot { UrlGuard.isBlockedAddress(it) || (LocalServers.isPrivate(it) && !local) }
        if (ok.isEmpty()) throw UnknownHostException("Destino bloqueado: $hostname")
        return ok
    }
}

object GuardedHttp {
    /** Cliente para mídia e listas de terceiros: sem cache em disco, com guarda de URL e DNS. */
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .cache(null)
        .dns(GuardDns)
        // Validação padrão do Android; certificado fixado só para servidores locais confirmados pelo usuário.
        .sslSocketFactory(LocalServers.socketFactory, LocalServers.trustManager)
        .hostnameVerifier(LocalServers.hostnameVerifier)
        // Antes de conectar (IPs literais não passam pelo DNS) e em cada redirecionamento.
        .addInterceptor(GuardInterceptor())
        .addNetworkInterceptor(GuardInterceptor())
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

/**
 * Confirma que um vídeo responde antes de mostrar "Assistir":
 * HEAD (ou GET dos primeiros bytes), tipo de conteúdo compatível e, para HLS, manifesto válido.
 */
class StreamValidator(guarded: OkHttpClient) {
    enum class Result { OK, INVALID, UNKNOWN }

    companion object { private const val OK_TTL_MS = 20 * 60 * 1000L }

    private val client = guarded.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Fontes confirmadas há pouco não são verificadas de novo (abre o vídeo mais rápido). */
    private val recent = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun validate(p: Playable): Result = withContext(Dispatchers.IO) {
        val key = p.directUrl ?: p.torrent?.torrentUrl ?: p.torrent?.magnet ?: ""
        recent[key]?.let { if (System.currentTimeMillis() - it < OK_TTL_MS) return@withContext Result.OK }
        val r = validateNow(p)
        if (r == Result.OK && key.isNotEmpty()) {
            if (recent.size > 200) recent.clear()
            recent[key] = System.currentTimeMillis()
        }
        r
    }

    private fun validateNow(p: Playable): Result {
        var unknown = false
        for (u in (listOfNotNull(p.directUrl) + p.mirrors).distinct().take(3)) {
            when (check(u, p.headers)) {
                Result.OK -> return Result.OK
                Result.UNKNOWN -> unknown = true
                Result.INVALID -> Unit
            }
        }
        for (t in listOfNotNull(p.torrent) + p.altTorrents) {
            // .torrent importado (já validado ao importar) ou magnet com hash válido.
            if (t.localFile != null && java.io.File(t.localFile).exists()) return Result.OK
            if (t.magnet != null && MagnetLink.parse(t.magnet) != null) return Result.OK
            t.torrentUrl?.let { url ->
                when (check(url, emptyMap())) {
                    Result.OK -> return Result.OK
                    Result.UNKNOWN -> unknown = true
                    Result.INVALID -> Unit
                }
            }
        }
        return if (unknown) Result.UNKNOWN else Result.INVALID
    }

    fun check(url: String, headers: Map<String, String>): Result {
        if (UrlGuard.check(url) != null) return Result.INVALID
        val hls = url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") }
        return try {
            if (hls) checkGet(url, headers, hls = true) else {
                val head = request(url, headers).head().build()
                client.newCall(head).execute().use { r ->
                    when {
                        r.code == 405 || r.code == 403 || r.code == 501 -> null
                        else -> classify(r, hls = false)
                    }
                } ?: checkGet(url, headers, hls = false)
            }
        } catch (e: BlockedUrlException) {
            Result.INVALID
        } catch (e: IOException) {
            Result.UNKNOWN
        }
    }

    private fun checkGet(url: String, headers: Map<String, String>, hls: Boolean): Result {
        val get = request(url, headers).header("Range", "bytes=0-2047").get().build()
        return client.newCall(get).execute().use { r ->
            val base = classify(r, hls)
            if (base != Result.OK || !hls) return@use base
            val head = r.body?.source()?.let { src -> src.request(64); src.buffer.snapshot().utf8().take(64) }.orEmpty()
            if (head.trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U")) Result.OK else Result.INVALID
        }
    }

    private fun request(url: String, headers: Map<String, String>): Request.Builder =
        Request.Builder().url(url).header("User-Agent", headers["User-Agent"] ?: CatalogRepository.USER_AGENT).apply {
            headers["Referer"]?.let { header("Referer", it) }
        }

    private fun classify(r: Response, hls: Boolean): Result {
        if (r.code == 404 || r.code == 410 || r.code == 401 || r.code == 451) return Result.INVALID
        if (r.code >= 500 || r.code == 429) return Result.UNKNOWN
        if (r.code !in 200..299) return Result.INVALID
        val type = r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (UrlGuard.isExecutableResponse(type, r.request.url.toString())) return Result.INVALID
        if (!hls && type.startsWith("text/html")) return Result.INVALID
        return Result.OK
    }
}
