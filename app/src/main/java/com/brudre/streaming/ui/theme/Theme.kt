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
    val NewReleases: ImageVector by lazy {
        icon("new_releases", "M23,12l-2.44,-2.79l0.34,-3.69l-3.61,-0.82L15.4,1.5L12,2.96L8.6,1.5L6.71,4.69L3.1,5.5L3.44,9.2L1,12l2.44,2.79l-0.34,3.7l3.61,0.82L8.6,22.5l3.4,-1.47l3.4,1.46l1.89,-3.19l3.61,-0.82l-0.34,-3.69L23,12zM13,17h-2v-2h2v2zM13,13h-2V7h2v6z")
    }
    val Trending: ImageVector by lazy {
        icon("trending_up", "M16,6l2.29,2.29l-4.88,4.88l-4,-4L2,16.59L3.41,18l6,-6l4,4l6.3,-6.29L22,12V6z")
    }
    val LiveTv: ImageVector by lazy {
        icon("live_tv", "M21,6h-7.59l3.29,-3.29L16,2l-4,4 -4,-4 -0.71,0.71L10.59,6H3c-1.1,0 -2,0.89 -2,2v12c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V8c0,-1.11 -0.9,-2 -2,-2zM21,20H3V8h18v12zM9,10v8l7,-4z")
    }
    val History: ImageVector by lazy {
        icon("history", "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z")
    }
    val Globe: ImageVector by lazy {
        icon("public", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM11,19.93c-3.95,-0.49 -7,-3.85 -7,-7.93 0,-0.62 0.08,-1.21 0.21,-1.79L9,15v1c0,1.1 0.9,2 2,2v1.93zM17.9,17.39c-0.26,-0.81 -1,-1.39 -1.9,-1.39h-1v-3c0,-0.55 -0.45,-1 -1,-1L8,12v-2h2c0.55,0 1,-0.45 1,-1L11,7h2c1.1,0 2,-0.9 2,-2v-0.41c2.93,1.19 5,4.06 5,7.41 0,2.08 -0.8,3.97 -2.1,5.39z")
    }
    val Bookmark: ImageVector by lazy {
        icon("bookmark", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18V5h10v13z")
    }
}
