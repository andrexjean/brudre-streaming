package com.brudre.streaming.sources

import com.brudre.streaming.data.CatalogException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Tipos de falha de uma fonte, com explicação em português para a TV. */
enum class ErrorKind(val title: String, val hint: String) {
    DNS("Endereço não encontrado (DNS)", "O nome do servidor não existe ou não é resolvido pela sua rede/provedor. Confira o endereço."),
    UNREACHABLE("Servidor indisponível", "O servidor recusou ou não aceitou a conexão. Pode estar fora do ar; tente mais tarde."),
    TIMEOUT("Tempo esgotado", "O servidor demorou demais para responder. Verifique a internet ou tente mais tarde."),
    HTTP("Erro do servidor (HTTP)", "O servidor respondeu, mas recusou o pedido ou o arquivo não existe mais."),
    TLS("Certificado de segurança inválido", "A conexão segura (HTTPS) falhou: certificado vencido, inválido ou data do aparelho errada."),
    FORMAT("Formato incompatível", "O endereço não aponta para um formato aceito (vídeo, HLS, M3U, catálogo JSON/RSS, magnet ou .torrent)."),
    CATALOG("Catálogo inválido", "O arquivo foi encontrado, mas a estrutura não segue o formato de catálogo do BruDre ou não tem títulos válidos."),
    MAGNET("Magnet inválido", "O link magnet não tem um hash de torrent válido."),
    NO_PEERS("Sem participantes (peers)", "Ninguém está compartilhando esse torrent agora e não há servidor web de apoio."),
    NO_VIDEO("Sem vídeo reproduzível", "O conteúdo não tem um arquivo de vídeo compatível com o aparelho."),
    BLOCKED("Bloqueada pela segurança", "O endereço foi bloqueado pelas regras de segurança do BruDre (destino local, executável ou sem HTTPS)."),
    STORAGE("Problema de armazenamento", "Falta espaço ou houve erro ao gravar no aparelho. Limpe o cache em Configurações."),
    UNKNOWN("Falha não identificada", "Veja os detalhes técnicos."),
}

/** Falha classificada: mensagem para o usuário + erro original para diagnóstico. */
data class SourceFailure(val kind: ErrorKind, val message: String, val technical: String) {
    /** Linha curta para listas e avisos. */
    val summary: String get() = "${kind.title}: $message"
}

/** Resposta HTTP sem sucesso (mantém o código para a classificação). */
class HttpStatusException(val code: Int, message: String = "servidor respondeu $code") : IOException(message)

/** Erro já classificado (ex.: magnet inválido, torrent sem vídeo). */
open class ClassifiedException(val kind: ErrorKind, message: String, cause: Throwable? = null) : IOException(message, cause)

object ErrorClassifier {
    fun classify(e: Throwable): SourceFailure {
        val chain = generateSequence(e) { it.cause }.take(8).toList()
        val technical = chain.joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message ?: "-"}" }.take(600)
        fun f(kind: ErrorKind, msg: String = kind.hint) = SourceFailure(kind, msg, technical)
        chain.firstOrNull { it is ClassifiedException && it.kind != ErrorKind.UNKNOWN }?.let { val c = it as ClassifiedException; return f(c.kind, c.message ?: c.kind.hint) }
        chain.firstOrNull { it is BlockedUrlException }?.let { return f(ErrorKind.BLOCKED, it.message ?: ErrorKind.BLOCKED.hint) }
        chain.firstOrNull { it is HttpStatusException }?.let { return f(ErrorKind.HTTP, httpMessage((it as HttpStatusException).code)) }
        for (c in chain) {
            when (c) {
                is UnknownHostException -> return f(ErrorKind.DNS, "Não foi possível localizar ${hostIn(c.message) ?: "o servidor"}. ${ErrorKind.DNS.hint}")
                is SocketTimeoutException -> return f(ErrorKind.TIMEOUT)
                is ConnectException, is NoRouteToHostException, is PortUnreachableException -> return f(ErrorKind.UNREACHABLE)
                is SSLHandshakeException, is SSLPeerUnverifiedException, is CertificateException -> return f(ErrorKind.TLS)
                is SSLException -> return f(ErrorKind.TLS)
            }
        }
        val text = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
        return when {
            "enospc" in text || "no space left" in text || "armazenamento insuficiente" in text -> f(ErrorKind.STORAGE, e.message ?: ErrorKind.STORAGE.hint)
            e is InterruptedIOException && "timeout" in text -> f(ErrorKind.TIMEOUT)
            "timeout" in text || "timed out" in text -> f(ErrorKind.TIMEOUT)
            "connection reset" in text || "unexpected end of stream" in text || "connection refused" in text -> f(ErrorKind.UNREACHABLE)
            e is CatalogException || e is org.json.JSONException || e is IllegalArgumentException -> f(ErrorKind.CATALOG, e.message ?: ErrorKind.CATALOG.hint)
            else -> f(ErrorKind.UNKNOWN, e.message ?: ErrorKind.UNKNOWN.hint)
        }
    }

    fun httpMessage(code: Int): String = when (code) {
        401, 403 -> "Acesso negado pelo servidor (HTTP $code). A fonte exige autorização que o BruDre não possui."
        404, 410 -> "Arquivo não encontrado no servidor (HTTP $code). O endereço pode ter mudado ou sido removido."
        408 -> "O servidor encerrou o pedido por demora (HTTP 408)."
        429 -> "O servidor limitou o número de acessos (HTTP 429). Tente mais tarde."
        in 500..599 -> "O servidor está com problema interno (HTTP $code). Tente mais tarde."
        else -> "O servidor respondeu com erro HTTP $code."
    }

    private fun hostIn(msg: String?): String? = msg?.let { Regex("\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
}
