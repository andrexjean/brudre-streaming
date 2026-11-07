package com.brudre.streaming.torrent

import android.content.Context
import android.system.Os
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Certificados confiáveis para o OpenSSL embutido no libtorrent4j.
 *
 * Causa da falha na 9.0: o OpenSSL 3.5 do libtorrent4j foi compilado com OPENSSLDIR apontando para a máquina
 * de build ("/home/runner/work/.../openssl/ssl/cert.pem"), que não existe no Android. Sem nenhuma CA carregada,
 * toda conexão HTTPS (web seeds e trackers) era recusada. A 9.0.37 contornou isso desligando a validação.
 *
 * Correção: exporta as CAs do SISTEMA Android (as mesmas que o app e o navegador usam; CAs instaladas pelo
 * usuário ficam de fora) para um arquivo PEM privado do app e aponta o OpenSSL para ele via SSL_CERT_FILE,
 * antes de a sessão BitTorrent ser criada. A validação de certificado e de hostname do libtorrent fica LIGADA.
 */
object TlsTrust {
    private const val TAG = "BruDreTLS"
    private const val FILE_NAME = "ca-bundle.pem"

    /** Resultado do diagnóstico (exibido em Gerenciar fontes → Diagnóstico). */
    @Volatile var status: String = "não verificado"
        private set
    @Volatile var certificateCount: Int = 0
        private set

    /**
     * Garante o pacote de CAs e configura o OpenSSL. Retorna true se há CAs carregáveis.
     * Regerado quando o Android muda (atualização de sistema pode trocar as CAs) ou se o arquivo sumir.
     */
    @Synchronized
    fun install(context: Context): Boolean {
        return try {
            val dir = File(context.filesDir, "tls").apply { mkdirs() }
            val file = File(dir, FILE_NAME)
            val stamp = File(dir, "stamp")
            val expected = "${android.os.Build.FINGERPRINT}|v1"
            if (!file.exists() || file.length() < 1024 || !stamp.exists() || stamp.readText() != expected) {
                val pem = systemCertificatesPem()
                if (pem.second == 0) {
                    status = "nenhuma CA do sistema encontrada: HTTPS do BitTorrent indisponível"
                    Log.e(TAG, status)
                    return false
                }
                val tmp = File(dir, "$FILE_NAME.tmp")
                tmp.writeText(pem.first)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                stamp.writeText(expected)
                certificateCount = pem.second
            } else if (certificateCount == 0) {
                certificateCount = Regex("BEGIN CERTIFICATE").findAll(file.readText()).count()
            }
            Os.setenv("SSL_CERT_FILE", file.absolutePath, true)
            // Diretório inexistente: impede o OpenSSL de procurar no caminho da máquina de build.
            Os.setenv("SSL_CERT_DIR", dir.absolutePath, true)
            status = "validação ativa • $certificateCount CAs do sistema Android (trackers HTTPS); servidores web pela conexão segura do Android"
            Log.i(TAG, "OpenSSL do libtorrent usando ${file.absolutePath} ($certificateCount CAs)")
            true
        } catch (e: Exception) {
            status = "falha ao preparar certificados: ${e.message}"
            Log.e(TAG, status, e)
            false
        }
    }

    /** Somente CAs do sistema ("system:"), nunca as instaladas pelo usuário ("user:"). */
    private fun systemCertificatesPem(): Pair<String, Int> {
        val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val sb = StringBuilder(256 * 1024)
        var n = 0
        val now = System.currentTimeMillis()
        for (alias in ks.aliases().toList()) {
            if (!alias.startsWith("system:")) continue
            val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
            if (cert.notAfter.time < now) continue // CA vencida não entra
            sb.append("-----BEGIN CERTIFICATE-----\n")
            sb.append(Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"))
            sb.append("\n-----END CERTIFICATE-----\n")
            n++
        }
        return sb.toString() to n
    }
}
