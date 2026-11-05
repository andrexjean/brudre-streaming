package com.brudre.streaming.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.db.fraction
import com.brudre.streaming.ui.MainViewModel
import com.brudre.streaming.ui.components.BruButton
import com.brudre.streaming.ui.components.Dots
import com.brudre.streaming.ui.components.RatingBadge
import com.brudre.streaming.ui.components.SectionHeader
import com.brudre.streaming.ui.components.TitleCard
import com.brudre.streaming.ui.components.TitleRow
import com.brudre.streaming.ui.components.formatDuration
import com.brudre.streaming.ui.theme.BruColors
import com.brudre.streaming.ui.theme.BruIcons
import kotlinx.coroutines.delay

data class RowRef(val key: String, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null)

@Composable
fun HomeScreen(
    vm: MainViewModel,
    playFocus: FocusRequester,
    onOpen: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onPlayChannel: (com.brudre.streaming.live.LiveChannel) -> Unit = {},
) {
    val hero by vm.hero.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val loadingId by vm.playLoading.collectAsStateWithLifecycle()
    // Início: só linhas que tocam no BruDre; o catálogo TMDB entra com clássicos que têm cópia gratuita.
    val rows = listOfNotNull(
        RowRef("featured", Icons.Filled.PlayArrow),
        RowRef("curated_torrent", BruIcons.Movie),
        RowRef("series", BruIcons.Layers),
        RowRef("kids", Icons.Filled.Face),
        if (vm.tmdbEnabled) RowRef("tmdb_pd_classics", Icons.Filled.Star) else null,
        RowRef("docs_featured", BruIcons.Globe),
    )
    LazyColumn(Modifier.fillMaxSize().testTag("home"), contentPadding = PaddingValues(bottom = 48.dp)) {
        item(key = "hero") {
            Hero(hero, playFocus, loadingId, onPlay, onOpen)
        }
        if (continueWatching.isNotEmpty()) {
            item(key = "continue") {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("row_continue")) {
                    SectionHeader("Continuar Assistindo", Icons.Filled.PlayArrow)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(continueWatching, key = { it.titleId }) { p ->
                            TitleCard(
                                p.toTitle(),
                                width = 250.dp,
                                progress = p.fraction(),
                                caption = if (p.episodeLabel.isNotBlank() && p.episodeLabel != p.titleName) p.episodeLabel else "${(p.fraction() * 100).toInt()}% assistido",
                                modifier = Modifier.testTag("continue_${p.titleId}"),
                            ) { onPlay(p.toTitle()) }
                        }
                    }
                }
            }
        }
        items(rows.take(2), key = { it.key }) { ref -> CatalogRow(vm, ref, onOpen) }
        if (vm.liveEnabled) {
            item(key = "live_home") {
                LiveCategoryRow(vm, "iptv_br", "news", "TV ao Vivo — Notícias do Brasil", onPlay = onPlayChannel)
            }
        }
        items(rows.drop(2), key = { it.key }) { ref -> CatalogRow(vm, ref, onOpen) }
    }
}

@Composable
fun CatalogRow(vm: MainViewModel, ref: RowRef, onOpen: (Title) -> Unit) {
    val state by vm.row(ref.key).collectAsStateWithLifecycle()
    TitleRow(
        label = com.brudre.streaming.data.CatalogRepository.label(ref.key),
        state = state,
        icon = ref.icon,
        tag = ref.key,
        onRetry = { vm.retryRow(ref.key) },
        onOpen = onOpen,
    )
}

@Composable
fun Hero(
    items: List<Title>,
    playFocus: FocusRequester,
    loadingId: String?,
    onPlay: (Title) -> Unit,
    onOpen: (Title) -> Unit,
) {
    if (items.isEmpty()) return
    var index by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val safeIndex = index.mod(items.size)
    LaunchedEffect(items.size, focused) {
        while (!focused && items.size > 1) {
            delay(9_000)
            index = (index + 1).mod(items.size)
        }
    }
    val current = items[safeIndex]
    val detailsFocus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(318.dp)
            .onFocusChanged { focused = it.hasFocus }
            .testTag("hero"),
    ) {
        Crossfade(targetState = current, animationSpec = tween(700), label = "hero") { t ->
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = t.bannerUrl ?: t.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.66f)
                        .align(Alignment.TopEnd),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0.0f to BruColors.Bg,
                                0.36f to BruColors.Bg.copy(alpha = 0.92f),
                                0.62f to BruColors.Bg.copy(alpha = 0.25f),
                                1.0f to Color.Transparent,
                            ),
                        ),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, BruColors.Bg))),
                )
            }
        }
        Column(
            Modifier
                .fillMaxHeight()
                .width(500.dp)
                .padding(start = 44.dp, top = 18.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                current.name.uppercase(),
                color = Color.White,
                fontSize = if (current.name.length > 18) 30.sp else 40.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 6.sp,
                maxLines = 2,
                lineHeight = 44.sp,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("hero_title"),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                current.year?.let { Text(it, color = BruColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                RatingBadge(current.rating)
                formatDuration(current.durationMin)?.let { Text(it, color = BruColors.TextDim, fontSize = 16.sp) }
                if (current.genres.isNotEmpty()) Text(current.genres.take(2).joinToString(" • "), color = BruColors.TextDim, fontSize = 16.sp, maxLines = 1)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                current.synopsis ?: "",
                color = BruColors.TextDim,
                fontSize = 15.sp,
                maxLines = 3,
                lineHeight = 21.sp,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BruButton(
                    if (loadingId == current.id) "Abrindo…" else "Assistir Agora",
                    icon = Icons.Filled.PlayArrow,
                    primary = true,
                    modifier = Modifier
                        .focusRequester(playFocus)
                        .focusProperties { right = detailsFocus }
                        .testTag("hero_play")
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && items.size > 1) {
                                index = (safeIndex - 1).mod(items.size); true
                            } else false
                        },
                ) { onPlay(current) }
                BruButton(
                    "Mais Detalhes",
                    icon = Icons.Filled.Info,
                    modifier = Modifier
                        .focusRequester(detailsFocus)
                        .focusProperties { left = playFocus }
                        .testTag("hero_details")
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && items.size > 1) {
                                index = (safeIndex + 1).mod(items.size); true
                            } else false
                        },
                ) { onOpen(current) }
            }
            Spacer(Modifier.height(16.dp))
            Dots(items.size, safeIndex)
        }
    }
}

/** Telas Filmes / Séries / Infantil: várias linhas do catálogo. */
@Composable
fun BrowseScreen(
    vm: MainViewModel,
    title: String,
    rows: List<RowRef>,
    tag: String,
    onOpen: (Title) -> Unit,
    liveCategory: String? = null,
    onPlayChannel: (com.brudre.streaming.live.LiveChannel) -> Unit = {},
) {
    LazyColumn(Modifier.fillMaxSize().testTag(tag), contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp)) {
        item(key = "header") {
            Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 40.dp, top = 8.dp))
        }
        items(rows, key = { it.key }) { ref -> CatalogRow(vm, ref, onOpen) }
        if (liveCategory != null && vm.liveEnabled) {
            item(key = "live_$liveCategory") {
                LiveCategoryRow(vm, "iptv_br", liveCategory, "Canais ao vivo — $title", onPlay = onPlayChannel)
            }
        }
    }
}
