package com.brudre.streaming.sources

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/**
 * Validação de URLs vindas de fontes não confiáveis (listas M3U, metadados, redirecionamentos).
 * Funções puras: usadas pelo interceptor de rede, pelo parser de listas e pelos testes.
 */
object UrlGuard {
    private val ALLOWED_SCHEMES = setOf("http", "https")

    /** Extensões que nunca são mídia: instaladores, executáveis e scripts. */
    val EXECUTABLE_EXTENSIONS = setOf(
        "apk", "apks", "xapk", "aab", "dex", "jar", "exe", "msi", "bat", "cmd", "com", "scr", "pif",
        "dll", "so", "bin", "elf", "sh", "ps1", "vbs", "wsf", "app", "dmg", "pkg", "deb", "rpm",
    )

    val EXECUTABLE_MIME = setOf(
        "application/vnd.android.package-archive", "application/x-msdownload", "application/x-msdos-program",
        "application/x-dosexec", "application/x-executable", "application/x-elf", "application/x-sharedlib",
        "application/java-archive", "application/x-java-archive", "application/x-sh", "application/x-shellscript",
        "application/x-msi", "application/x-apple-diskimage",
    )

    /** Canais FAST (Pluto TV) redirecionam para URLs assinadas de vários KB. */
    const val MAX_URL_LENGTH = 16_384

    /** Motivo do bloqueio, ou null se a URL pode ser acessada. */
    fun check(url: String?): String? {
        if (url.isNullOrBlank()) return "Endereço vazio"
        if (url.length > MAX_URL_LENGTH) return "Endereço longo demais"
        if (url.any { it.isWhitespace() || it.code < 0x20 }) return "Endereço com caracteres inválidos"
        val uri = runCatching { URI(url) }.getOrNull() ?: return "Endereço malformado"
        val scheme = uri.scheme?.lowercase() ?: return "Endereço sem protocolo"
        if (scheme !in ALLOWED_SCHEMES) return "Protocolo não permitido ($scheme)"
        if (uri.rawUserInfo != null) return "Endereço com credenciais embutidas"
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return "Endereço sem servidor"
        if (host.isEmpty()) return "Endereço sem servidor"
        if (host == "localhost" || host.endsWith(".localhost")) return "Destino local bloqueado"
        literalAddress(host)?.let { if (isBlockedAddress(it)) return "Destino local bloqueado" }
        if (uri.port != -1 && uri.port !in 1..65535) return "Porta inválida"
        if (isExecutablePath(uri.rawPath)) return "Arquivo executável bloqueado"
        return null
    }

    fun isAllowed(url: String?): Boolean = check(url) == null

    fun isExecutablePath(path: String?): Boolean {
        val last = path?.substringAfterLast('/')?.lowercase() ?: return false
        if (!last.contains('.')) return false
        return last.substringAfterLast('.') in EXECUTABLE_EXTENSIONS
    }

    fun isExecutableResponse(contentType: String?, url: String?): Boolean {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (mime != null && mime in EXECUTABLE_MIME) return true
        val path = url?.let { runCatching { URI(it).rawPath }.getOrNull() }
        return isExecutablePath(path)
    }

    /** Loopback, link-local, multicast e "qualquer endereço": nunca são destino de mídia. */
    fun isBlockedAddress(addr: InetAddress): Boolean =
        addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress || addr.isMulticastAddress ||
            (addr is Inet4Address && addr.address[0].toInt() == 0)

    /** Converte só IPs literais (não faz consulta de DNS). */
    private fun literalAddress(host: String): InetAddress? {
        val h = host.removePrefix("[").removeSuffix("]")
        val isV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h)
        val isV6 = h.contains(':') && h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
        if (!isV4 && !isV6) return null
        if (isV4 && h.split('.').any { it.toInt() > 255 }) return null
        return runCatching { InetAddress.getByName(h) }.getOrNull()
    }

    fun hostOf(url: String?): String? = url?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }
}
