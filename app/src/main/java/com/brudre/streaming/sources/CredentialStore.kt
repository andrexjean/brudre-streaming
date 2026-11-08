package com.brudre.streaming.sources

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credenciais das fontes (chave de API, token), cifradas com AES-256-GCM por uma chave do Android Keystore
 * (não exportável: os bytes da chave nunca saem do hardware/sistema). Ficam fora do JSON das fontes, nunca vão
 * na exportação e são apagadas com a fonte.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("brudre_credentials", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    fun put(sourceId: String, secret: String?) {
        if (secret.isNullOrEmpty()) { prefs.edit().remove(sourceId).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = c.iv + c.doFinal(secret.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(sourceId, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun get(sourceId: String): String? {
        val raw = prefs.getString(sourceId, null) ?: return null
        return try {
            val data = Base64.decode(raw, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12)) }
            String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("BruDreSources", "Credencial ilegível (chave do sistema trocada?): descartada")
            prefs.edit().remove(sourceId).apply()
            null
        }
    }

    fun has(sourceId: String) = prefs.contains(sourceId)

    /** Move a credencial quando o id da fonte muda (edição ou redetecção de formato). */
    fun move(from: String, to: String) { if (from != to) get(from)?.let { put(to, it); put(from, null) } }

    companion object { private const val ALIAS = "brudre_source_credentials" }
}
