package com.brudre.streaming.ui.screens

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.brudre.streaming.sources.UserSource
import com.brudre.streaming.ui.MainViewModel
import com.brudre.streaming.ui.components.BruButton
import com.brudre.streaming.ui.components.FullScreenDialogWindow
import com.brudre.streaming.ui.theme.BruColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Configurações → Gerenciar fontes: adicionar (formato identificado automaticamente), editar, excluir,
 * ativar/desativar, testar, atualizar, prioridade e importar/exportar. Tudo pelo controle remoto.
 */
@Composable
fun SourceManagerScreen(vm: MainViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FullScreenDialogWindow()
        val context = LocalContext.current
        val sources by vm.userSources.collectAsStateWithLifecycle()
        val testing by vm.testingSources.collectAsStateWithLifecycle()
        var editing by remember { mutableStateOf<UserSource?>(null) }
        var adding by remember { mutableStateOf(false) }
        var importing by remember { mutableStateOf(false) }
        val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        val first = remember { FocusRequester() }

        Box(Modifier.fillMaxSize().background(BruColors.Bg).testTag("source_manager")) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 48.dp, end = 40.dp, top = 32.dp, bottom = 40.dp)) {
                item {
                    Text("Gerenciar fontes", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Formatos: magnet, .torrent, vídeo HTTPS, HLS (.m3u8), playlist IPTV M3U, catálogo JSON do BruDre e RSS. " +
                            "O formato é identificado sozinho e a fonte só é salva depois de validada. A ordem da lista é a prioridade de reprodução. " +
                            "Use apenas fontes de conteúdo com distribuição autorizada; no BitTorrent seu IP fica visível aos outros participantes.",
                        color = BruColors.TextFaint, fontSize = 13.sp, modifier = Modifier.width(860.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BruButton("Adicionar fonte", icon = Icons.Filled.Add, primary = true, modifier = Modifier.focusRequester(first).testTag("src_add")) { adding = true }
                        BruButton("Testar todas", icon = Icons.Filled.Refresh, modifier = Modifier.testTag("src_test_all")) { vm.testAllSources(toast) }
                        BruButton("Exportar", modifier = Modifier.testTag("src_export")) { vm.exportSources(toast) }
                        BruButton("Importar", modifier = Modifier.testTag("src_import")) { importing = true }
                        BruButton("Fechar", modifier = Modifier.testTag("src_close"), onClick = onDismiss)
                    }
                    Spacer(Modifier.height(18.dp))
                    if (sources.isEmpty()) {
                        Text("Nenhuma fonte cadastrada ainda.", color = BruColors.TextDim, fontSize = 16.sp, modifier = Modifier.testTag("src_empty"))
                    }
                }
                items(sources, key = { it.id }) { s ->
                    SourceCard(
                        s, index = sources.indexOf(s), total = sources.size, testing = s.id in testing,
                        onToggle = { vm.toggleSource(s.id) },
                        onTest = { vm.testSource(s.id, toast) },
                        onRefresh = { vm.refreshSource(s.id, toast) },
                        onUp = { vm.moveSource(s.id, -1) },
                        onDown = { vm.moveSource(s.id, +1) },
                        onEdit = { editing = s },
                        onDelete = { vm.deleteSource(s.id); toast("${s.name} excluída") },
                    )
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

        if (adding || editing != null) {
            val e = editing
            SourceDialog(
                title = if (e == null) "Adicionar fonte" else "Editar fonte",
                initialName = e?.name ?: "",
                initialUrl = e?.url ?: "https://",
                onDismiss = { adding = false; editing = null },
                onSave = { name, url, done ->
                    vm.saveSource(e?.id, name, url) { ok, msg ->
                        toast(msg)
                        done(ok, msg)
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
                onSave = { _, url, done -> vm.importSources(url) { msg -> toast(msg); done(true, msg); importing = false } },
            )
        }
    }
}

@Composable
private fun SourceCard(
    s: UserSource,
    index: Int,
    total: Int,
    testing: Boolean,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onRefresh: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember(s.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .padding(vertical = 6.dp)
            .width(880.dp)
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
                else -> "${if (t.ok) "✓" else "✗"} ${t.message} • ${SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(t.at))}"
            },
            color = when { testing -> BruColors.Cyan; t?.ok == true -> Color(0xFF6BE3A1); t != null -> BruColors.Danger; else -> BruColors.TextDim },
            fontSize = 13.sp, modifier = Modifier.testTag("src_status_${s.id}"),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item { BruButton(if (s.enabled) "Ativada" else "Desativada", primary = s.enabled, modifier = Modifier.testTag("src_toggle_${s.id}"), onClick = onToggle) }
            item { BruButton("Testar", icon = Icons.Filled.Refresh, modifier = Modifier.testTag("src_test_${s.id}"), onClick = onTest) }
            item { BruButton("Atualizar", modifier = Modifier.testTag("src_refresh_${s.id}"), onClick = onRefresh) }
            if (index > 0) item { BruButton("Subir", icon = Icons.Filled.KeyboardArrowUp, modifier = Modifier.testTag("src_up_${s.id}"), onClick = onUp) }
            if (index < total - 1) item { BruButton("Descer", icon = Icons.Filled.KeyboardArrowDown, modifier = Modifier.testTag("src_down_${s.id}"), onClick = onDown) }
            item { BruButton("Editar", icon = Icons.Filled.Edit, modifier = Modifier.testTag("src_edit_${s.id}"), onClick = onEdit) }
            item {
                BruButton(if (confirmDelete) "Confirmar exclusão" else "Excluir", icon = Icons.Filled.Delete, modifier = Modifier.testTag("src_delete_${s.id}")) {
                    if (confirmDelete) onDelete() else confirmDelete = true
                }
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
    hint: String = "Cole o endereço (https://…) ou o magnet. O BruDre identifica o formato, testa e só salva se a fonte estiver válida.",
    action: String = "Identificar e salvar",
    onDismiss: () -> Unit,
    onSave: (String, String, (Boolean, String) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = BruColors.Cyan, unfocusedBorderColor = BruColors.CardBorder,
        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = BruColors.Cyan,
    )
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(BruColors.Navy).border(1.dp, BruColors.CardBorder, RoundedCornerShape(16.dp))
                .padding(24.dp).width(660.dp).testTag("src_dialog"),
        ) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(hint, color = BruColors.TextFaint, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            if (showName) {
                OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text("Nome (opcional)") }, colors = colors, modifier = Modifier.fillMaxWidth().testTag("src_name"))
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                url, { url = it.trim().take(4096) }, singleLine = true, label = { Text("Endereço ou magnet") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), colors = colors, modifier = Modifier.fillMaxWidth().testTag("src_url"),
            )
            message?.let { Text(it, color = BruColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).testTag("src_dialog_msg")) }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BruButton(if (busy) "Verificando…" else action, primary = true, modifier = Modifier.testTag("src_save")) {
                    if (!busy) {
                        busy = true; message = null
                        onSave(name, url) { ok, msg -> busy = false; if (!ok) message = msg }
                    }
                }
                BruButton("Cancelar", onClick = onDismiss)
            }
        }
    }
}
