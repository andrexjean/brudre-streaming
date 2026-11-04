package com.brudre.streaming.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

object BruColors {
    val Bg = Color(0xFF050814)
    val Navy = Color(0xFF0A1533)
    val NavyLight = Color(0xFF13234F)
    val Card = Color(0xFF0F1A35)
    val CardBorder = Color(0xFF223563)
    val Neon = Color(0xFF2F7BFF)
    val Cyan = Color(0xFF37D8FF)
    val Lilac = Color(0xFF9D76FF)
    val Text = Color(0xFFF2F5FF)
    val TextDim = Color(0xFFB4C1E0)
    val TextFaint = Color(0xFF7D8BB0)
    val Badge = Color(0xFFF5C518)
    val Danger = Color(0xFFFF6B7A)
}

private val scheme = darkColorScheme(
    primary = BruColors.Cyan,
    onPrimary = Color(0xFF00121A),
    secondary = BruColors.Lilac,
    background = BruColors.Bg,
    onBackground = BruColors.Text,
    surface = BruColors.Card,
    onSurface = BruColors.Text,
    surfaceVariant = BruColors.NavyLight,
    onSurfaceVariant = BruColors.TextDim,
    outline = BruColors.CardBorder,
    error = BruColors.Danger,
)

@Composable
fun BruDreTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Ícones de navegação desenhados localmente (evita a biblioteca de ícones estendida, ~30 MB de dex). */
object BruIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
            .build()

    val Movie: ImageVector by lazy {
        icon("movie", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z")
    }
    val Layers: ImageVector by lazy {
        icon("layers", "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z")
    }
    val Bookmark: ImageVector by lazy {
        icon("bookmark", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18V5h10v13z")
    }
}
