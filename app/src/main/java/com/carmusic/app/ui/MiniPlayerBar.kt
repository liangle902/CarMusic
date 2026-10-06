package com.carmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.SongItem

/** 底部迷你播放条：封面、歌名与当前歌词、播放/下一首/队列，底边为播放进度。 */
@Composable
internal fun MiniPlayerBar(service: PlaybackService, song: SongItem, playing: Boolean, openPlayer: () -> Unit, openQueue: () -> Unit) {
    val compact = LocalMusicWindow.current.compactHeight
    val position by service.currentPosition.collectAsState()
    val duration by service.duration.collectAsState()
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val colors = MaterialTheme.colorScheme
    Surface(onClick = openPlayer, tonalElevation = 3.dp, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) 4.dp else 12.dp).semantics { contentDescription = "打开正在播放" }) {
        Column {
            Row(Modifier.padding(start = 10.dp, end = 4.dp, top = if (compact) 4.dp else 8.dp, bottom = if (compact) 2.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val size = if (compact) 40.dp else 52.dp
                Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant)) {
                    if (song.cover.isNotBlank()) AsyncImage(ApiClient.coverUrl(song.source, song.cover), "封面", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    SongTitle(song.name, fontSize = if (compact) 13.sp else 15.sp)
                    MiniLyric(service, compact = true)
                }
                IconButton(onClick = service::togglePlayPause, modifier = Modifier.size(48.dp).semantics { contentDescription = if (playing) "暂停" else "播放" }) { MusicIcon(if (playing) "暂停" else "播放") }
                IconButton(onClick = service::playNext, modifier = Modifier.size(48.dp).semantics { contentDescription = "下一首" }) { MusicIcon("下一首") }
                IconButton(onClick = openQueue, modifier = Modifier.size(48.dp).semantics { contentDescription = "打开播放队列" }) { MusicIcon("播放队列") }
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp), color = colors.primary, trackColor = colors.surfaceVariant)
        }
    }
}
