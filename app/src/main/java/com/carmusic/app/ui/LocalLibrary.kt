package com.carmusic.app.ui

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.ui.model.SongItem
import com.carmusic.app.service.PlaybackService
import kotlinx.coroutines.*

@Composable
fun LocalLibrary(service:PlaybackService,content: @Composable (List<SongItem>, (SongItem)->Unit,(List<SongItem>)->Unit)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val imported by AppStore.imports.collectAsState()
    var downloaded by remember {mutableStateOf<List<SongItem>>(emptyList())};var status by remember {mutableStateOf("")}
    var removing by remember {mutableStateOf<List<SongItem>>(emptyList())}
    var deleting by remember {mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {withContext(Dispatchers.IO) {uris.forEach {uri ->
            try {
                context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val filename=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst()) it.getString(0) else null} ?: "本地音乐"
                val metadata=MediaMetadataRetriever()
                val song=try {metadata.setDataSource(context,uri);SongItem(id=uri.toString(),name=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?:filename,artist=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?:"本地音乐",album=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?:"",duration=(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0)/1000,source="local",streamUrl=uri.toString())} finally {metadata.release()}
                withContext(Dispatchers.Main){AppStore.addImport(song)}
            } catch(e:CancellationException){throw e} catch(e:Exception){withContext(Dispatchers.Main){status="部分文件导入失败：${e.message}"}}
        }}}
    }
    LaunchedEffect(Unit){try {downloaded=ApiClient.localSongs()} catch(e:CancellationException){throw e} catch(e:Exception){status=e.message?:"本地文件加载失败"}}
    Column {
        Row {Button(onClick={picker.launch(arrayOf("audio/*"))}){Text("导入本地音乐")};TextButton(onClick={scope.launch {try {downloaded=ApiClient.localSongs();status=""} catch(e:CancellationException){throw e} catch(e:Exception){status=e.message?:"刷新失败"}}}){Text("刷新")}}
        if(status.isNotBlank()) Text(status,Modifier.padding(vertical=8.dp))
        content((imported+downloaded).distinctBy {it.key},{removing=listOf(it)},{removing=it})
    }
    if(removing.isNotEmpty()) {
        val importsCount=removing.count {song->imported.any {it.key==song.key}}
        val filesCount=removing.size-importsCount
        AlertDialog(onDismissRequest={if(!deleting) removing=emptyList()},title={Text("移除 ${removing.size} 首本地音乐？")},text={Text(listOf(if(importsCount>0) "$importsCount 首导入记录将移除，原文件保留。" else "",if(filesCount>0) "$filesCount 首下载文件将永久删除。" else "").filter {it.isNotBlank()}.joinToString("\n"))},dismissButton={TextButton(enabled=!deleting,onClick={removing=emptyList()}){Text("取消")}},confirmButton={TextButton(enabled=!deleting,onClick={val targets=removing.toList();deleting=true;scope.launch {
            var success=0;val failures=mutableListOf<String>()
            try {for(song in targets) {try {if(imported.any {it.key==song.key}) AppStore.removeImport(song) else {ApiClient.json("/local_music",mapOf("id" to song.id),"DELETE");service.removeFromQueue(song)};success++} catch(e:CancellationException){throw e} catch(e:Exception){failures+="${song.name}：${e.message}"}};downloaded=ApiClient.localSongs();status="已移除 $success 首"+if(failures.isNotEmpty()) "\n"+failures.joinToString("\n") else "";removing=emptyList()} catch(e:CancellationException){throw e} catch(e:Exception){status=e.message?:"刷新失败"} finally {deleting=false}
        }}){Text(if(deleting) "处理中…" else "确认")}})
    }
}
