package com.brudre.streaming.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/** HTTP 429/503 com Retry-After: o servidor pediu para esperar [delayMs] antes de tentar de novo. */
class RetryAfterException(code: Int, val delayMs: Long) : HttpStatusException(code)

/**
 * Novas tentativas para falhas TRANSITÓRIAS de fontes (9.2.43): tempo esgotado, conexão recusada/reiniciada,
 * HTTP 408/429/502/503/504. Espera exponencial (1 s, 2 s), respeita Retry-After (até 8 s), nunca passa do prazo
 * total e nunca tenta para sempre. DNS, TLS, autenticação, 404 e formato não são repetidos: não adianta.
 * Cancelável: ao sair da tela, a espera e a tentativa em andamento são canceladas.
 */
object SourceHttp {
    const val MAX_ATTEMPTS = 3
    const val MAX_RETRY_AFTER_MS = 8_000L

    /** Prazo total para uma consulta com novas tentativas (por tentativa vale o tempo-limite da fonte). */
    fun deadlineMs(timeoutSec: Int): Long = minOf(timeoutSec.coerceIn(3, 60) * 2, 60) * 1000L

    /** Espera antes da próxima tentativa, ou null se a falha não é transitória. */
    fun retryDelay(e: Throwable, attempt: Int): Long? {
        val backoff = 1_000L shl (attempt - 1).coerceIn(0, 4)
        val text = generateSequence(e) { it.cause }.take(5).mapNotNull { it.message?.lowercase() }.joinToString(" ")
        return when {
            e is RetryAfterException -> e.delayMs.coerceIn(0, MAX_RETRY_AFTER_MS)
            e is HttpStatusException -> if (e.code in setOf(408, 429, 502, 503, 504)) backoff else null
            e is ClassifiedException || e is BlockedUrlException -> null
            e is SocketTimeoutException || e is ConnectException -> backoff
            e is IOException && ("connection reset" in text || "unexpected end of stream" in text || "timeout" in text) -> backoff
            else -> null
        }
    }

    /** "Retry-After: 5" (segundos). Datas HTTP e valores inválidos → null (usa a espera exponencial). */
    fun parseRetryAfter(v: String?): Long? = v?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 }

    suspend fun <T> retrying(
        deadlineMs: Long,
        maxAttempts: Int = MAX_ATTEMPTS,
        now: () -> Long = System::currentTimeMillis,
        sleep: suspend (Long) -> Unit = { delay(it) },
        onRetry: (attempt: Int, e: Throwable, waitMs: Long) -> Unit = { _, _, _ -> },
        block: suspend (attempt: Int) -> T,
    ): T {
        val end = now() + deadlineMs
        var attempt = 1
        while (true) {
            try {
                return block(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val wait = retryDelay(e, attempt) ?: throw e
                if (attempt >= maxAttempts || now() + wait >= end) throw e
                onRetry(attempt, e, wait)
                sleep(wait)
                attempt++
            }
        }
    }
}
