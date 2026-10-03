package com.carmusic.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable
fun BatchDownload(songs:List<SongItem>) {
    val scope=rememberCoroutineScope();var confirm by remember {mutableStateOf(false)}
    var task by remember {mutableStateOf<Job?>(null)};var progress by remember {mutableIntStateOf(0)}
    var message by remember {mutableStateOf("")}
    val remote=songs.filter {it.source!="local"}.distinctBy {it.key}
    if(remote.isEmpty()) return
    if(task?.isActive==true) TextButton(onClick={task?.cancel();message="已取消剩余下载"}){Text("下载 $progress / ${remote.size} · 取消")}
    else TextButton(onClick={confirm=true}){Text("下载所选 · ${remote.size} 首")}
    if(message.isNotBlank()) Text(message,style=MaterialTheme.typography.bodySmall)
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("下载 ${remote.size} 首音乐？")},text={Text("已下载的曲目会跳过，其他曲目保存到设置中的下载目录。")},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}},confirmButton={TextButton(onClick={confirm=false;task=scope.launch {
        progress=0;message="";var failed=0;val notices=mutableListOf<String>()
        try {val concurrency=(ApiClient.settings().get("downloadConcurrency")?.asInt?:2).coerceIn(1,8);val semaphore=Semaphore(concurrency)
            coroutineScope {remote.map {song->async {semaphore.withPermit {try {if(ApiClient.offlineUri(song)==null) {val result=ApiClient.download(song);if(result.notice.isNotBlank()) notices+="${song.name}：${result.notice}"};Unit} catch(e:CancellationException){throw e} catch(_:Exception){failed++} finally {progress++}}}}.awaitAll()}
            message=if(failed==0) "${remote.size} 首音乐已就绪" else "${remote.size-failed} 首已就绪，$failed 首下载失败"
            if(notices.isNotEmpty()) message+="\n${notices.size} 首有提示\n"+notices.take(3).joinToString("\n")
        } catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"下载失败"}
    }}){Text("开始下载")}})
}
