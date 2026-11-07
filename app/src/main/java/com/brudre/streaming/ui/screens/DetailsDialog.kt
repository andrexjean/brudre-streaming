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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.brudre.streaming.data.Parental
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TitleDetails
import com.brudre.streaming.data.db.ProgressEntity
import com.brudre.streaming.data.db.fraction
import com.brudre.streaming.ui.Load
import com.brudre.streaming.ui.MainViewModel
import com.brudre.streaming.ui.components.BruButton
import com.brudre.streaming.ui.components.BruChip
import com.brudre.streaming.ui.components.FullScreenDialogWindow
import com.brudre.streaming.ui.components.PinDialog
import com.brudre.streaming.ui.components.ProgressBar
import com.brudre.streaming.ui.components.RatingBadge
import com.brudre.streaming.ui.components.formatDuration
import com.brudre.streaming.ui.components.formatTime
import com.brudre.streaming.ui.theme.BruColors
import kotlinx.coroutines.delay

/**
 * Tela de detalhes em janela própria: o foco do controle remoto fica preso aqui
 * e volta ao card de origem ao fechar.
 */
@Composable
fun DetailsDialog(
    vm: MainViewModel,
    title: Title,
    onDismiss: () -> Unit,
    onPlay: (Title, Playable?, Boolean, TitleDetails?) -> Unit,
    onFreeApp: (Title, com.brudre.streaming.data.FreeProvider) -> Unit = { _, _ -> },
    onPlayOption: (TitleDetails, com.brudre.streaming.data.SourceOption, Playable?) -> Unit = { _, _, _ -> },
) {
    var choosing by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FullScreenDialogWindow()
        val state by vm.details.collectAsStateWithLifecycle()
        val favoriteFlow = remember(title.id) { vm.isFavorite(title.id) }
        val isFavorite by favoriteFlow.collectAsStateWithLifecycle(initialValue = false)
        val progressFlow = remember(title.id) { vm.progressFor(title.id) }
        val progress by progressFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        val loadingId by vm.playLoading.collectAsStateWithLifecycle()
        val mySettings by vm.settings.collectAsStateWithLifecycle()
        val unlockedUntil by vm.unlockedUntil.collectAsStateWithLifecycle()
        var showPin by remember { mutableStateOf(false) }
        val primaryFocus = remember { FocusRequester() }
        val details = (state as? Load.Ok)?.value
        val shown = com.brudre.streaming.data.VerifiedLanguages.apply(details?.title ?: title)

        Box(
            Modifier
                .fillMaxSize()
                .background(BruColors.Bg)
                .testTag("details"),
        ) {
            AsyncImage(
                model = shown.bannerUrl ?: shown.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.62f).align(Alignment.TopEnd),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to BruColors.Bg,
                        0.42f to BruColors.Bg.copy(alpha = 0.94f),
                        0.75f to BruColors.Bg.copy(alpha = 0.35f),
                        1f to BruColors.Bg.copy(alpha = 0.15f),
                    ),
                ),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 48.dp, top = 36.dp, end = 32.dp, bottom = 24.dp),
            ) {
                Text(
                    shown.name,
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(620.dp).testTag("details_title"),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(shown.kind.label, color = BruColors.Cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    shown.year?.let { Text(it, color = BruColors.Text, fontSize = 15.sp) }
                    RatingBadge(shown.rating)
                    formatDuration(shown.durationMin)?.let { Text(it, color = BruColors.TextDim, fontSize = 15.sp) }
                    if (shown.genres.isNotEmpty()) Text(shown.genres.joinToString(" • "), color = BruColors.TextDim, fontSize = 15.sp, maxLines = 1)
                }
                shown.languageLine?.let { line ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        shown.languageBadge?.let { b ->
                            Text(
                                b, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(5.dp))
                                    .background(if (shown.ptAudio) Color(0xCC1F8A4C) else if (shown.ptSubtitles) Color(0xCC2F4C9C) else Color(0xCC5A5F73))
                                    .padding(horizontal = 6.dp, vertical = 2.dp).testTag("details_lang_badge"),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(line, color = BruColors.TextDim, fontSize = 14.sp, modifier = Modifier.testTag("details_language"))
                    }
                }
                com.brudre.streaming.data.VerifiedLanguages.line(shown.id)?.let { v ->
                    Text(v, color = BruColors.Cyan, fontSize = 13.sp, modifier = Modifier.testTag("details_language_verified"))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    shown.synopsis ?: "Sinopse não informada pela fonte.",
                    color = BruColors.TextDim,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(600.dp),
                )
                Spacer(Modifier.height(18.dp))

                when (val s = state) {
                    is Load.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = BruColors.Cyan, strokeWidth = 3.dp, modifier = Modifier.width(26.dp).height(26.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(
                            if (title.isTmdb) "Procurando uma fonte gratuita e autorizada desta obra…" else "Verificando a fonte do vídeo…",
                            color = BruColors.TextDim, fontSize = 15.sp, modifier = Modifier.testTag("details_loading"),
                        )
                        Spacer(Modifier.width(24.dp))
                        FavoriteButton(isFavorite, Modifier.focusRequester(primaryFocus)) { vm.toggleFavorite(shown) }
                    }
                    is Load.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.message, color = BruColors.Danger, fontSize = 15.sp)
                        Spacer(Modifier.width(16.dp))
                        BruButton("Tentar novamente", icon = Icons.Filled.Refresh, modifier = Modifier.focusRequester(primaryFocus).testTag("details_retry")) { vm.reloadDetails() }
                        Spacer(Modifier.width(12.dp))
                        FavoriteButton(isFavorite) { vm.toggleFavorite(shown) }
                    }
                    is Load.Ok -> if (unlockedUntil >= 0 && Parental.needsPin(s.value.title.rating, mySettings.maxRating) && !vm.isUnlocked()) {
                        LockedSection(
                            rating = s.value.title.rating ?: "",
                            hasPin = mySettings.pinHash.isNotEmpty(),
                            primaryFocus = primaryFocus,
                            onUnlock = { showPin = true },
                        )
                    } else if (s.value.freeElsewhere) FreeAppsSection(
                        details = s.value,
                        isFavorite = isFavorite,
                        primaryFocus = primaryFocus,
                        onToggleFavorite = { vm.toggleFavorite(s.value.title) },
                        onOpen = { p -> onFreeApp(s.value.title, p) },
                    ) else AvailableSection(
                        details = s.value,
                        progress = progress,
                        isFavorite = isFavorite,
                        loading = loadingId == shown.id,
                        primaryFocus = primaryFocus,
                        onToggleFavorite = { vm.toggleFavorite(s.value.title) },
                        onPlay = { p, fromStart -> onPlay(s.value.title, p, fromStart, s.value) },
                        onRetry = { vm.reloadDetails() },
                        onChoose = { choosing = true },
                    )
                }
            }
        }

        val ok = (state as? Load.Ok)?.value
        val wantChooser by vm.requestChooser.collectAsStateWithLifecycle()
        LaunchedEffect(wantChooser, ok) { if (wantChooser && ok != null) { vm.chooserShown(); if (ok.options.isNotEmpty()) choosing = true } }
        if (choosing && ok != null) {
            SourceOptionsDialog(ok, onDismiss = { choosing = false }) { option -> choosing = false; onPlayOption(ok, option, null) }
        }

        if (showPin) {
            PinDialog(
                title = if (mySettings.pinHash.isEmpty()) "Crie a senha dos pais" else "Senha dos pais",
                subtitle = if (mySettings.pinHash.isEmpty()) "Escolha 4 números. Ela será pedida para títulos acima da classificação liberada."
                else "Digite a senha de 4 números para liberar este título por 30 minutos.",
                onDismiss = { showPin = false },
                onSubmit = { pin -> vm.submitPin(pin).also { if (it == null) showPin = false } },
            )
        }

        LaunchedEffect(state::class, unlockedUntil) {
            delay(120)
            runCatching { primaryFocus.requestFocus() }
        }
    }
}

@Composable
private fun LockedSection(rating: String, hasPin: Boolean, primaryFocus: FocusRequester, onUnlock: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x33FF6B7A))
                .border(1.dp, BruColors.Danger, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("details_locked"),
        ) {
            Text("Classificação $rating anos — protegido pelo controle dos pais", color = Color.White, fontSize = 15.sp)
        }
        Spacer(Modifier.width(16.dp))
        BruButton(
            if (hasPin) "Digite a senha" else "Criar senha",
            icon = Icons.Filled.Lock,
            primary = true,
            modifier = Modifier.focusRequester(primaryFocus).testTag("btn_unlock"),
            onClick = onUnlock,
        )
    }
}

@Composable
private fun FavoriteButton(isFavorite: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    BruButton(
        if (isFavorite) "Na Minha Lista" else "Minha Lista",
        icon = if (isFavorite) Icons.Filled.Check else Icons.Filled.Add,
        modifier = modifier.testTag("btn_favorite"),
        onClick = onClick,
    )
}

/** Título grátis em app oficial (Pluto TV, YouTube, Plex…): botão por serviço. */
@Composable
private fun FreeAppsSection(
    details: TitleDetails,
    isFavorite: Boolean,
    primaryFocus: FocusRequester,
    onToggleFavorite: () -> Unit,
    onOpen: (com.brudre.streaming.data.FreeProvider) -> Unit,
) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            details.freeProviders.take(3).forEachIndexed { i, p ->
                BruButton(
                    "Assistir grátis no ${com.brudre.streaming.data.FreeApps.label(p.name)}",
                    icon = Icons.Filled.PlayArrow,
                    primary = i == 0,
                    modifier = Modifier
                        .then(if (i == 0) Modifier.focusRequester(primaryFocus) else Modifier)
                        .testTag("btn_free_$i"),
                ) { onOpen(p) }
            }
            FavoriteButton(isFavorite, onClick = onToggleFavorite)
        }
        Spacer(Modifier.height(10.dp))
        Text(details.sourceLabel, color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.testTag("details_source"))
        Text(
            "Abre o app oficial gratuito (com anúncios). Se não estiver instalado, a Play Store abre para instalar sem custo.",
            color = BruColors.TextFaint, fontSize = 12.sp,
        )
    }
}

@Composable
private fun AvailableSection(
    details: TitleDetails,
    progress: List<ProgressEntity>,
    isFavorite: Boolean,
    loading: Boolean,
    primaryFocus: FocusRequester,
    onToggleFavorite: () -> Unit,
    onPlay: (Playable, Boolean) -> Unit,
    onRetry: () -> Unit,
    onChoose: () -> Unit = {},
) {
    val playables = details.playables.filter { it.hasSource }
    val byKey = progress.associateBy { it.episodeKey }
    val last = progress.maxByOrNull { it.updatedAt }
    val target = playables.firstOrNull { it.key == last?.episodeKey } ?: playables.firstOrNull()
    val targetProgress = target?.let { byKey[it.key] }
    val canResume = targetProgress != null && targetProgress.positionMs > 15_000 &&
        (targetProgress.durationMs == 0L || targetProgress.positionMs < targetProgress.durationMs * 0.95)

    Column {
        if (target == null) {
            // Título só catalogado: sem botão Assistir e sem redirecionar para serviços pagos.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x33FF6B7A))
                        .border(1.dp, BruColors.Danger, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("details_unavailable"),
                ) {
                    Text(
                        if (details.title.isTmdb) "Sem fonte gratuita no momento" else "Indisponível: nenhuma fonte autorizada e compatível foi encontrada.",
                        color = Color.White, fontSize = 15.sp,
                    )
                }
                Spacer(Modifier.width(16.dp))
                if (details.options.isNotEmpty()) {
                    // Só há fontes sem associação confirmada (sem ano): o usuário decide.
                    BruButton("Escolher fonte (${details.options.size})", primary = true, modifier = Modifier.focusRequester(primaryFocus).testTag("btn_sources"), onClick = onChoose)
                    Spacer(Modifier.width(12.dp))
                    FavoriteButton(isFavorite, onClick = onToggleFavorite)
                } else FavoriteButton(isFavorite, Modifier.focusRequester(primaryFocus), onToggleFavorite)
            }
        } else if (!details.confirmed) {
            // Fonte identificada, mas não respondeu à verificação: o Assistir só aparece depois de confirmar.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x33F5C518))
                        .border(1.dp, BruColors.Badge, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("details_unconfirmed"),
                ) {
                    Text("Fonte encontrada, mas não respondeu agora", color = Color.White, fontSize = 15.sp)
                }
                Spacer(Modifier.width(16.dp))
                BruButton("Verificar de novo", icon = Icons.Filled.Refresh, primary = true, modifier = Modifier.focusRequester(primaryFocus).testTag("details_recheck"), onClick = onRetry)
                Spacer(Modifier.width(12.dp))
                FavoriteButton(isFavorite, onClick = onToggleFavorite)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val episodePrefix = if (details.isEpisodic) "T${target.season}:E${target.number} " else ""
                BruButton(
                    when {
                        loading -> "Abrindo…"
                        canResume -> "Continuar $episodePrefix• ${formatTime(targetProgress!!.positionMs)}"
                        details.isEpisodic -> "Assistir ${episodePrefix.trim()}"
                        else -> "Assistir"
                    },
                    icon = Icons.Filled.PlayArrow,
                    primary = true,
                    modifier = Modifier.focusRequester(primaryFocus).testTag("btn_play"),
                ) { onPlay(target, false) }
                if (canResume) {
                    BruButton("Do início", icon = Icons.Filled.Refresh, modifier = Modifier.testTag("btn_restart")) { onPlay(target, true) }
                }
                if (details.options.size > 1) {
                    BruButton("Escolher fonte (${details.options.size})", modifier = Modifier.testTag("btn_sources"), onClick = onChoose)
                }
                FavoriteButton(isFavorite, onClick = onToggleFavorite)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            buildString {
                append(details.sourceLabel)
                details.title.license?.let { append("  •  ").append(it) }
            },
            color = BruColors.TextFaint,
            fontSize = 13.sp,
            modifier = Modifier.testTag("details_source"),
        )

        if (details.isEpisodic && playables.isNotEmpty()) {
            val seasons = playables.map { it.season }.distinct().sorted()
            var season by remember(details.title.id) { mutableIntStateOf(target?.season ?: seasons.first()) }
            Spacer(Modifier.height(14.dp))
            if (seasons.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(seasons, key = { it }) { s ->
                        BruChip("Temporada $s", selected = s == season, modifier = Modifier.testTag("season_$s")) { season = s }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Text("Episódios", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
            ) {
                items(playables.filter { it.season == season }, key = { it.key }) { p ->
                    EpisodeCard(p, byKey[p.key], Modifier.testTag("episode_${p.number}")) { onPlay(p, false) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(p: Playable, progress: ProgressEntity?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .width(230.dp)
            .graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s }
            .clip(shape)
            .background(if (focused) BruColors.NavyLight else BruColors.Card)
            .border(if (focused) 2.dp else 1.dp, if (focused) BruColors.Cyan else BruColors.CardBorder, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(12.dp),
    ) {
        Text("Episódio ${p.number}", color = BruColors.Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(p.label, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(42.dp))
        p.durationSec?.let { Text(formatTime(it * 1000L), color = BruColors.TextFaint, fontSize = 12.sp) }
        if (progress != null && progress.positionMs > 0) {
            Spacer(Modifier.height(6.dp))
            ProgressBar(progress.fraction())
        }
    }
}

/** Escolha manual da fonte: todas as fontes compatíveis encontradas, na ordem automática. */
@Composable
private fun SourceOptionsDialog(details: TitleDetails, onDismiss: () -> Unit, onPick: (com.brudre.streaming.data.SourceOption) -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(BruColors.Navy).border(1.dp, BruColors.CardBorder, RoundedCornerShape(16.dp))
                .padding(24.dp).width(720.dp).testTag("source_options"),
        ) {
            Text("Fontes encontradas", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Na ordem automática (prioridade e histórico). Se a escolhida falhar, o player tenta as seguintes.",
                color = BruColors.TextFaint, fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            details.options.forEachIndexed { i, o ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(10.dp)
                Column(
                    Modifier.padding(vertical = 4.dp).width(672.dp).clip(shape)
                        .background(if (focused) BruColors.NavyLight else BruColors.Card)
                        .border(if (focused) 2.dp else 1.dp, if (focused) BruColors.Cyan else BruColors.CardBorder, shape)
                        .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(o) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("option_$i"),
                ) {
                    Text("${i + 1}. ${o.name}${if (o.native) " (nativa)" else ""}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(o.formats, o.qualityInfo, o.languageInfo, "${o.playables.size} vídeo(s)", o.health).joinToString(" • "),
                        color = BruColors.TextFaint, fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            BruButton("Cancelar", onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
}
