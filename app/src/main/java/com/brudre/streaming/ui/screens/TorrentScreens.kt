package com.brudre.streaming.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brudre.streaming.data.db.ProgressEntity
import com.brudre.streaming.data.db.fraction
import com.brudre.streaming.sources.SourceFailure
import com.brudre.streaming.torrent.CacheManager
import com.brudre.streaming.torrent.TorrentVideo
import com.brudre.streaming.ui.MainViewModel
import com.brudre.streaming.ui.components.BruButton
import com.brudre.streaming.ui.components.BruChip
import com.brudre.streaming.ui.components.FullScreenDialogWindow
import com.brudre.streaming.ui.components.ProgressBar
import com.brudre.streaming.ui.components.formatTime
import com.brudre.streaming.ui.theme.BruColors

/**
 * Arquivos do torrent: vídeos agrupados por temporada, episódio reconhecido (S01E01, 1x01…),
 * legendas encontradas no torrent e progresso individual. Só o vídeo escolhido é baixado.
 */
@Composable
fun TorrentFilesDialog(vm: MainViewModel, pick: MainViewModel.TorrentPick) {
    Dialog(onDismissRequest = { vm.closeTorrentPick() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FullScreenDialogWindow()
        val progressFlow = remember(pick.title.id) { vm.progressFor(pick.title.id) }
        val progress by progressFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        val byKey = progress.associateBy { it.episodeKey }
        val first = remember { FocusRequester() }
        val videos = pick.meta?.videos.orEmpty()
        val seasons = videos.mapNotNull { it.episode?.season }.distinct().sorted()
        var season by remember(pick.meta) { mutableStateOf(seasons.firstOrNull()) }
        val shown = if (season == null) videos else videos.filter { it.episode?.season == season || (it.episode == null && season == seasons.firstOrNull()) }
        val keyOf: (TorrentVideo) -> String = { v -> "${pick.baseKey.removeSuffix(":auto")}#${v.file.index}" }

        Box(Modifier.fillMaxSize().background(BruColors.Bg).testTag("torrent_files")) {
            Column(Modifier.padding(start = 48.dp, top = 32.dp, end = 40.dp, bottom = 24.dp)) {
                Text(pick.title.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                pick.meta?.let { m ->
                    Text(
                        "${m.videos.size} vídeo(s) • ${m.files.size} arquivo(s) • ${CacheManager.formatBytes(m.totalSize)} no total. Só o vídeo escolhido é baixado (progressivo); " +
                            "os demais não ocupam espaço. No BitTorrent seu IP fica visível aos outros participantes.",
                        color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.width(900.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                when {
                    pick.error != null -> ErrorBlock(pick.error, first, onRetry = { vm.retryTorrentPick() }, onClose = { vm.closeTorrentPick() })
                    pick.meta == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = BruColors.Cyan, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(pick.status ?: "Carregando…", color = BruColors.TextDim, fontSize = 16.sp, modifier = Modifier.testTag("torrent_status"))
                        Spacer(Modifier.width(24.dp))
                        BruButton("Cancelar", modifier = Modifier.focusRequester(first)) { vm.closeTorrentPick() }
                    }
                    else -> {
                        if (seasons.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(seasons, key = { it }) { s -> BruChip("Temporada $s", selected = s == season, modifier = Modifier.testTag("tseason_$s")) { season = s } }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        LazyColumn(contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(shown, key = { it.file.index }) { v ->
                                VideoRow(
                                    v, byKey[keyOf(v)],
                                    Modifier.then(if (v == shown.firstOrNull()) Modifier.focusRequester(first) else Modifier).testTag("tvideo_${v.file.index}"),
                                ) { vm.playTorrentVideo(v) }
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(pick.meta == null, pick.error == null) { kotlinx.coroutines.delay(120); runCatching { first.requestFocus() } }
    }
}

@Composable
private fun VideoRow(v: TorrentVideo, progress: ProgressEntity?, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .width(920.dp)
            .clip(shape)
            .background(if (focused) BruColors.NavyLight else BruColors.Card)
            .border(if (focused) 2.dp else 1.dp, if (focused) BruColors.Cyan else BruColors.CardBorder, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                v.episode?.let { "Temporada ${it.season} • Episódio ${it.episode}" } ?: "Vídeo",
                color = BruColors.Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(v.file.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(CacheManager.formatBytes(v.file.size))
                    if (v.subtitles.isNotEmpty()) append(" • legendas no torrent: ").append(v.subtitles.joinToString { com.brudre.streaming.torrent.TorrentContent.languageLabel(com.brudre.streaming.torrent.TorrentContent.subtitleLanguage(it.name)) })
                    if (progress != null && progress.positionMs > 0) append(" • parou em ").append(formatTime(progress.positionMs))
                },
                color = BruColors.TextFaint, fontSize = 12.sp,
            )
            if (progress != null && progress.positionMs > 0) { Spacer(Modifier.height(4.dp)); ProgressBar(progress.fraction()) }
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = if (focused) BruColors.Cyan else BruColors.TextDim)
    }
}

/** Erro classificado com "Detalhes técnicos" (erro original) e nova tentativa. */
@Composable
fun ErrorBlock(f: SourceFailure, focus: FocusRequester, onRetry: () -> Unit, onClose: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    Column(Modifier.width(900.dp).testTag("error_block")) {
        Text(f.kind.title, color = BruColors.Danger, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("error_kind"))
        Text(f.message, color = Color.White, fontSize = 15.sp)
        if (f.message != f.kind.hint) Text(f.kind.hint, color = BruColors.TextFaint, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BruButton("Testar novamente", icon = Icons.Filled.Refresh, primary = true, modifier = Modifier.focusRequester(focus).testTag("error_retry"), onClick = onRetry)
            BruButton(if (details) "Ocultar detalhes" else "Detalhes técnicos", modifier = Modifier.testTag("error_details")) { details = !details }
            BruButton("Fechar", onClick = onClose)
        }
        if (details) Text(f.technical, color = BruColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp).testTag("error_technical"))
    }
}
