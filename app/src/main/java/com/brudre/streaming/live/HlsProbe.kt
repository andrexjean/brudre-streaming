package com.brudre.streaming.live

import java.net.URI

/** Leitura mínima de playlists HLS para verificar se um canal está de fato no ar (funções puras). */
object HlsProbe {
    data class Variant(val uri: String, val bandwidth: Long, val codecs: String?)

    data class Playlist(
        val isMaster: Boolean,
        val variants: List<Variant>,
        val segments: List<String>,
        val endList: Boolean,
    )

    /** null se o texto não for uma playlist HLS. */
    fun parse(text: String): Playlist? {
        val lines = text.lineSequence().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) return null
        val variants = ArrayList<Variant>()
        val segments = ArrayList<String>()
        var pendingInf: String? = null
        var endList = false
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-STREAM-INF", true) -> pendingInf = l
                l.startsWith("#EXT-X-ENDLIST", true) -> endList = true
                l.startsWith("#") -> Unit
                pendingInf != null -> {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(pendingInf)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
                    val codecs = Regex("CODECS=\"([^\"]*)\"").find(pendingInf)?.groupValues?.get(1)
                    variants += Variant(l, bw, codecs)
                    pendingInf = null
                }
                else -> segments += l
            }
        }
        return Playlist(variants.isNotEmpty(), variants, segments, endList)
    }

    /** Variante mais leve: verifica mais rápido e é a primeira que o player tenta no Wi-Fi fraco. */
    fun lightest(p: Playlist): Variant? = p.variants.minByOrNull { it.bandwidth }

    fun resolve(base: String, ref: String): String? =
        runCatching { URI(base).resolve(URI(ref.trim().replace(" ", "%20"))).toString() }.getOrNull()

    /** Em transmissões ao vivo o segmento mais recente é o que importa. */
    fun probeSegment(p: Playlist): String? = if (p.endList) p.segments.firstOrNull() else p.segments.lastOrNull()
}
