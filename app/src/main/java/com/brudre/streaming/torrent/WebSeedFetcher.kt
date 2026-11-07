package com.brudre.streaming.torrent

import android.util.Log
import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.sources.ErrorClassifier
import com.brudre.streaming.sources.HttpStatusException
import com.brudre.streaming.sources.UrlGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.FileStorage
import org.libtorrent4j.TorrentInfo
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Servidor web do titular (web seed, BEP 19) baixado pelo OkHttp PROTEGIDO do app, em vez do HTTPS interno do
 * libtorrent: certificado e hostname validados pelo Android, cada redirecionamento conferido pelo GuardInterceptor
 * (sem http://, sem destino local, sem executável). Os bytes entram no libtorrent por add_piece, que confere o hash
 * SHA-1 da peça antes de aceitar — dado adulterado é descartado.
 *
 * Só baixa as peças que o player está pedindo (janela à frente e o fim do arquivo), no máximo [parallel] por vez.
 */
class WebSeedFetcher(
    private val stream: TorrentStream,
    seeds: List<String>,
    http: OkHttpClient,
    private val onError: (String) -> Unit,
) {
    private val client = http.newBuilder().retryOnConnectionFailure(true).build()
    private val ti: TorrentInfo = stream.info
    private val fs: FileStorage = ti.files()
    private val multiFile = ti.numFiles() > 1 || fs.filePath(0).contains('/')
    private val seeds = seeds.filter { it.startsWith("https://") && UrlGuard.isAllowed(it) }.distinct().toMutableList()
    private val inFlight = ConcurrentHashMap.newKeySet<Int>()
    private val failedPieces = ConcurrentHashMap<Int, Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parallel = if (ti.pieceLength() > 8 * 1024 * 1024) 1 else 2
    private var job: Job? = null

    @Volatile var bytesPerSecond: Int = 0
        private set
    val activeSeeds: Int get() = seeds.size
    @Volatile var lastError: String? = null
        private set

    fun start() {
        if (seeds.isEmpty()) return
        job = scope.launch {
            var windowBytes = 0L
            var windowStart = System.currentTimeMillis()
            while (isActive && !stream.closed && seeds.isNotEmpty()) {
                val wanted = stream.wantedPieces().filter { !stream.havePiece(it) && it !in inFlight && (failedPieces[it] ?: 0) < 3 }
                for (p in wanted.take(parallel - inFlight.size)) {
                    inFlight += p
                    launch {
                        try {
                            val data = fetchPiece(p)
                            stream.handle.addPiece(p, data)
                            synchronized(this@WebSeedFetcher) { windowBytes += data.size }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failedPieces[p] = (failedPieces[p] ?: 0) + 1
                            val f = ErrorClassifier.classify(e)
                            lastError = f.summary
                            onError(f.summary)
                            Log.w(TAG, "Web seed falhou na peça $p: ${f.technical}")
                            // Erro de segurança/certificado ou servidor que não tem o arquivo: abandona esse servidor.
                            if (f.kind in FATAL) synchronized(seeds) { if (seeds.isNotEmpty()) seeds.removeAt(0) }
                        } finally {
                            inFlight -= p
                        }
                    }
                }
                delay(150)
                val now = System.currentTimeMillis()
                if (now - windowStart >= 2_000) {
                    synchronized(this@WebSeedFetcher) { bytesPerSecond = (windowBytes * 1000 / (now - windowStart)).toInt(); windowBytes = 0 }
                    windowStart = now
                }
            }
            bytesPerSecond = 0
        }
    }

    fun stop() { job?.cancel(); scope.coroutineContext[Job]?.cancel() }

    /** Lê a peça [p] dos arquivos do servidor (uma peça pode atravessar vários arquivos). */
    private fun fetchPiece(p: Int): ByteArray {
        val seed = synchronized(seeds) { seeds.firstOrNull() } ?: throw IllegalStateException("sem servidor web")
        val pieceStart = p.toLong() * ti.pieceLength()
        val pieceSize = ti.pieceSize(p).toLong()
        val out = ByteArrayOutputStream(pieceSize.toInt())
        var pos = pieceStart
        val end = pieceStart + pieceSize
        for (i in 0 until fs.numFiles()) {
            val fo = fs.fileOffset(i)
            val fsz = fs.fileSize(i)
            if (fsz == 0L || fo + fsz <= pos) continue
            if (fo >= end) break
            val from = pos - fo
            val len = minOf(end, fo + fsz) - pos
            if (fs.fileFlags(i).and_(FileStorage.FLAG_PAD_FILE).non_zero()) {
                out.write(ByteArray(len.toInt()))
            } else {
                out.write(range(urlFor(seed, i), from, len))
            }
            pos += len
            if (pos >= end) break
        }
        if (out.size().toLong() != pieceSize) throw java.io.IOException("peça $p incompleta (${out.size()} de $pieceSize bytes)")
        return out.toByteArray()
    }

    /** BEP 19: torrent de vários arquivos = seed + nome/caminho (cada parte codificada); arquivo único = seed (+ nome se terminar em /). */
    private fun urlFor(seed: String, file: Int): String {
        val path = fs.filePath(file).replace('\\', '/')
        return if (multiFile) {
            (if (seed.endsWith("/")) seed else "$seed/") + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        } else if (seed.endsWith("/")) seed + URLEncoder.encode(path, "UTF-8").replace("+", "%20") else seed
    }

    private fun range(url: String, from: Long, len: Long): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", CatalogRepository.USER_AGENT).header("Range", "bytes=$from-${from + len - 1}").build()
        client.newCall(req).execute().use { r ->
            if (r.code != 206 && !(r.code == 200 && from == 0L)) throw HttpStatusException(r.code, "servidor web respondeu ${r.code}")
            val src = r.body?.source() ?: throw java.io.IOException("resposta vazia")
            if (!src.request(len)) throw java.io.IOException("servidor web enviou menos dados que o pedido")
            return src.buffer.readByteArray(len)
        }
    }

    companion object {
        private const val TAG = "BruDreWebSeed"
        private val FATAL = setOf(
            com.brudre.streaming.sources.ErrorKind.TLS, com.brudre.streaming.sources.ErrorKind.BLOCKED,
            com.brudre.streaming.sources.ErrorKind.DNS, com.brudre.streaming.sources.ErrorKind.HTTP,
        )
    }
}
