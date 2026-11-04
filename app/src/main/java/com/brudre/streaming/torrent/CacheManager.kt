package com.brudre.streaming.torrent

import java.io.File
import java.util.Locale

/** Controle do armazenamento temporário (8 GB no Mi Stick). */
object CacheManager {

    fun dirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /**
     * Apaga dados de torrents antigos. Arquivos em [keep] (streams ativos) são preservados.
     * Com [limitBytes] = 0 apaga tudo que não estiver ativo.
     */
    fun trimTorrentDir(dir: File, keep: Set<File>, limitBytes: Long): Long {
        if (!dir.exists()) return 0
        val keepPaths = keep.map { it.absolutePath }.toSet()
        val candidates = dir.walkBottomUp()
            .filter { it.isFile && it.absolutePath !in keepPaths }
            .sortedBy { it.lastModified() }
            .toList()
        var total = dirSize(dir)
        var freed = 0L
        for (f in candidates) {
            if (limitBytes > 0 && total <= limitBytes) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                freed += len
            }
        }
        // Remove pastas vazias
        dir.walkBottomUp().filter { it.isDirectory && it != dir && (it.list()?.isEmpty() == true) }.forEach { it.delete() }
        return freed
    }

    /** Apaga todo o conteúdo de [dir] exceto [keep]. Retorna bytes liberados. */
    fun clearDir(dir: File, keep: Set<File> = emptySet()): Long = trimTorrentDir(dir, keep, 0)

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale("pt", "BR"), if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }
}
