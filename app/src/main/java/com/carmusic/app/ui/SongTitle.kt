package com.carmusic.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** One readable line everywhere; only overflowing titles start moving. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SongTitle(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    color: Color = LocalContentColor.current,
    fontWeight: FontWeight? = null,
    scrolling: Boolean = true
) {
    Text(
        text = text.replace('\n', ' ').replace('\r', ' '),
        modifier = if (scrolling) modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            initialDelayMillis = 1200,
            delayMillis = 1500,
            velocity = 28.dp
        ) else modifier,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip
    )
}
