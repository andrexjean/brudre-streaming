package com.brudre.streaming.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brudre.streaming.BuildConfig
import com.brudre.streaming.data.LanguageChoices
import com.brudre.streaming.data.Quality
import com.brudre.streaming.data.Parental
import com.brudre.streaming.data.SourceMode
import com.brudre.streaming.data.StreamingApps
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.db.fraction
import com.brudre.streaming.torrent.CacheManager
import com.brudre.streaming.ui.Load
import com.brudre.streaming.ui.MainViewModel
import com.brudre.streaming.ui.components.BruButton
import com.brudre.streaming.ui.components.BruChip
import com.brudre.streaming.ui.components.PinDialog
import com.brudre.streaming.ui.components.SectionHeader
import com.brudre.streaming.ui.components.TitleCard
import com.brudre.streaming.ui.theme.BruColors
import com.brudre.streaming.ui.theme.BruIcons
import com.brudre.streaming.update.UpdateState

// ------------------------------------------------------------ Minha Lista

@Composable
fun MyListScreen(vm: MainViewModel, onOpen: (Title) -> Unit) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("mylist"), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Text("Minha Lista", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 40.dp, top = 8.dp, bottom = 12.dp))
        }
        item {
            SectionHeader("Salvos (${favorites.size})", BruIcons.Bookmark)
            if (favorites.isEmpty()) {
                Text(
                    "Sua lista está vazia. Abra um título e escolha \"Minha Lista\".",
                    color = BruColors.TextFaint,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(start = 40.dp, bottom = 12.dp).testTag("mylist_empty"),
                )
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 40.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(favorites, key = { it.titleId }) { f ->
                        TitleCard(f.toTitle(), caption = f.year, modifier = Modifier.testTag("fav_${f.titleId}")) { onOpen(f.toTitle()) }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Histórico")
            if (history.isEmpty()) {
                Text("Nada assistido ainda.", color = BruColors.TextFaint, fontSize = 15.sp, modifier = Modifier.padding(start = 40.dp).testTag("history_empty"))
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 40.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(history, key = { it.titleId }) { p ->
                        TitleCard(
                            p.toTitle(),
                            progress = p.fraction(),
                            caption = p.episodeLabel.takeIf { it.isNotBlank() && it != p.titleName },
                            modifier = Modifier.testTag("history_${p.titleId}"),
                        ) { onOpen(p.toTitle()) }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- Pesquisa

private val SUGGESTIONS = listOf(
    "Faroeste" to "western", "Terror" to "horror", "Comédia" to "comedy", "Aventura" to "adventure",
    "Ficção" to "science fiction", "Desenhos" to "cartoon", "Chaplin" to "Chaplin", "Popeye" to "Popeye", "Sherlock" to "Sherlock",
)

@Composable
fun SearchScreen(vm: MainViewModel, onOpen: (Title) -> Unit) {
    var query by rememberSaveable { mutableStateOf(vm.lastQuery) }
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { q: String -> keyboard?.hide(); vm.search(q) }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp).testTag("search")) {
        Text("Pesquisa", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Título, gênero ou palavra-chave (ex.: Popeye)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BruColors.Cyan,
                    unfocusedBorderColor = BruColors.CardBorder,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = BruColors.Cyan,
                ),
                modifier = Modifier.width(520.dp).testTag("search_field"),
            )
            Spacer(Modifier.width(14.dp))
            BruButton("Pesquisar", icon = Icons.Filled.Search, primary = true, modifier = Modifier.testTag("search_button")) { submit(query) }
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(SUGGESTIONS, key = { it.second }) { (label, q) ->
                BruChip(label, modifier = Modifier.testTag("suggest_$q")) { query = q; submit(q) }
            }
        }
        Spacer(Modifier.height(14.dp))
        when (val r = results) {
            null -> Text("Use o teclado da TV ou escolha uma sugestão acima.", color = BruColors.TextFaint, fontSize = 15.sp)
            is Load.Loading -> Text("Pesquisando…", color = BruColors.TextDim, fontSize = 15.sp, modifier = Modifier.testTag("search_loading"))
            is Load.Error -> Text(r.message, color = BruColors.Danger, fontSize = 15.sp, modifier = Modifier.testTag("search_error"))
            is Load.Ok -> if (r.value.isEmpty()) {
                Text("Nenhum título encontrado para \"${vm.lastQuery}\".", color = BruColors.TextFaint, fontSize = 15.sp, modifier = Modifier.testTag("search_empty"))
            } else {
                Text("${r.value.size} resultados", color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.testTag("search_count"))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(210.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize().testTag("search_results"),
                ) {
                    items(r.value, key = { it.id }) { t ->
                        TitleCard(t, caption = listOfNotNull(t.kind.label, t.year).joinToString(" • "), modifier = Modifier.testTag("result_${t.id}")) { onOpen(t) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------- Configurações

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val usage by vm.cacheUsage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshCacheUsage() }
    fun <T> next(list: List<T>, current: T): T = list[(list.indexOf(current) + 1).mod(list.size)]
    var pinMode by remember { mutableStateOf<PinMode?>(null) }
    var pendingRating by remember { mutableStateOf(false) }

    when (pinMode) {
        PinMode.CREATE, PinMode.NEW -> PinDialog(
            title = "Nova senha dos pais",
            subtitle = "Escolha 4 números. Guarde bem: ela libera títulos acima da classificação.",
            onDismiss = { pinMode = null },
            onSubmit = { pin ->
                vm.changePin(pin)
                pinMode = null
                Toast.makeText(context, "Senha salva", Toast.LENGTH_SHORT).show()
                null
            },
        )
        PinMode.VERIFY, PinMode.VERIFY_FOR_CHANGE -> PinDialog(
            title = "Senha dos pais",
            subtitle = "Digite a senha atual para continuar.",
            onDismiss = { pinMode = null; pendingRating = false },
            onSubmit = { pin ->
                vm.submitPin(pin)?.let { return@PinDialog it }
                if (pinMode == PinMode.VERIFY_FOR_CHANGE) pinMode = PinMode.NEW
                else {
                    pinMode = null
                    if (pendingRating) {
                        pendingRating = false
                        vm.updateSettings { v -> v.copy(maxRating = next(Parental.RATINGS, v.maxRating)) }
                    }
                }
                null
            },
        )
        null -> Unit
    }

    LazyColumn(Modifier.fillMaxSize().testTag("settings"), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 48.dp)) {
        item { Text("Configurações", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)) }
        item {
            Text("Controle dos pais", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Pornografia e conteúdo explícito são bloqueados sempre, sem senha que libere. A senha protege títulos acima da classificação escolhida.",
                color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.width(760.dp),
            )
            SettingRow(
                "Senha dos pais (PIN)",
                if (s.pinHash.isEmpty()) "Criar" else "Trocar",
                if (s.pinHash.isEmpty()) "Nenhuma senha definida ainda" else "Pedida para títulos acima do limite e para mudar estas opções",
                "set_pin",
            ) {
                if (s.pinHash.isEmpty()) pinMode = PinMode.CREATE else pinMode = PinMode.VERIFY_FOR_CHANGE
            }
            SettingRow(
                "Liberado sem senha até",
                if (s.maxRating == "L") "Livre" else "${s.maxRating} anos",
                "Títulos com classificação maior pedem a senha (Livre, 10, 12, 14, 16, 18)",
                "set_max_rating",
            ) {
                val applyNext = { vm.updateSettings { v -> v.copy(maxRating = next(Parental.RATINGS, v.maxRating)) } }
                if (s.pinHash.isEmpty() || vm.isUnlocked()) applyNext() else { pendingRating = true; pinMode = PinMode.VERIFY }
            }
            Spacer(Modifier.height(12.dp))
        }
        item {
            Text("Meus streamings", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Marque os serviços que vocês assinam. Eles aparecem primeiro em \"Onde assistir\" e o \"Assistir Agora\" abre direto neles.",
                color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.width(760.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp), modifier = Modifier.testTag("my_services")) {
                items(StreamingApps.SELECTABLE, key = { it.key }) { app ->
                    val on = app.key in s.myServices
                    BruChip(if (on) "✓ ${app.label}" else app.label, selected = on, modifier = Modifier.testTag("service_${app.key}")) {
                        vm.updateSettings { v -> v.copy(myServices = if (on) v.myServices - app.key else v.myServices + app.key) }
                    }
                }
            }
        }
        item {
            SettingRow(
                "Lançamentos: só o que eu assino",
                if (s.onlyMine) "Sim" else "Não",
                if (s.myServices.isEmpty()) "Marque seus streamings acima para usar este filtro" else "Esconde títulos que estão só em serviços que vocês não assinam",
                "set_only_mine",
            ) { vm.updateSettings { it.copy(onlyMine = !it.onlyMine) } }
        }
        item {
            SettingRow("Abertura animada", if (s.intro) "Ligada" else "Desligada", "Animação do logo ao abrir o app (qualquer tecla pula)", "set_intro") {
                vm.updateSettings { it.copy(intro = !it.intro) }
            }
        }
        item {
            SettingRow("Fonte de reprodução", s.sourceMode.label, s.sourceMode.description, "set_source") {
                vm.updateSettings { it.copy(sourceMode = next(SourceMode.entries.toList(), it.sourceMode)) }
            }
        }
        item {
            SettingRow("Qualidade", s.quality.label, "Leve economiza memória e banda no Mi Stick", "set_quality") {
                vm.updateSettings { it.copy(quality = next(Quality.entries.toList(), it.quality)) }
            }
        }
        item {
            val audio = LanguageChoices.audio
            SettingRow("Idioma do áudio", audio.firstOrNull { it.code == s.audioLanguage }?.label ?: "Original", "Usado quando o vídeo tiver mais de uma faixa", "set_audio") {
                vm.updateSettings { v -> v.copy(audioLanguage = next(audio.map { it.code }, v.audioLanguage)) }
            }
        }
        item {
            val subs = LanguageChoices.subtitles
            SettingRow("Legendas", subs.firstOrNull { it.code == s.subtitleLanguage }?.label ?: "Desligadas", "Também podem ser trocadas no player (botão CC)", "set_subtitles") {
                vm.updateSettings { v -> v.copy(subtitleLanguage = next(subs.map { it.code }, v.subtitleLanguage)) }
            }
        }
        item {
            SettingRow("Limite do cache de vídeo", CacheManager.formatBytes(s.cacheLimitMb * 1024L * 1024L), "Espaço máximo para dados temporários do torrent", "set_cache_limit") {
                vm.updateSettings { it.copy(cacheLimitMb = next(LanguageChoices.cacheMb, it.cacheLimitMb)) }
            }
        }
        item {
            SettingRow("Conexões BitTorrent", "${s.torrentConnections}", "Menos conexões = menos memória (vale na próxima reprodução)", "set_connections") {
                vm.updateSettings { it.copy(torrentConnections = next(LanguageChoices.connections, it.torrentConnections)) }
            }
        }
        item {
            val upd by vm.update.collectAsStateWithLifecycle()
            val (value, hint) = when (val u = upd) {
                is UpdateState.Available -> "Atualizar para ${u.release.tag}" to "Nova versão disponível. OK baixa e abre a instalação."
                is UpdateState.Downloading -> "Baixando ${u.percent}%" to "Aguarde o download terminar"
                is UpdateState.NeedsPermission -> "Permitir" to "O Android pede para liberar \"instalar apps desconhecidos\" para o BruDre (só na primeira vez). Depois volte e aperte OK."
                is UpdateState.ReadyToInstall -> "Instalar" to "Confirme \"Atualizar\" na tela do Android"
                UpdateState.Checking -> "Verificando…" to "Consultando a última versão"
                UpdateState.UpToDate -> "Atualizado" to "Você já tem a versão mais recente. OK verifica de novo."
                is UpdateState.Error -> "Tentar de novo" to u.message
                UpdateState.Idle -> "Verificar" to "OK procura uma versão nova"
            }
            Spacer(Modifier.height(16.dp))
            SettingRow("Atualização do app (versão ${BuildConfig.VERSION_NAME})", value, hint, "set_update") { vm.startUpdate() }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                BruButton("Limpar dados temporários", icon = Icons.Filled.Delete, modifier = Modifier.testTag("btn_clear_cache")) {
                    vm.clearCache { freed -> Toast.makeText(context, "Liberados ${CacheManager.formatBytes(freed)}", Toast.LENGTH_LONG).show() }
                }
                Text("Em uso: ${CacheManager.formatBytes(usage)}", color = BruColors.TextDim, fontSize = 14.sp, modifier = Modifier.testTag("cache_usage"))
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            BruButton("Limpar histórico e progresso", icon = Icons.Filled.Delete, modifier = Modifier.testTag("btn_clear_history")) {
                vm.clearHistory { Toast.makeText(context, "Histórico apagado", Toast.LENGTH_SHORT).show() }
            }
        }
        item {
            Spacer(Modifier.height(24.dp))
            Text(
                "BruDre Streaming ${BuildConfig.VERSION_NAME} — uso doméstico.\n" +
                    "Catálogo: Internet Archive (acervo público) e obras abertas publicadas pelos titulares. " +
                    "O app não usa indexadores de terceiros nem contorna DRM. Títulos sem fonte autorizada aparecem como indisponíveis.\n" +
                    "Lançamentos: catálogo do TMDB; disponibilidade por streaming fornecida pela JustWatch. " +
                    "Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB.",
                color = BruColors.TextFaint,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.width(760.dp),
            )
        }
    }
}

private enum class PinMode { CREATE, NEW, VERIFY, VERIFY_FOR_CHANGE }

@Composable
private fun SettingRow(label: String, value: String, hint: String, tag: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .padding(vertical = 5.dp)
            .width(760.dp)
            .clip(shape)
            .background(if (focused) BruColors.NavyLight else BruColors.Card)
            .border(if (focused) 2.dp else 1.dp, if (focused) BruColors.Cyan else BruColors.CardBorder, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(hint, color = BruColors.TextFaint, fontSize = 13.sp)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (focused) BruColors.Cyan.copy(alpha = 0.25f) else Color(0x22FFFFFF))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(value, color = BruColors.Cyan, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("${tag}_value"))
        }
    }
}
