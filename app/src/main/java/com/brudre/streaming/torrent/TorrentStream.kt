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
    /** Legendas encontradas dentro do torrent para este vídeo. */
    @Volatile var subtitles: List<TorrentSubtitle> = emptyList()
    /** Servidor web do titular baixado pelo OkHttp protegido (null se o torrent não tem web seed). */
    @Volatile var webSeed: WebSeedFetcher? = null
    @Volatile var httpsTrackers: HttpsTrackerBridge? = null

    /** Peças que o player precisa agora: janela à frente da leitura, fim do arquivo (índice do MP4) e legendas. */
    fun wantedPieces(): List<Int> {
        val from = lastWindowStart.takeIf { it >= 0 } ?: math.firstPiece
        val out = ArrayList<Int>()
        out += math.window(from, readAhead)
        if (from == math.firstPiece) { out += math.lastPiece; if (math.lastPiece - 1 > math.firstPiece) out += math.lastPiece - 1 }
        subtitles.forEach { s -> out += s.stream.math.window(s.stream.math.firstPiece, s.stream.math.lastPiece - s.stream.math.firstPiece + 1) }
        return out.distinct()
    }

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
                val st = stats()
                val msg = "Nenhum participante ou servidor enviou o trecho necessário em ${timeoutMs / 1000} s (${statusLine()})."
                // Sem ninguém conectado = sem peers; com conexões mas sem dados = lento demais.
                throw com.brudre.streaming.sources.ClassifiedException(
                    if (st == null || st.peers == 0) com.brudre.streaming.sources.ErrorKind.NO_PEERS else com.brudre.streaming.sources.ErrorKind.TIMEOUT, msg)
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

    data class Stats(val peers: Int, val seeds: Int, val downloadRate: Int, val progress: Float, val webSeeds: Int = 0, val availablePeers: Int = 0)

    fun stats(): Stats? = safe {
        val st = handle.status()
        val ws = webSeed
        Stats(st.numPeers(), st.numSeeds(), st.downloadPayloadRate() + (ws?.bytesPerSecond ?: 0), st.progress(),
            ws?.activeSeeds ?: 0, runCatching { st.listPeers() }.getOrDefault(0))
    }

    /** Bytes que ainda faltam na janela inicial (para estimar o tempo até começar). */
    fun missingBytesFrom(fromPiece: Int, count: Int = readAhead): Long {
        var missing = 0
        for (p in math.window(fromPiece, count)) if (!havePiece(p)) missing++
        return missing.toLong() * info.pieceLength()
    }

    /** Segundos até ter a janela inicial, só com taxa estável (>0 por ao menos 3 medições); senão null. */
    private val rates = ArrayDeque<Int>()
    fun etaSeconds(fromPiece: Int): Int? {
        val r = stats()?.downloadRate ?: return null
        rates.addLast(r); while (rates.size > 5) rates.removeFirst()
        if (rates.size < 3 || rates.any { it <= 0 }) return null
        val avg = rates.average()
        val missing = missingBytesFrom(fromPiece)
        if (missing == 0L) return 0
        return (missing / avg).toInt().coerceAtMost(3600)
    }

    fun statusLine(): String {
        val s = stats() ?: return "sem status"
        return "${s.peers} conexões • ${CacheManager.formatBytes(s.downloadRate.toLong())}/s"
    }

    fun close() {
        closed = true
        webSeed?.stop()
        httpsTrackers?.stop()
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
        const val PIECE_TIMEOUT_MS = 30_000L
    }
}

/** Legenda (.srt/.vtt/.ass) lida do próprio torrent pelo mesmo caminho torrent://. */
data class TorrentSubtitle(val stream: TorrentStream, val language: String, val label: String, val ext: String)
