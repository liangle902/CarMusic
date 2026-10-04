package com.carmusic.app.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun NativePlayer(service: PlaybackService, openQueue: () -> Unit) {
    val song by service.currentSong.collectAsState()
    val decodedBitrate by service.audioBitrate.collectAsState()
    val resolved by service.resolvedSong.collectAsState()
    val bitrate=decodedBitrate?:resolved?.bitrate?.takeIf {it>0}?.let {if(it>=10000) (it+500)/1000 else it}
    val position by service.currentPosition.collectAsState()
    val duration by service.duration.collectAsState()
    val playing by service.isPlaying.collectAsState()
    val favorite by service.isFavorite.collectAsState()
    val mode by service.playMode.collectAsState()
    val error by service.playbackError.collectAsState()
    val vinyl by AppStore.vinyl.collectAsState()
    val window = LocalMusicWindow.current
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricError by remember { mutableStateOf<String?>(null) }
    var lyricLoading by remember { mutableStateOf(false) }
    var lyricRetry by remember { mutableIntStateOf(0) }
    val state = rememberLazyListState()
    var browsingLyrics by remember { mutableStateOf(false) }
    LaunchedEffect(song?.key, resolved?.key, lyricRetry) {
        lyrics = emptyList(); lyricError = null
        (resolved ?: song)?.let {
            lyricLoading = true
            try { lyrics = ApiClient.fetchLyrics(it) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { lyricError = e.message ?: "歌词暂时无法加载" }
            finally { lyricLoading = false }
        }
    }
    val active = lyrics.indexOfLast { it.timeMs <= position }.coerceAtLeast(0)
    LaunchedEffect(active, browsingLyrics) {
        if (!browsingLyrics && !state.isScrollInProgress && lyrics.isNotEmpty())
            state.animateScrollToItem((active - 1).coerceAtLeast(0))
    }
    LaunchedEffect(state, lyrics) {
        state.interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is DragInteraction.Start -> browsingLyrics = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    while (state.isScrollInProgress) delay(50)
                    delay(2000)
                    browsingLyrics = false
                    if (lyrics.isNotEmpty()) state.animateScrollToItem(
                        (lyrics.indexOfLast { it.timeMs <= service.currentPosition.value } - 1).coerceAtLeast(0)
                    )
                }
            }
        }
    }
    val lyricContent: @Composable (Modifier) -> Unit = { modifier ->
        LazyColumn(state = state, modifier = modifier,
            contentPadding = PaddingValues(vertical = if (window.compactHeight) 8.dp else 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (lyrics.isEmpty()) item {
                Text(if (lyricLoading) "正在加载歌词…" else lyricError ?: "未找到匹配歌词", Modifier.padding(16.dp))
                if (!lyricLoading && song != null) TextButton(onClick = { lyricRetry++ }) { Text("重试歌词") }
            }
            itemsIndexed(lyrics) { index, line ->
                TimedLyricText(line, position, index == active) { browsingLyrics = false; service.seekTo(line.timeMs) }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val horizontal = window.horizontal && maxWidth >= 420.dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (horizontal) 24.dp else 14.dp)) {
                val cover = song?.let { ApiClient.coverUrl(it.source, it.cover) }
                if (horizontal) {
                    if (window.compactHeight) {
                        Row(Modifier.weight(.46f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            val size = minOf(availableWidth * .19f, (availableHeight - 104.dp).coerceAtLeast(48.dp), 180.dp)
                            AlbumArtwork(cover, vinyl, playing, size)
                            Column(Modifier.weight(1f)) {
                                PlayerSongInfo(song, favorite, service::toggleFavorite, compact = true, bitrate = bitrate)
                                song?.let { SongSourceStatus(it) }
                                error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2, fontSize = 11.sp) }
                            }
                        }
                    } else {
                        Column(Modifier.weight(.4f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            val size = minOf(availableWidth * .3f, (availableHeight - 240.dp).coerceAtLeast(80.dp), 350.dp)
                            AlbumArtwork(cover, vinyl, playing, size)
                            Spacer(Modifier.height(16.dp))
                            PlayerSongInfo(song, favorite, service::toggleFavorite, compact = false, bitrate = bitrate)
                            song?.let { SongSourceStatus(it) }
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                        }
                    }
                    lyricContent(Modifier.weight(.6f).fillMaxHeight())
                } else {
                    AlbumArtwork(cover, vinyl, playing, minOf(availableWidth * .43f, availableHeight * .45f, 350.dp))
                    lyricContent(Modifier.weight(1f).fillMaxHeight())
                }
            }
            if (!horizontal) {
                PlayerSongInfo(song, favorite, service::toggleFavorite, compact = window.compactHeight, bitrate = bitrate)
                song?.let { SongSourceStatus(it) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2) }
            }
            PlayerTransport(service, position, duration, playing, mode, song != null, horizontal || window.compactHeight, openQueue)
        }
    }
}

@Composable
private fun PlayerSongInfo(song: SongItem?, favorite: Boolean, toggleFavorite: () -> Unit, compact: Boolean, bitrate: Int?) {
    Row(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SongTitle(song?.name ?: "还没有正在播放的歌曲", fontSize = if (compact) 20.sp else 24.sp,
                suffix=bitrate?.takeIf {song!=null&&it>0}?.let {"$it kbps"},suffixFontSize=if(compact) 12.sp else 14.sp,
                suffixColor=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(song?.let { "${it.artist} · ${it.album}" } ?: "去歌单或搜索中选择音乐",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, bottom = if (compact) 6.dp else 12.dp))
        }
        IconButton(onClick = toggleFavorite, enabled = song != null,
            modifier = Modifier.semantics { contentDescription = if (favorite) "取消收藏当前歌曲" else "收藏当前歌曲" }) {
            MusicIcon("我的收藏", color = if (favorite) Color(0xFFE85063) else MaterialTheme.colorScheme.onSurfaceVariant, filled = favorite)
        }
    }
}

@Composable
private fun PlayerTransport(service: PlaybackService, position: Long, duration: Long, playing: Boolean,
                            mode: PlayMode, enabled: Boolean, compact: Boolean, openQueue: () -> Unit) {
    val modeName = when (mode) {
        PlayMode.SEQUENCE -> "顺序播放"; PlayMode.SHUFFLE -> "随机播放"
        PlayMode.REPEAT_ONE -> "单曲循环"; PlayMode.REPEAT_ALL -> "列表循环"
    }
    val currentSong by service.currentSong.collectAsState()
    var seeking by remember(currentSong?.key) { mutableStateOf<Float?>(null) }
    val displayedPosition = seeking?.toLong() ?: position
    val slider: @Composable (Modifier) -> Unit = { modifier ->
        Slider(displayedPosition.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
            onValueChange = { seeking = it },
            onValueChangeFinished = { seeking?.let { service.seekTo(it.toLong()) }; seeking = null },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = enabled, modifier = modifier)
    }
    if (compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(playerTime(displayedPosition), fontSize = 12.sp); slider(Modifier.weight(1f)); Text(playerTime(duration), fontSize = 12.sp)
    } else {
        slider(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(playerTime(displayedPosition)); Text(playerTime(duration)) }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 0.dp else 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (compact) IconButton(onClick = service::togglePlayMode, modifier = Modifier.size(48.dp).semantics { contentDescription = modeName }) { MusicIcon(modeName) }
        Row(if (compact) Modifier else Modifier.fillMaxWidth(), horizontalArrangement = if (compact) Arrangement.spacedBy(24.dp) else Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = service::playPrevious, enabled = enabled, modifier = Modifier.size(if (compact) 48.dp else 64.dp).semantics { contentDescription = "上一首" }) { MusicIcon("上一首", Modifier.size(30.dp)) }
            FilledIconButton(onClick = service::togglePlayPause, enabled = enabled, modifier = Modifier.size(if (compact) 56.dp else 76.dp).semantics { contentDescription = if (playing) "暂停" else "播放" }) {
                MusicIcon(if (playing) "暂停" else "播放", Modifier.size(if (compact) 28.dp else 36.dp), MaterialTheme.colorScheme.onPrimary)
            }
            IconButton(onClick = service::playNext, enabled = enabled, modifier = Modifier.size(if (compact) 48.dp else 64.dp).semantics { contentDescription = "下一首" }) { MusicIcon("下一首", Modifier.size(30.dp)) }
        }
        if (compact) IconButton(onClick = openQueue, modifier = Modifier.size(48.dp).semantics { contentDescription = "打开播放队列" }) { MusicIcon("播放队列") }
    }
    if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = service::togglePlayMode, modifier = Modifier.size(56.dp).semantics { contentDescription = modeName }) { MusicIcon(modeName) }
        IconButton(onClick = openQueue, modifier = Modifier.size(56.dp).semantics { contentDescription = "打开播放队列" }) { MusicIcon("播放队列") }
    }
}

private fun playerTime(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
