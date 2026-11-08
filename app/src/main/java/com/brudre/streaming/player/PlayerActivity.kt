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
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
    private var reconnects = 0
    private var reconnectJob: Job? = null
    private val recorded = HashSet<String>()
    /** Trocas e novas tentativas desta sessão do player (limite evita ciclos). */
    private var switches = 0
    private var sameSourceRetries = 0
    private val maxSwitches: Int get() = attempts.size + SAME_SOURCE_RETRIES * 2
    private var keepAudio: String? = null
    private var keepText: String? = null
    /** true enquanto o próprio app ajusta as faixas (não é escolha do usuário). */
    private var applyingTracks = false
    private var announcedTracks = false
    private val isLive: Boolean get() = ::playable.isInitialized && playable.live

    /** Duração da primeira fonte que tocou: a alternativa precisa bater (mesmo filme/episódio, mesma versão). */
    private var referenceDurationMs = C.TIME_UNSET
    private var durationAttempt = -1
    private var durationDialog: AlertDialog? = null
    @Volatile var durationMismatch: String? = null
        private set

    /** Medições para o relatório de homologação (tempo até o vídeo, travamentos, memória). */
    private val openedAt = android.os.SystemClock.elapsedRealtime()
    private var firstReadyAt = 0L
    private var bufferingSince = 0L
    private var rebuffers = 0
    private var rebufferMs = 0L
    private var fatalShown = false
    private var pssPeakKb = 0
    private var homologRecorded = false
    private var attemptStartedAt = android.os.SystemClock.elapsedRealtime()

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
        attempts = PlaybackPlan.attempts(playable, if (playable.live) com.brudre.streaming.data.SourceMode.DIRECT else services.settings.current.sourceMode, services.sources.torrentAllowed)
        if (attempts.isEmpty()) {
            showFatal(if (playable.torrent != null && !services.sources.torrentAllowed) "Este título só tem fonte BitTorrent, que está desativada em Configurações > Fontes de conteúdo."
                else "Este título não possui uma fonte autorizada para reprodução.")
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
            text = when {
                playable.live -> "\u25CF AO VIVO  ${title.name}"
                playable.label.isNotBlank() && playable.label != title.name -> "${title.name} — ${playable.label}"
                else -> title.name
            }
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
        // Cliente protegido: cada redirecionamento é validado e respostas executáveis são recusadas.
        val http = OkHttpDataSource.Factory(services.guardedHttp)
            .setUserAgent(playable.headers["User-Agent"] ?: CatalogRepository.USER_AGENT)
            .setDefaultRequestProperties(playable.headers.filterKeys { it != "User-Agent" })
        val upstream = DefaultDataSource.Factory(this, http)
        val routing = RoutingDataSource.Factory(TorrentDataSource.Factory(), upstream)

        // Buffers reduzidos: o Mi Stick tem 1 GB de RAM.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(if (playable.live) 8_000 else 15_000, if (playable.live) 20_000 else 40_000, 2_000, 4_000)
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
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    if (firstReadyAt == 0L) {
                        firstReadyAt = nowMs
                        lifecycleScope.launch(Dispatchers.IO) { pssPeakKb = maxOf(pssPeakKb, com.brudre.streaming.data.Homologation.pssKb()) }
                    }
                    if (bufferingSince > 0) { rebufferMs += nowMs - bufferingSince; bufferingSince = 0 }
                    hasBeenReady = true
                    reconnects = 0
                    checkDuration()
                    watchdogJob?.cancel()
                    stallJob?.cancel()
                    if (currentAttempt !is SourceAttempt.Torrent) showStatus(null)
                    if (isLive) services.live.markOnline(title.id.removePrefix(com.brudre.streaming.data.Title.LIVE_PREFIX))
                    // Tocou de verdade: prova da etapa "Reprodução validada" da fonte cadastrada
                    currentAttempt?.sourceId?.let { id -> if (recorded.add(id)) { val ms = (nowMs - attemptStartedAt).toInt().takeIf { it > 0 }; services.appScope.launch(Dispatchers.IO) { services.sourceStore.recordPlayback(id, ms) } } }
                    com.brudre.streaming.torrent.TorrentLibrary.hashOf(title.id)?.let { h -> services.appScope.launch(Dispatchers.IO) { services.torrentLibrary.markPlayed(h) } }
                }
                if (state == Player.STATE_BUFFERING && hasBeenReady) {
                    if (bufferingSince == 0L) { bufferingSince = android.os.SystemClock.elapsedRealtime(); rebuffers++ }
                    // Travou no meio: se não voltar em pouco tempo, troca de fonte.
                    stallJob?.cancel()
                    stallJob = lifecycleScope.launch {
                        delay(if (isLive) LIVE_STALL_MS else if (currentAttempt is SourceAttempt.Torrent) TORRENT_STALL_MS else DIRECT_STALL_MS)
                        if (player?.playbackState == Player.STATE_BUFFERING) {
                            failCurrent("A fonte parou de enviar dados.", true)
                        }
                    }
                }
                if (state == Player.STATE_ENDED) {
                    // Transmissão ao vivo não "termina": se o servidor encerrar, reconecta.
                    if (isLive) { failCurrent("A transmissão foi interrompida.", true); return }
                    saveProgress(completed = true)
                    finish()
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (isLive) return
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).language } }
                val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).language } }
                if (audio.isEmpty()) return
                lastTrackLanguages = audio to text
                com.brudre.streaming.data.VerifiedLanguages.record(title.id, audio, text)
                if (!announcedTracks && (audio.size > 1 || text.isNotEmpty())) {
                    announcedTracks = true
                    fun names(l: List<String?>) = l.map { com.brudre.streaming.data.VerifiedLanguages.normalize(it)?.let { c -> com.brudre.streaming.data.Languages.name(c) } ?: "idioma desconhecido" }.distinct().joinToString(", ")
                    val msg = "Áudio: ${names(audio)}" + if (text.isNotEmpty()) " • Legendas: ${names(text)}" else ""
                    Toast.makeText(this@PlayerActivity, "$msg (troque em ⚙ / CC)", Toast.LENGTH_LONG).show()
                }
            }

            override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
                if (applyingTracks || isLive) return
                // Escolha manual no menu do player → vira a preferência salva (vale para os próximos vídeos).
                val audioOverride = parameters.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
                val textOverride = parameters.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
                val audioLang = audioOverride?.trackIndices?.firstOrNull()?.let { audioOverride.mediaTrackGroup.getFormat(it).language }
                val textLang = textOverride?.trackIndices?.firstOrNull()?.let { textOverride.mediaTrackGroup.getFormat(it).language }
                val textOff = parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                val a = com.brudre.streaming.data.VerifiedLanguages.normalize(audioLang)
                val t = if (textOff) "off" else com.brudre.streaming.data.VerifiedLanguages.normalize(textLang)
                if (a != null || t != null) {
                    services.settings.update { s -> s.copy(audioLanguage = a ?: s.audioLanguage, subtitleLanguage = t ?: s.subtitleLanguage) }
                    Log.i(TAG, "Preferência salva: áudio=${a ?: "-"} legenda=${t ?: "-"}")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isLive && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Ficou para trás da janela ao vivo: volta para o ponto atual sem trocar de fonte.
                    player?.seekToDefaultPosition()
                    player?.prepare()
                    return
                }
                val info = PlayerErrors.describe(error)
                Log.w(TAG, "Erro de reprodução (${currentAttempt?.label}): ${error.errorCodeName}", error)
                failCurrent(info.message, info.retryOtherSource)
            }
        })
        playerView.player = p
        player = p
    }

    private fun startAttempt(index: Int, keepRetries: Boolean = false) {
        val p = player ?: return
        if (!keepRetries) sameSourceRetries = 0
        attemptIndex = index
        attemptStartedAt = android.os.SystemClock.elapsedRealtime()
        // Mantém o idioma de áudio/legenda que estava em uso ao trocar de fonte.
        if (keepAudio != null || keepText != null) {
            applyingTracks = true
            val b = p.trackSelectionParameters.buildUpon()
            keepAudio?.let { b.setPreferredAudioLanguage(it) }
            when (keepText) { null -> Unit; "off" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true); else -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(keepText) }
            p.trackSelectionParameters = b.build()
            applyingTracks = false
        }
        val attempt = attempts.getOrNull(index) ?: return
        currentAttempt = attempt
        openJob?.cancel()
        watchdogJob?.cancel()
        stallJob?.cancel()
        releaseTorrent()
        lastErrorMessage = null

        when (attempt) {
            is SourceAttempt.Direct -> {
                showStatus(
                    when {
                        isLive && attempts.size > 1 -> "Sinal ${index + 1} de ${attempts.size}"
                        attempts.size > 1 -> "Fonte: ${attempt.label}"
                        else -> null
                    },
                )
                prepare(p, Uri.parse(attempt.url), if (isLive) MimeTypes.APPLICATION_M3U8 else guessMime(attempt.url))
                startWatchdog(if (isLive) LIVE_START_TIMEOUT_MS else 45_000)
            }
            is SourceAttempt.Torrent -> {
                showStatus("BitTorrent: preparando… (seu IP fica visível aos outros participantes)")
                // Prazo total (metadados + primeiros dados) contado desde já: se o motor travar, troca de fonte.
                startWatchdog(TORRENT_START_TIMEOUT_MS)
                openJob = lifecycleScope.launch {
                    try {
                        val stream = services.torrent.open(attempt.ref) { msg -> runOnUiThread { showStatus("BitTorrent: $msg") } }
                        if (!isActive || isFinishing) { services.torrent.close(stream); return@launch }
                        torrentStream = stream
                        prepare(p, stream.uri, guessMime(stream.file.name), stream.subtitles)
                        startTicker()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha no torrent", e)
                        val f = com.brudre.streaming.sources.ErrorClassifier.classify(e)
                        failCurrent("${f.kind.title}: ${f.message}", true)
                    }
                }
            }
        }
    }

    private fun prepare(p: ExoPlayer, uri: Uri, mime: String?, torrentSubs: List<com.brudre.streaming.torrent.TorrentSubtitle> = emptyList()) {
        val subtitlePref = services.settings.current.subtitleLanguage
        // Legendas que vieram dentro do torrent (.srt/.vtt/.ass), lidas pelo mesmo motor.
        val fromTorrent = torrentSubs.map { t ->
            MediaItem.SubtitleConfiguration.Builder(t.stream.uri)
                .setMimeType(when (t.ext) { "vtt" -> MimeTypes.TEXT_VTT; "ass", "ssa" -> MimeTypes.TEXT_SSA; else -> MimeTypes.APPLICATION_SUBRIP })
                .setLanguage(t.language)
                .setLabel(t.label)
                .setSelectionFlags(if (t.language == subtitlePref) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        lastSubtitleCount = fromTorrent.size + playable.subtitles.size
        val subs = fromTorrent + playable.subtitles.map { s ->
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
        if (isLive) {
            p.setMediaItem(item)
            p.prepare()
            p.playWhenReady = true
            return
        }
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
            n.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
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
            // Falha antes de começar a tocar conta no histórico da fonte cadastrada (se for dela).
            if (!hasBeenReady && !isLive) currentAttempt?.sourceId?.let { id -> services.appScope.launch(Dispatchers.IO) { services.sourceStore.recordResult(id, false, message) } }
            if (isLive && reconnects < LIVE_RECONNECTS) {
                // Reconexão automática do mesmo sinal, com espera crescente (3 s, 6 s).
                reconnects++
                showStatus("Reconectando… ($reconnects/$LIVE_RECONNECTS)")
                player?.stop()
                reconnectJob?.cancel()
                reconnectJob = lifecycleScope.launch {
                    delay(3_000L * reconnects)
                    startAttempt(attemptIndex)
                }
                return@runOnUiThread
            }
            reconnects = 0
            val keep = player?.currentPosition ?: 0L
            if (hasBeenReady && !isLive) resumeMs = keep
            rememberTracks()
            // Falha DURANTE a reprodução: 1ª vez tenta de novo a mesma fonte (do mesmo ponto); depois troca.
            if (retryOtherSource && hasBeenReady && !isLive && sameSourceRetries < SAME_SOURCE_RETRIES && switches < maxSwitches) {
                sameSourceRetries++
                switches++
                showStatus("Reconectando à mesma fonte…")
                player?.stop()
                reconnectJob?.cancel()
                reconnectJob = lifecycleScope.launch { delay(2_000); startAttempt(attemptIndex, keepRetries = true) }
                return@runOnUiThread
            }
            val next = attemptIndex + 1
            // Limite de trocas: nunca volta em ciclo para fontes que já falharam.
            if (retryOtherSource && next < attempts.size && switches < maxSwitches) {
                switches++
                Toast.makeText(this, "$message Tentando ${attempts[next].label}…", Toast.LENGTH_LONG).show()
                player?.stop()
                startAttempt(next)
            } else {
                player?.stop()
                if (isLive) {
                    services.live.markOffline(title.id.removePrefix(com.brudre.streaming.data.Title.LIVE_PREFIX))
                    showFatal("Canal fora do ar no momento. $message")
                } else showFatal(message + if (attempts.size > 1) " Todas as ${attempts.size} fontes deste vídeo foram tentadas." else "")
            }
        }
    }

    /**
     * Fallback inteligente: depois de trocar de fonte, confere a duração com a da fonte anterior.
     * Diferença maior que max(60 s, 5%) indica outra versão/edição (ou outro episódio): pausa e pergunta.
     */
    private fun checkDuration() {
        val d = player?.duration ?: return
        if (isLive || d == C.TIME_UNSET || d <= 0) return
        if (referenceDurationMs == C.TIME_UNSET) { referenceDurationMs = d; durationAttempt = attemptIndex; return }
        if (attemptIndex == durationAttempt || durationDialog?.isShowing == true) return
        if (durationsMatch(referenceDurationMs, d)) { durationAttempt = attemptIndex; return }
        fun fmt(ms: Long) = "%d:%02d:%02d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60)
        val msg = "A fonte alternativa (${currentAttempt?.label ?: "outra fonte"}) tem duração ${fmt(d)}, e a anterior tinha ${fmt(referenceDurationMs)}. " +
            "Pode ser outra versão, edição ou episódio."
        durationMismatch = msg
        Log.w(TAG, "Duração incompatível: $msg")
        player?.pause()
        durationDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Duração diferente")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Continuar assim") { _, _ -> referenceDurationMs = d; durationAttempt = attemptIndex; player?.play() }
            .setNeutralButton("Escolher outra fonte") { _, _ -> services.chooseSourceFor = title; finish() }
            .setNegativeButton("Voltar") { _, _ -> finish() }
            .show()
    }

    /** Idiomas de áudio/legenda em uso: aplicados na próxima fonte (mesmo filme/episódio). */
    private fun rememberTracks() {
        val p = player ?: return
        val groups = p.currentTracks.groups
        groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { g ->
            (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { keepAudio = g.getTrackFormat(it).language }
        }
        val text = groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        keepText = text?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
            ?: if (p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) "off" else keepText
    }

    private fun showFatal(message: String) {
        fatalShown = true
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
                // Sem fonte que funcione: o usuário escolhe outra manualmente (nunca troca sozinho para outra versão).
                if (!isLive) setNeutralButton("Escolher outra fonte") { _, _ -> services.chooseSourceFor = title; finish() }
            }
            .apply {
                if (attempts.isNotEmpty()) setPositiveButton("Tentar novamente") { _, _ ->
                    hasBeenReady = false
                    reconnects = 0
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
                        val p = player
                        val bufferS = if (p != null && hasBeenReady) ((p.bufferedPosition - p.currentPosition).coerceAtLeast(0) / 1000) else null
                        // Tempo até começar: só com taxa medida e estável (senão não mostra nada).
                        val eta = if (!hasBeenReady && resumeMs <= 0) s.etaSeconds(s.math.firstPiece) else null
                        lastTorrentStatus = buildString {
                            append("BitTorrent • ")
                            append(if (stats.peers == 0 && stats.webSeeds == 0) "procurando participantes" else "${stats.peers} conectados")
                            if (stats.availablePeers > stats.peers) append(" de ${stats.availablePeers} conhecidos")
                            if (stats.webSeeds > 0) append(" • ${stats.webSeeds} servidor(es) web")
                            append(" • ${com.brudre.streaming.torrent.CacheManager.formatBytes(stats.downloadRate.toLong())}/s")
                            if (bufferS != null) append(" • buffer ${bufferS}s")
                            if (eta != null && eta > 0) append(" • início em ~${eta}s")
                            if (buffering && hasBeenReady) append(" • carregando")
                        }
                        showStatus(lastTorrentStatus)
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
        if (!hasBeenReady || isLive) return
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
        recordHomologation()
        releaseAll()
        super.onDestroy()
    }

    private fun recordHomologation() {
        if (homologRecorded || !::playable.isInitialized) return
        homologRecorded = true
        val end = android.os.SystemClock.elapsedRealtime()
        val kind = when { isLive -> "Ao vivo"; currentAttempt is SourceAttempt.Torrent -> "BitTorrent"; else -> "HTTP" }
        val pss = pssPeakKb
        services.appScope.launch(Dispatchers.IO) {
            com.brudre.streaming.data.Homologation.recordPlayback(com.brudre.streaming.data.Homologation.Playback(
                kind, firstReadyAt.takeIf { it > 0 }?.let { it - openedAt }, rebuffers, rebufferMs,
                if (firstReadyAt > 0) end - firstReadyAt - rebufferMs else 0L, fatalShown, switches, maxOf(pss, com.brudre.streaming.data.Homologation.pssKb()),
                lastErrorMessage,
            ))
        }
    }

    private fun releaseAll() {
        errorDialog?.dismiss()
        errorDialog = null
        openJob?.cancel()
        watchdogJob?.cancel()
        stallJob?.cancel()
        tickerJob?.cancel()
        reconnectJob?.cancel()
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
            // Ao vivo sem janela de DVR: setas não fazem busca.
            val canSeek = !isLive || p.isCurrentMediaItemSeekable
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    if (p.isPlaying) p.pause() else p.play()
                    playerView.showController(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); playerView.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { p.seekForward(); playerView.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { p.seekBack(); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controllerVisible && canSeek) { p.seekTo(p.currentPosition + 10_000); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!controllerVisible && canSeek) { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); playerView.showController(); return true }
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
    fun attemptsForTest(): List<SourceAttempt> = attempts
    fun torrentStreamForTest(): TorrentStream? = torrentStream
    @Volatile var lastSubtitleCount = 0
        private set
    @Volatile var lastTorrentStatus: String? = null
        private set
    /** Idiomas das faixas reais (áudio, legenda) — testes. */
    @Volatile var lastTrackLanguages: Pair<List<String?>, List<String?>>? = null
        private set
    fun switchesForTest() = switches

    companion object {
        private const val TAG = "BruDrePlayer"
        /** Mesma obra: diferença de até max(60 s, 5%) (créditos, cortes de abertura, arredondamentos do contêiner). */
        fun durationsMatch(referenceMs: Long, otherMs: Long): Boolean =
            kotlin.math.abs(referenceMs - otherMs) <= maxOf(60_000L, referenceMs / 20)
        private const val TORRENT_START_TIMEOUT_MS = 90_000L
        private const val TORRENT_STALL_MS = 45_000L
        private const val SESSION_IDLE_MS = 20_000L
        private const val DIRECT_STALL_MS = 60_000L
        private const val LIVE_STALL_MS = 20_000L
        private const val LIVE_START_TIMEOUT_MS = 25_000L
        private const val LIVE_RECONNECTS = 2
        /** Novas tentativas na mesma fonte quando ela falha no meio do vídeo. */
        private const val SAME_SOURCE_RETRIES = 1
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
