package com.brudre.streaming.torrent

import android.net.Uri
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Arquivo de vídeo dentro de um torrent, lido progressivamente.
 * O player pede bytes; o stream garante que as peças correspondentes
 * cheguem primeiro (deadlines do libtorrent) e bloqueia até estarem no disco.
 */
class TorrentStream(
    val handle: TorrentHandle,
    val info: TorrentInfo,
    val fileIndex: Int,
    val file: File,
    val math: PieceMath,
) {
    val key: String = "${handle.infoHash().toHex()}/$fileIndex"
    val uri: Uri = Uri.parse("torrent://$key")
    val fileSize: Long get() = math.fileSize

    @Volatile var closed = false
        private set

    private val readAhead = math.piecesFor(READ_AHEAD_BYTES).coerceIn(4, 48)
    @Volatile private var lastWindowStart = -1

    /** Prioriza o início e o fim do arquivo (MP4 pode ter o índice "moov" no final). */
    fun prime() {
        requestWindow(math.firstPiece, readAhead, force = true)
        val last = math.lastPiece
        safe { handle.setPieceDeadline(last, 1500) }
        if (last - 1 > math.firstPiece) safe { handle.setPieceDeadline(last - 1, 2000) }
    }

    fun havePiece(piece: Int): Boolean = safe { handle.havePiece(piece) } ?: false

    /** Define prazos crescentes para as próximas peças a partir de [fromPiece]. */
    fun requestWindow(fromPiece: Int, count: Int = readAhead, force: Boolean = false) {
        if (!force && fromPiece == lastWindowStart) return
        lastWindowStart = fromPiece
        var i = 0
        for (p in math.window(fromPiece, count)) {
            if (!havePiece(p)) safe { handle.setPieceDeadline(p, DEADLINE_BASE_MS + i * DEADLINE_STEP_MS) }
            i++
        }
    }

    /** Após um seek, descarta prazos antigos para concentrar banda no novo trecho. */
    fun onSeek(filePosition: Long) {
        safe { handle.clearPieceDeadlines() }
        requestWindow(math.pieceAt(filePosition), force = true)
    }

    /** Bloqueia até a peça estar baixada e verificada. */
    @Throws(IOException::class)
    fun awaitPiece(piece: Int, timeoutMs: Long = PIECE_TIMEOUT_MS) {
        if (havePiece(piece)) return
        requestWindow(piece, force = true)
        val start = System.currentTimeMillis()
        var lastNudge = start
        while (!havePiece(piece)) {
            if (closed) throw InterruptedIOException("Stream fechado")
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Leitura cancelada")
            val now = System.currentTimeMillis()
            if (now - start > timeoutMs) {
                val st = statusLine()
                throw IOException("Torrent sem dados para continuar (${st}). Nenhum peer ou servidor enviou o trecho necessário.")
            }
            if (now - lastNudge > 2000) {
                requestWindow(piece, force = true)
                lastNudge = now
            }
            try {
                Thread.sleep(40)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Leitura cancelada")
            }
        }
    }

    data class Stats(val peers: Int, val seeds: Int, val downloadRate: Int, val progress: Float)

    fun stats(): Stats? = safe {
        val st = handle.status()
        Stats(st.numPeers(), st.numSeeds(), st.downloadPayloadRate(), st.progress())
    }

    fun statusLine(): String {
        val s = stats() ?: return "sem status"
        return "${s.peers} conexões • ${CacheManager.formatBytes(s.downloadRate.toLong())}/s"
    }

    fun close() {
        closed = true
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        if (closed || !handle.isValid) null else block()
    } catch (e: Throwable) {
        null
    }

    companion object {
        private const val READ_AHEAD_BYTES = 12L * 1024 * 1024
        private const val DEADLINE_BASE_MS = 300
        private const val DEADLINE_STEP_MS = 250
        const val PIECE_TIMEOUT_MS = 60_000L
    }
}
