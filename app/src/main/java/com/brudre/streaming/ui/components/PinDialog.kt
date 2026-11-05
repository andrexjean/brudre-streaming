package com.brudre.streaming.ui.components

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.brudre.streaming.ui.theme.BruColors
import kotlinx.coroutines.delay

/**
 * Teclado numérico para o PIN do controle dos pais, operável só com o controle remoto
 * (setas + OK) ou com as teclas numéricas, se o controle tiver.
 * [onSubmit] retorna a mensagem de erro, ou null se o PIN foi aceito.
 */
@Composable
fun PinDialog(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> String?,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    fun press(d: String) {
        error = null
        if (pin.length < 4) pin += d
        if (pin.length == 4) {
            val e = onSubmit(pin)
            if (e != null) { error = e; pin = "" }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FullScreenDialogWindow()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xEE050814))
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val digit = DIGIT_KEYS[e.key]
                    if (digit != null) { press(digit); true }
                    else if (e.key == Key.Backspace || e.key == Key.Delete) { pin = pin.dropLast(1); true }
                    else false
                }
                .testTag("pin_dialog"),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(subtitle, color = BruColors.TextDim, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.width(520.dp))
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(4) { i ->
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (i < pin.length) BruColors.Cyan else Color(0x33FFFFFF)),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(error ?: " ", color = BruColors.Danger, fontSize = 14.sp, modifier = Modifier.testTag("pin_error"))
                Spacer(Modifier.height(10.dp))
                val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✕"))
                rows.forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        r.forEach { label ->
                            PinKey(
                                label,
                                Modifier
                                    .then(if (label == "1") Modifier.focusRequester(first) else Modifier)
                                    .testTag("pin_key_$label"),
                            ) {
                                when (label) {
                                    "⌫" -> pin = pin.dropLast(1)
                                    "✕" -> onDismiss()
                                    else -> press(label)
                                }
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            delay(150)
            runCatching { first.requestFocus() }
        }
    }
}

@Composable
private fun PinKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .size(width = 86.dp, height = 56.dp)
            .clip(shape)
            .background(if (focused) BruColors.Cyan.copy(alpha = 0.35f) else BruColors.Card)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else BruColors.CardBorder, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

private val DIGIT_KEYS = mapOf(
    Key.Zero to "0", Key.One to "1", Key.Two to "2", Key.Three to "3", Key.Four to "4",
    Key.Five to "5", Key.Six to "6", Key.Seven to "7", Key.Eight to "8", Key.Nine to "9",
    Key.NumPad0 to "0", Key.NumPad1 to "1", Key.NumPad2 to "2", Key.NumPad3 to "3", Key.NumPad4 to "4",
    Key.NumPad5 to "5", Key.NumPad6 to "6", Key.NumPad7 to "7", Key.NumPad8 to "8", Key.NumPad9 to "9",
)

/** Faz a janela do Dialog ocupar a tela inteira da TV (sem margens nem faixas do conteúdo de trás). */
@Composable
fun FullScreenDialogWindow() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@SideEffect
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.decorView.setPadding(0, 0, 0, 0)
        window.attributes = window.attributes.apply {
            gravity = android.view.Gravity.FILL
            horizontalMargin = 0f
            verticalMargin = 0f
        }
    }
}
