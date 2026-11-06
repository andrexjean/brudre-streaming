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

/** TV ao Vivo: canais abertos do Brasil (por categoria), em português de outros países e do mundo (separados por idioma). */
@Composable
fun LiveScreen(vm: MainViewModel, onPlay: (LiveChannel) -> Unit) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val liveFavs = favorites.filter { it.titleId.startsWith(LiveChannel.PREFIX) }
    val br by vm.liveList("iptv_br").collectAsStateWithLifecycle()
    val progress by vm.liveCheckProgress.collectAsStateWithLifecycle()
    val brAll = (br as? Load.Ok)?.value.orEmpty()
    val brVisible = rememberVisible(vm, brAll)
    val brGroups = remember(brVisible) { LiveCategories.group(brVisible) }
    val favChannels = remember(liveFavs, brAll) { liveFavs.mapNotNull { f -> vm.services.live.find(f.titleId.removePrefix(LiveChannel.PREFIX)) } }
    LazyColumn(Modifier.fillMaxSize().testTag("live"), contentPadding = PaddingValues(bottom = 48.dp)) {
        item(key = "header") {
            Column(Modifier.padding(start = 40.dp, top = 8.dp, end = 40.dp)) {
                Text("TV ao Vivo", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Canais abertos de listas públicas (iptv-org). Canais pagos e adultos são removidos automaticamente; " +
                        "os sinais são mantidos por terceiros e alguns podem estar fora do ar. Segure OK num canal para favoritar.",
                    color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.width(820.dp),
                )
                val hidden = brAll.size - brVisible.size
                val status = when {
                    progress != null -> "Testando os sinais: ${progress!!.done} de ${progress!!.total}…"
                    hidden > 0 -> "Sinais testados neste aparelho • $hidden canais fora do ar foram ocultados"
                    else -> null
                }
                if (status != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text(status, color = BruColors.Cyan, fontSize = 13.sp, modifier = Modifier.testTag("live_check_status"))
                        if (progress == null) {
                            Spacer(Modifier.width(14.dp))
                            BruButton("Testar de novo", icon = Icons.Filled.Refresh, modifier = Modifier.testTag("live_recheck")) { vm.recheckLive() }
                        }
                    }
                }
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
            item(key = "favs") { ChannelRow(vm, "Canais favoritos", favChannels, "live_favs", onPlay) }
        }
        when (val s = br) {
            is Load.Loading -> item(key = "br_loading") { Column(Modifier.padding(top = 18.dp)) { SectionHeader("Brasil", BruIcons.LiveTv); SkeletonRow() } }
            is Load.Error -> item(key = "br_error") { ErrorRow("Brasil", s.message, "live_br") { vm.retryLive("iptv_br") } }
            is Load.Ok -> {
                items(brGroups, key = { "br_" + it.first.key }) { (cat, list) ->
                    ChannelRow(vm, "Brasil — ${cat.label}", list, "live_br_${cat.key}", onPlay)
                }
            }
        }
        item(key = "por") { PortugueseRows(vm, onPlay) }
        // Mundo: cada categoria/país em duas linhas — com português e só no idioma original.
        WORLD_ROWS.forEach { (id, label) -> item(key = "world_$id") { WorldListRows(vm, id, label, onPlay) } }
        items(vm.liveListsInfo().filter { !it.builtIn }, key = { it.id }) { l -> WholeListRow(vm, l.id, "Minha lista: ${l.name}", onPlay) }
    }
}

/** Listas do mundo exibidas em TV ao Vivo (categoria ou país). */
val WORLD_ROWS = listOf(
    "iptv_news" to "Mundo — Notícias", "iptv_movies" to "Mundo — Filmes", "iptv_series" to "Mundo — Séries",
    "iptv_kids" to "Mundo — Infantil", "iptv_docs" to "Mundo — Documentários", "iptv_ent" to "Mundo — Entretenimento",
    "iptv_music" to "Mundo — Música", "iptv_sports" to "Mundo — Esportes",
    "iptv_c_us" to "Estados Unidos", "iptv_c_es" to "Espanha", "iptv_c_mx" to "México", "iptv_c_ar" to "Argentina",
    "iptv_c_fr" to "França", "iptv_c_it" to "Itália",
)

/**
 * Lista do mundo separada por idioma: "em português" (países lusófonos ou canal da lista em português)
 * e "outros idiomas" (sem áudio em português informado).
 */
@Composable
fun WorldListRows(vm: MainViewModel, listId: String, label: String, onPlay: (LiveChannel) -> Unit) {
    val state by vm.liveList(listId).collectAsStateWithLifecycle()
    val por by vm.liveList("iptv_por").collectAsStateWithLifecycle()
    when (val s = state) {
        is Load.Loading -> Column(Modifier.padding(top = 18.dp)) { SectionHeader(label, BruIcons.LiveTv); SkeletonRow() }
        is Load.Error -> ErrorRow(label, s.message, "live_$listId") { vm.retryLive(listId) }
        is Load.Ok -> {
            val portuguese = (por as? Load.Ok)?.value.orEmpty()
            val (pt, other) = remember(s.value, portuguese) {
                val sorted = s.value.sortedBy { if (it.logo != null) 0 else 1 }
                val split = LiveRepository.splitByPortuguese(sorted, portuguese)
                split.first.take(60) to split.second.take(80)
            }
            androidx.compose.runtime.LaunchedEffect(pt, other) { vm.checkChannels(pt + other) }
            val ptVisible = rememberVisible(vm, pt)
            val otherVisible = rememberVisible(vm, other)
            Column {
                if (ptVisible.isNotEmpty()) ChannelRow(vm, "$label — em português", ptVisible, "live_${listId}_pt", onPlay)
                if (otherVisible.isNotEmpty()) ChannelRow(vm, "$label — outros idiomas (sem áudio em português)", otherVisible, "live_${listId}_world", onPlay)
            }
        }
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
            val visible = rememberVisible(vm, s.value)
            val byCountry = remember(visible) {
                visible.filter { it.country != null && it.country != "br" }.groupBy { it.country!! }
                    .entries.sortedByDescending { it.value.size }.take(6)
            }
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
        is Load.Ok -> {
            val shown = remember(s.value) { s.value.sortedBy { if (it.logo != null) 0 else 1 }.take(80) }
            // Listas do mundo são testadas só quando a linha aparece (e só os canais exibidos).
            androidx.compose.runtime.LaunchedEffect(shown) { vm.checkChannels(shown) }
            ChannelRow(vm, label, rememberVisible(vm, shown), "live_$listId", onPlay)
        }
    }
}

/** Canais de um grupo da lista (ex.: "Movies" → canais de filmes 24 h grátis em Filmes). */
@Composable
fun LiveGroupRow(vm: MainViewModel, listId: String, group: String, label: String, onPlay: (LiveChannel) -> Unit) {
    val state by vm.liveList(listId).collectAsStateWithLifecycle()
    val all = (state as? Load.Ok)?.value ?: return
    val visible = rememberVisible(vm, all)
    val list = remember(visible, group) { visible.filter { ch -> ch.groups.any { it.equals(group, true) } } }
    if (list.isNotEmpty()) ChannelRow(vm, label, list, "live_${listId}_g_$group", onPlay)
}

/** Lista sem os canais que falharam no teste de sinal (recalculada só quando o resultado muda). */
@Composable
fun rememberVisible(vm: MainViewModel, list: List<LiveChannel>): List<LiveChannel> {
    val health by vm.liveHealth.collectAsStateWithLifecycle()
    val offline by vm.offlineChannels.collectAsStateWithLifecycle()
    return remember(list, health, offline) { vm.visibleChannels(list) }
}

/** Linha de canais de uma categoria de uma lista (usada também em Início, Infantil e Documentários). */
@Composable
fun LiveCategoryRow(vm: MainViewModel, listId: String, category: String, label: String, onPlay: (LiveChannel) -> Unit) {
    val state by vm.liveList(listId).collectAsStateWithLifecycle()
    when (val s = state) {
        is Load.Loading -> Column(Modifier.padding(top = 18.dp)) { SectionHeader(label, BruIcons.LiveTv); SkeletonRow() }
        is Load.Error -> ErrorRow(label, s.message, "live_${listId}_$category") { vm.retryLive(listId) }
        is Load.Ok -> {
            val visible = rememberVisible(vm, s.value)
            val list = remember(visible, category) { LiveCategories.inCategory(visible, category) }
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
    val caption = remember(ch) { listOfNotNull(LiveCategories.categoryOf(ch).label, if (ch.notAlways) "nem sempre no ar" else null).joinToString(" • ") }
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
                .then(if (focused) Modifier.shadow(18.dp, shape, ambientColor = BruColors.Cyan, spotColor = BruColors.Cyan) else Modifier)
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
            caption,
            color = if (focused) Color.White else BruColors.TextFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
    }
}
