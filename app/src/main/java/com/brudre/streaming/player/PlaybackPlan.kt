package com.brudre.streaming.player

import androidx.media3.common.PlaybackException
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.SourceMode
import com.brudre.streaming.data.TorrentRef

/** Fonte concreta a tentar, em ordem. */
sealed class SourceAttempt {
    abstract val label: String

    data class Direct(val url: String) : SourceAttempt() {
        override val label = "streaming direto"
    }

    data class Torrent(val ref: TorrentRef) : SourceAttempt() {
        override val label = "BitTorrent"
    }
}

object PlaybackPlan {
    /** Ordem de tentativas conforme a preferência do usuário e a natureza da fonte. */
    fun attempts(playable: Playable, mode: SourceMode): List<SourceAttempt> {
        val direct = playable.directUrl?.let { SourceAttempt.Direct(it) }
        val torrent = playable.torrent?.let { SourceAttempt.Torrent(it) }
        val ordered = when (mode) {
            SourceMode.DIRECT -> listOf(direct, torrent)
            SourceMode.TORRENT -> listOf(torrent, direct)
            SourceMode.AUTO -> if (playable.preferTorrent) listOf(torrent, direct) else listOf(direct, torrent)
        }
        return ordered.filterNotNull()
    }
}

object PlayerErrors {
    /** Mensagem amigável para o usuário + se vale tentar outra fonte. */
    data class Info(val message: String, val retryOtherSource: Boolean)

    fun describe(error: PlaybackException): Info = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            Info("Sem conexão com a internet ou servidor indisponível.", true)
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
            Info("O vídeo não foi encontrado na fonte.", true)
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            Info("Formato de vídeo incompatível com este aparelho.", true)
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        PlaybackException.ERROR_CODE_TIMEOUT ->
            Info("A reprodução demorou demais para responder.", true)
        else -> {
            val cause = generateSequence(error.cause) { it.cause }.mapNotNull { it.message }.firstOrNull()
            Info(cause ?: "Falha na reprodução (${error.errorCodeName}).", true)
        }
    }
}
