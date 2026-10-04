package com.carmusic.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit


internal fun sourceName(id:String)=mapOf("qq" to "QQ音乐","netease" to "网易云","kuwo" to "酷我","kugou" to "酷狗","migu" to "咪咕","soda" to "汽水","bilibili" to "Bilibili","local" to "本地音乐")[id]?:id

@Composable
fun SongSourceStatus(song:SongItem) {
    val states by AppStore.playbackChecks.collectAsState()
    val mappings by AppStore.resolvedSources.collectAsState()
    val resolved=mappings[song.key]
    val status=states[song.key]?:if(song.source=="local") "本地音乐" else "待检测"
    LaunchedEffect(song.key) { com.carmusic.app.data.SourceInspection.request(song) }
    Text("${sourceName(song.source)} · $status"+(resolved?.takeIf {it.key!=song.key}?.let {" · 已换源 ${sourceName(it.source)}"}?:""),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=10.sp,lineHeight=if(LocalMusicWindow.current.compactHeight) 14.sp else androidx.compose.ui.unit.TextUnit.Unspecified,color=if(status in listOf("音源失效","当前源失效","检测失败","未找到可用音源")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
