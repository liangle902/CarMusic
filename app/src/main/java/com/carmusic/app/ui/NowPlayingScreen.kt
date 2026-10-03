package com.carmusic.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.components.BottomNavBar
import com.carmusic.app.ui.components.ControlPad
import com.carmusic.app.ui.components.DualLineKaraokeView
import com.carmusic.app.ui.components.FullscreenLyricsView
import com.carmusic.app.ui.components.PlaylistDrawer
import com.carmusic.app.ui.components.SearchDialog
import com.carmusic.app.ui.components.VinylRecordView
import com.carmusic.app.ui.model.LyricLine
import com.carmusic.app.ui.model.SongItem
import com.carmusic.app.ui.theme.DarkBackground
import com.carmusic.app.ui.theme.DarkSurface
import com.carmusic.app.ui.theme.TextPrimary
import com.carmusic.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun NowPlayingScreen(
    service: PlaybackService
) {
    val currentSong by service.currentSong.collectAsState()
    val isPlaying by service.isPlaying.collectAsState()
    val currentPosition by service.currentPosition.collectAsState()
    val duration by service.duration.collectAsState()
    val playlist by service.playlist.collectAsState()
    val playMode by service.playMode.collectAsState()
    val isFavorite by service.isFavorite.collectAsState()

    var isLyricsExpanded by remember { mutableStateOf(false) }
    var isPlaylistOpen by remember { mutableStateOf(false) }
    var isSearchOpen by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }

    val scope = rememberCoroutineScope()

    // 每次切换歌曲自动获取歌词
    LaunchedEffect(currentSong?.id) {
        val song = currentSong
        if (song != null) {
            lyrics = ApiClient.fetchLyrics(song)
        } else {
            lyrics = emptyList()
        }
    }

    // 动态黑胶唱片尺寸过渡
    val vinylSize by animateDpAsState(
        targetValue = if (isLyricsExpanded) 110.dp else 300.dp,
        animationSpec = tween(durationMillis = 400),
        label = "VinylSize"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        DarkBackground,
                        DarkSurface
                    )
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ==========================================
            // 1. 【70% 沉浸视听区 (视听互斥与角落退避动效)】
            // ==========================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.70f)
            ) {
                if (!isLyricsExpanded) {
                    // 形态 1：默认【黑胶唱片大图主导态】
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 36.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 歌曲标题与歌手
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        ) {
                            Text(
                                text = currentSong?.name ?: "星河音乐",
                                color = TextPrimary,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = "${currentSong?.artist ?: "全网无损聚合"} · ${currentSong?.album ?: "吉利博越L 8155 沉浸座舱"}",
                                color = TextSecondary,
                                fontSize = 16.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        // 居中大黑胶自转封面
                        VinylRecordView(
                            coverUrl = currentSong?.cover ?: "",
                            isPlaying = isPlaying,
                            size = vinylSize,
                            onClick = { isLyricsExpanded = true }
                        )

                        // 唱片正下方：卡拉OK双行大字号歌词
                        DualLineKaraokeView(
                            lyrics = lyrics,
                            currentPositionMs = currentPosition,
                            onClick = { isLyricsExpanded = true }
                        )
                    }
                } else {
                    // 形态 2：点击后的【歌词沉浸态】 (全屏流动歌词 + 黑胶唱片退避至左下角)
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 全屏歌词滚动展示 (覆盖在上方)
                        FullscreenLyricsView(
                            lyrics = lyrics,
                            currentPositionMs = currentPosition,
                            onLineClick = { timeMs -> service.seekTo(timeMs) },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 28.dp, bottom = 20.dp)
                        )

                        // 缩小后的黑胶唱片常驻视觉区左下角 (部分歌词半透明穿透覆盖在它周围)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 24.dp, bottom = 16.dp)
                        ) {
                            VinylRecordView(
                                coverUrl = currentSong?.cover ?: "",
                                isPlaying = isPlaying,
                                size = vinylSize,
                                onClick = { isLyricsExpanded = false } // 点击缩略唱片弹回大图
                            )
                        }
                    }
                }
            }

            // ==========================================
            // 2. 【25% 驾驶盲操触控面板】
            // ==========================================
            ControlPad(
                isPlaying = isPlaying,
                currentPositionMs = currentPosition,
                durationMs = duration,
                playMode = playMode,
                isFavorite = isFavorite,
                onPlayPause = { service.togglePlayPause() },
                onPrevious = { service.playPrevious() },
                onNext = { service.playNext() },
                onSeek = { pos -> service.seekTo(pos) },
                onTogglePlayMode = { service.togglePlayMode() },
                onToggleFavorite = { service.toggleFavorite() },
                onTogglePlaylist = { isPlaylistOpen = !isPlaylistOpen },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.25f)
            )

            // ==========================================
            // 3. 【5% 底部常驻导航条】
            // ==========================================
            BottomNavBar(
                onSearchClick = { isSearchOpen = true },
                onFavoritesClick = { isPlaylistOpen = true },
                onSettingsClick = { /* Settings dialog */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.05f)
            )
        }

        // ==========================================
        // 4. 【半屏半透明右侧滑出播放列表抽屉】
        // ==========================================
        AnimatedVisibility(
            visible = isPlaylistOpen,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            PlaylistDrawer(
                isOpen = isPlaylistOpen,
                playlist = playlist,
                currentSong = currentSong,
                onSongClick = { song ->
                    service.playSong(song)
                    isPlaylistOpen = false
                },
                onClose = { isPlaylistOpen = false }
            )
        }

        // ==========================================
        // 5. 【全网搜索对话框】
        // ==========================================
        SearchDialog(
            isOpen = isSearchOpen,
            onDismiss = { isSearchOpen = false },
            onSongSelected = { song ->
                val currentList = playlist.toMutableList()
                if (currentList.none { it.id == song.id }) {
                    currentList.add(0, song)
                    service.playlist.value = currentList
                }
                service.playSong(song)
            }
        )
    }
}
