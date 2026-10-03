package com.carmusic.app.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Available application space, including changes from system insets and the keyboard. */
@Immutable
internal data class MusicWindow(val width: Dp, val height: Dp) {
    val horizontal: Boolean get() = width >= 600.dp && width > height
    val compactHeight: Boolean get() = height < 480.dp
    val compactHeader: Boolean get() = horizontal || compactHeight
}

internal val LocalMusicWindow = staticCompositionLocalOf { MusicWindow(400.dp, 800.dp) }

@Composable
internal fun musicDialogContentHeight(maximum: Dp = 360.dp): Dp =
    (LocalMusicWindow.current.height - 180.dp).coerceIn(80.dp, maximum)
