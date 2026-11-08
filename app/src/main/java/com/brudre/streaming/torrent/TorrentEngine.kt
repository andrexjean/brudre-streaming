package com.brudre.streaming.torrent

import android.content.Context
import android.net.Uri
import android.util.Log
import com.brudre.streaming.data.AppSettings
import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.data.TorrentCleanup
import com.brudre.streaming.data.TorrentRef
import com.brudre.streaming.sources.ClassifiedException
import com.brudre.streaming.sources.ErrorKind
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

class TorrentException(message: String, cause: Throwable? = null, kind: ErrorKind = ErrorKind.UNKNOWN) : ClassifiedException(kind, message, cause)

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
    /** .torrent importados e metadados de magnets já obtidos (pequenos; evitam buscar de novo na DHT). */
    val metaDir: File get() = File(context.filesDir, "torrents").apply { mkdirs() }

    val isRunning: Boolean get() = session?.isRunning == true
    /** Último erro de web seed (ex.: certificado inválido) — diagnóstico e testes. */
    @Volatile var lastWebSeedError: String? = null
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
            .stopTrackerTimeout(1)
        pack.setDhtBootstrapNodes(DHT_BOOTSTRAP)
        // Validação TLS LIGADA (certificado + hostname) para web seeds e trackers HTTPS.
        // As CAs do sistema Android são entregues ao OpenSSL do libtorrent por [TlsTrust].
        val caReady = TlsTrust.install(context)
        runCatching {
            val field = org.libtorrent4j.swig.settings_pack.bool_types::class.java.getField("validate_https_trackers")
            pack.setBoolean((field.get(null) as org.libtorrent4j.swig.settings_pack.bool_types).swigValue(), true)
        }.onFailure { Log.w(TAG, "validate_https_trackers indisponível nesta versão", it) }
        if (!caReady) Log.e(TAG, "Sem CAs: web seeds HTTPS vão falhar com segurança (não há conexão sem validação)")
        // Categorias de alerta de tracker e DHT (diagnóstico de qual mecanismo achou participantes), somadas às atuais
        runCatching {
            val f = org.libtorrent4j.swig.settings_pack.int_types::class.java.getField("alert_mask")
            val id = (f.get(null) as org.libtorrent4j.swig.settings_pack.int_types).swigValue()
            val get = pack.javaClass.getMethod("getInteger", Int::class.javaPrimitiveType)
            val set = pack.javaClass.getMethod("setInteger", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val cur = get.invoke(pack, id) as Int
            set.invoke(pack, id, cur or ALERT_ERROR or ALERT_TRACKER or ALERT_STATUS or ALERT_DHT)
        }.onFailure { Log.w(TAG, "Máscara de alertas não ajustada: ${it.javaClass.simpleName}") }
        val s = SessionManager(false)
        // Diagnóstico: falhas de web seed (servidor HTTPS do titular) aparecem no log.
        runCatching {
            s.addListener(object : org.libtorrent4j.AlertListener {
                override fun types(): IntArray = intArrayOf(org.libtorrent4j.alerts.AlertType.URL_SEED.swig())
                override fun alert(alert: org.libtorrent4j.alerts.Alert<*>) {
                    lastWebSeedError = com.brudre.streaming.sources.Secrets.redactText(alert.message())
                    Log.w(TAG, "Web seed: $lastWebSeedError")
                }
            })
        }
        s.start(SessionParams(pack))
        session = s
        Log.i(TAG, "Sessão BitTorrent iniciada (conexões=${values.torrentConnections})")
        s
    }

    /**
     * Abre um torrent autorizado e prepara o arquivo de vídeo para leitura progressiva.
     * Lança [TorrentException] com mensagem amigável em caso de falha.
     */
    /** Aberturas em andamento (buscando metadados): a sessão não pode ser desligada nesse intervalo. */
    private val opening = java.util.concurrent.atomic.AtomicInteger(0)

    suspend fun open(ref: TorrentRef, onStatus: (String) -> Unit = {}): TorrentStream {
        opening.incrementAndGet()
        try {
            return openNow(ref, onStatus)
        } finally {
            opening.decrementAndGet()
        }
    }

    private suspend fun openNow(ref: TorrentRef, onStatus: (String) -> Unit): TorrentStream = withContext(Dispatchers.IO) {
        val s = ensureSession()
        onStatus("Obtendo metadados do torrent…")
        val bytes = loadTorrentBytes(s, ref)
        val ti = try {
            TorrentInfo(bytes)
        } catch (e: Throwable) {
            throw TorrentException("Arquivo .torrent inválido", e, ErrorKind.FORMAT)
        }
        val files = ti.files()
        val index = pickFile(ti, ref.fileIndex, ref.filePathHint)
            ?: throw TorrentException("O torrent não contém um vídeo compatível", kind = ErrorKind.NO_VIDEO)
        val size = files.fileSize(index)
        // Legendas do próprio torrent para este vídeo (arquivos pequenos, baixados junto).
        val meta = runCatching { TorrentContent.parse(bytes) }.getOrNull()
        val subtitleFiles = meta?.let { m ->
            val video = m.files.firstOrNull { it.index == index }
            if (video == null) emptyList() else TorrentContent.subtitlesFor(video, m.files.filter { it.isSubtitle && it.size in 1..MAX_SUBTITLE_BYTES })
        }.orEmpty()

        val limit = if (settings.current.torrentCleanup == TorrentCleanup.KEEP_LIMIT) settings.current.cacheLimitMb * 1024L * 1024L else 0L
        CacheManager.trimTorrentDir(saveDir, keep = active.values.map { it.file }.toSet() + File(saveDir, files.filePath(index)), limitBytes = maxOf(limit, settings.current.cacheLimitMb * 1024L * 1024L))
        val free = saveDir.usableSpace
        if (free < size + FREE_SPACE_MARGIN) {
            throw TorrentException(
                "Armazenamento insuficiente: o vídeo precisa de ${CacheManager.formatBytes(size)} e há ${CacheManager.formatBytes(free)} livres. Limpe o cache em Configurações.",
                kind = ErrorKind.STORAGE,
            )
        }

        // Só o vídeo escolhido (e as legendas dele) é baixado: os outros vídeos ficam ignorados.
        val priorities = Priority.array(Priority.IGNORE, ti.numFiles())
        priorities[index] = Priority.TOP_PRIORITY
        subtitleFiles.forEach { priorities[it.index] = Priority.TOP_PRIORITY }
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
        // Torrent já aberto antes (outro episódio): aplica as prioridades deste vídeo.
        runCatching { th.prioritizeFiles(priorities) }
        addSources(th, ref, meta)
        th.resume()

        fun streamFor(i: Int) = TorrentStream(
            handle = th, info = ti, fileIndex = i, file = File(saveDir, files.filePath(i)),
            math = PieceMath(files.fileOffset(i), files.fileSize(i), ti.pieceLength(), ti.numPieces()),
        )
        val subtitleStreams = subtitleFiles.map { f ->
            val sub = streamFor(f.index)
            val lang = TorrentContent.subtitleLanguage(f.name)
            TorrentStreams.register(sub)
            sub.prime()
            TorrentSubtitle(sub, lang, "${TorrentContent.languageLabel(lang)} (do torrent)", f.ext)
        }
        val stream = streamFor(index).also { it.subtitles = subtitleStreams }
        stream.prime()
        webSeedsOf(ref, meta).takeIf { it.isNotEmpty() }?.let { seeds ->
            stream.webSeed = WebSeedFetcher(stream, seeds, http) { err -> lastWebSeedError = err }.also { it.start() }
        }
        active[stream.key] = stream
        TorrentStreams.register(stream)
        Log.i(TAG, "Torrent pronto: ${files.fileName(index)} (${CacheManager.formatBytes(size)}), peças=${ti.numPieces()}, legendas=${subtitleStreams.size}")
        stream
    }

    /** Resultado do diagnóstico de rede: participantes vistos por torrent (−1 = .torrent inválido). */
    /**
     * Resultado do diagnóstico de rede por torrent: participantes vistos e QUAL mecanismo os encontrou
     * (tracker UDP, tracker HTTPS, tracker HTTP, DHT), mais as falhas de tracker (certificado, DNS…) sem segredos.
     */
    data class PeerProbe(
        val name: String, val connected: Int, val known: Int, val swarm: Int,
        val viaUdp: Int = 0, val viaHttps: Int = 0, val viaHttp: Int = 0, val viaDht: Int = 0,
        val trackerReplies: List<String> = emptyList(), val trackerErrors: List<String> = emptyList(), val alertsSeen: Int = 0,
    ) {
        val best get() = maxOf(connected, known, swarm)
        /** "tracker HTTPS (12), DHT (40)" ou "nenhum". */
        val mechanisms: String get() = listOfNotNull(
            "tracker UDP ($viaUdp)".takeIf { viaUdp > 0 }, "tracker HTTPS ($viaHttps)".takeIf { viaHttps > 0 },
            "tracker HTTP ($viaHttp)".takeIf { viaHttp > 0 }, "DHT ($viaDht)".takeIf { viaDht > 0 },
        ).joinToString(", ").ifEmpty { if (alertsSeen == 0) "indeterminado (sem alertas do motor)" else "nenhum" }
    }

    private class ProbeStats {
        @Volatile var udp = 0; @Volatile var https = 0; @Volatile var http = 0; @Volatile var dht = 0; @Volatile var alerts = 0
        val replies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
    }

    /**
     * Diagnóstico da rede BitTorrent (DHT, trackers, participantes): adiciona os torrents SEM baixar nenhum arquivo
     * (todas as prioridades IGNORE), observa por [seconds] e remove tudo (inclusive a pasta de teste).
     * [extraTrackers] = trackers acrescentados a cada torrent (padrão: os públicos de apoio). A validação TLS dos
     * trackers HTTPS continua ligada: um certificado inválido aparece em [PeerProbe.trackerErrors].
     */
    suspend fun probePeers(torrents: List<ByteArray>, seconds: Int, extraTrackers: List<String> = FALLBACK_TRACKERS): List<PeerProbe> = withContext(Dispatchers.IO) {
        opening.incrementAndGet()
        val dir = File(context.cacheDir, "torrent-probe").apply { mkdirs() }
        val handles = ArrayList<TorrentHandle>()
        val infos = torrents.map { b -> runCatching { TorrentInfo(b) }.getOrNull() }
        val stats = infos.map { ProbeStats() }
        val byName = infos.mapIndexedNotNull { i, ti -> ti?.name()?.let { it to i } }.toMap()
        // Alertas de tracker e DHT, lidos por reflexão (nomes estáveis do libtorrent; ausentes = mecanismo "indeterminado")
        val listener = object : org.libtorrent4j.AlertListener {
            override fun types(): IntArray = listOf("TRACKER_REPLY", "TRACKER_ERROR", "TRACKER_WARNING", "DHT_REPLY")
                .mapNotNull { n -> runCatching { org.libtorrent4j.alerts.AlertType.valueOf(n).swig() }.getOrNull() }.toIntArray()
            override fun alert(alert: org.libtorrent4j.alerts.Alert<*>) {
                val name = runCatching { alert.javaClass.getMethod("torrentName").invoke(alert) as? String }.getOrNull()
                val st = name?.let { byName[it] }?.let { stats.getOrNull(it) } ?: return
                st.alerts++
                val msg = com.brudre.streaming.sources.Secrets.redactText(alert.message()) ?: return
                val type = runCatching { alert.type().name }.getOrDefault("")
                val n = Regex("received (?:DHT )?peers: (\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val tracker = Regex("\\(((?:udp|https?)://[^)\\s]+)\\)").find(msg)?.groupValues?.get(1).orEmpty()
                when {
                    type == "DHT_REPLY" -> st.dht += n
                    type == "TRACKER_REPLY" -> {
                        when { tracker.startsWith("udp://") -> st.udp += n; tracker.startsWith("https://") -> st.https += n; else -> st.http += n }
                        if (st.replies.size < 10) st.replies += "${tracker.substringBefore('?')}: $n"
                    }
                    else -> if (st.errors.size < 10) st.errors += msg.take(240)
                }
            }
        }
        val s = ensureSession()
        runCatching { s.addListener(listener) }
        try {
            infos.filterNotNull().forEach { ti ->
                s.download(ti, dir, null, Priority.array(Priority.IGNORE, ti.numFiles()), null, TorrentFlags.UPLOAD_MODE)
            }
            val found = infos.map { ti ->
                ti ?: return@map null
                var h: TorrentHandle? = null
                val t0 = System.currentTimeMillis()
                while (h == null && System.currentTimeMillis() - t0 < 10_000) { h = s.find(ti.infoHash())?.takeIf { it.isValid }; if (h == null) delay(100) }
                h?.also { th -> handles += th; extraTrackers.forEach { runCatching { th.addTracker(AnnounceEntry(it)) } }; runCatching { th.resume(); th.forceReannounce(); th.forceDHTAnnounce() } }
            }
            val best = IntArray(infos.size)
            val conn = IntArray(infos.size)
            val known = IntArray(infos.size)
            val end = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < end) {
                coroutineContext.ensureActive()
                found.forEachIndexed { i, h ->
                    val st = h?.takeIf { it.isValid }?.let { runCatching { it.status() }.getOrNull() } ?: return@forEachIndexed
                    conn[i] = maxOf(conn[i], st.numPeers())
                    known[i] = maxOf(known[i], runCatching { st.listPeers() }.getOrDefault(0))
                    best[i] = maxOf(best[i], runCatching { maxOf(st.numComplete(), 0) + maxOf(st.numIncomplete(), 0) }.getOrDefault(0))
                }
                delay(1_000)
            }
            infos.mapIndexed { i, ti ->
                val p = stats[i]
                if (ti == null) PeerProbe("(inválido)", -1, -1, -1)
                else PeerProbe(ti.name(), conn[i], known[i], best[i], p.udp, p.https, p.http, p.dht, p.replies.toList(), p.errors.toList(), p.alerts)
            }
        } finally {
            runCatching { s.removeListener(listener) }
            session?.let { ss -> handles.forEach { runCatching { ss.remove(it, SessionHandle.DELETE_FILES) } } }
            dir.deleteRecursively()
            opening.decrementAndGet()
        }
    }

    /**
     * Metadados (.torrent) de um magnet, .torrent remoto ou importado, para listar os arquivos antes de tocar.
     * O resultado fica guardado em [metaDir] (pelo hash) e não é buscado de novo.
     */
    suspend fun metadata(ref: TorrentRef, onStatus: (String) -> Unit = {}): ByteArray {
        opening.incrementAndGet()
        try {
            return withContext(Dispatchers.IO) {
                cachedBytes(ref)?.let { return@withContext it }
                onStatus("Obtendo a lista de arquivos do torrent…")
                loadTorrentBytes(ensureSession(), ref)
            }
        } finally {
            opening.decrementAndGet()
        }
    }

    /** Cache de metadados por endereço do .torrent (nome = hash do endereço sem segredos): evita baixar de novo. */
    private fun urlIndex(url: String) = File(metaDir, "url-" + com.brudre.streaming.live.M3uParser.stableId("torrent-url", com.brudre.streaming.sources.Secrets.sanitizeUrl(url) ?: url) + ".hash")

    private fun rememberUrl(url: String, bytes: ByteArray) {
        runCatching { urlIndex(url).writeText(TorrentContent.parse(bytes).infoHash) }
    }

    private fun cachedBytes(ref: TorrentRef): ByteArray? {
        ref.localFile?.let { path ->
            val f = File(path).canonicalFile
            if (!f.path.startsWith(metaDir.canonicalPath + File.separator)) throw TorrentException("Arquivo .torrent fora da pasta do app", kind = ErrorKind.BLOCKED)
            if (!f.exists()) throw TorrentException("O arquivo .torrent importado não existe mais", kind = ErrorKind.STORAGE)
            if (f.length() > TorrentContent.MAX_TORRENT_BYTES) throw TorrentException("Arquivo .torrent grande demais", kind = ErrorKind.FORMAT)
            return f.readBytes()
        }
        val hash = ref.magnet?.let { TorrentContent.magnetHash(it) }
            ?: ref.torrentUrl?.let { u -> runCatching { urlIndex(u).takeIf { it.exists() }?.readText()?.trim() }.getOrNull()?.takeIf { Regex("[a-f0-9]{40}").matches(it) } }
            ?: return null
        val f = File(metaDir, "$hash.torrent")
        return if (f.exists() && f.length() in 1..TorrentContent.MAX_TORRENT_BYTES.toLong()) f.readBytes() else null
    }

    private fun remember(bytes: ByteArray) {
        runCatching {
            val meta = TorrentContent.parse(bytes)
            val f = File(metaDir, "${meta.infoHash}.torrent")
            if (!f.exists()) f.writeBytes(bytes)
        }
    }

    /**
     * Garante que o torrent tenha como encontrar dados: trackers e web seeds do magnet oficial
     * (o .torrent publicado pode trazer só trackers WebRTC, inúteis fora do navegador)
     * e trackers públicos de apoio. Web seed = servidor HTTP do próprio titular.
     */
    private fun addSources(th: TorrentHandle, ref: TorrentRef, meta: TorrentMeta?) {
        val magnet = ref.magnet?.let { MagnetLink.parse(it) }
        val trackers = (magnet?.trackers.orEmpty().filter { it.startsWith("udp://") || it.startsWith("http") } + FALLBACK_TRACKERS).distinct()
        trackers.forEach { url -> runCatching { th.addTracker(AnnounceEntry(url)) } }
        // Web seeds: só HTTPS e só destinos permitidos pelo UrlGuard.
        // Web seeds HTTPS NÃO passam pelo libtorrent (o HTTPS interno dele não conseguiu validar certificados no Android):
        // ficam com o [WebSeedFetcher], que usa o OkHttp protegido. Os do .torrent são retirados do motor.
        runCatching { th.urlSeeds().forEach { th.removeUrlSeed(it) } }
        runCatching { th.forceReannounce() }
        Log.i(TAG, "Fontes adicionadas: ${trackers.size} trackers; web seeds pelo OkHttp: ${webSeedsOf(ref, meta).size}")
    }

    /** Servidores web do titular (magnet ws= e url-list do .torrent): só https e permitidos pelo UrlGuard. */
    private fun webSeedsOf(ref: TorrentRef, meta: TorrentMeta?): List<String> =
        (ref.magnet?.let { MagnetLink.parse(it) }?.webSeeds.orEmpty() + meta?.webSeeds.orEmpty()).distinct()
            .filter { it.startsWith("https://") && com.brudre.streaming.sources.UrlGuard.isAllowed(it) }

    private suspend fun loadTorrentBytes(s: SessionManager, ref: TorrentRef): ByteArray {
        cachedBytes(ref)?.let { return it }
        var lastError: Throwable? = null
        ref.torrentUrl?.let { url ->
            try {
                // Falhas transitórias (tempo esgotado, 429/503 com Retry-After) têm novas tentativas com prazo máximo
                val bytes = com.brudre.streaming.sources.SourceHttp.retrying(com.brudre.streaming.sources.SourceHttp.deadlineMs(30)) { downloadTorrentFile(url) }
                return bytes.also { remember(it); rememberUrl(url, it) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
                Log.w(TAG, "Falha ao baixar .torrent, tentando magnet: ${com.brudre.streaming.sources.ErrorClassifier.classify(e).summary}")
            }
        }
        ref.magnet?.let { magnet ->
            coroutineContext.ensureActive()
            if (!com.brudre.streaming.sources.FeedParser.isValidMagnet(magnet)) throw TorrentException("Magnet sem hash de torrent válido", kind = ErrorKind.MAGNET)
            // "xs" (fonte exata): o .torrent publicado em HTTPS, aceito só se o hash bater com o do magnet.
            val expected = TorrentContent.magnetHash(magnet)
            MagnetLink.parse(magnet)?.exactSources.orEmpty().take(2).forEach { xs ->
                runCatching { downloadTorrentFile(xs) }.getOrNull()?.let { bytes ->
                    if (runCatching { TorrentContent.parse(bytes).infoHash }.getOrNull() == expected) return bytes.also { remember(it) }
                }
            }
            val data = s.fetchMagnet(magnet, MAGNET_TIMEOUT_S, saveDir)
            if (data != null && data.isNotEmpty()) return data.also { remember(it) }
            throw TorrentException("Nenhum participante enviou os metadados em ${MAGNET_TIMEOUT_S}s (torrent sem peers no momento).", lastError, ErrorKind.NO_PEERS)
        }
        lastError?.let { throw it }
        throw TorrentException("Não foi possível obter o .torrent", lastError)
    }

    private fun downloadTorrentFile(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", CatalogRepository.USER_AGENT).build()
        com.brudre.streaming.sources.UrlGuard.check(url)?.let { throw com.brudre.streaming.sources.BlockedUrlException(it) }
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val wait = com.brudre.streaming.sources.SourceHttp.parseRetryAfter(resp.header("Retry-After"))
                if ((resp.code == 429 || resp.code == 503) && wait != null) throw com.brudre.streaming.sources.RetryAfterException(resp.code, wait)
                throw com.brudre.streaming.sources.HttpStatusException(resp.code, "Servidor do .torrent respondeu ${resp.code}")
            }
            val body = resp.body ?: throw TorrentException("Resposta vazia")
            if (body.contentLength() > MAX_TORRENT_BYTES) throw TorrentException("Arquivo .torrent grande demais", kind = ErrorKind.FORMAT)
            val bytes = body.bytes()
            if (bytes.size > MAX_TORRENT_BYTES || bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) throw TorrentException("Conteúdo não é um .torrent", kind = ErrorKind.FORMAT)
            return bytes
        }
    }

    /** Fecha o stream e apaga os dados baixados (streaming, não download permanente). */
    fun close(stream: TorrentStream) {
        stream.close()
        stream.subtitles.forEach { it.stream.close(); TorrentStreams.unregister(it.stream) }
        active.remove(stream.key)
        TorrentStreams.unregister(stream)
        val s = session ?: return
        try {
            val keep = settings.current.torrentCleanup == TorrentCleanup.KEEP_LIMIT
            if (stream.handle.isValid) {
                if (keep) s.remove(stream.handle) else s.remove(stream.handle, SessionHandle.DELETE_FILES)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Falha ao remover torrent", e)
        }
    }

    /** Encerra a sessão se não houver streams (bloqueia ~1 s: chamar fora da UI thread). */
    fun shutdownIfIdle() {
        // A sessão é desligada FORA do lock: stop() pode demorar (trackers/DHT) e não pode
        // bloquear a abertura de um novo vídeo.
        val toStop: SessionManager = synchronized(lock) {
            // Outro vídeo pode estar abrindo (metadados do magnet): não derruba a sessão dele.
            if (active.isNotEmpty() || opening.get() > 0) return
            val s = session ?: return
            session = null
            s
        }
        try { toStop.stop() } catch (e: Throwable) { Log.w(TAG, "Falha ao encerrar sessão", e) }
        Log.i(TAG, "Sessão BitTorrent encerrada")
        if (active.isEmpty()) {
            val keep = settings.current.torrentCleanup == TorrentCleanup.KEEP_LIMIT
            CacheManager.trimTorrentDir(saveDir, keep = emptySet(), limitBytes = if (keep) settings.current.cacheLimitMb * 1024L * 1024L else 0)
        }
    }

    companion object {
        private const val TAG = "BruDreTorrent"
        private const val MAGNET_TIMEOUT_S = 30
        private const val MAX_TORRENT_BYTES = 16 * 1024 * 1024
        private const val FREE_SPACE_MARGIN = 64L * 1024 * 1024
        private const val DHT_BOOTSTRAP =
            "dht.libtorrent.org:25401,router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881"
        // Bits de alert_category do libtorrent 2.0
        private const val ALERT_ERROR = 1
        private const val ALERT_TRACKER = 16
        private const val ALERT_STATUS = 64
        private const val ALERT_DHT = 1024
        val FALLBACK_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
        )
        private val VIDEO_EXT = listOf(".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi", ".ts", ".m2ts")
        private const val MAX_SUBTITLE_BYTES = 2L * 1024 * 1024

        /** Arquivo escolhido pelo usuário ([index]); senão a dica do catálogo; senão o único/maior vídeo. */
        fun pickFile(ti: TorrentInfo, index: Int?, hint: String?): Int? {
            val fs = ti.files()
            val n = ti.numFiles()
            if (index != null && index in 0 until n && VIDEO_EXT.any { fs.fileName(index).lowercase().endsWith(it) }) return index
            // Dica de episódio ("S01E02", vinda de indexadores): o arquivo desse episódio no pacote da temporada.
            Regex("^S(\\d{1,2})E(\\d{1,3})$", RegexOption.IGNORE_CASE).matchEntire(hint ?: "")?.let { m ->
                val want = EpisodeId(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                for (i in 0 until n) {
                    if (VIDEO_EXT.none { fs.fileName(i).lowercase().endsWith(it) }) continue
                    if (TorrentContent.episodeOf(fs.filePath(i).replace('\\', '/')) == want) return i
                }
                // Só um vídeo no torrent: é o episódio anunciado. Vários e nenhum é o pedido: não toca outro episódio.
                val videos = (0 until n).filter { i -> VIDEO_EXT.any { fs.fileName(i).lowercase().endsWith(it) } }
                return videos.singleOrNull()
            }
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
data class MagnetLink(val infoHash: String?, val trackers: List<String>, val webSeeds: List<String>, val exactSources: List<String> = emptyList()) {
    companion object {
        fun parse(uri: String): MagnetLink? {
            if (!uri.startsWith("magnet:?")) return null
            val params = uri.removePrefix("magnet:?").split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }
            val hash = params.firstOrNull { it.first == "xt" }?.second?.removePrefix("urn:btih:")?.lowercase()
            return MagnetLink(
                hash, params.filter { it.first == "tr" }.map { it.second }, params.filter { it.first == "ws" }.map { it.second },
                params.filter { it.first == "xs" || it.first == "as" }.map { it.second }.filter { it.startsWith("https://") },
            )
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
