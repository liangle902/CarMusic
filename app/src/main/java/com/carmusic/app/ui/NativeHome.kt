package com.carmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.SongItem

@Composable
fun NativeHome(service: PlaybackService, navigate: (String) -> Unit) {
    val song by service.currentSong.collectAsState()
    val playing by service.isPlaying.collectAsState()
    val position by service.currentPosition.collectAsState()
    val favorites by AppStore.favorites.collectAsState()
    val recent by AppStore.recent.collectAsState()
    val colors = MaterialTheme.colorScheme
    val window = LocalMusicWindow.current
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(if (window.compactHeight) 12.dp else 20.dp)) {
        if (!window.horizontal) Column {
            Text(MUSIC_SLOGAN, fontSize = if (window.compactHeight) 22.sp else 25.sp)
            Text("发现喜欢的旋律，收藏每一刻心情", color = colors.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (window.horizontal) Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeResume(song, playing, position, service, navigate, Modifier.weight(.53f))
            Column(Modifier.weight(.47f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeLibraryEntries(favorites.size, navigate)
                HomeSettingsEntry(navigate)
            }
        } else {
            HomeResume(song, playing, position, service, navigate)
            HomeLibraryEntries(favorites.size, navigate)
            HomeSettingsEntry(navigate)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("最近听过", fontSize = 20.sp)
            TextButton(onClick = { navigate("最近听过") }) { Text("查看全部", fontSize = 12.sp) }
        }
        if (recent.isEmpty()) Text("播放过的音乐会留在这里", color = colors.onSurfaceVariant, fontSize = 12.sp)
        else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            recent.take(6).forEach { item ->
                Surface(onClick = { service.playSongFirst(item); navigate("正在播放") }, color = colors.surface, shape = RoundedCornerShape(14.dp)) {
                    if (window.horizontal) Row(Modifier.width(210.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AsyncImage(ApiClient.coverUrl(item.source, item.cover), item.name, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f)) { SongTitle(item.name, fontSize = 13.sp); Text(item.artist, maxLines = 1, fontSize = 11.sp, color = colors.onSurfaceVariant) }
                    } else Column(Modifier.width(112.dp).padding(10.dp)) {
                        AsyncImage(ApiClient.coverUrl(item.source, item.cover), item.name, Modifier.size(92.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        SongTitle(item.name, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)); Text(item.artist, maxLines = 1, fontSize = 10.sp, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HomeResume(song: SongItem?, playing: Boolean, position: Long, service: PlaybackService, navigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val window = LocalMusicWindow.current
    Surface(modifier, color = colors.surface, shape = RoundedCornerShape(22.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val artSize = (maxWidth * .38f).coerceIn(76.dp, if (window.compactHeight) 160.dp else 260.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(artSize).clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant).clickable { navigate("正在播放") }, contentAlignment = Alignment.Center) {
                    if (song?.cover?.isNotBlank() == true) AsyncImage(ApiClient.coverUrl(song.source, song.cover), "上次播放的专辑封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else StarLogo(Modifier.size(artSize * .65f))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (playing) "正在播放" else "上次听到这里", color = colors.primary, fontSize = 11.sp)
                    SongTitle(song?.name ?: "从喜欢的音乐开始", fontSize = if (window.compactHeight) 20.sp else 22.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(song?.let { "${it.artist} · ${it.album}" } ?: "选择一首喜欢的歌", color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp))
                    Button(onClick = { if (song == null) navigate("歌单列表") else { if (!playing) service.togglePlayPause(); navigate("正在播放") } },
                        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(if (song == null) "选择音乐" else if (playing) "进入播放" else "继续播放", fontSize = 12.sp)
                    }
                    if (song != null) Text("${homeTime(position)} / ${homeTime(song.duration * 1000)}", fontSize = 10.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeLibraryEntries(favoriteCount: Int, navigate: (String) -> Unit) {
    val window = LocalMusicWindow.current
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("歌单列表" to "我的歌单与各平台精选", "我的收藏" to "$favoriteCount 首喜欢的歌，随时聆听").forEach { (name, description) ->
            Surface(onClick = { navigate(name) }, color = colors.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(if (window.compactHeight) 12.dp else 16.dp).heightIn(min = if (window.compactHeight) 92.dp else 124.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { MusicIcon(name); MusicIcon("箭头", Modifier.size(18.dp), colors.onSurfaceVariant) }
                    Spacer(Modifier.height(if (window.compactHeight) 10.dp else 22.dp)); Text(name, fontSize = if (window.compactHeight) 16.sp else 20.sp)
                    Text(description, fontSize = 11.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeSettingsEntry(navigate: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = { navigate("系统设置") }, color = colors.surface, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MusicIcon("系统设置")
            Column(Modifier.weight(1f)) { Text("系统设置", fontSize = 16.sp); Text("平台账号、播放与下载", color = colors.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }
            MusicIcon("箭头", Modifier.size(20.dp), colors.onSurfaceVariant)
        }
    }
}

private fun homeTime(ms: Long) = "%02d:%02d".format(ms / 60000, ms / 1000 % 60)
