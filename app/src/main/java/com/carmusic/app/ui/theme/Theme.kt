package com.carmusic.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.carmusic.app.data.AppStore
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkBackground = Color(0xFF151B23)
val DarkSurface = Color(0xFF202832)
val DarkCard = Color(0x2AFFFFFF) // 半透明毛玻璃
val DarkCardBorder = Color(0x33FFFFFF)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val AccentGold = Color(0xFFFFD166) // 卡拉OK当前句发光高亮金
val AccentBlue = Color(0xFF8EBEF2)
val ActiveProgress = Color(0xFF38BDF8)
val InactiveTrack = Color(0x33FFFFFF)

private val DarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    secondary = AccentGold,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    primaryContainer = Color(0xFF263F58), onPrimaryContainer = Color(0xFFD5E8FF),
    secondaryContainer = Color(0xFF263F58), onSecondaryContainer = Color(0xFFD5E8FF),
    surfaceVariant = Color(0xFF293440), onSurfaceVariant = Color(0xFFAEBAC7), surfaceTint = AccentBlue
)

@Composable
fun CarMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val preference by AppStore.theme.collectAsState()
    val useDark = when (preference) { "day" -> false; "night" -> true; else -> darkTheme }
    MaterialTheme(
        colorScheme = if (useDark) DarkColorScheme else lightColorScheme(
            primary = Color(0xFF396D9B), secondary = Color(0xFF9B6A29),
            background = Color(0xFFEDF2F6), surface = Color.White,
            onBackground = Color(0xFF193044), onSurface = Color(0xFF193044),
            primaryContainer = Color(0xFFDAE8F3), onPrimaryContainer = Color(0xFF183E61),
            secondaryContainer = Color(0xFFDAE8F3), onSecondaryContainer = Color(0xFF183E61),
            surfaceVariant = Color(0xFFE0E9F0), onSurfaceVariant = Color(0xFF4E6579),
            surfaceTint = Color(0xFF396D9B)),
        content = content
    )
}
