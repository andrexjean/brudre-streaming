package com.brudre.streaming.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.brudre.streaming.data.Title
import com.brudre.streaming.ui.Load
import com.brudre.streaming.ui.theme.BruColors

// ------------------------------------------------------------------ fundo

@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BruColors.Navy, BruColors.Bg, BruColors.Bg)))
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(BruColors.Neon.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.05f),
                        radius = size.width * 0.45f,
                    ),
                    radius = size.width * 0.45f,
                    center = Offset(size.width * 0.92f, size.height * 0.05f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(BruColors.Lilac.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(0f, size.height),
                        radius = size.width * 0.4f,
                    ),
                    radius = size.width * 0.4f,
                    center = Offset(0f, size.height),
                )
            },
        content = content,
    )
}

// ------------------------------------------------------------------ logo

@Composable
fun TvGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.075f
        drawLine(BruColors.Cyan, Offset(w * 0.5f, h * 0.30f), Offset(w * 0.28f, h * 0.04f), stroke, StrokeCap.Round)
        drawLine(BruColors.Cyan, Offset(w * 0.5f, h * 0.30f), Offset(w * 0.74f, h * 0.02f), stroke, StrokeCap.Round)
        drawRoundRect(
            color = BruColors.Cyan,
            topLeft = Offset(stroke / 2, h * 0.30f),
            size = Size(w - stroke, h * 0.68f),
            cornerRadius = CornerRadius(w * 0.16f),
            style = Stroke(stroke),
        )
        val cx = w * 0.53f
        val cy = h * 0.64f
        val tri = Path().apply {
            moveTo(cx - w * 0.13f, cy - h * 0.16f)
            lineTo(cx - w * 0.13f, cy + h * 0.16f)
            lineTo(cx + w * 0.17f, cy)
            close()
        }
        drawPath(tri, BruColors.Neon)
    }
}

/** Logotipo: TV à direita, sem sobrepor "Streaming". */
@Composable
fun BruLogo(modifier: Modifier = Modifier, scale: Float = 1f) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) { append("Bru") }
                    withStyle(SpanStyle(color = BruColors.Cyan)) { append("Dre") }
                },
                fontSize = (28 * scale).sp,
                fontWeight = FontWeight.Black,
                lineHeight = (28 * scale).sp,
            )
            Text("Streaming", color = BruColors.TextDim, fontSize = (11 * scale).sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.width((8 * scale).dp))
        TvGlyph(Modifier.size((34 * scale).dp))
    }
}

// --------------------------------------------------------------- botões

@Composable
fun BruButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "btnScale")
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        primary && focused -> Brush.horizontalGradient(listOf(BruColors.Cyan, BruColors.Neon))
        primary -> Brush.horizontalGradient(listOf(BruColors.Neon, Color(0xFF1A62D8)))
        focused -> Brush.horizontalGradient(listOf(Color(0xFF2B3D6E), Color(0xFF253461)))
        else -> Brush.horizontalGradient(listOf(Color(0x332B3D6E), Color(0x332B3D6E)))
    }
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.5f }
            .then(if (focused) Modifier.shadow(14.dp, shape, ambientColor = BruColors.Cyan, spotColor = BruColors.Cyan) else Modifier)
            .clip(shape)
            .background(bg, shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else BruColors.CardBorder, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun BruChip(text: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(
                when {
                    focused -> BruColors.Cyan.copy(alpha = 0.35f)
                    selected -> BruColors.Neon.copy(alpha = 0.35f)
                    else -> BruColors.Card
                },
                shape,
            )
            .border(if (focused || selected) 2.dp else 1.dp, if (focused) Color.White else if (selected) BruColors.Cyan else BruColors.CardBorder, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
fun RatingBadge(rating: String?) {
    val label = rating ?: "NC"
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (rating == null) BruColors.CardBorder else BruColors.Badge)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(label, color = if (rating == null) BruColors.TextDim else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- cards

/** Card 16:9 com foco luminoso; opcionalmente mostra barra de progresso. */
@Composable
fun TitleCard(
    title: Title,
    modifier: Modifier = Modifier,
    width: Dp = 210.dp,
    progress: Float? = null,
    caption: String? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(150), label = "cardScale")
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .width(width)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .then(if (focused) Modifier.shadow(18.dp, shape, ambientColor = BruColors.Cyan, spotColor = BruColors.Cyan) else Modifier)
                .clip(shape)
                .background(Brush.linearGradient(listOf(BruColors.NavyLight, BruColors.Card)))
                .border(if (focused) 3.dp else 1.dp, if (focused) BruColors.Cyan else BruColors.CardBorder, shape),
        ) {
            Text(
                title.name,
                color = BruColors.TextDim,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(12.dp),
            )
            AsyncImage(
                model = title.posterUrl,
                contentDescription = title.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.55f)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6050814)))),
            )
            Text(
                title.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, end = 10.dp, bottom = if (progress != null) 16.dp else 8.dp),
            )
            // Diferencia "com fonte gratuita" de "só catálogo" (a fonte é procurada ao abrir).
            val badge = when {
                title.isTmdb && title.license == com.brudre.streaming.data.TmdbSource.FREE_LICENSE -> "GRÁTIS" to Color(0xCC0E6E8C)
                title.isTmdb -> "CATÁLOGO" to Color(0xCC3A4466)
                title.isArchive || title.isCurated -> "GRÁTIS" to Color(0xCC0E6E8C)
                title.id.startsWith("feed:") -> "FONTE EXTERNA" to Color(0xCC5B3FB0)
                else -> null
            }
            if (badge != null && progress == null) {
                Text(
                    badge.first, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(5.dp))
                        .background(badge.second).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            val langTitle = remember(title) { com.brudre.streaming.data.VerifiedLanguages.apply(title) }
            langTitle.languageBadge?.let { lb ->
                Text(
                    lb, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (langTitle.ptAudio) Color(0xCC1F8A4C) else if (langTitle.ptSubtitles) Color(0xCC2F4C9C) else Color(0xCC5A5F73))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (progress != null) {
                ProgressBar(
                    progress,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
        if (caption != null) {
            Text(
                caption,
                color = if (focused) Color.White else BruColors.TextFaint,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp),
            )
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x55FFFFFF)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .background(Brush.horizontalGradient(listOf(BruColors.Neon, BruColors.Cyan))),
        )
    }
}

// ---------------------------------------------------------------- linhas

@Composable
fun SectionHeader(label: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 40.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = BruColors.Cyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TitleRow(
    label: String,
    state: Load<List<Title>>,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tag: String = label,
    onRetry: () -> Unit,
    /** Catálogo paginado: botão "Carregar mais" no fim da linha (carrega a próxima página sob demanda). */
    onMore: (() -> Unit)? = null,
    onOpen: (Title) -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = 18.dp).testTag("row_$tag")) {
        SectionHeader(label, icon)
        when (state) {
            is Load.Loading -> SkeletonRow()
            is Load.Error -> Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.message, color = BruColors.Danger, fontSize = 15.sp)
                Spacer(Modifier.width(16.dp))
                BruButton("Tentar novamente", icon = Icons.Filled.Refresh, modifier = Modifier.testTag("retry_$tag"), onClick = onRetry)
            }
            is Load.Ok -> if (state.value.isEmpty()) {
                Text("Nada encontrado nesta seção.", color = BruColors.TextFaint, modifier = Modifier.padding(start = 40.dp))
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 40.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(state.value, key = { it.id }) { t ->
                        TitleCard(
                            t,
                            caption = listOfNotNull(t.year, t.genres.firstOrNull()).joinToString(" • ").ifBlank { null },
                            modifier = Modifier.testTag("card_${tag}_${t.id}"),
                        ) { onOpen(t) }
                    }
                    if (onMore != null) item(key = "more_$tag") {
                        Box(Modifier.width(210.dp).aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                            BruButton("Carregar mais", icon = Icons.Filled.Refresh, modifier = Modifier.testTag("more_$tag"), onClick = onMore)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SkeletonRow() {
    Row(Modifier.padding(horizontal = 40.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        repeat(5) {
            Box(
                Modifier
                    .width(210.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BruColors.Card),
            )
        }
    }
}

@Composable
fun Dots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(
                Modifier
                    .height(8.dp)
                    .width(if (i == selected) 22.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (i == selected) BruColors.Cyan else Color(0x66FFFFFF)),
            )
        }
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDuration(min: Int?): String? = min?.let { if (it >= 60) "${it / 60}h${"%02d".format(it % 60)}" else "${it}min" }
