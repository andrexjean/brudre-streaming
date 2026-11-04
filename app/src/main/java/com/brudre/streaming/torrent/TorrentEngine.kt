package com.brudre.streaming.torrent

import android.content.Context
import android.net.Uri
import android.util.Log
import com.brudre.streaming.data.AppSettings
import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.data.TorrentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class TorrentException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Motor BitTorrent real (libtorrent via libtorrent4j).
 * Uma sessão leve, iniciada sob demanda e encerrada quando não há streams ativos,
 * para não ocupar memória do Mi TV Stick (1 GB).
 */
class TorrentEngine(
    private val context: Context,
    private val http: OkHttpClient,
    private val settings: AppSettings,
) {
    private val lock = Any()
    @Volatile private var session: SessionManager? = null
    private val active = ConcurrentHashMap<String, TorrentStream>()

    val saveDir: File get() = File(context.cacheDir, "torrent-stream").apply { mkdirs() }

    val isRunning: Boolean get() = session?.isRunning == true
    val activeCount: Int get() = active.size

    private fun ensureSession(): SessionManager = synchronized(lock) {
        session?.takeIf { it.isRunning }?.let { return it }
        val values = settings.current
        val pack = SettingsPack()
            .connectionsLimit(values.torrentConnections)
            .activeDownloads(2)
            .activeSeeds(1)
            .activeLimit(3)
            .uploadRateLimit(128 * 1024)
            .maxPeerlistSize(500)
            .maxQueuedDiskBytes(4 * 1024 * 1024)
        pack.setDhtBootstrapNodes(DHT_BOOTSTRAP)
        val s = SessionManager(false)
        s.start(SessionParams(pack))
        session = s
        Log.i(TAG, "Sessão BitTorrent iniciada (conexões=${values.torrentConnections})")
        s
    }

    /**
     * Abre um torrent autorizado e prepara o arquivo de vídeo para leitura progressiva.
     * Lança [TorrentException] com mensagem amigável em caso de falha.
     */
    suspend fun open(ref: TorrentRef, onStatus: (String) -> Unit = {}): TorrentStream = withContext(Dispatchers.IO) {
        val s = ensureSession()
        onStatus("Obtendo metadados do torrent…")
        val bytes = loadTorrentBytes(s, ref)
        val ti = try {
            TorrentInfo(bytes)
        } catch (e: Throwable) {
            throw TorrentException("Arquivo .torrent inválido", e)
        }
        val files = ti.files()
        val index = pickFile(ti, ref.filePathHint)
            ?: throw TorrentException("O torrent não contém um vídeo compatível")
        val size = files.fileSize(index)

        CacheManager.trimTorrentDir(saveDir, keep = active.values.map { it.file }.toSet(), limitBytes = settings.current.cacheLimitMb * 1024L * 1024L)
        val free = saveDir.usableSpace
        if (free < size + FREE_SPACE_MARGIN) {
            throw TorrentException(
                "Armazenamento insuficiente: o vídeo precisa de ${CacheManager.formatBytes(size)} e há ${CacheManager.formatBytes(free)} livres. Limpe o cache em Configurações."
            )
        }

        val priorities = Priority.array(Priority.IGNORE, ti.numFiles())
        priorities[index] = Priority.TOP_PRIORITY
        coroutineContext.ensureActive()
        onStatus("Conectando aos peers…")
        s.download(ti, saveDir, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)

        var handle: TorrentHandle? = null
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 15_000) {
            coroutineContext.ensureActive()
            val h = s.find(ti.infoHash())
            if (h != null && h.isValid) { handle = h; break }
            delay(100)
        }
        val th = handle ?: throw TorrentException("Não foi possível iniciar o torrent")
        addSources(th, ref)
        th.resume()

        val stream = TorrentStream(
            handle = th,
            info = ti,
            fileIndex = index,
            file = File(saveDir, files.filePath(index)),
            math = PieceMath(files.fileOffset(index), size, ti.pieceLength(), ti.numPieces()),
        )
        stream.prime()
        active[stream.key] = stream
        TorrentStreams.register(stream)
        Log.i(TAG, "Torrent pronto: ${files.fileName(index)} (${CacheManager.formatBytes(size)}), peças=${ti.numPieces()}")
        stream
    }

    /**
     * Garante que o torrent tenha como encontrar dados: trackers e web seeds do magnet oficial
     * (o .torrent publicado pode trazer só trackers WebRTC, inúteis fora do navegador)
     * e trackers públicos de apoio. Web seed = servidor HTTP do próprio titular.
     */
    private fun addSources(th: TorrentHandle, ref: TorrentRef) {
        val magnet = ref.magnet?.let { MagnetLink.parse(it) }
        val trackers = (magnet?.trackers.orEmpty().filter { it.startsWith("udp://") || it.startsWith("http") } + FALLBACK_TRACKERS).distinct()
        trackers.forEach { url -> runCatching { th.addTracker(AnnounceEntry(url)) } }
        magnet?.webSeeds.orEmpty().forEach { url -> runCatching { th.addUrlSeed(url) } }
        runCatching { th.forceReannounce() }
        Log.i(TAG, "Fontes adicionadas: ${trackers.size} trackers, ${magnet?.webSeeds?.size ?: 0} web seeds")
    }

    private suspend fun loadTorrentBytes(s: SessionManager, ref: TorrentRef): ByteArray {
        var lastError: Throwable? = null
        ref.torrentUrl?.let { url ->
            try {
                return downloadTorrentFile(url)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Falha ao baixar .torrent, tentando magnet", e)
            }
        }
        ref.magnet?.let { magnet ->
            coroutineContext.ensureActive()
            val data = s.fetchMagnet(magnet, MAGNET_TIMEOUT_S, saveDir)
            if (data != null && data.isNotEmpty()) return data
            throw TorrentException("Torrent sem peers: metadados não encontrados em ${MAGNET_TIMEOUT_S}s", lastError)
        }
        throw TorrentException("Não foi possível obter o .torrent oficial", lastError)
    }

    private fun downloadTorrentFile(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", CatalogRepository.USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw TorrentException("Servidor do .torrent respondeu ${resp.code}")
            val body = resp.body ?: throw TorrentException("Resposta vazia")
            if (body.contentLength() > MAX_TORRENT_BYTES) throw TorrentException("Arquivo .torrent grande demais")
            val bytes = body.bytes()
            if (bytes.size > MAX_TORRENT_BYTES || bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) throw TorrentException("Conteúdo não é um .torrent")
            return bytes
        }
    }

    /** Fecha o stream e apaga os dados baixados (streaming, não download permanente). */
    fun close(stream: TorrentStream) {
        stream.close()
        active.remove(stream.key)
        TorrentStreams.unregister(stream)
        val s = session ?: return
        try {
            if (stream.handle.isValid) s.remove(stream.handle, SessionHandle.DELETE_FILES)
        } catch (e: Throwable) {
            Log.w(TAG, "Falha ao remover torrent", e)
        }
    }

    /** Encerra a sessão se não houver streams (bloqueia ~1 s: chamar fora da UI thread). */
    fun shutdownIfIdle() {
        synchronized(lock) {
            if (active.isNotEmpty()) return
            val s = session ?: return
            session = null
            try { s.stop() } catch (e: Throwable) { Log.w(TAG, "Falha ao encerrar sessão", e) }
            Log.i(TAG, "Sessão BitTorrent encerrada")
        }
        CacheManager.trimTorrentDir(saveDir, keep = emptySet(), limitBytes = 0)
    }

    companion object {
        private const val TAG = "BruDreTorrent"
        private const val MAGNET_TIMEOUT_S = 30
        private const val MAX_TORRENT_BYTES = 16 * 1024 * 1024
        private const val FREE_SPACE_MARGIN = 64L * 1024 * 1024
        private const val DHT_BOOTSTRAP =
            "dht.libtorrent.org:25401,router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881"
        private val FALLBACK_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
        )
        private val VIDEO_EXT = listOf(".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi")

        fun pickFile(ti: TorrentInfo, hint: String?): Int? {
            val fs = ti.files()
            val n = ti.numFiles()
            if (!hint.isNullOrBlank()) {
                for (i in 0 until n) {
                    val path = fs.filePath(i).replace('\\', '/')
                    if (path.equals(hint, true) || path.endsWith("/$hint", true) || fs.fileName(i).equals(hint, true)) return i
                }
            }
            var best: Int? = null
            var bestSize = -1L
            for (i in 0 until n) {
                val name = fs.fileName(i).lowercase()
                if (VIDEO_EXT.none { name.endsWith(it) }) continue
                val size = fs.fileSize(i)
                if (size > bestSize) { best = i; bestSize = size }
            }
            return best
        }
    }
}

/** Leitura dos parâmetros de um link magnet (BEP 9). */
data class MagnetLink(val infoHash: String?, val trackers: List<String>, val webSeeds: List<String>) {
    companion object {
        fun parse(uri: String): MagnetLink? {
            if (!uri.startsWith("magnet:?")) return null
            val params = uri.removePrefix("magnet:?").split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }
            val hash = params.firstOrNull { it.first == "xt" }?.second?.removePrefix("urn:btih:")?.lowercase()
            return MagnetLink(hash, params.filter { it.first == "tr" }.map { it.second }, params.filter { it.first == "ws" }.map { it.second })
        }
    }
}

/** Registro dos streams abertos, consultado pelo [TorrentDataSource] a partir da URI torrent://. */
object TorrentStreams {
    private val streams = ConcurrentHashMap<String, TorrentStream>()

    fun register(stream: TorrentStream) { streams[stream.key] = stream }
    fun unregister(stream: TorrentStream) { streams.remove(stream.key) }
    fun find(uri: Uri): TorrentStream? = streams[uri.host + uri.path]
}
