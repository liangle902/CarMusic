package com.carmusic.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carmusic.app.service.PlaybackService
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NativeQueue(service: PlaybackService) {
    val queue by service.playlist.collectAsState()
    val current by service.currentSong.collectAsState()
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = (queue.indexOfFirst { it.key == current?.key } - 1).coerceAtLeast(0)
    )
    val haptic = LocalHapticFeedback.current
    val reorder = rememberReorderableLazyListState(state) { from, to ->
        // Resolve stable keys against the live queue, including any concurrent additions/removals.
        val live = service.playlist.value
        service.moveQueue(live.indexOfFirst { it.key == from.key }, live.indexOfFirst { it.key == to.key })
    }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("播放队列 · ${queue.size}", Modifier.weight(1f), fontSize = 22.sp)
            TextButton(onClick = { confirm = true }, enabled = queue.isNotEmpty()) { Text("清空") }
        }
        Text("拖动右侧手柄调整顺序，移除不会修改原歌单。", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
        if (queue.isEmpty()) Text("播放队列为空", Modifier.padding(vertical = 24.dp))
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 540.dp),
            state = state,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Compose 1.6 keeps the first visible key anchored during list changes.
            // A stationary spacer prevents that correction from shifting a dragged first song.
            item(key = "queue-scroll-anchor") { Spacer(Modifier.height(1.dp)) }
            itemsIndexed(queue, key = { _, song -> song.key }) { index, song ->
                ReorderableItem(reorder, key = song.key) { dragging ->
                    val elevation by animateDpAsState(if (dragging) 10.dp else 0.dp, label = "queue-lift")
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        shadowElevation = elevation,
                        color = if (dragging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", Modifier.width(28.dp), fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f).clickable(enabled = !dragging) { service.playSong(song) }.padding(vertical = 14.dp)) {
                                SongTitle(song.name, color = if (current?.key == song.key) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface, scrolling = !dragging)
                                Text(song.artist, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SongSourceStatus(song)
                            }
                            IconButton(onClick = { service.removeFromQueue(song) }, enabled = !dragging,
                                modifier = Modifier.size(48.dp).semantics { contentDescription = "移出队列 ${song.name}" }) {
                                Text("×", fontSize = 23.sp)
                            }
                            Box(
                                Modifier.size(48.dp).draggableHandle(
                                    onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onDragStopped = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                ).semantics {
                                    contentDescription = "拖动排序 ${song.name}"
                                    stateDescription = "第 ${index + 1} 首"
                                    customActions = listOf(
                                        CustomAccessibilityAction("向上移动") {
                                            val from = service.playlist.value.indexOfFirst { it.key == song.key }
                                            if (from > 0) { service.moveQueue(from, from - 1); true } else false
                                        },
                                        CustomAccessibilityAction("向下移动") {
                                            val live = service.playlist.value
                                            val from = live.indexOfFirst { it.key == song.key }
                                            if (from in 0 until live.lastIndex) { service.moveQueue(from, from + 1); true } else false
                                        }
                                    )
                                }, contentAlignment = Alignment.Center
                            ) { MusicIcon("拖动", Modifier.size(24.dp), MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text("清空播放队列？") },
        text = { Text("不会删除歌单或收藏中的歌曲。") },
        confirmButton = { TextButton(onClick = { service.clearQueue(); confirm = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
    )
}
