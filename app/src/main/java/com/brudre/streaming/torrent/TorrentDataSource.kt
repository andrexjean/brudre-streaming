package com.brudre.streaming.torrent

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Interface de leitura entre o motor BitTorrent e o Media3/ExoPlayer.
 * Lê o arquivo parcialmente baixado, aguardando cada peça necessária.
 */
class TorrentDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var stream: TorrentStream? = null
    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var currentPiece = -1
    private var opened = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val s = TorrentStreams.find(dataSpec.uri) ?: throw IOException("Stream torrent não encontrado (${dataSpec.uri})")
        if (dataSpec.position > s.fileSize) throw EOFException("Posição além do fim do arquivo")
        stream = s
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, s.fileSize - position)
        } else {
            s.fileSize - position
        }
        currentPiece = -1
        if (position > 0) s.onSeek(position)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val s = stream ?: throw IOException("Stream não aberto")
        val piece = s.math.pieceAt(position)
        if (piece != currentPiece) {
            s.awaitPiece(piece)
            s.requestWindow(piece + 1)
            currentPiece = piece
        }
        val file = raf ?: openFile(s).also { raf = it }
        val maxInPiece = s.math.pieceEndInFile(piece) - position
        val toRead = minOf(length.toLong(), remaining, maxInPiece).toInt().coerceAtLeast(1)
        var n = -1
        var attempts = 0
        while (n < 0) {
            file.seek(position)
            n = file.read(buffer, offset, toRead)
            if (n < 0) {
                // Peça verificada mas ainda não refletida no tamanho do arquivo: aguarda o disco.
                if (++attempts > 200 || s.closed) throw IOException("Falha ao ler dados do torrent no disco")
                try { Thread.sleep(25) } catch (e: InterruptedException) { throw java.io.InterruptedIOException() }
            }
        }
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    private fun openFile(s: TorrentStream): RandomAccessFile {
        val deadline = System.currentTimeMillis() + 10_000
        while (!s.file.exists()) {
            if (System.currentTimeMillis() > deadline) throw IOException("Arquivo do torrent não foi criado")
            try { Thread.sleep(50) } catch (e: InterruptedException) { throw java.io.InterruptedIOException() }
        }
        return RandomAccessFile(s.file, "r")
    }

    override fun getUri(): Uri? = uri

    @Throws(IOException::class)
    override fun close() {
        uri = null
        try {
            raf?.close()
        } finally {
            raf = null
            stream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = TorrentDataSource()
    }
}

/**
 * Encaminha URIs torrent:// para o [TorrentDataSource] e o resto (HTTP, legendas) para o upstream.
 */
class RoutingDataSource(
    private val torrentFactory: DataSource.Factory,
    private val upstreamFactory: DataSource.Factory,
) : DataSource {

    private val listeners = ArrayList<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme == "torrent") torrentFactory.createDataSource() else upstreamFactory.createDataSource()
        listeners.forEach { ds.addTransferListener(it) }
        current = ds
        return ds.open(dataSpec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: throw IOException("DataSource não aberto")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    @Throws(IOException::class)
    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(
        private val torrentFactory: DataSource.Factory,
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(torrentFactory, upstreamFactory)
    }
}
