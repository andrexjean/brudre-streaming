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
import com.brudre.streaming.ui.components.BruLogo
import com.brudre.streaming.ui.screens.BrowseScreen
import com.brudre.streaming.ui.screens.DetailsDialog
import com.brudre.streaming.ui.screens.HomeScreen
import com.brudre.streaming.ui.screens.MyListScreen
import com.brudre.streaming.ui.screens.RowRef
import com.brudre.streaming.ui.screens.SearchScreen
import com.brudre.streaming.ui.screens.SettingsScreen
import com.brudre.streaming.ui.theme.BruColors
import com.brudre.streaming.ui.theme.BruDreTheme
import com.brudre.streaming.ui.theme.BruIcons
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
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
    val playFocus = remember { FocusRequester() }
    var lastBack by remember { mutableLongStateOf(0L) }

    val toast: (String) -> Unit = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    val playTitle: (Title) -> Unit = { t ->
        vm.play(t, onLaunch = { context.startActivity(it) }, onError = toast)
    }
    val open: (Title) -> Unit = { t -> vm.openDetails(t) }

    AppBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(section, online) { section = it }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    Section.HOME -> HomeScreen(vm, playFocus, open, playTitle)
                    Section.MOVIES -> BrowseScreen(
                        vm, "Filmes",
                        listOf(RowRef("featured", BruIcons.Movie), RowRef("curated_torrent"), RowRef("scifi"), RowRef("comedy"), RowRef("noir"), RowRef("silent"), RowRef("recent")),
                        "movies", open,
                    )
                    Section.SERIES -> BrowseScreen(vm, "Séries", listOf(RowRef("series", BruIcons.Layers), RowRef("series_recent")), "series", open)
                    Section.KIDS -> BrowseScreen(
                        vm, "Infantil",
                        listOf(RowRef("curated_kids", Icons.Filled.Star), RowRef("kids", Icons.Filled.Face), RowRef("kids_animation")),
                        "kids", open,
                    )
                    Section.MY_LIST -> MyListScreen(vm, open)
                    Section.SEARCH -> SearchScreen(vm, open)
                    Section.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }

    detailsTitle?.let { t ->
        DetailsDialog(
            vm = vm,
            title = t,
            onDismiss = { vm.closeDetails() },
            onPlay = { title, playable, fromStart, details ->
                vm.play(title, playable, fromStart, details, onLaunch = { context.startActivity(it) }, onError = toast)
            },
        )
    }

    LaunchedEffect(Unit) {
        delay(400)
        runCatching { playFocus.requestFocus() }
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
private fun TopBar(section: Section, online: Boolean, onSelect: (Section) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 28.dp, top = 14.dp, bottom = 6.dp)
            .testTag("topbar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BruLogo()
        Spacer(Modifier.width(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NavItem(Section.HOME, Icons.Filled.Home, section, onSelect)
            NavItem(Section.MOVIES, BruIcons.Movie, section, onSelect)
            NavItem(Section.SERIES, BruIcons.Layers, section, onSelect)
            NavItem(Section.KIDS, Icons.Filled.Face, section, onSelect)
            NavItem(Section.MY_LIST, BruIcons.Bookmark, section, onSelect)
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
        NavIcon(Section.SEARCH, Icons.Filled.Search, section, onSelect)
        Spacer(Modifier.width(8.dp))
        NavIcon(Section.SETTINGS, Icons.Filled.Settings, section, onSelect)
    }
}

@Composable
private fun NavItem(target: Section, icon: ImageVector, current: Section, onSelect: (Section) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val selected = current == target
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "nav")
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (selected || focused) 10.dp else 0.dp, shape, ambientColor = BruColors.Cyan, spotColor = BruColors.Cyan)
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
        Icon(icon, null, tint = if (selected || focused) Color.White else BruColors.TextDim, modifier = Modifier.size(20.dp))
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
