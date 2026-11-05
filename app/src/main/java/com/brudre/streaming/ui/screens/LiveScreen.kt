package com.brudre.streaming.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.brudre.streaming.live.LiveCategories
import com.brudre.streaming.live.LiveChannel
import com.brudre.streaming.live.LiveRepository
import com.brudre.streaming.ui.Load
import com.brudre.streaming.ui.MainViewModel
import com.brudre.streaming.ui.components.BruButton
import com.brudre.streaming.ui.components.SectionHeader
import com.brudre.streaming.ui.components.SkeletonRow
import com.brudre.streaming.ui.theme.BruColors
import com.brudre.streaming.ui.theme.BruIcons

/** TV ao Vivo: canais abertos por categoria (Brasil), por país (língua portuguesa) e do mundo. */
@Composable
fun LiveScreen(vm: MainViewModel, onPlay: (LiveChannel) -> Unit) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val liveFavs = favorites.filter { it.titleId.startsWith(LiveChannel.PREFIX) }
    val br by vm.liveList("iptv_br").collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("live"), contentPadding = PaddingValues(bottom = 48.dp)) {
        item(key = "header") {
            Column(Modifier.padding(start = 40.dp, top = 8.dp, end = 40.dp)) {
                Text("TV ao Vivo", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Canais abertos de listas públicas (iptv-org). Canais pagos e adultos são removidos automaticamente; " +
                        "os sinais são mantidos por terceiros e alguns podem estar fora do ar. Segure OK num canal para favoritar.",
                    color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.width(820.dp),
                )
            }
        }
        if (!vm.liveEnabled) {
            item(key = "disabled") {
                Text(
                    "TV ao Vivo está desativada em Configurações > Fontes de conteúdo.",
                    color = BruColors.Danger, fontSize = 15.sp, modifier = Modifier.padding(start = 40.dp, top = 16.dp).testTag("live_disabled"),
                )
            }
            return@LazyColumn
        }
        if (liveFavs.isNotEmpty()) {
            item(key = "favs") {
                val channels = liveFavs.mapNotNull { f -> vm.services.live.find(f.titleId.removePrefix(LiveChannel.PREFIX)) }
                ChannelRow(vm, "Canais favoritos", channels, "live_favs", onPlay)
            }
        }
        when (val s = br) {
            is Load.Loading -> item(key = "br_loading") { Column(Modifier.padding(top = 18.dp)) { SectionHeader("Brasil", BruIcons.LiveTv); SkeletonRow() } }
            is Load.Error -> item(key = "br_error") { ErrorRow("Brasil", s.message, "live_br") { vm.retryLive("iptv_br") } }
            is Load.Ok -> {
                val groups = LiveCategories.group(s.value)
                items(groups, key = { "br_" + it.first.key }) { (cat, list) ->
                    ChannelRow(vm, "Brasil — ${cat.label}", list, "live_br_${cat.key}", onPlay)
                }
            }
        }
        item(key = "por") { PortugueseRows(vm, onPlay) }
        item(key = "world_news") { WholeListRow(vm, "iptv_news", "Mundo — Notícias", onPlay) }
        item(key = "world_kids") { WholeListRow(vm, "iptv_kids", "Mundo — Infantil", onPlay) }
        item(key = "world_docs") { WholeListRow(vm, "iptv_docs", "Mundo — Documentários", onPlay) }
        items(vm.liveListsInfo().filter { !it.builtIn }, key = { it.id }) { l -> WholeListRow(vm, l.id, "Minha lista: ${l.name}", onPlay) }
    }
}

/** Canais em português de outros países, uma linha por país. */
@Composable
private fun PortugueseRows(vm: MainViewModel, onPlay: (LiveChannel) -> Unit) {
    val state by vm.liveList("iptv_por").collectAsStateWithLifecycle()
    when (val s = state) {
        is Load.Loading -> Column(Modifier.padding(top = 18.dp)) { SectionHeader("Em português", BruIcons.Globe); SkeletonRow() }
        is Load.Error -> ErrorRow("Em português", s.message, "live_por") { vm.retryLive("iptv_por") }
        is Load.Ok -> {
            val byCountry = s.value.filter { it.country != null && it.country != "br" }.groupBy { it.country!! }
                .entries.sortedByDescending { it.value.size }.take(6)
            Column {
                byCountry.forEach { (code, list) ->
                    ChannelRow(vm, "Em português — ${LiveRepository.countryName(code) ?: code.uppercase()}", list, "live_por_$code", onPlay)
                }
            }
        }
    }
}

@Composable
private fun WholeListRow(vm: MainViewModel, listId: String, label: String, onPlay: (LiveChannel) -> Unit) {
    val state by vm.liveList(listId).collectAsStateWithLifecycle()
    when (val s = state) {
        is Load.Loading -> Column(Modifier.padding(top = 18.dp)) { SectionHeader(label, BruIcons.LiveTv); SkeletonRow() }
        is Load.Error -> ErrorRow(label, s.message, "live_$listId") { vm.retryLive(listId) }
        is Load.Ok -> ChannelRow(vm, label, s.value.sortedBy { if (it.logo != null) 0 else 1 }.take(80), "live_$listId", onPlay)
    }
}

/** Linha de canais de uma categoria de uma lista (usada também em Início, Infantil e Documentários). */
@Composable
fun LiveCategoryRow(vm: MainViewModel, listId: String, category: String, label: String, onPlay: (LiveChannel) -> Unit) {
    val state by vm.liveList(listId).collectAsStateWithLifecycle()
    when (val s = state) {
        is Load.Loading -> Column(Modifier.padding(top = 18.dp)) { SectionHeader(label, BruIcons.LiveTv); SkeletonRow() }
        is Load.Error -> ErrorRow(label, s.message, "live_${listId}_$category") { vm.retryLive(listId) }
        is Load.Ok -> {
            val list = LiveCategories.inCategory(s.value, category)
            if (list.isNotEmpty()) ChannelRow(vm, label, list, "live_${listId}_$category", onPlay)
        }
    }
}

@Composable
private fun ErrorRow(label: String, message: String, tag: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp).testTag("row_$tag")) {
        SectionHeader(label, BruIcons.LiveTv)
        Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = BruColors.Danger, fontSize = 15.sp)
            Spacer(Modifier.width(16.dp))
            BruButton("Tentar novamente", icon = Icons.Filled.Refresh, modifier = Modifier.testTag("retry_$tag"), onClick = onRetry)
        }
    }
}

@Composable
fun ChannelRow(vm: MainViewModel, label: String, channels: List<LiveChannel>, tag: String, onPlay: (LiveChannel) -> Unit) {
    if (channels.isEmpty()) return
    val offline by vm.offlineChannels.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 18.dp).testTag("row_$tag")) {
        SectionHeader("$label (${channels.size})", BruIcons.LiveTv)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(channels, key = { it.id }) { ch ->
                ChannelCard(
                    ch,
                    offline = ch.id in offline,
                    modifier = Modifier.testTag("channel_${tag}_${ch.id}"),
                    onLongPress = {
                        vm.toggleFavoriteChannel(ch) { added ->
                            Toast.makeText(context, if (added) "${ch.name} nos favoritos" else "${ch.name} removido dos favoritos", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { onPlay(ch) }
            }
        }
    }
}

@Composable
fun ChannelCard(
    ch: LiveChannel,
    offline: Boolean,
    modifier: Modifier = Modifier,
    width: Dp = 190.dp,
    onLongPress: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(150), label = "chScale")
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .width(width)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter || e.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                when {
                    !ok -> false
                    e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 1 -> { longPressed = true; onLongPress(); true }
                    e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 1 -> true
                    e.type == KeyEventType.KeyUp && longPressed -> { longPressed = false; true }
                    else -> false
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .shadow(if (focused) 18.dp else 2.dp, shape, ambientColor = BruColors.Cyan, spotColor = BruColors.Cyan)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF16275A), BruColors.Card)))
                .border(if (focused) 3.dp else 1.dp, if (focused) BruColors.Cyan else BruColors.CardBorder, shape)
                .alpha(if (offline) 0.55f else 1f),
        ) {
            if (ch.logo == null) {
                Text(
                    ch.name, color = BruColors.TextDim, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(12.dp),
                )
            } else {
                AsyncImage(
                    model = ch.logo,
                    contentDescription = ch.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(start = 26.dp, end = 26.dp, top = 18.dp, bottom = 26.dp),
                )
            }
            Row(
                Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (offline) Color(0xCC3A4466) else Color(0xCCB0182E)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!offline) Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(4.dp))
                Text(if (offline) "FORA DO AR" else "AO VIVO", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            ch.quality?.let {
                Text(
                    it, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            Text(
                ch.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xB3050814)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Text(
            listOfNotNull(LiveCategories.categoryOf(ch).label, if (ch.notAlways) "nem sempre no ar" else null).joinToString(" • "),
            color = if (focused) Color.White else BruColors.TextFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
    }
}
