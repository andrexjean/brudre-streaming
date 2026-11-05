package com.brudre.streaming.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brudre.streaming.data.Title
import com.brudre.streaming.ui.components.AppBackground
import com.brudre.streaming.ui.components.BruButton
import com.brudre.streaming.ui.components.BruVideoIntro
import com.brudre.streaming.ui.components.BruLogo
import com.brudre.streaming.ui.components.IntroState
import com.brudre.streaming.update.UpdateState
import com.brudre.streaming.ui.screens.BrowseScreen
import com.brudre.streaming.ui.screens.DetailsDialog
import com.brudre.streaming.ui.screens.HomeScreen
import com.brudre.streaming.ui.screens.LiveScreen
import com.brudre.streaming.ui.screens.MyListScreen
import com.brudre.streaming.ui.screens.RowRef
import com.brudre.streaming.ui.screens.SearchScreen
import com.brudre.streaming.ui.screens.SettingsScreen
import com.brudre.streaming.ui.theme.BruColors
import com.brudre.streaming.ui.theme.BruDreTheme
import com.brudre.streaming.ui.theme.BruIcons
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Voltando da tela "instalar apps desconhecidos": retoma a atualização.
        androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java].resumeUpdateIfPending()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BruDreTheme {
                val vm: MainViewModel = viewModel()
                BruDreRoot(vm)
            }
        }
    }
}

@Composable
fun BruDreRoot(vm: MainViewModel) {
    val context = LocalContext.current
    var section by rememberSaveable { mutableStateOf(Section.HOME) }
    val detailsTitle by vm.detailsTitle.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    var lastBack by remember { mutableLongStateOf(0L) }
    var userNavigated by remember { mutableStateOf(false) }

    val toast: (String) -> Unit = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    val launch: (android.content.Intent) -> Unit = { context.startActivity(it) }
    val freeApp: (Title, com.brudre.streaming.data.FreeProvider) -> Unit = { t, p -> toast(FreeAppLauncher.open(context, p, t)) }
    val playTitle: (Title) -> Unit = { t -> vm.play(t, onLaunch = launch, onError = toast, onFreeApp = freeApp) }
    val playChannel: (com.brudre.streaming.live.LiveChannel) -> Unit = { ch -> vm.playChannel(ch, launch) }
    val tmdbOn = vm.tmdbEnabled
    fun tmdb(vararg refs: RowRef): List<RowRef> = if (tmdbOn) refs.toList() else emptyList()
    // Canais (Minha Lista / pesquisa) tocam direto; os demais abrem os detalhes.
    val open: (Title) -> Unit = { t -> if (t.isLive) playTitle(t) else vm.openDetails(t) }

    var showIntro by remember { mutableStateOf(!IntroState.shown && vm.settings.value.intro) }
    var skipIntro by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { IntroState.shown = true }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                // Durante a abertura, qualquer tecla do controle pula a animação.
                if (showIntro) {
                    if (e.type == KeyEventType.KeyDown) skipIntro = true
                    true
                } else false
            },
    ) {
    AppBackground {
        Column(Modifier.fillMaxSize().onPreviewKeyEvent { userNavigated = true; false }) {
            TopBar(section, online, update, onUpdate = { vm.startUpdate() }) { section = it }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    Section.HOME -> HomeScreen(vm, playFocus, open, playTitle, playChannel)
                    Section.RELEASES -> BrowseScreen(
                        vm, "Lançamentos",
                        tmdb(RowRef("tmdb_free_new", Icons.Filled.PlayArrow), RowRef("tmdb_new_movies", BruIcons.NewReleases), RowRef("tmdb_upcoming_streaming"),
                            RowRef("tmdb_free_new_series", Icons.Filled.PlayArrow), RowRef("tmdb_new_series", BruIcons.Layers)),
                        "releases", open,
                    )
                    Section.TRENDING -> BrowseScreen(
                        vm, "Em Alta",
                        tmdb(RowRef("tmdb_free_trending", Icons.Filled.PlayArrow), RowRef("tmdb_trending_movies", BruIcons.Trending), RowRef("tmdb_popular_movies"),
                            RowRef("tmdb_free_series", Icons.Filled.PlayArrow), RowRef("tmdb_trending_series", BruIcons.Trending), RowRef("tmdb_popular_series")),
                        "trending", open,
                    )
                    Section.MOVIES -> BrowseScreen(
                        vm, "Filmes",
                        tmdb(RowRef("tmdb_free_movies", Icons.Filled.PlayArrow), RowRef("tmdb_free_new", BruIcons.NewReleases)) +
                            listOf(RowRef("featured", Icons.Filled.PlayArrow)) +
                            tmdb(RowRef("tmdb_trending_movies", BruIcons.Trending), RowRef("tmdb_free_action"), RowRef("tmdb_free_comedy"), RowRef("tmdb_free_horror")) +
                            listOf(RowRef("curated_torrent", BruIcons.Movie), RowRef("adventure"), RowRef("westerns"), RowRef("comedy"), RowRef("scifi"), RowRef("noir")) +
                            tmdb(RowRef("tmdb_pd_classics", Icons.Filled.Star)),
                        "movies", open, onPlayChannel = playChannel, liveGroup = "Movies",
                    )
                    Section.SERIES -> BrowseScreen(
                        vm, "Séries",
                        tmdb(RowRef("tmdb_free_series", Icons.Filled.PlayArrow), RowRef("tmdb_trending_series", BruIcons.Trending)) +
                            tmdb(RowRef("tmdb_new_series", BruIcons.NewReleases), RowRef("tmdb_free_new_series")) +
                            listOf(RowRef("series", BruIcons.Layers), RowRef("series_recent")),
                        "series", open, onPlayChannel = playChannel, liveGroup = "Series",
                    )
                    Section.KIDS -> BrowseScreen(
                        vm, "Infantil",
                        tmdb(RowRef("tmdb_free_family", Icons.Filled.PlayArrow), RowRef("tmdb_free_kids", Icons.Filled.Face)) +
                            listOf(RowRef("curated_kids", Icons.Filled.PlayArrow), RowRef("kids", Icons.Filled.Face), RowRef("kids_animation")) +
                            tmdb(RowRef("tmdb_pd_anim")),
                        "kids", open, liveCategory = "kids", onPlayChannel = playChannel,
                    )
                    Section.CLASSICS -> BrowseScreen(
                        vm, "Clássicos",
                        tmdb(RowRef("tmdb_pd_classics", Icons.Filled.Star), RowRef("tmdb_pd_top"), RowRef("tmdb_pd_comedy"), RowRef("tmdb_pd_horror")) +
                            listOf(RowRef("silent", BruIcons.History), RowRef("noir"), RowRef("recent")),
                        "classics", open,
                    )
                    Section.DOCS -> BrowseScreen(
                        vm, "Documentários",
                        listOf(RowRef("docs_featured", BruIcons.Globe)) + tmdb(RowRef("tmdb_free_docs", Icons.Filled.PlayArrow)) +
                            listOf(RowRef("docs_prelinger"), RowRef("docs_science"), RowRef("docs_recent")) +
                            tmdb(RowRef("tmdb_pd_docs")),
                        "docs", open, liveCategory = "docs", onPlayChannel = playChannel,
                    )
                    Section.LIVE -> LiveScreen(vm, onPlay = playChannel)
                    Section.MY_LIST -> MyListScreen(vm, open)
                    Section.SEARCH -> SearchScreen(vm, open)
                    Section.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }

    if (showIntro) BruVideoIntro(skip = skipIntro) { showIntro = false }
    }

    detailsTitle?.let { t ->
        DetailsDialog(
            vm = vm,
            title = t,
            onDismiss = { vm.closeDetails() },
            onPlay = { title, playable, fromStart, details ->
                vm.play(title, playable, fromStart, details, onLaunch = launch, onError = toast, onFreeApp = freeApp)
            },
            onFreeApp = freeApp,
        )
    }

    // Foco inicial no "Assistir Agora". Repete algumas vezes porque o herói pode compor depois.
    // Foco inicial no "Assistir Agora"; para assim que o usuário aperta uma tecla, para não puxá-lo de volta.
    LaunchedEffect(Unit) {
        repeat(8) {
            delay(350)
            if (userNavigated) return@LaunchedEffect
            runCatching { playFocus.requestFocus() }
        }
    }

    BackHandler(enabled = detailsTitle == null) {
        if (section != Section.HOME) {
            section = Section.HOME
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBack < 2500) (context as? Activity)?.finish()
            else {
                lastBack = now
                Toast.makeText(context, "Pressione Voltar novamente para sair", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun TopBar(section: Section, online: Boolean, update: UpdateState, onUpdate: () -> Unit, onSelect: (Section) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Com 8 seções, em telas abaixo de ~1500 dp (todas as TVs 720p/1080p) só o item ativo/focado mostra o texto.
        val compact = maxWidth < 1500.dp
        TopBarContent(section, online, compact, update, onUpdate, onSelect)
    }
}

@Composable
private fun UpdateBadge(update: UpdateState, onClick: () -> Unit) {
    val label = when (update) {
        is UpdateState.Available -> "Atualizar"
        is UpdateState.Downloading -> "Baixando ${update.percent}%"
        is UpdateState.NeedsPermission -> "Permitir atualização"
        is UpdateState.ReadyToInstall -> "Instalar"
        else -> return
    }
    BruButton(label, icon = Icons.Filled.Refresh, primary = true, modifier = Modifier.testTag("update_badge"), onClick = onClick)
    Spacer(Modifier.width(10.dp))
}

@Composable
private fun TopBarContent(section: Section, online: Boolean, compact: Boolean, update: UpdateState, onUpdate: () -> Unit, onSelect: (Section) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 28.dp, top = 14.dp, bottom = 6.dp)
            .testTag("topbar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BruLogo(scale = if (compact) 0.8f else 1f)
        Spacer(Modifier.width(if (compact) 10.dp else 24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NavItem(Section.HOME, Icons.Filled.Home, section, compact, onSelect)
            NavItem(Section.RELEASES, BruIcons.NewReleases, section, compact, onSelect)
            NavItem(Section.TRENDING, BruIcons.Trending, section, compact, onSelect)
            NavItem(Section.MOVIES, BruIcons.Movie, section, compact, onSelect)
            NavItem(Section.SERIES, BruIcons.Layers, section, compact, onSelect)
            NavItem(Section.KIDS, Icons.Filled.Face, section, compact, onSelect)
            NavItem(Section.CLASSICS, BruIcons.History, section, compact, onSelect)
            NavItem(Section.DOCS, BruIcons.Globe, section, compact, onSelect)
            NavItem(Section.LIVE, BruIcons.LiveTv, section, compact, onSelect)
            NavItem(Section.MY_LIST, BruIcons.Bookmark, section, compact, onSelect)
        }
        Spacer(Modifier.weight(1f))
        if (!online) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x44FF6B7A))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("offline_badge"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, null, tint = BruColors.Danger, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sem internet", color = Color.White, fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
        }
        UpdateBadge(update, onUpdate)
        NavIcon(Section.SEARCH, Icons.Filled.Search, section, onSelect)
        Spacer(Modifier.width(8.dp))
        NavIcon(Section.SETTINGS, Icons.Filled.Settings, section, onSelect)
    }
}

@Composable
private fun NavItem(target: Section, icon: ImageVector, current: Section, compact: Boolean, onSelect: (Section) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val selected = current == target
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "nav")
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (selected || focused) Modifier.shadow(10.dp, shape, ambientColor = BruColors.Cyan, spotColor = BruColors.Cyan) else Modifier)
            .clip(shape)
            .background(
                when {
                    focused -> BruColors.Cyan.copy(alpha = 0.28f)
                    selected -> BruColors.NavyLight
                    else -> Color.Transparent
                },
            )
            .border(if (selected || focused) 2.dp else 0.dp, if (focused) Color.White else if (selected) BruColors.Cyan else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(target) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("nav_${target.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, target.label, tint = if (selected || focused) Color.White else BruColors.TextDim, modifier = Modifier.size(20.dp))
        if (!compact || selected || focused) {
            Spacer(Modifier.width(6.dp))
            Text(
                target.label,
                color = if (selected || focused) Color.White else BruColors.TextDim,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun NavIcon(target: Section, icon: ImageVector, current: Section, onSelect: (Section) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val selected = current == target
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (focused) BruColors.Cyan.copy(alpha = 0.3f) else if (selected) BruColors.NavyLight else Color(0x22FFFFFF))
            .border(if (focused || selected) 2.dp else 0.dp, if (focused) Color.White else if (selected) BruColors.Cyan else Color.Transparent, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(target) }
            .testTag("nav_${target.name}"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = target.label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
