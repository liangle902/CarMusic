package com.carmusic.app.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.clickable
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
internal fun NativePlayer(service: PlaybackService, onArtist: (String) -> Unit = {}, openQueue: () -> Unit) {
    val song by service.currentSong.collectAsState()
    val decodedBitrate by service.audioBitrate.collectAsState()
    val resolved by service.resolvedSong.collectAsState()
    val candidates by service.candidates.collectAsState()
    val bitrate=decodedBitrate?:candidates.firstOrNull {it.song.key==resolved?.key}?.bitrate?.takeIf {it>0}
        ?:resolved?.bitrate?.takeIf {it>0}?.let {if(it>=10000) (it+500)/1000 else it}
    var choosingSource by remember { mutableStateOf(false) }
    val chooseSource = { choosingSource = true }
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
    var lyricsFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(song?.key, resolved?.key, lyricRetry) {
        // 同一首歌只是换了码率版本时，先保留已有歌词，新歌词到了再替换，避免闪一下。
        val sameSong = lyricsFor == song?.key
        if (!sameSong) lyrics = emptyList()
        lyricError = null
        (resolved ?: song)?.let {
            lyricLoading = true
            try { ApiClient.fetchLyrics(it).let { loaded -> if (loaded.isNotEmpty() || !sameSong) lyrics = loaded }; lyricsFor = song?.key }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!sameSong) lyricError = e.message ?: "歌词暂时无法加载" }
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
                                PlayerSongInfo(song, favorite, service::toggleFavorite, compact = true, bitrate = bitrate, onArtist = onArtist, onBitrate = chooseSource)
                                error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2, fontSize = 11.sp) }
                            }
                        }
                    } else {
                        Column(Modifier.weight(.4f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            val size = minOf(availableWidth * .3f, (availableHeight - 240.dp).coerceAtLeast(80.dp), 350.dp)
                            AlbumArtwork(cover, vinyl, playing, size)
                            Spacer(Modifier.height(16.dp))
                            PlayerSongInfo(song, favorite, service::toggleFavorite, compact = false, bitrate = bitrate, onArtist = onArtist, onBitrate = chooseSource)
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
                PlayerSongInfo(song, favorite, service::toggleFavorite, compact = window.compactHeight, bitrate = bitrate, onArtist = onArtist, onBitrate = chooseSource)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2) }
            }
            PlayerTransport(service, position, duration, playing, mode, song != null, horizontal || window.compactHeight, openQueue)
        }
    }
    if (choosingSource) song?.let { BitratePicker(it, resolved, service) { choosingSource = false } }
}

/** 列出当前歌曲可播放的各档码率，点选后切换；不展示来自哪个平台。 */
@Composable
private fun BitratePicker(song: SongItem, resolved: SongItem?, service: PlaybackService, onClose: () -> Unit) {
    var options by remember { mutableStateOf(service.candidates.value) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(song.key) {
        try { options = service.loadCandidates() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        loading = false
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("选择码率") }, confirmButton = { TextButton(onClick = onClose) { Text("关闭") } }, text = {
        Column {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!loading && options.isEmpty()) Text("没有找到其他可用的码率")
            val currentKey = (resolved ?: song).key
            // 码率相同的版本对用户没有区别，只保留一个（优先保留正在播放的）。
            val shown = options.sortedByDescending { it.song.key == currentKey }.distinctBy { it.bitrate }.sortedByDescending { it.bitrate }
            LazyColumn(Modifier.heightIn(max = musicDialogContentHeight(350.dp))) {
                items(shown, key = { it.song.key }) { option ->
                    val current = option.song.key == currentKey
                    TextButton(onClick = { if (!current) service.playAlternate(song, option.song); onClose() }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (option.bitrate > 0) "${option.bitrate} kbps" else "未知码率") + (if (current) " · 当前" else ""),
                            Modifier.fillMaxWidth(), color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    })
}

@Composable
private fun PlayerSongInfo(song: SongItem?, favorite: Boolean, toggleFavorite: () -> Unit, compact: Boolean, bitrate: Int?, onArtist: (String) -> Unit, onBitrate: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SongTitle(song?.name ?: "还没有正在播放的歌曲", Modifier.weight(1f, fill = false), fontSize = if (compact) 20.sp else 24.sp)
                // 码率可点击：打开音源列表手动切换。本地歌曲只显示不可点。
                if (song != null && (song.source != "local" || bitrate != null)) {
                    val label = bitrate?.takeIf { it > 0 }?.let { "$it kbps" } ?: "码率"
                    Text(" · $label", fontSize = if (compact) 12.sp else 14.sp, maxLines = 1,
                        color = if (song.source == "local") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically)
                            .then(if (song.source == "local") Modifier else Modifier.clickable(onClickLabel = "切换码率", onClick = onBitrate)))
                }
            }
            val artists = song?.artist.orEmpty().split(Regex("\\s*(?:[&/、,，;；]|\\sfeat\\.?\\s)\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
            val colors = MaterialTheme.colorScheme
            Row(Modifier.padding(top = 2.dp, bottom = if (compact) 4.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (song == null) Text("去歌单或搜索中选择音乐", color = colors.onSurfaceVariant, fontSize = 13.sp)
                else {
                    artists.forEachIndexed { index, name ->
                        if (index > 0) Text(" / ", color = colors.onSurfaceVariant, fontSize = 13.sp)
                        Text(name, color = colors.primary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically)
                                .clickable(onClickLabel = "搜索歌手 $name") { onArtist(name) })
                    }
                    if (song.album.isNotBlank()) Text(" · ${song.album}", color = colors.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
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
