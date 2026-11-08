package com.brudre.streaming.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brudre.streaming.data.LanguageChoices
import com.brudre.streaming.data.TorrentCleanup
import com.brudre.streaming.sources.Health
import com.brudre.streaming.sources.ProviderStatus
import com.brudre.streaming.sources.SourceType
import com.brudre.streaming.sources.UserSource
import com.brudre.streaming.torrent.CacheManager
import com.brudre.streaming.torrent.TorrentEntry
import com.brudre.streaming.ui.MainViewModel
import com.brudre.streaming.ui.components.BruButton
import com.brudre.streaming.ui.components.BruChip
import com.brudre.streaming.ui.components.FullScreenDialogWindow
import com.brudre.streaming.ui.theme.BruColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Seções do gerenciador (Configurações → Gerenciar fontes). */
enum class SourceSection(val label: String, val tag: String) {
    MINE("Minhas fontes", "mine"), LIBRARY("Biblioteca", "library"), CATALOGS("Catálogos", "catalogs"), TORRENT("BitTorrent", "torrent"),
    NATIVE("Fontes nativas", "native"), DIAG("Diagnóstico", "diag"),
}

private val CATALOG_TYPES = setOf(SourceType.CATALOG_JSON, SourceType.RSS, SourceType.SEARCH, SourceType.M3U)
private fun dateOf(at: Long) = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(at))

/**
 * Gerenciar fontes, em seções navegáveis pelo D-pad: Minhas fontes, Catálogos, BitTorrent,
 * Fontes nativas e Diagnóstico. Adicionar identifica o formato sozinho; "Salvar sem validar" mantém a fonte
 * desativada até um teste dar certo.
 */
@Composable
fun SourceManagerScreen(vm: MainViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FullScreenDialogWindow()
        val context = LocalContext.current
        val sources by vm.userSources.collectAsStateWithLifecycle()
        val testing by vm.testingSources.collectAsStateWithLifecycle()
        var section by rememberSaveable { mutableStateOf(SourceSection.MINE) }
        var editing by remember { mutableStateOf<UserSource?>(null) }
        var adding by remember { mutableStateOf(false) }
        var importing by remember { mutableStateOf(false) }
        var pastingMagnet by remember { mutableStateOf(false) }
        var linking by remember { mutableStateOf<TorrentEntry?>(null) }
        val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        val first = remember { FocusRequester() }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importTorrentUri(it, open = false, onResult = toast) } }

        Box(Modifier.fillMaxSize().background(BruColors.Bg).testTag("source_manager")) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 48.dp, end = 40.dp, top = 28.dp, bottom = 40.dp)) {
                item(key = "head") {
                    Text("Gerenciar fontes", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Formatos: magnet, .torrent, vídeo HTTPS, HLS (.m3u8), playlist IPTV M3U, catálogo JSON do BruDre, RSS e API de pesquisa do BruDre " +
                            "(endereço com {query}). O formato é identificado sozinho. A ordem da lista é a prioridade. " +
                            "Use apenas fontes com distribuição autorizada; no BitTorrent seu IP fica visível aos outros participantes.",
                        color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.width(900.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(SourceSection.entries, key = { it.name }) { s ->
                            BruChip(s.label, selected = s == section, modifier = Modifier.then(if (s == SourceSection.MINE) Modifier.focusRequester(first) else Modifier).testTag("src_tab_${s.tag}")) { section = s }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BruButton("Adicionar fonte", icon = Icons.Filled.Add, primary = true, modifier = Modifier.testTag("src_add")) { adding = true }
                        BruButton("Testar todas", icon = Icons.Filled.Refresh, modifier = Modifier.testTag("src_test_all")) { vm.testAllSources(toast) }
                        BruButton("Exportar", modifier = Modifier.testTag("src_export")) { vm.exportSources(toast) }
                        BruButton("Importar", modifier = Modifier.testTag("src_import")) { importing = true }
                        BruButton("Fechar", modifier = Modifier.testTag("src_close"), onClick = onDismiss)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                when (section) {
                    SourceSection.MINE -> sourceList(this, sources, sources, testing, vm, toast, { editing = it }, "Nenhuma fonte cadastrada ainda.")
                    SourceSection.CATALOGS -> sourceList(this, sources.filter { it.type in CATALOG_TYPES }, sources, testing, vm, toast, { editing = it },
                        "Nenhum catálogo. Adicione um catálogo JSON/RSS, uma playlist M3U ou uma API de pesquisa (endereço com {query}).")
                    SourceSection.TORRENT -> {
                        item(key = "tor_actions") {
                            TorrentActions(vm, onPaste = { pastingMagnet = true }, onImport = {
                                try { picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream")) } catch (e: android.content.ActivityNotFoundException) {
                                    toast("Este aparelho não tem seletor de arquivos. Abra o .torrent por um gerenciador de arquivos (\"Abrir com BruDre\"), compartilhe-o com o BruDre ou use um endereço https://.")
                                }
                            })
                        }
                        sourceList(this, sources.filter { it.type.isTorrent }, sources, testing, vm, toast, { editing = it }, null)
                        item(key = "tor_hist_head") {
                            Text("Histórico de torrents", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                        }
                        torrentHistory(this, vm, onLink = { linking = it })
                    }
                    SourceSection.LIBRARY -> libraryItems(this, vm, sources, toast)
                    SourceSection.NATIVE -> item(key = "native") { NativeSection(vm) }
                    SourceSection.DIAG -> item(key = "diag") { DiagnosticsSection(vm, sources, toast) }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

        if (adding || editing != null) {
            val e = editing
            AdvancedSourceDialog(
                source = e,
                hasCredential = e?.let { vm.hasCredential(it.id) } ?: false,
                onDismiss = { adding = false; editing = null },
                onSave = { form, done ->
                    vm.saveSource(e?.id, form) { ok, msg, detail ->
                        if (ok) toast(msg)
                        done(ok, msg, detail)
                        if (ok) { adding = false; editing = null }
                    }
                },
                onReadCert = { u, done -> vm.readLocalCertificate(u, done) },
                onSaveUnvalidated = { form, done ->
                    vm.saveSourceUnvalidated(e?.id, form.name, form.url) { ok, msg, detail ->
                        if (ok) toast(msg)
                        done(ok, msg, detail)
                        if (ok) { adding = false; editing = null }
                    }
                },
            )
        }
        if (importing) {
            SourceDialog(
                title = "Importar fontes",
                initialName = "",
                initialUrl = "https://",
                showName = false,
                hint = "Deixe \"https://\" para importar o arquivo exportado neste aparelho, ou informe o endereço de um arquivo brudre-fontes.json.",
                action = "Importar",
                onDismiss = { importing = false },
                onSave = { _, url, done -> vm.importSources(url) { msg -> toast(msg); done(true, msg, null); importing = false } },
            )
        }
        if (pastingMagnet) {
            SourceDialog(
                title = "Adicionar magnet ou .torrent",
                initialName = "",
                initialUrl = "magnet:?xt=urn:btih:",
                showName = false,
                hint = "Cole o link magnet ou o endereço https:// de um arquivo .torrent. Ele entra no histórico; ao abrir, você escolhe o vídeo/episódio.",
                action = "Adicionar",
                onDismiss = { pastingMagnet = false },
                onSave = { _, url, done ->
                    val msg = if (url.startsWith("magnet:", true)) vm.addMagnet(url, open = false) else vm.addTorrentUrlOnly(url)
                    val ok = msg.startsWith("Magnet adicionado") || msg.startsWith(".torrent adicionado")
                    if (ok) { toast(msg); pastingMagnet = false }
                    done(ok, msg, null)
                },
            )
        }
        linking?.let { t ->
            LinkDialog(t, onDismiss = { linking = null }) { name, year -> vm.linkTorrent(t.hash, name, year); linking = null; toast("Associação salva") }
        }
    }
}

private fun sourceList(
    scope: androidx.compose.foundation.lazy.LazyListScope,
    shown: List<UserSource>,
    all: List<UserSource>,
    testing: Set<String>,
    vm: MainViewModel,
    toast: (String) -> Unit,
    onEdit: (UserSource) -> Unit,
    empty: String?,
) {
    if (shown.isEmpty() && empty != null) scope.item(key = "empty") { Text(empty, color = BruColors.TextDim, fontSize = 16.sp, modifier = Modifier.testTag("src_empty")) }
    val dups = vm.services.sourceStore.duplicates()
    scope.items(shown, key = { it.id }) { s ->
        val index = all.indexOf(s)
        SourceCard(
            s, index = index, total = all.size, testing = s.id in testing, duplicate = s.id in dups,
            onToggle = { vm.toggleSource(s.id, toast) },
            onTest = { vm.testSource(s.id, toast) },
            onRefresh = { vm.refreshSource(s.id, toast) },
            onUp = { vm.moveSource(s.id, -1) },
            onDown = { vm.moveSource(s.id, +1) },
            onEdit = { onEdit(s) },
            onDelete = { vm.deleteSource(s.id); toast("${s.name} excluída") },
        )
    }
}

@Composable
private fun SourceCard(
    s: UserSource,
    index: Int,
    total: Int,
    testing: Boolean,
    duplicate: Boolean = false,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onRefresh: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember(s.id) { mutableStateOf(false) }
    var showDetail by remember(s.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .padding(vertical = 6.dp)
            .width(900.dp)
            .clip(shape)
            .background(if (s.enabled) BruColors.Card else Color(0xFF0B1227))
            .border(1.dp, if (s.enabled) BruColors.CardBorder else Color(0xFF1A2443), shape)
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .testTag("src_card_${s.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}.", color = BruColors.Cyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(s.name, color = if (s.enabled) Color.White else BruColors.TextFaint, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (s.pending) {
                Text("SEM VALIDAÇÃO", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(6.dp)).background(BruColors.Badge).padding(horizontal = 8.dp, vertical = 3.dp).testTag("src_pending_${s.id}"))
            } else if (s.validatedAt > 0 && !s.enabled) {
                Text("VALIDADA — ATIVE", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF6BE3A1)).padding(horizontal = 8.dp, vertical = 3.dp).testTag("src_validated_${s.id}"))
            }
            if (duplicate) {
                Text("DUPLICADA", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(6.dp)).background(BruColors.Danger).padding(horizontal = 8.dp, vertical = 3.dp).testTag("src_dup_${s.id}"))
            }
            Text(
                s.type.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xCC2F4C9C)).padding(horizontal = 8.dp, vertical = 3.dp).testTag("src_type_${s.id}"),
            )
        }
        Text(s.url, color = BruColors.TextFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val t = s.lastTest
        Text(
            when {
                testing -> "Testando…"
                t == null -> "Ainda não testada"
                else -> "${if (t.ok) "✓" else "✗"} ${t.message} • verificada em ${dateOf(t.at)}"
            },
            color = when { testing -> BruColors.Cyan; t?.ok == true -> Color(0xFF6BE3A1); t != null -> BruColors.Danger; else -> BruColors.TextDim },
            fontSize = 13.sp, modifier = Modifier.testTag("src_status_${s.id}"),
        )
        Text(
            listOfNotNull(
                "Prioridade ${index + 1} de $total",
                if (s.enabled) "ativa" else "inativa",
                t?.items?.takeIf { it > 0 }?.let { "$it resultado(s)" },
                "uso: ${s.stats.summary}",
            ).joinToString(" • "),
            color = BruColors.TextDim, fontSize = 12.sp, modifier = Modifier.testTag("src_meta_${s.id}"),
        )
        Text("Capacidades: ${s.capabilities.summary}", color = BruColors.TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("src_caps_${s.id}"))
        s.stats.lastError?.let { Text("Última falha de uso: $it", color = BruColors.TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (showDetail && t?.detail != null) Text(t.detail, color = BruColors.TextDim, fontSize = 11.sp, modifier = Modifier.testTag("src_detail_${s.id}"))
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item { BruButton(if (s.enabled) "Ativada" else "Desativada", primary = s.enabled, modifier = Modifier.testTag("src_toggle_${s.id}"), onClick = onToggle) }
            item { BruButton("Testar", icon = Icons.Filled.Refresh, modifier = Modifier.testTag("src_test_${s.id}"), onClick = onTest) }
            item { BruButton("Atualizar (redetecta)", modifier = Modifier.testTag("src_refresh_${s.id}"), onClick = onRefresh) }
            if (index > 0) item { BruButton("Subir", icon = Icons.Filled.KeyboardArrowUp, modifier = Modifier.testTag("src_up_${s.id}"), onClick = onUp) }
            if (index < total - 1) item { BruButton("Descer", icon = Icons.Filled.KeyboardArrowDown, modifier = Modifier.testTag("src_down_${s.id}"), onClick = onDown) }
            item { BruButton("Editar", icon = Icons.Filled.Edit, modifier = Modifier.testTag("src_edit_${s.id}"), onClick = onEdit) }
            if (t?.detail != null) item { BruButton(if (showDetail) "Ocultar detalhes" else "Detalhes técnicos", modifier = Modifier.testTag("src_details_${s.id}")) { showDetail = !showDetail } }
            item {
                BruButton(if (confirmDelete) "Confirmar exclusão" else "Excluir", icon = Icons.Filled.Delete, modifier = Modifier.testTag("src_delete_${s.id}")) {
                    if (confirmDelete) onDelete() else confirmDelete = true
                }
            }
        }
    }
}

@Composable
private fun TorrentActions(vm: MainViewModel, onPaste: () -> Unit, onImport: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Column(Modifier.width(900.dp)) {
        Text(
            "Reproduza seus próprios torrents sem cadastrá-los num catálogo: cole um magnet, importe um .torrent ou use \"Compartilhar\"/\"Abrir com\" em outro app. " +
                "Torrents com vários vídeos mostram a lista de episódios; só o escolhido é baixado.",
            color = BruColors.TextFaint, fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BruButton("Colar magnet ou .torrent", icon = Icons.Filled.Add, primary = true, modifier = Modifier.testTag("tor_paste"), onClick = onPaste)
            BruButton("Importar arquivo .torrent", modifier = Modifier.testTag("tor_import"), onClick = onImport)
        }
        Spacer(Modifier.height(12.dp))
        Text("Depois de assistir", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(TorrentCleanup.entries, key = { it.name }) { c ->
                BruChip(c.label, selected = settings.torrentCleanup == c, modifier = Modifier.testTag("tor_cleanup_${c.name}")) { vm.updateSettings { it.copy(torrentCleanup = c) } }
            }
        }
        Text(settings.torrentCleanup.description, color = BruColors.TextDim, fontSize = 12.sp)
        Text("Limite do armazenamento temporário", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(LanguageChoices.cacheMb, key = { it }) { mb ->
                BruChip(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB", selected = settings.cacheLimitMb == mb, modifier = Modifier.testTag("tor_cache_$mb")) { vm.updateSettings { it.copy(cacheLimitMb = mb) } }
            }
        }
    }
}

private fun torrentHistory(scope: androidx.compose.foundation.lazy.LazyListScope, vm: MainViewModel, onLink: (TorrentEntry) -> Unit) {
    scope.item(key = "tor_hist") {
        val list by vm.torrentHistory.collectAsStateWithLifecycle()
        if (list.isEmpty()) Text("Nenhum torrent adicionado ainda.", color = BruColors.TextDim, fontSize = 15.sp, modifier = Modifier.testTag("tor_hist_empty"))
        Column {
            list.forEach { e -> TorrentCard(e, onOpen = { vm.openTorrentEntry(e) }, onLink = { onLink(e) }, onRemove = { vm.removeTorrent(e.hash) }) }
        }
    }
}

@Composable
private fun TorrentCard(e: TorrentEntry, onOpen: () -> Unit, onLink: () -> Unit, onRemove: () -> Unit) {
    var confirm by remember(e.hash) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.padding(vertical = 5.dp).width(900.dp).clip(shape).background(BruColors.Card).border(1.dp, BruColors.CardBorder, shape)
            .padding(horizontal = 18.dp, vertical = 10.dp).testTag("tor_card_${e.hash}"),
    ) {
        Text(e.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                when { e.localFile != null -> ".torrent importado"; e.magnet != null -> "magnet"; else -> ".torrent remoto" },
                e.videoCount.takeIf { it > 0 }?.let { "$it vídeo(s)" },
                e.totalSize.takeIf { it > 0 }?.let { CacheManager.formatBytes(it) },
                "adicionado ${dateOf(e.addedAt)}",
                e.lastPlayedAt.takeIf { it > 0 }?.let { "assistido ${dateOf(it)}" },
                e.linkedName?.let { "associado a: $it${e.linkedYear?.let { y -> " ($y)" } ?: ""}" },
            ).joinToString(" • "),
            color = BruColors.TextFaint, fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item { BruButton("Arquivos e assistir", icon = Icons.Filled.PlayArrow, primary = true, modifier = Modifier.testTag("tor_open_${e.hash}"), onClick = onOpen) }
            item { BruButton(if (e.linkedName == null) "Associar a filme/série" else "Mudar associação", modifier = Modifier.testTag("tor_link_${e.hash}"), onClick = onLink) }
            item { BruButton(if (confirm) "Confirmar remoção" else "Remover", icon = Icons.Filled.Delete, modifier = Modifier.testTag("tor_remove_${e.hash}")) { if (confirm) onRemove() else confirm = true } }
        }
    }
}

@Composable
private fun NativeSection(vm: MainViewModel) {
    val all by vm.sourceStatus.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val natives = all.filter { !it.removable }
    Column(Modifier.width(900.dp)) {
        Text("Ordem entre fontes", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Quando a mesma obra existe numa fonte nativa e numa fonte sua, qual tentar primeiro.", color = BruColors.TextFaint, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            BruChip("Nativas primeiro", selected = !settings.userSourcesFirst, modifier = Modifier.testTag("order_native_first")) { vm.updateSettings { it.copy(userSourcesFirst = false) } }
            BruChip("Minhas fontes primeiro", selected = settings.userSourcesFirst, modifier = Modifier.testTag("order_user_first")) { vm.updateSettings { it.copy(userSourcesFirst = true) } }
        }
        Text("Preferências da escolha automática", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text(
            "Idioma de áudio e de legenda seguem Configurações (atual: áudio ${settings.audioLanguage.ifBlank { "original" }}, legenda ${settings.subtitleLanguage}). " +
                "Quando a fonte não informa idioma ou resolução, isso conta como desconhecido — nunca é presumido.",
            color = BruColors.TextFaint, fontSize = 13.sp,
        )
        Text("Resolução preferida", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(LanguageChoices.resolutions, key = { it.code }) { c ->
                BruChip(c.label, selected = settings.preferredResolution.toString() == c.code, modifier = Modifier.testTag("pref_res_${c.code}")) { vm.updateSettings { it.copy(preferredResolution = c.code.toInt()) } }
            }
        }
        val mine by vm.userSources.collectAsStateWithLifecycle()
        Text("Fonte preferida", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            item(key = "none") { BruChip("Nenhuma", selected = settings.preferredSourceId.isEmpty(), modifier = Modifier.testTag("pref_src_none")) { vm.updateSettings { it.copy(preferredSourceId = "") } } }
            items(mine.filter { it.enabled }, key = { it.id }) { u ->
                BruChip(u.name, selected = settings.preferredSourceId == u.id, modifier = Modifier.testTag("pref_src_${u.id}")) { vm.updateSettings { it.copy(preferredSourceId = u.id) } }
            }
        }
        Text("Revalidação automática das fontes (ao abrir o app)", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(LanguageChoices.revalidate, key = { it.code }) { c ->
                BruChip(c.label, selected = settings.revalidateHours.toString() == c.code, modifier = Modifier.testTag("pref_reval_${c.code}")) { vm.updateSettings { it.copy(revalidateHours = c.code.toInt()) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        natives.forEach { p -> NativeCard(p) { vm.setSourceEnabled(p.id, !p.enabled) } }
    }
}

@Composable
private fun NativeCard(p: ProviderStatus, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.padding(vertical = 5.dp).width(900.dp).clip(shape).background(BruColors.Card).border(1.dp, BruColors.CardBorder, shape)
            .padding(horizontal = 18.dp, vertical = 10.dp).testTag("native_${p.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(p.description, color = BruColors.TextFaint, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(p.health.label, p.lastLatencyMs?.let { "${it} ms" }, "✓ ${p.successes} • ✗ ${p.failures}", p.lastError?.let { "último erro: $it" }).joinToString(" • "),
                color = if (p.health == Health.ERROR) BruColors.Danger else BruColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        BruButton(if (p.enabled) "Ativada" else "Desativada", primary = p.enabled, modifier = Modifier.testTag("native_toggle_${p.id}"), onClick = onToggle)
    }
}

@Composable
private fun DiagnosticsSection(vm: MainViewModel, sources: List<UserSource>, toast: (String) -> Unit) {
    val online by vm.online.collectAsStateWithLifecycle()
    val cache by vm.cacheUsage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshCacheUsage() }
    val tested = sources.filter { it.lastTest != null }
    Column(Modifier.width(900.dp).testTag("diag")) {
        Text("Conectividade", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(if (online) "Internet: conectada" else "Internet: SEM CONEXÃO", color = if (online) Color(0xFF6BE3A1) else BruColors.Danger, fontSize = 14.sp)
        Text("Dados temporários (vídeo, imagens, listas): ${CacheManager.formatBytes(cache)}", color = BruColors.TextDim, fontSize = 13.sp)
        Text("Certificados HTTPS do BitTorrent: ${com.brudre.streaming.torrent.TlsTrust.status}", color = BruColors.TextDim, fontSize = 13.sp, modifier = Modifier.testTag("diag_tls"))
        Spacer(Modifier.height(10.dp))
        HomologationPanel(vm, toast)
        Spacer(Modifier.height(10.dp))
        Text("Validação e estatísticas", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "${sources.size} fontes • ${sources.count { it.enabled }} ativas • ${sources.count { it.pending }} aguardando validação • " +
                "${tested.count { it.lastTest!!.ok }} OK e ${tested.count { !it.lastTest!!.ok }} com falha no último teste • ${vm.services.sourceStore.duplicates().size} duplicada(s)",
            color = BruColors.TextDim, fontSize = 13.sp, modifier = Modifier.testTag("diag_summary"),
        )
        Spacer(Modifier.height(8.dp))
        BruButton("Testar todas agora", icon = Icons.Filled.Refresh, primary = true, modifier = Modifier.testTag("diag_test_all")) { vm.testAllSources(toast) }
        Spacer(Modifier.height(10.dp))
        sources.forEach { s ->
            val t = s.lastTest
            Column(Modifier.padding(vertical = 4.dp).testTag("diag_${s.id}")) {
                Text("${s.name} — ${s.type.label}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        t?.let { (if (it.ok) "✓ " else "✗ ") + it.message + " (${dateOf(it.at)})" } ?: "nunca testada",
                        "uso real: ${s.stats.summary}",
                        s.stats.lastError?.let { "último erro de uso: $it" },
                    ).joinToString(" • "),
                    color = if (t?.ok == false) BruColors.Danger else BruColors.TextDim, fontSize = 12.sp,
                )
                t?.detail?.takeIf { !t.ok }?.let { Text("Detalhes técnicos: $it", color = BruColors.TextFaint, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** Homologação do aparelho: medições reais do uso, checklist do D-pad, teste da rede BitTorrent e relatório exportável. */
@Composable
private fun HomologationPanel(vm: MainViewModel, toast: (String) -> Unit) {
    val H = com.brudre.streaming.data.Homologation
    var report by remember { mutableStateOf<String?>(null) }
    var probe by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Text("Homologação do aparelho", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Text(
        "Use o app normalmente por alguns dias (abrir, assistir filmes e episódios, BitTorrent e HTTP, uma sessão longa) e gere o relatório. " +
            "Só vale como homologação quando gerado no próprio aparelho — o emulador não homologa o Mi Stick.",
        color = BruColors.TextFaint, fontSize = 12.sp,
    )
    Text("Controle remoto (D-pad): marque o que você conferiu", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    H.DPAD_ITEMS.forEach { (k, label) ->
        key(k, tick) {
            val state = H.dpad(k)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                BruChip("OK", selected = state == true, modifier = Modifier.testTag("hom_ok_$k")) { H.setDpad(k, if (state == true) null else true); tick++ }
                Spacer(Modifier.width(6.dp))
                BruChip("Falhou", selected = state == false, modifier = Modifier.testTag("hom_fail_$k")) { H.setDpad(k, if (state == false) null else false); tick++ }
                Spacer(Modifier.width(10.dp))
                Text(label, color = BruColors.TextDim, fontSize = 12.sp)
            }
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            BruButton("Gerar relatório", primary = true, modifier = Modifier.testTag("hom_report")) {
                scope.launch {
                    val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { H.export() } }
                    f.onSuccess { file ->
                        report = file.readText()
                        toast("Relatório salvo em ${file.path}")
                        runCatching {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_SUBJECT, "Homologação BruDre Streaming").putExtra(android.content.Intent.EXTRA_TEXT, report)
                            vm.launcher?.invoke(android.content.Intent.createChooser(send, "Compartilhar relatório"))
                        }
                    }.onFailure { toast("Não foi possível gerar: ${it.message}") }
                }
            }
        }
        item {
            BruButton(if (probe == "testando") "Testando (40 s)…" else "Testar rede BitTorrent", modifier = Modifier.testTag("hom_bt")) {
                if (probe == "testando") return@BruButton
                probe = "testando"
                scope.launch {
                    probe = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            // .torrent de filmes abertos (Blender/WebTorrent, licença livre): só conta participantes, nada é baixado.
                            val urls = listOf("https://webtorrent.io/torrents/sintel.torrent", "https://webtorrent.io/torrents/big-buck-bunny.torrent")
                            val bytes = urls.mapNotNull { u -> runCatching { vm.services.inspector.fetchBytes(u, com.brudre.streaming.torrent.TorrentContent.MAX_TORRENT_BYTES.toLong()) }.getOrNull() }
                            if (bytes.isEmpty()) error("não foi possível baixar os .torrent de teste")
                            val r = vm.services.torrent.probePeers(bytes, 40)
                            vm.services.torrent.shutdownIfIdle()
                            r.joinToString("; ") { "${it.name}: ${it.connected} conectados, ${it.known} conhecidos, enxame ${it.swarm}" }
                        }.getOrElse { "Falhou: ${it.message}" }
                    }
                }
            }
        }
        item { BruButton("Zerar medições", modifier = Modifier.testTag("hom_reset")) { H.reset(); report = null; tick++; toast("Medições zeradas") } }
    }
    probe?.takeIf { it != "testando" }?.let { Text("Rede BitTorrent: $it", color = BruColors.TextDim, fontSize = 12.sp, modifier = Modifier.testTag("hom_bt_result")) }
    report?.let { Text(it, color = BruColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp).testTag("hom_text")) }
}

@Composable
private fun LinkDialog(e: TorrentEntry, onDismiss: () -> Unit, onSave: (String?, String?) -> Unit) {
    var name by remember { mutableStateOf(e.linkedName ?: e.name.replace(Regex("[._]"), " ").replace(Regex("(?i)\\b(19|20)\\d{2}\\b.*"), "").trim()) }
    var year by remember { mutableStateOf(e.linkedYear ?: Regex("\\b(19|20)\\d{2}\\b").find(e.name)?.value ?: "") }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = BruColors.Cyan, unfocusedBorderColor = BruColors.CardBorder, focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = BruColors.Cyan)
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(BruColors.Navy).border(1.dp, BruColors.CardBorder, RoundedCornerShape(16.dp)).padding(24.dp).width(620.dp).testTag("tor_link_dialog")) {
            Text("Associar a filme ou série", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("O torrent passa a aparecer como fonte na tela do título com esse nome e ano (opcional).", color = BruColors.TextFaint, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(name, { name = it.take(120) }, singleLine = true, label = { Text("Nome do filme ou série") }, colors = colors, modifier = Modifier.fillMaxWidth().testTag("tor_link_name"))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("Ano (opcional)") }, colors = colors, modifier = Modifier.fillMaxWidth().testTag("tor_link_year"))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BruButton("Salvar", primary = true, modifier = Modifier.testTag("tor_link_save")) { onSave(name, year) }
                BruButton("Remover associação") { onSave(null, null) }
                BruButton("Cancelar", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun SourceDialog(
    title: String,
    initialName: String,
    initialUrl: String,
    showName: Boolean = true,
    hint: String = "Cole o endereço (https://…), o magnet ou um modelo de API de pesquisa com {query}. O BruDre identifica o formato e testa antes de salvar.",
    action: String = "Identificar e salvar",
    onDismiss: () -> Unit,
    onSave: (String, String, (Boolean, String, String?) -> Unit) -> Unit,
    onSaveUnvalidated: ((String, String, (Boolean, String, String?) -> Unit) -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var showDetail by remember { mutableStateOf(false) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = BruColors.Cyan, unfocusedBorderColor = BruColors.CardBorder,
        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = BruColors.Cyan,
    )
    val run: () -> Unit = {
        if (!busy) {
            busy = true; message = null; detail = null; showDetail = false
            onSave(name, url) { ok, msg, d -> busy = false; if (!ok) { message = msg; detail = d } }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(BruColors.Navy).border(1.dp, BruColors.CardBorder, RoundedCornerShape(16.dp))
                .padding(24.dp).width(700.dp).testTag("src_dialog"),
        ) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(hint, color = BruColors.TextFaint, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            if (showName) {
                OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text("Nome (opcional)") }, colors = colors, modifier = Modifier.fillMaxWidth().testTag("src_name"))
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                url, { url = it.trim().take(4096) }, singleLine = true, label = { Text("Endereço, magnet ou modelo de pesquisa") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), colors = colors, modifier = Modifier.fillMaxWidth().testTag("src_url"),
            )
            message?.let { Text(it, color = BruColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).testTag("src_dialog_msg")) }
            if (showDetail) detail?.let { Text(it, color = BruColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp).testTag("src_dialog_detail")) }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BruButton(if (busy) "Verificando…" else if (message != null) "Testar novamente" else action, primary = true, modifier = Modifier.testTag("src_save"), onClick = run)
                if (message != null && onSaveUnvalidated != null) {
                    BruButton("Salvar sem validar", modifier = Modifier.testTag("src_save_unvalidated")) {
                        if (!busy) { busy = true; onSaveUnvalidated(name, url) { ok, msg, d -> busy = false; if (!ok) { message = msg; detail = d } } }
                    }
                }
                if (message != null && detail != null) {
                    BruButton(if (showDetail) "Ocultar detalhes" else "Detalhes técnicos", modifier = Modifier.testTag("src_dialog_details")) { showDetail = !showDetail }
                }
                BruButton("Cancelar", onClick = onDismiss)
            }
        }
    }
}

/**
 * Cadastro avançado: nome, endereço, protocolo (automático ou manual), autenticação + credencial (cifrada,
 * nunca exibida), categorias, tempo-limite, limite de resultados e estado. Tudo navegável pelo D-pad.
 */
@Composable
private fun AdvancedSourceDialog(
    source: UserSource?,
    hasCredential: Boolean,
    onDismiss: () -> Unit,
    onSave: (MainViewModel.SourceForm, (Boolean, String, String?) -> Unit) -> Unit,
    onSaveUnvalidated: (MainViewModel.SourceForm, (Boolean, String, String?) -> Unit) -> Unit,
    onReadCert: (String, (Boolean, String, String?) -> Unit) -> Unit = { _, _ -> },
) {
    var name by remember { mutableStateOf(source?.name ?: "") }
    var localPin by remember { mutableStateOf(source?.localPin) }
    var certText by remember { mutableStateOf<String?>(null) }
    var readPin by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf(source?.url ?: "https://") }
    var advanced by remember { mutableStateOf(source != null && (source.manualType || source.auth != com.brudre.streaming.sources.AuthMethod.NONE || source.categories.isNotEmpty())) }
    var type by remember { mutableStateOf(if (source?.manualType == true) source.type else null) }
    var auth by remember { mutableStateOf(source?.auth ?: com.brudre.streaming.sources.AuthMethod.NONE) }
    var key by remember { mutableStateOf("") }
    var timeout by remember { mutableStateOf(source?.timeoutSec ?: UserSource.DEFAULT_TIMEOUT_S) }
    var limit by remember { mutableStateOf(source?.resultLimit ?: UserSource.DEFAULT_LIMIT) }
    var categories by remember { mutableStateOf(source?.categories ?: emptySet()) }
    var enabled by remember { mutableStateOf(source?.enabled ?: true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var showDetail by remember { mutableStateOf(false) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = BruColors.Cyan, unfocusedBorderColor = BruColors.CardBorder,
        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = BruColors.Cyan,
    )
    fun form() = MainViewModel.SourceForm(name, url, type, auth, key.takeIf { it.isNotEmpty() }, timeout, limit, categories, enabled, localPin)
    val done: (Boolean, String, String?) -> Unit = { ok, msg, d -> busy = false; if (!ok) { message = msg; detail = d } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LazyColumn(
            Modifier.clip(RoundedCornerShape(16.dp)).background(BruColors.Navy).border(1.dp, BruColors.CardBorder, RoundedCornerShape(16.dp))
                .width(860.dp).padding(24.dp).testTag("src_dialog"),
        ) {
            item {
                Text(if (source == null) "Adicionar fonte" else "Editar fonte", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Endereço https://, magnet, descrição OpenSearch, descritor REST/GraphQL, indexador Torznab/Newznab (a apikey colada no endereço vai cifrada) ou modelo com {query}. O formato é identificado e testado antes de salvar.",
                    color = BruColors.TextFaint, fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text("Nome da fonte") }, colors = colors, modifier = Modifier.fillMaxWidth().testTag("src_name"))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    url, { url = it.trim().take(4096) }, singleLine = true, label = { Text("Endereço") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), colors = colors, modifier = Modifier.fillMaxWidth().testTag("src_url"),
                )
                if (com.brudre.streaming.sources.UserSourcesJson.mayBeLocal(url)) {
                    Column(Modifier.padding(top = 10.dp).border(1.dp, BruColors.CardBorder, RoundedCornerShape(10.dp)).padding(12.dp).testTag("src_local")) {
                        Text("Servidor local (rede privada)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            localPin?.let { "Certificado confirmado: ${com.brudre.streaming.sources.LocalServers.pretty(it).take(29)}…  Só este certificado é aceito neste endereço." }
                                ?: "Só HTTPS. Leia o certificado do servidor e confira a impressão digital SHA-256 com a do seu servidor antes de confirmar.",
                            color = BruColors.TextFaint, fontSize = 12.sp, modifier = Modifier.testTag("src_local_state"),
                        )
                        certText?.let { Text(it, color = BruColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).testTag("src_local_cert")) }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 8.dp)) {
                            item {
                                BruButton("Ler certificado", modifier = Modifier.testTag("src_local_read")) {
                                    certText = "Lendo…"; readPin = null
                                    onReadCert(url) { ok, text, pin -> certText = text; readPin = if (ok) pin else null }
                                }
                            }
                            readPin?.let { p ->
                                item { BruButton("Confirmo: é o meu servidor", primary = true, modifier = Modifier.testTag("src_local_confirm")) { localPin = p; readPin = null } }
                            }
                            if (localPin != null) item { BruButton("Remover confirmação", modifier = Modifier.testTag("src_local_forget")) { localPin = null } }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                BruChip(if (advanced) "Ocultar opções avançadas" else "Opções avançadas", selected = advanced, modifier = Modifier.testTag("src_advanced")) { advanced = !advanced }
            }
            if (advanced) {
                item { ChoiceRow("Protocolo", listOf<Pair<String, com.brudre.streaming.sources.SourceType?>>("Automático" to null) + SourceType.entries.map { it.label to it }, type, "src_type") { type = it } }
                item { ChoiceRow("Autenticação", com.brudre.streaming.sources.AuthMethod.entries.map { it.label to it }, auth, "src_auth") { auth = it } }
                if (auth != com.brudre.streaming.sources.AuthMethod.NONE) item {
                    OutlinedTextField(
                        key, { key = it.trim().take(512) }, singleLine = true,
                        label = { Text(if (hasCredential) "Credencial (guardada — deixe vazio para manter)" else "Credencial (chave ou token)") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        colors = colors, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("src_key"),
                    )
                    Text("Cifrada pelo Android Keystore; nunca aparece na tela nem na exportação.", color = BruColors.TextFaint, fontSize = 11.sp)
                }
                item {
                    Text("Categorias (vazio = todas)", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(UserSource.CATEGORIES, key = { it }) { c ->
                            BruChip(UserSource.CATEGORY_LABELS[c] ?: c, selected = c in categories, modifier = Modifier.testTag("src_cat_$c")) {
                                categories = if (c in categories) categories - c else categories + c
                            }
                        }
                    }
                }
                item { ChoiceRow("Tempo-limite por consulta", listOf(5, 12, 20, 30).map { "$it s" to it }, timeout, "src_timeout") { timeout = it } }
                item { ChoiceRow("Resultados por página", listOf(20, 50, 100).map { "$it" to it }, limit, "src_limit") { limit = it } }
                item { ChoiceRow("Estado", listOf("Ativa" to true, "Inativa" to false), enabled, "src_enabled") { enabled = it } }
            }
            item {
                message?.let { Text(it, color = BruColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).testTag("src_dialog_msg")) }
                if (showDetail) detail?.let { Text(it, color = BruColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp).testTag("src_dialog_detail")) }
                Spacer(Modifier.height(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        BruButton(if (busy) "Verificando…" else if (message != null) "Testar novamente" else "Identificar, testar e salvar", primary = true, modifier = Modifier.testTag("src_save")) {
                            if (!busy) { busy = true; message = null; detail = null; showDetail = false; onSave(form(), done) }
                        }
                    }
                    if (message != null) item {
                        BruButton("Salvar sem validar", modifier = Modifier.testTag("src_save_unvalidated")) { if (!busy) { busy = true; onSaveUnvalidated(form(), done) } }
                    }
                    if (message != null && detail != null) item {
                        BruButton(if (showDetail) "Ocultar detalhes" else "Detalhes técnicos", modifier = Modifier.testTag("src_dialog_details")) { showDetail = !showDetail }
                    }
                    item { BruButton("Cancelar", onClick = onDismiss) }
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<String, T>>, selected: T, tag: String, onPick: (T) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, color = Color.White, fontSize = 15.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(options.size) { i ->
                val (text, value) = options[i]
                BruChip(text, selected = value == selected, modifier = Modifier.testTag("${tag}_$i")) { onPick(value) }
            }
        }
    }
}

/** Biblioteca de fontes candidatas avaliadas: serviços e protocolos contados separadamente; "Adicionar" só onde há integração. */
private fun libraryItems(scope: androidx.compose.foundation.lazy.LazyListScope, vm: MainViewModel, mine: List<UserSource>, toast: (String) -> Unit) {
    val entries = com.brudre.streaming.sources.IndexerRegistry.entries(vm.getApplication<android.app.Application>())
    val services = entries.count { it.isService }
    val integrated = entries.count { it.integration == com.brudre.streaming.sources.IndexerRegistry.Integration.INTEGRADO }
    scope.item(key = "lib_head") {
        Text(
            "${entries.size} entradas avaliadas: $services serviços e ${entries.size - services} protocolos • $integrated integrados. " +
                "Serviços de software livre distribuem sistemas (não vídeo) e servem só para testar o motor BitTorrent.",
            color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.width(900.dp).testTag("lib_summary"),
        )
    }
    com.brudre.streaming.sources.IndexerRegistry.GROUPS.forEach { (group, label) ->
        val list = entries.filter { it.group == group }
        if (list.isEmpty()) return@forEach
        scope.item(key = "lib_g_$group") { Text(label, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) }
        scope.items(list, key = { "lib_${it.id}" }) { e -> LibraryCard(e, added = e.addUrl != null && mine.any { UserSource.normalizedUrl(it.url) == UserSource.normalizedUrl(e.addUrl) }) { finished ->
            vm.saveSource(null, MainViewModel.SourceForm(e.name, e.addUrl!!)) { ok, msg, _ -> finished(); toast(if (ok) "${e.name}: adicionada. $msg" else "${e.name}: $msg") }
        } }
    }
}

@Composable
private fun LibraryCard(e: com.brudre.streaming.sources.IndexerRegistry.Entry, added: Boolean, onAdd: (finished: () -> Unit) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    var busy by remember(e.id) { mutableStateOf(false) }
    Row(
        Modifier.padding(vertical = 4.dp).width(900.dp).clip(shape).background(BruColors.Card).border(1.dp, BruColors.CardBorder, shape)
            .padding(horizontal = 18.dp, vertical = 10.dp).testTag("lib_card_${e.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text(e.integration.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(
                        when (e.integration) {
                            com.brudre.streaming.sources.IndexerRegistry.Integration.INTEGRADO -> Color(0xCC1F8A4C)
                            com.brudre.streaming.sources.IndexerRegistry.Integration.NAO_INTEGRADO, com.brudre.streaming.sources.IndexerRegistry.Integration.NAO_SUPORTADO, com.brudre.streaming.sources.IndexerRegistry.Integration.INDISPONIVEL -> Color(0xCC8A2A2A)
                            else -> Color(0xCC5A5F73)
                        },
                    ).padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Text(listOfNotNull(e.protocol, e.search.ifBlank { null }?.let { "pesquisa: $it" }, e.torrent.ifBlank { null }?.let { "torrent: $it" }).joinToString(" • "),
                color = BruColors.TextDim, fontSize = 12.sp)
            Text(e.note, color = BruColors.TextFaint, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            e.restrictions?.let { Text("Restrições: $it", color = BruColors.TextFaint, fontSize = 11.sp) }
            listOfNotNull(e.availability?.let { "Disponibilidade: $it" }, e.api?.let { "API/feed: $it" }, e.adapter?.let { "Adaptadores: $it" },
                e.condition?.let { "Condição para integrar: $it" }, e.test?.let { "Teste: $it" }).forEach { Text(it, color = BruColors.TextFaint, fontSize = 11.sp) }
        }
        if (e.addUrl != null) {
            Spacer(Modifier.width(12.dp))
            BruButton(if (added) "Já adicionada" else if (busy) "Testando…" else "Adicionar", primary = !added, modifier = Modifier.testTag("lib_add_${e.id}")) {
                if (!added && !busy) { busy = true; onAdd { busy = false } }
            }
        }
    }
}
