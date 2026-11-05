package com.brudre.streaming.ui.components

import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.brudre.streaming.R
import com.brudre.streaming.ui.theme.BruColors
import kotlinx.coroutines.delay

/** Estado observável da vinheta (para testes instrumentados). */
object IntroVideoState {
    @Volatile var started = false
    @Volatile var positionMs = 0L
    @Volatile var usedFallback = false
}

/**
 * Vinheta cinematográfica de 7 s (vídeo local H.264/AAC 1080p em res/raw, sem internet).
 * Áudio no fluxo de mídia: respeita o volume do sistema. OK/Voltar pulam (tratado em [skip]).
 * Se o aparelho não conseguir decodificar o vídeo, usa a animação desenhada [BruIntro].
 */
@Composable
fun BruVideoIntro(skip: Boolean, onFinished: () -> Unit) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val finish = {
        if (!done) { done = true; onFinished() }
    }
    if (failed) {
        IntroVideoState.usedFallback = true
        BruIntro(skip = skip, onFinished = finish)
        return
    }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
            setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.brudre_intro)))
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) IntroVideoState.started = true
                if (state == Player.STATE_ENDED) finish()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("BruDre", "Vinheta em vídeo indisponível (${error.errorCodeName}); usando a animação desenhada")
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(skip) { if (skip) finish() }
    // Garantia: nunca prende o usuário (ex.: decodificador travado).
    LaunchedEffect(Unit) {
        var waited = 0L
        while (!done && waited < MAX_MS) {
            delay(250); waited += 250
            IntroVideoState.positionMs = player.currentPosition
        }
        finish()
    }
    Box(Modifier.fillMaxSize().background(BruColors.Bg).testTag("intro")) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
            modifier = Modifier.fillMaxSize().testTag("intro_video"),
        )
    }
}

private const val MAX_MS = 9_500L
