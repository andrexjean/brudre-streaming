package com.brudre.streaming

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.StatFs

/** Diagnóstico do aparelho (versão do Android, ABI, memória, armazenamento e decodificadores). */
object DeviceInfo {
    data class Codec(val label: String, val supported: Boolean, val hardware: Boolean, val maxHeight: Int?)

    data class Info(
        val model: String,
        val android: String,
        val api: Int,
        val abis: List<String>,
        val ramTotalMb: Long,
        val ramAvailMb: Long,
        val lowRamDevice: Boolean,
        val memoryClassMb: Int,
        val storageFreeMb: Long,
        val storageTotalMb: Long,
        val codecs: List<Codec>,
    ) {
        fun summary(): String = "$model • Android $android (API $api) • ${abis.firstOrNull() ?: "?"} • RAM ${ramTotalMb} MB"
        fun codecLine(): String = codecs.joinToString("  ") { c ->
            "${c.label} " + when {
                !c.supported -> "não"
                c.hardware -> "HW" + (c.maxHeight?.let { " até ${it}p" } ?: "")
                else -> "software"
            }
        }
    }

    private val TYPES = listOf(
        "H.264" to "video/avc", "H.265" to "video/hevc", "VP9" to "video/x-vnd.on2.vp9", "AV1" to "video/av01", "AAC" to "audio/mp4a-latm",
    )

    fun collect(context: Context): Info {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(context.filesDir.absolutePath)
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList())
        val codecs = TYPES.map { (label, mime) ->
            val decs = infos.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            val hw = decs.filter { d ->
                if (Build.VERSION.SDK_INT >= 29) d.isHardwareAccelerated
                else !d.name.startsWith("OMX.google.", true) && !d.name.startsWith("c2.android.", true)
            }
            val best = (hw.ifEmpty { decs }).firstOrNull()
            val maxH = best?.takeIf { mime.startsWith("video/") }?.let { d ->
                runCatching { d.getCapabilitiesForType(mime).videoCapabilities?.supportedHeights?.upper }.getOrNull()
            }
            Codec(label, decs.isNotEmpty(), hw.isNotEmpty(), maxH)
        }
        return Info(
            model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            android = Build.VERSION.RELEASE ?: "?",
            api = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            ramTotalMb = mem.totalMem / (1024 * 1024),
            ramAvailMb = mem.availMem / (1024 * 1024),
            lowRamDevice = am.isLowRamDevice,
            memoryClassMb = am.memoryClass,
            storageFreeMb = stat.availableBytes / (1024 * 1024),
            storageTotalMb = stat.totalBytes / (1024 * 1024),
            codecs = codecs,
        )
    }
}
