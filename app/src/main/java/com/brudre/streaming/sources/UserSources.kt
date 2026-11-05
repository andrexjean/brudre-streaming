package com.brudre.streaming.sources

import com.brudre.streaming.data.optStringOrNull
import com.brudre.streaming.live.M3uParser
import org.json.JSONArray
import org.json.JSONObject

/** Formatos aceitos em Configurações → Gerenciar fontes. */
enum class SourceType(val label: String) {
    CATALOG_JSON("Catálogo BruDre (JSON)"),
    RSS("Feed RSS de vídeos"),
    M3U("Playlist IPTV (M3U)"),
    HLS("Transmissão HLS (M3U8)"),
    VIDEO("Vídeo HTTP/HTTPS"),
    MAGNET("Magnet link"),
    TORRENT("Arquivo .torrent");

    val isCatalog get() = this == CATALOG_JSON || this == RSS
    val isSingle get() = this == HLS || this == VIDEO || this == MAGNET || this == TORRENT

    /** Prefixo do id: mantém o roteamento dos módulos (catálogos, TV ao vivo, links). */
    val idPrefix get() = when {
        isCatalog -> CATALOG_PREFIX
        this == M3U -> M3U_PREFIX
        else -> LINK_PREFIX
    }

    companion object {
        /** Iguais a FeedRepository.PREFIX e LiveRepository.CUSTOM_PREFIX. */
        const val CATALOG_PREFIX = "feed_"
        const val M3U_PREFIX = "m3u_"
        const val LINK_PREFIX = "link_"
    }
}

data class SourceTest(val ok: Boolean, val at: Long, val message: String, val items: Int = 0)

/** Fonte cadastrada pelo usuário. A posição na lista ([priority], menor primeiro) define a prioridade. */
data class UserSource(
    val id: String,
    val name: String,
    val url: String,
    val type: SourceType,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastTest: SourceTest? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url).put("type", type.name)
        .put("enabled", enabled).put("priority", priority).put("addedAt", addedAt)
        .put("test", lastTest?.let { JSONObject().put("ok", it.ok).put("at", it.at).put("msg", it.message).put("items", it.items) } ?: JSONObject.NULL)

    companion object {
        fun idFor(type: SourceType, url: String) = type.idPrefix + M3uParser.stableId("feed", url.trim())

        fun fromJson(o: JSONObject): UserSource? {
            val type = runCatching { SourceType.valueOf(o.getString("type")) }.getOrNull() ?: return null
            val url = o.optString("url").trim()
            if (url.isEmpty()) return null
            return UserSource(
                id = idFor(type, url),
                name = o.optString("name").trim().ifBlank { "Fonte" }.take(60),
                url = url,
                type = type,
                enabled = o.optBoolean("enabled", true),
                priority = o.optInt("priority", 0),
                addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                lastTest = o.optJSONObject("test")?.let { SourceTest(it.optBoolean("ok"), it.optLong("at"), it.optString("msg"), it.optInt("items")) },
            )
        }
    }
}

/** Importação/exportação das fontes (arquivo JSON versionado). */
object UserSourcesJson {
    const val FORMAT = "brudre-fontes"

    fun export(list: List<UserSource>): String = JSONObject()
        .put("format", FORMAT).put("version", 1)
        .put("sources", JSONArray().apply { list.sortedBy { it.priority }.forEach { put(it.toJson().apply { remove("test") }) } })
        .toString(2)

    /** Lê um arquivo exportado; fontes inseguras ou de formato desconhecido são descartadas. */
    fun import(text: String): List<UserSource> {
        val root = try { JSONObject(text.trim()) } catch (e: Exception) { throw IllegalArgumentException("Arquivo de configuração inválido") }
        if (root.optString("format") != FORMAT) throw IllegalArgumentException("Não é um arquivo de fontes do BruDre")
        val arr = root.optJSONArray("sources") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { UserSource.fromJson(it) } }
            .filter { s -> if (s.type == SourceType.MAGNET) FeedParser.isValidMagnet(s.url) else UrlGuard.isAllowed(s.url) && s.url.startsWith("https://", true) }
            .distinctBy { it.id }
            .take(MAX_SOURCES)
    }

    const val MAX_SOURCES = 40

    fun listToJson(list: List<UserSource>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    fun listFromJson(json: String): List<UserSource> = runCatching {
        val a = JSONArray(json); (0 until a.length()).mapNotNull { UserSource.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}

/**
 * Identificação automática do formato pelos primeiros bytes (e pelo tipo informado pelo servidor).
 * Nunca presume que um endereço é catálogo: páginas web, executáveis e conteúdo desconhecido são recusados.
 */
object SourceDetector {
    data class Detection(val type: SourceType?, val reason: String)

    fun classify(url: String, contentType: String?, head: ByteArray): Detection {
        val u = url.trim()
        if (u.startsWith("magnet:", true)) {
            return if (FeedParser.isValidMagnet(u)) Detection(SourceType.MAGNET, "Magnet link válido") else Detection(null, "Magnet sem hash de torrent válido")
        }
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (UrlGuard.isExecutableResponse(mime, u) || isExecutable(head)) return Detection(null, "Arquivo executável ou instalador: bloqueado")
        val text = String(head, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        val lower = text.take(4096).lowercase()
        return when {
            text.startsWith("#EXTM3U") -> when {
                lower.contains("#ext-x-stream-inf") || lower.contains("#ext-x-targetduration") || lower.contains("#ext-x-media-sequence") ->
                    Detection(SourceType.HLS, "Transmissão HLS")
                lower.contains("#extinf") -> Detection(SourceType.M3U, "Playlist IPTV M3U")
                else -> Detection(null, "Lista M3U vazia")
            }
            text.startsWith("#EXTINF") -> Detection(SourceType.M3U, "Playlist IPTV M3U")
            isTorrent(head) || mime == "application/x-bittorrent" -> Detection(SourceType.TORRENT, "Arquivo .torrent")
            text.startsWith("{") -> if (lower.contains("\"items\"")) Detection(SourceType.CATALOG_JSON, "Catálogo JSON do BruDre")
            else Detection(null, "JSON sem a lista \"items\" do formato de catálogo do BruDre")
            text.startsWith("<") -> when {
                lower.contains("<rss") || lower.contains("<channel") -> Detection(SourceType.RSS, "Feed RSS")
                lower.contains("<html") || lower.contains("<!doctype html") -> Detection(null, "É uma página web, não uma fonte de vídeo")
                else -> Detection(null, "XML não reconhecido")
            }
            isVideo(head) || mime.startsWith("video/") -> Detection(SourceType.VIDEO, "Vídeo")
            mime.contains("mpegurl") -> Detection(SourceType.HLS, "Transmissão HLS")
            else -> Detection(null, "Formato não reconhecido")
        }
    }

    private fun isExecutable(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val mz = b[0] == 'M'.code.toByte() && b[1] == 'Z'.code.toByte()
        val elf = b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
        val dex = b[0] == 'd'.code.toByte() && b[1] == 'e'.code.toByte() && b[2] == 'x'.code.toByte() && b[3] == '\n'.code.toByte()
        // ZIP/APK: só é aceito se não houver vídeo (MP4 nunca começa com PK)
        val zip = b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()
        return mz || elf || dex || zip
    }

    private fun isTorrent(b: ByteArray): Boolean {
        if (b.isEmpty() || b[0] != 'd'.code.toByte()) return false
        val s = String(b, 0, minOf(b.size, 4096), Charsets.ISO_8859_1)
        return s.contains("4:info") || s.contains("8:announce") || s.contains("13:announce-list")
    }

    private fun isVideo(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val ftyp = b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()
        val ebml = b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()
        val ts = b[0] == 0x47.toByte() && (b.size < 189 || b[188] == 0x47.toByte())
        return ftyp || ebml || ts
    }
}
