package com.brudre.streaming.player

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.brudre.streaming.BruDreApp
import com.brudre.streaming.Services
import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.Title
import com.brudre.streaming.torrent.RoutingDataSource
import com.brudre.streaming.torrent.TorrentDataSource
import com.brudre.streaming.torrent.TorrentStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Player em tela cheia (Media3/ExoPlayer) com streaming direto ou BitTorrent,
 * troca automática de fonte, retomada de posição e controle total pelo controle remoto.
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var services: Services
    private lateinit var title: Title
    private lateinit var playable: Playable

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private lateinit var headerView: TextView

    private var attempts: List<SourceAttempt> = emptyList()
    private var attemptIndex = 0
    private var currentAttempt: SourceAttempt? = null
    private var torrentStream: TorrentStream? = null
    private var openJob: Job? = null
    private var watchdogJob: Job? = null
    private var tickerJob: Job? = null
    private var stallJob: Job? = null
    private var resumeMs = 0L
    private var hasBeenReady = false
    private var errorDialog: AlertDialog? = null

    /** Último estado observado, para testes instrumentados. */
    @Volatile var lastErrorMessage: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        services = BruDreApp.services(this)
        val t = intent.getStringExtra(EXTRA_TITLE)?.let { runCatching { Title.fromJson(JSONObject(it)) }.getOrNull() }
        val p = intent.getStringExtra(EXTRA_PLAYABLE)?.let { runCatching { Playable.fromJson(JSONObject(it)) }.getOrNull() }
        if (t == null || p == null) {
            Toast.makeText(this, "Nada para reproduzir", Toast.LENGTH_LONG).show()
            finish(); return
        }
        title = t
        playable = p
        resumeMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildViews()
        attempts = PlaybackPlan.attempts(playable, services.settings.current.sourceMode)
        if (attempts.isEmpty()) {
            showFatal("Este título não possui uma fonte autorizada para reprodução.")
            return
        }
        createPlayer()
        startAttempt(0)
    }

    // ------------------------------------------------------------------ views

    private fun buildViews() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            useController = true
            controllerShowTimeoutMs = 4000
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            keepScreenOn = true
            isFocusable = true
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                headerView.visibility = visibility
            })
        }
        headerView = TextView(this).apply {
            text = if (playable.label.isNotBlank() && playable.label != title.name) "${title.name} — ${playable.label}" else title.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(48, 32, 48, 0) }
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(24, 12, 24, 12)
            background = GradientDrawable().apply { cornerRadius = 24f; setColor(Color.argb(170, 8, 16, 40)); setStroke(2, Color.argb(200, 55, 216, 255)) }
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 32, 48, 0) }
            visibility = View.GONE
        }
        root.addView(playerView)
        root.addView(headerView)
        root.addView(statusView)
        setContentView(root)
        playerView.requestFocus()
    }

    private fun showStatus(text: String?) {
        if (text.isNullOrBlank()) statusView.visibility = View.GONE
        else { statusView.text = text; statusView.visibility = View.VISIBLE }
    }

    // ----------------------------------------------------------------- player

    private fun createPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(CatalogRepository.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val upstream = DefaultDataSource.Factory(this, http)
        val routing = RoutingDataSource.Factory(TorrentDataSource.Factory(), upstream)

        // Buffers reduzidos: o Mi Stick tem 1 GB de RAM.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 2_000, 4_000)
            .setTargetBufferBytes(24 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)

        val settings = services.settings.current
        val p = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(routing))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        val params = p.trackSelectionParameters.buildUpon().setMaxVideoSize(1920, 1080)
        if (settings.audioLanguage.isNotBlank()) params.setPreferredAudioLanguage(settings.audioLanguage)
        if (settings.subtitleLanguage != "off") params.setPreferredTextLanguage(settings.subtitleLanguage)
        p.trackSelectionParameters = params.build()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    hasBeenReady = true
                    watchdogJob?.cancel()
                    stallJob?.cancel()
                    if (currentAttempt !is SourceAttempt.Torrent) showStatus(null)
                }
                if (state == Player.STATE_BUFFERING && hasBeenReady) {
                    // Travou no meio: se não voltar em pouco tempo, troca de fonte.
                    stallJob?.cancel()
                    stallJob = lifecycleScope.launch {
                        delay(if (currentAttempt is SourceAttempt.Torrent) TORRENT_STALL_MS else DIRECT_STALL_MS)
                        if (player?.playbackState == Player.STATE_BUFFERING) {
                            failCurrent("A fonte parou de enviar dados.", true)
                        }
                    }
                }
                if (state == Player.STATE_ENDED) {
                    saveProgress(completed = true)
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val info = PlayerErrors.describe(error)
                Log.w(TAG, "Erro de reprodução (${currentAttempt?.label}): ${error.errorCodeName}", error)
                failCurrent(info.message, info.retryOtherSource)
            }
        })
        playerView.player = p
        player = p
    }

    private fun startAttempt(index: Int) {
        val p = player ?: return
        attemptIndex = index
        val attempt = attempts.getOrNull(index) ?: return
        currentAttempt = attempt
        openJob?.cancel()
        watchdogJob?.cancel()
        stallJob?.cancel()
        releaseTorrent()
        lastErrorMessage = null

        when (attempt) {
            is SourceAttempt.Direct -> {
                showStatus(if (attempts.size > 1) "Fonte: streaming direto" else null)
                prepare(p, Uri.parse(attempt.url), guessMime(attempt.url))
                startWatchdog(45_000)
            }
            is SourceAttempt.Torrent -> {
                showStatus("BitTorrent: preparando…")
                // Prazo total (metadados + primeiros dados) contado desde já: se o motor travar, troca de fonte.
                startWatchdog(TORRENT_START_TIMEOUT_MS)
                openJob = lifecycleScope.launch {
                    try {
                        val stream = services.torrent.open(attempt.ref) { msg -> runOnUiThread { showStatus("BitTorrent: $msg") } }
                        if (!isActive || isFinishing) { services.torrent.close(stream); return@launch }
                        torrentStream = stream
                        prepare(p, stream.uri, guessMime(stream.file.name))
                        startTicker()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha no torrent", e)
                        failCurrent(e.message ?: "Falha no BitTorrent", true)
                    }
                }
            }
        }
    }

    private fun prepare(p: ExoPlayer, uri: Uri, mime: String?) {
        val subtitlePref = services.settings.current.subtitleLanguage
        val subs = playable.subtitles.map { s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(if (s.url.endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(s.language)
                .setLabel(s.label)
                .setSelectionFlags(if (s.language == subtitlePref) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(uri)
            .apply { if (mime != null) setMimeType(mime) }
            .setSubtitleConfigurations(subs)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title.name).setSubtitle(playable.label).build())
            .build()
        val start = if (hasBeenReady) p.currentPosition else resumeMs
        p.setMediaItem(item, start.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
    }

    private fun guessMime(name: String): String? {
        val n = name.lowercase().substringBefore('?')
        return when {
            n.endsWith(".mp4") || n.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            n.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            n.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            else -> null
        }
    }

    private fun startWatchdog(timeoutMs: Long) {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            delay(timeoutMs)
            val p = player ?: return@launch
            if (p.playbackState != Player.STATE_READY && !p.isPlaying) {
                failCurrent("A fonte não respondeu a tempo.", true)
            }
        }
    }

    private fun failCurrent(message: String, retryOtherSource: Boolean) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            openJob?.cancel()
            lastErrorMessage = message
            val keep = player?.currentPosition ?: 0L
            if (hasBeenReady) resumeMs = keep
            val next = attemptIndex + 1
            if (retryOtherSource && next < attempts.size) {
                Toast.makeText(this, "$message Tentando ${attempts[next].label}…", Toast.LENGTH_LONG).show()
                player?.stop()
                startAttempt(next)
            } else {
                player?.stop()
                showFatal(message)
            }
        }
    }

    private fun showFatal(message: String) {
        lastErrorMessage = message
        showStatus(null)
        errorDialog?.dismiss()
        errorDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Não foi possível reproduzir")
            .setMessage(message)
            .setCancelable(true)
            .setOnCancelListener { finish() }
            .setNegativeButton("Voltar") { _, _ -> finish() }
            .apply {
                if (attempts.isNotEmpty()) setPositiveButton("Tentar novamente") { _, _ ->
                    hasBeenReady = false
                    if (player == null) createPlayer()
                    startAttempt(0)
                }
            }
            .show()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            while (isActive) {
                val s = torrentStream
                if (s != null) {
                    val stats = s.stats()
                    val buffering = player?.playbackState == Player.STATE_BUFFERING
                    if (stats != null) {
                        showStatus(
                            "BitTorrent • ${stats.peers} conexões • " +
                                "${com.brudre.streaming.torrent.CacheManager.formatBytes(stats.downloadRate.toLong())}/s" +
                                (if (buffering) " • carregando" else "")
                        )
                    }
                    if (!buffering && playerView.isControllerFullyVisible.not() && hasBeenReady) {
                        // Mantém o indicador discreto durante a reprodução.
                        statusView.alpha = 0.55f
                    } else statusView.alpha = 1f
                }
                delay(1000)
            }
        }
    }

    // ----------------------------------------------------------- progresso

    private var lastSaved = 0L

    private fun saveProgress(completed: Boolean = false) {
        val p = player ?: return
        if (!hasBeenReady) return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: ((playable.durationSec ?: 0) * 1000L)
        val position = if (completed) duration else p.currentPosition
        val lib = services.library
        services.appScope.launch(Dispatchers.IO) {
            lib.savePosition(title.id, playable.key, title.name, playable.label, title.kind, title.posterUrl, position, duration)
        }
        lastSaved = System.currentTimeMillis()
    }

    private val progressSaver: Job by lazy {
        lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                if (player?.isPlaying == true) saveProgress()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::playable.isInitialized) progressSaver.start()
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) releaseAll()
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    private fun releaseAll() {
        errorDialog?.dismiss()
        errorDialog = null
        openJob?.cancel()
        watchdogJob?.cancel()
        stallJob?.cancel()
        tickerJob?.cancel()
        player?.release()
        player = null
        releaseTorrent()
        val engine = services.torrent
        // Mantém a sessão um pouco (o usuário pode abrir outro título em seguida) e depois libera a memória.
        services.appScope.launch(Dispatchers.IO) {
            delay(SESSION_IDLE_MS)
            engine.shutdownIfIdle()
        }
    }

    private fun releaseTorrent() {
        val s = torrentStream ?: return
        torrentStream = null
        tickerJob?.cancel()
        s.close()
        val engine = services.torrent
        services.appScope.launch(Dispatchers.IO) { engine.close(s) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) services.releaseMemory()
    }

    // --------------------------------------------------------- controle remoto

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN && errorDialog?.isShowing != true) {
            val controllerVisible = playerView.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    if (p.isPlaying) p.pause() else p.play()
                    playerView.showController(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); playerView.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { p.seekForward(); playerView.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { p.seekBack(); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controllerVisible) { p.seekTo(p.currentPosition + 10_000); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!controllerVisible) { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controllerVisible) {
                    if (p.isPlaying) p.pause() else p.play()
                    playerView.showController(); return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (!controllerVisible) { playerView.showController(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- testes

    /** Acesso para testes instrumentados. */
    fun playerForTest(): ExoPlayer? = player
    fun currentAttemptForTest(): SourceAttempt? = currentAttempt

    companion object {
        private const val TAG = "BruDrePlayer"
        private const val TORRENT_START_TIMEOUT_MS = 90_000L
        private const val TORRENT_STALL_MS = 45_000L
        private const val SESSION_IDLE_MS = 20_000L
        private const val DIRECT_STALL_MS = 60_000L
        const val EXTRA_TITLE = "title"
        const val EXTRA_PLAYABLE = "playable"
        const val EXTRA_START_MS = "start"

        fun intent(context: Context, title: Title, playable: Playable, startMs: Long): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_TITLE, title.toJson().toString())
                .putExtra(EXTRA_PLAYABLE, playable.toJson().toString())
                .putExtra(EXTRA_START_MS, startMs)
    }
}
