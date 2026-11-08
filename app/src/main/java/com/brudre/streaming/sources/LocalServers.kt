package com.brudre.streaming.sources

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Servidores locais autorizados pelo usuário (ex.: Jackett/Prowlarr/Jellyfin na rede de casa).
 *
 * Regras:
 * - Destinos da rede privada (10/8, 172.16/12, 192.168/16, fc00::/7) só são acessados se o host:porta foi
 *   cadastrado como servidor local. Loopback, link-local e o próprio aparelho continuam bloqueados.
 * - Sempre HTTPS. O tráfego sem criptografia continua desligado no app inteiro (nenhuma exceção global).
 * - O certificado do servidor (geralmente autoassinado) é FIXADO: a impressão digital SHA-256 é mostrada no
 *   cadastro e confirmada pelo usuário; depois disso só esse certificado é aceito, só para esse host:porta.
 *   Para os demais hosts a validação é a do Android, sem alteração.
 * - Importação de fontes não traz fixações (cada servidor precisa ser confirmado no aparelho).
 */
object LocalServers {
    private val pins = ConcurrentHashMap<String, String>()

    fun key(host: String, port: Int) = host.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]") + ":" + port

    private fun hostPort(url: String): Pair<String, Int>? = runCatching {
        val u = URI(url.substringBefore('{').ifBlank { url })
        val h = u.host ?: return null
        h to (if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80)
    }.getOrNull()

    fun keyOf(url: String): String? = hostPort(url)?.let { (h, p) -> key(h, p) }

    /** Recarrega a partir das fontes salvas (chamado pelo SourceStore a cada mudança). */
    fun sync(all: Map<String, String>) {
        pins.keys.retainAll(all.keys)
        pins.putAll(all)
    }

    fun register(url: String, pin: String) { keyOf(url)?.let { pins[it] = normalizePin(pin) } }
    fun unregister(url: String) { keyOf(url)?.let { pins.remove(it) } }

    fun pinFor(host: String, port: Int): String? = pins[key(host, port)]
    fun isRegisteredHost(host: String): Boolean { val h = key(host, 0).substringBeforeLast(':'); return pins.keys.any { it.substringBeforeLast(':') == h } }
    fun allows(url: String): Boolean = keyOf(url)?.let { pins.containsKey(it) } == true
    val count get() = pins.size

    /** Rede privada (não inclui loopback/link-local, que nunca são permitidos). */
    fun isPrivate(addr: InetAddress): Boolean = when (addr) {
        is Inet4Address -> addr.isSiteLocalAddress
        is Inet6Address -> (addr.address[0].toInt() and 0xfe) == 0xfc
        else -> false
    }

    fun normalizePin(p: String) = p.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

    fun sha256(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /** "AB:CD:…" para exibição. */
    fun pretty(pin: String) = normalizePin(pin).chunked(2).joinToString(":").uppercase()

    private fun matches(host: String?, port: Int, chain: Array<out X509Certificate>?): Boolean? {
        if (host == null) return null
        val pin = pinFor(host, port) ?: return null
        val leaf = chain?.firstOrNull() ?: return false
        return sha256(leaf) == pin
    }

    private val system: X509TrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Validação do Android para todos os hosts; certificado fixado só para servidores locais cadastrados. */
    val trustManager: X509TrustManager = object : X509ExtendedTrustManager() {
        private fun sys(chain: Array<out X509Certificate>, authType: String, socket: Socket?, engine: SSLEngine?) {
            val s = system
            when {
                s is X509ExtendedTrustManager && socket != null -> s.checkServerTrusted(chain, authType, socket)
                s is X509ExtendedTrustManager && engine != null -> s.checkServerTrusted(chain, authType, engine)
                else -> s.checkServerTrusted(chain, authType)
            }
        }

        private fun check(chain: Array<out X509Certificate>, authType: String, host: String?, port: Int, socket: Socket?, engine: SSLEngine?) {
            when (matches(host, port, chain)) {
                true -> return
                false -> throw CertificateException("Certificado do servidor local mudou (não confere com o confirmado no cadastro)")
                null -> sys(chain, authType, socket, engine)
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) {
            val ssl = socket as? SSLSocket
            check(chain, authType, ssl?.handshakeSession?.peerHost ?: socket?.inetAddress?.hostAddress, socket?.port ?: -1, socket, null)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
            check(chain, authType, engine?.peerHost, engine?.peerPort ?: -1, null, engine)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = sys(chain, authType, null, null)
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = system.checkClientTrusted(chain, authType)
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = system.checkClientTrusted(chain, authType)
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    val socketFactory: SSLSocketFactory by lazy { SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory }

    /** Nome do host: verificação padrão; servidor local fixado dispensa o nome no certificado (é autoassinado por IP). */
    val hostnameVerifier = HostnameVerifier { host, session ->
        val pin = pinFor(host, session.peerPort)
        if (pin != null) (session.peerCertificates.firstOrNull() as? X509Certificate)?.let { sha256(it) == pin } == true
        else okhttp3.internal.tls.OkHostnameVerifier.verify(host, session)
    }

    data class CertInfo(val pin: String, val subject: String, val issuer: String, val notAfter: java.util.Date, val selfSigned: Boolean)

    /**
     * Lê o certificado de um servidor da rede privada para o usuário conferir (só o handshake; nenhum dado é enviado).
     * Recusa endereços públicos (esses usam a validação normal) e loopback/link-local.
     */
    fun fetchCertificate(url: String, timeoutMs: Int = 8_000): CertInfo {
        if (!url.startsWith("https://", true)) throw ClassifiedException(ErrorKind.BLOCKED, "Servidor local precisa de HTTPS (o app não usa conexões sem criptografia)")
        val (host, port) = hostPort(url) ?: throw ClassifiedException(ErrorKind.FORMAT, "Endereço inválido")
        val addrs = InetAddress.getAllByName(host).toList()
        if (addrs.isEmpty() || addrs.any { UrlGuard.isBlockedAddress(it) }) throw ClassifiedException(ErrorKind.BLOCKED, "O próprio aparelho e endereços link-local não são permitidos")
        if (!addrs.all { isPrivate(it) }) throw ClassifiedException(ErrorKind.BLOCKED, "$host não é da rede local: servidores públicos usam a validação normal de certificado")
        var captured: Array<out X509Certificate>? = null
        val capture = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) { captured = chain }
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException()
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
        val raw = Socket()
        raw.use {
            raw.connect(InetSocketAddress(addrs.first(), port), timeoutMs)
            raw.soTimeout = timeoutMs
            (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).use { s -> s.startHandshake() }
        }
        val leaf = captured?.firstOrNull() ?: throw ClassifiedException(ErrorKind.TLS, "O servidor não apresentou certificado")
        return CertInfo(sha256(leaf), leaf.subjectX500Principal.name.take(120), leaf.issuerX500Principal.name.take(120), leaf.notAfter, leaf.subjectX500Principal == leaf.issuerX500Principal)
    }
}
