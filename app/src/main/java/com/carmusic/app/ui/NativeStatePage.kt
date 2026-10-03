package com.carmusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import kotlinx.coroutines.*

@Composable
fun NativeStatePage(service:PlaybackService,navigate:(String)->Unit) {
    val favorites by AppStore.favorites.collectAsState();val queue by service.playlist.collectAsState();val error by service.playbackError.collectAsState()
    var engine by remember {mutableStateOf("正在连接音乐引擎…")};var busy by remember {mutableStateOf(false)};val scope=rememberCoroutineScope()
    suspend fun check(){busy=true;engine=try {ApiClient.json("/healthz");"音乐引擎可用"} catch(e:CancellationException){throw e} catch(e:Exception){"无法连接音乐引擎：${e.message}"};busy=false}
    LaunchedEffect(Unit){check()}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text(engine);if(busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else TextButton(onClick={scope.launch {check()}}){Text("重新检查")}
        if(favorites.isEmpty()) {Text("还没有收藏的歌曲",style=MaterialTheme.typography.titleLarge);Text("播放时点击红心，喜欢的音乐就会留在这里。");Button(onClick={navigate("歌单列表")}){Text("去发现音乐")}}
        if(queue.isEmpty()){Text("播放队列为空",style=MaterialTheme.typography.titleLarge);Text("从歌单或收藏中选择音乐，开始下一段旅程。");TextButton(onClick={navigate("本地音乐")}){Text("听本地音乐")}}
        error?.let {Text(it,color=MaterialTheme.colorScheme.error);Button(onClick={navigate("正在播放")}){Text("返回播放")}}
        TextButton(onClick={navigate("系统设置")}){Text("管理平台账号")}
    }
}
