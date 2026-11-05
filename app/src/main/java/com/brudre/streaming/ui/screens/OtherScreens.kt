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
import com.brudre.streaming.DeviceInfo
import com.brudre.streaming.live.LiveChannel
import com.brudre.streaming.sources.Health
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
    val allFavorites by vm.favorites.collectAsStateWithLifecycle()
    val favorites = allFavorites.filterNot { it.titleId.startsWith(LiveChannel.PREFIX) }
    val liveFavorites = allFavorites.filter { it.titleId.startsWith(LiveChannel.PREFIX) }
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
        if (liveFavorites.isNotEmpty()) {
            item {
                val channels = liveFavorites.mapNotNull { f -> vm.services.live.find(f.titleId.removePrefix(LiveChannel.PREFIX)) }
                ChannelRow(vm, "Canais favoritos", channels, "mylist_live") { ch -> vm.playChannel(ch) { context.startActivity(it) } }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Histórico — Continuar Assistindo")
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
    val channels by vm.channelResults.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { q: String -> keyboard?.hide(); vm.search(q) }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp).testTag("search")) {
        Text("Pesquisa", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Filme, série, desenho, documentário ou canal (ex.: Popeye, Cultura)") },
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
        if (channels.isNotEmpty()) {
            Box(Modifier.padding(start = 0.dp)) {
                Column(Modifier.padding(start = 0.dp)) {
                    Text("Canais ao vivo", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.testTag("search_channels")) {
                        items(channels, key = { it.id }) { ch ->
                            ChannelCard(ch, offline = false, width = 170.dp, modifier = Modifier.testTag("result_channel_${ch.id}")) {
                                vm.playChannel(ch) { context.startActivity(it) }
                            }
                        }
                    }
                }
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
                val playable = r.value.count { !it.isTmdb }
                Text(
                    "${r.value.size} resultados • $playable com fonte gratuita" + if (playable < r.value.size) " • ${r.value.size - playable} só no catálogo (a fonte é procurada ao abrir)" else "",
                    color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.testTag("search_count"),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(210.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize().testTag("search_results"),
                ) {
                    items(r.value, key = { it.id }) { t ->
                        TitleCard(t, caption = listOfNotNull(t.kind.label, t.year, if (t.isTmdb) "catálogo" else "grátis").joinToString(" • "), modifier = Modifier.testTag("result_${t.id}")) { onOpen(t) }
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
        item { SourcesSection(vm) }
        item {
            SettingRow("Abertura cinematográfica", if (s.intro) "Ligada" else "Desligada", "Vinheta de 7 s ao abrir o app (OK ou Voltar pula)", "set_intro") {
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
            SettingRow("Conexões BitTorrent", "${s.torrentConnections}", "Menos conexões = menos memória e banda (vale na próxima reprodução)", "set_connections") {
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
        item { DeviceSection() }
        item {
            Spacer(Modifier.height(24.dp))
            Text(
                "BruDre Streaming ${BuildConfig.VERSION_NAME} — uso doméstico, sem assinaturas nem pagamentos.\n" +
                    "Fontes: Internet Archive (domínio público e Creative Commons), obras abertas publicadas pelos titulares, " +
                    "listas públicas de canais abertos (iptv-org) e listas M3U que você adicionar. " +
                    "O app não usa indexadores de pirataria nem contorna DRM. Títulos sem fonte gratuita aparecem só como catálogo, sem botão Assistir.\n" +
                    "Metadados: este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB.",
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
private fun SettingRow(label: String, value: String, hint: String, tag: String, width: androidx.compose.ui.unit.Dp = 760.dp, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .padding(vertical = 5.dp)
            .width(width)
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

// ------------------------------------------------------ Fontes de conteúdo

@Composable
private fun SourcesSection(vm: MainViewModel) {
    val status by vm.sourceStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 4.dp)) {
        Text("Fontes de conteúdo", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "BruDre Source Manager: cada fonte pode ser ligada ou desligada; se uma falhar, as outras continuam. " +
                "Ordem de reprodução: HTTP/HLS direto → endereços alternativos → BitTorrent oficial.",
            color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.width(760.dp),
        )
        status.forEach { st ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val health = when {
                    !st.enabled -> "Desativada"
                    st.health == Health.ERROR -> "Falha: ${st.lastError ?: "erro"}"
                    st.health == Health.OK || st.health == Health.SLOW -> "${st.health.label}${st.lastLatencyMs?.let { " • ${it} ms" } ?: ""}"
                    else -> st.health.label
                }
                SettingRow(st.name, if (st.enabled) "Ativada" else "Desativada", "$health — ${st.description}", "source_${st.id}", width = if (st.removable) 620.dp else 760.dp) {
                    vm.setSourceEnabled(st.id, !st.enabled)
                }
                if (st.removable) {
                    Spacer(Modifier.width(10.dp))
                    BruButton("Remover", icon = Icons.Filled.Delete, modifier = Modifier.testTag("remove_${st.id}")) { vm.removeCustomList(st.id) }
                }
            }
        }
        SettingRow("Adicionar lista M3U", "Adicionar", "Listas gratuitas e autorizadas (só canais HLS por http/https; executáveis e endereços locais são bloqueados)", "add_m3u") {
            adding = true
        }
        Spacer(Modifier.height(12.dp))
    }
    if (adding) {
        AddListDialog(
            onDismiss = { adding = false },
            onAdd = { name, url ->
                vm.addCustomList(name, url) { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                adding = false
            },
        )
    }
}

@Composable
private fun AddListDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = BruColors.Cyan, unfocusedBorderColor = BruColors.CardBorder,
        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = BruColors.Cyan,
    )
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(BruColors.Navy)
                .border(1.dp, BruColors.CardBorder, RoundedCornerShape(16.dp))
                .padding(24.dp)
                .width(620.dp)
                .testTag("add_m3u_dialog"),
        ) {
            Text("Nova lista M3U", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Use apenas listas de canais gratuitos e autorizados.", color = BruColors.TextFaint, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("Nome") }, colors = fieldColors, modifier = Modifier.fillMaxWidth().testTag("m3u_name"))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                url, { url = it.trim().take(2048) }, singleLine = true, label = { Text("Endereço (https://…/lista.m3u)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), colors = fieldColors, modifier = Modifier.fillMaxWidth().testTag("m3u_url"),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BruButton("Adicionar", primary = true, modifier = Modifier.testTag("m3u_add")) { onAdd(name.ifBlank { "Minha lista" }, url) }
                BruButton("Cancelar", onClick = onDismiss)
            }
        }
    }
}

// ------------------------------------------------------------- Aparelho

@Composable
private fun DeviceSection() {
    val context = LocalContext.current
    val info = remember { runCatching { DeviceInfo.collect(context) }.getOrNull() } ?: return
    Column(Modifier.padding(top = 16.dp).testTag("device_info")) {
        Text("Este aparelho", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(info.summary(), color = BruColors.TextDim, fontSize = 14.sp)
        Text(
            "Memória livre ${info.ramAvailMb} MB${if (info.lowRamDevice) " (modo pouca memória)" else ""} • limite do app ${info.memoryClassMb} MB • " +
                "armazenamento livre ${CacheManager.formatBytes(info.storageFreeMb * 1024 * 1024)} de ${CacheManager.formatBytes(info.storageTotalMb * 1024 * 1024)}",
            color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.width(760.dp),
        )
        Text("Decodificadores: ${info.codecLine()}", color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.width(760.dp).testTag("device_codecs"))
    }
}
