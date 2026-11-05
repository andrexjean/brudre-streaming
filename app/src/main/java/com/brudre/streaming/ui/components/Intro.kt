package com.brudre.streaming.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brudre.streaming.ui.theme.BruColors
import kotlin.math.PI
import kotlin.math.sin

/** A abertura toca uma vez por inicialização do app (não repete ao voltar do player). */
object IntroState {
    @Volatile var shown = false
}

private val EaseOutBack = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** Progresso local de um trecho [start, end] da linha do tempo (0..1), com easing. */
private fun seg(t: Float, start: Float, end: Float, easing: (Float) -> Float = { FastOutSlowInEasing.transform(it) }): Float {
    val x = ((t - start) / (end - start)).coerceIn(0f, 1f)
    return easing(x)
}

/**
 * Abertura animada do BruDre (~3,5 s): brilho de fundo, feixes de luz, a TV se desenhando,
 * as letras entrando, um reflexo passando pelo logo e a transição para o catálogo.
 * Leve para o Mi Stick: só Canvas e camadas gráficas, sem vídeo nem imagens.
 * [skip] muda para true quando o usuário aperta qualquer tecla.
 */
@Composable
fun BruIntro(skip: Boolean, onFinished: () -> Unit) {
    val time = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val finished = remember { booleanArrayOf(false) }
    val finish = { if (!finished[0]) { finished[0] = true; onFinished() } }

    LaunchedEffect(Unit) {
        time.animateTo(1f, tween(durationMillis = 3200, easing = LinearEasing))
        if (!skip) {
            exit.animateTo(1f, tween(durationMillis = 550, easing = FastOutSlowInEasing))
            finish()
        }
    }
    LaunchedEffect(skip) {
        if (skip) {
            exit.animateTo(1f, tween(durationMillis = 280))
            finish()
        }
    }

    val t = time.value
    val out = exit.value

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - out }
            .testTag("intro"),
        contentAlignment = Alignment.Center,
    ) {
        // ---------------------------------------------------- fundo + luz
        Canvas(Modifier.fillMaxSize()) {
            drawRect(BruColors.Bg)
            val w = size.width
            val h = size.height
            val c = Offset(w / 2f, h / 2f)

            val glow = seg(t, 0.0f, 0.35f)
            drawCircle(
                Brush.radialGradient(listOf(BruColors.Neon.copy(alpha = 0.45f * glow), Color.Transparent), center = c, radius = w * 0.45f * (0.6f + 0.4f * glow)),
                radius = w * 0.45f, center = c,
            )
            drawCircle(
                Brush.radialGradient(listOf(BruColors.Lilac.copy(alpha = 0.25f * glow), Color.Transparent), center = Offset(w * 0.15f, h * 0.95f), radius = w * 0.4f),
                radius = w * 0.4f, center = Offset(w * 0.15f, h * 0.95f),
            )

            // Feixes de luz curvos varrendo a tela (como na arte de referência)
            val beam = seg(t, 0.05f, 0.7f, { LinearEasing.transform(it) })
            if (beam > 0f) {
                for (i in 0 until 3) {
                    val path = Path().apply {
                        val y0 = h * (0.18f + i * 0.06f)
                        moveTo(-w * 0.1f, y0 + h * 0.5f)
                        cubicTo(w * 0.25f, y0 + h * 0.15f, w * 0.6f, y0 + h * 0.05f, w * 1.1f, y0 - h * 0.12f)
                    }
                    val pm = PathMeasure().apply { setPath(path, false) }
                    val len = pm.length
                    val head = len * beam
                    val tail = (head - len * 0.45f).coerceAtLeast(0f)
                    val part = Path()
                    pm.getSegment(tail, head, part, true)
                    val fade = 1f - seg(t, 0.55f, 0.9f)
                    drawPath(
                        part,
                        Brush.linearGradient(listOf(Color.Transparent, BruColors.Cyan.copy(alpha = (0.7f - i * 0.18f) * fade), Color.White.copy(alpha = 0.9f * fade))),
                        style = Stroke(width = (6f - i * 1.6f) * density, cap = StrokeCap.Round),
                    )
                }
            }

            // Partículas subindo
            val pa = seg(t, 0.1f, 0.4f)
            for (i in 0 until 26) {
                val px = ((i * 137.5f) % 100f) / 100f
                val speed = 0.12f + (i % 5) * 0.04f
                val py = (1.05f - ((t * speed * 3f + i * 0.071f) % 1.1f))
                val tw = 0.5f + 0.5f * sin((t * 9f + i).toDouble() * PI / 2).toFloat()
                drawCircle(
                    (if (i % 3 == 0) BruColors.Lilac else BruColors.Cyan).copy(alpha = 0.55f * pa * tw),
                    radius = (1.2f + (i % 4) * 0.7f) * density,
                    center = Offset(px * w, py * h),
                )
            }
        }

        // ------------------------------------------------------- logotipo
        val logoIn = seg(t, 0.12f, 0.45f)
        Box(
            Modifier.graphicsLayer {
                val s = (0.86f + 0.14f * logoIn) * (1f + 0.18f * out)
                scaleX = s; scaleY = s
                compositingStrategy = CompositingStrategy.Offscreen
            }.drawWithContent {
                drawContent()
                // Reflexo de luz atravessando o logo
                val shine = seg(t, 0.62f, 0.86f, { LinearEasing.transform(it) })
                if (shine > 0f && shine < 1f) {
                    val x = -size.width * 0.4f + size.width * 1.8f * shine
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.75f), Color.Transparent),
                            start = Offset(x - size.width * 0.18f, 0f),
                            end = Offset(x + size.width * 0.18f, size.height),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Row {
                        val bru = seg(t, 0.32f, 0.58f, { EaseOutBack.transform(it) })
                        val dre = seg(t, 0.38f, 0.64f, { EaseOutBack.transform(it) })
                        Text(
                            "Bru", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .offset(x = (-70 * (1f - bru)).dp)
                                .graphicsLayer { alpha = seg(t, 0.32f, 0.5f) },
                        )
                        Text(
                            "Dre", color = BruColors.Cyan, fontSize = 64.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .offset(x = (70 * (1f - dre)).dp)
                                .graphicsLayer { alpha = seg(t, 0.38f, 0.56f) },
                        )
                    }
                    val st = seg(t, 0.55f, 0.8f)
                    Text(
                        "Streaming",
                        color = BruColors.TextDim,
                        fontSize = 22.sp,
                        letterSpacing = (16f - 11f * st).sp,
                        modifier = Modifier.graphicsLayer { alpha = st },
                    )
                }
                Spacer(Modifier.width(20.dp))
                AnimatedTvGlyph(t, Modifier.size(92.dp))
            }
        }
    }
}

/** A TV se desenhando: antenas crescem, moldura traçada, "play" surge com pulso de luz. */
@Composable
private fun AnimatedTvGlyph(t: Float, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.075f

        val ant = seg(t, 0.18f, 0.36f)
        val base = Offset(w * 0.5f, h * 0.30f)
        if (ant > 0f) {
            drawLine(BruColors.Cyan, base, Offset(base.x + (w * 0.28f - base.x) * ant, base.y + (h * 0.04f - base.y) * ant), stroke, StrokeCap.Round)
            drawLine(BruColors.Cyan, base, Offset(base.x + (w * 0.74f - base.x) * ant, base.y + (h * 0.02f - base.y) * ant), stroke, StrokeCap.Round)
        }

        val frame = seg(t, 0.12f, 0.42f)
        if (frame > 0f) {
            val rect = Path().apply {
                addRoundRect(RoundRect(stroke / 2, h * 0.30f, w - stroke / 2, h * 0.98f, CornerRadius(w * 0.16f)))
            }
            val pm = PathMeasure().apply { setPath(rect, false) }
            val part = Path()
            pm.getSegment(0f, pm.length * frame, part, true)
            drawPath(part, BruColors.Cyan, style = Stroke(stroke, cap = StrokeCap.Round))
        }

        val play = seg(t, 0.42f, 0.62f, { EaseOutBack.transform(it) })
        if (play > 0f) {
            val cx = w * 0.53f
            val cy = h * 0.64f
            val pulse = seg(t, 0.62f, 0.95f)
            drawCircle(
                Brush.radialGradient(listOf(BruColors.Neon.copy(alpha = 0.55f * (1f - pulse)), Color.Transparent), center = Offset(cx, cy), radius = w * (0.2f + 0.5f * pulse)),
                radius = w * (0.2f + 0.5f * pulse), center = Offset(cx, cy),
            )
            val s = play
            val tri = Path().apply {
                moveTo(cx - w * 0.13f * s, cy - h * 0.16f * s)
                lineTo(cx - w * 0.13f * s, cy + h * 0.16f * s)
                lineTo(cx + w * 0.17f * s, cy)
                close()
            }
            drawPath(tri, BruColors.Neon)
        }
    }
}
