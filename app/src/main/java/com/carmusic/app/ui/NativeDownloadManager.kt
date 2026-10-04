package com.carmusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.SongItem
import com.google.gson.JsonObject
import kotlinx.coroutines.*

@Composable
fun NativeDownloadManager(service:PlaybackService,songList:@Composable (List<SongItem>)->Unit) {
    var tab by remember {mutableStateOf("已完成")};var songs by remember {mutableStateOf<List<SongItem>>(emptyList())}
    var data by remember {mutableStateOf<JsonObject?>(null)};var page by remember {mutableIntStateOf(1)};var error by remember {mutableStateOf("")};var busy by remember {mutableStateOf(false)};var clear by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val requests=remember { LatestRequest() }
    suspend fun load(){
        val request=requests.begin();val requestedTab=tab;val requestedPage=page
        fun current()=requests.isCurrent(request)&&tab==requestedTab&&page==requestedPage
        busy=true;error=""
        try {
            if(requestedTab=="已完成") {val found=ApiClient.localSongs();if(current()) songs=found}
            else {val found=ApiClient.json("/api/downloads/records",mapOf("page" to requestedPage.toString(),"page_size" to "20")).asJsonObject;if(current()) data=found}
        } catch(e:CancellationException){throw e} catch(e:Exception){if(current()) error=e.message?:"下载记录加载失败"} finally {if(current()) busy=false}
    }
    LaunchedEffect(tab,page){load()}
    Column {
        Row {listOf("已完成","任务记录").forEach {label->FilterChip(tab==label,{tab=label;page=1},label={Text(label)},modifier=Modifier.padding(end=8.dp))};TextButton(onClick={scope.launch {load()}}){Text("刷新")}}
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        if(tab=="已完成") songList(songs)
        else {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("已有文件自动跳过重复下载",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=12.dp));TextButton(onClick={clear=true}){Text("清空记录")}}
            LazyColumn(Modifier.weight(1f)) {
                val records=data?.getAsJsonArray("records")
                if(records?.size()==0&&!busy) item {Text("还没有下载记录",Modifier.padding(vertical=24.dp))}
                records?.forEach {record->item {val row=record.asJsonObject;val name=row.get("Name")?.asString.orEmpty();val artist=row.get("Artist")?.asString.orEmpty();val source=row.get("Source")?.asString.orEmpty();val status=row.get("Status")?.asString.orEmpty()
                    Column(Modifier.fillMaxWidth().padding(vertical=14.dp)) {SongTitle("$name · $artist");Text(when(status){"success"->"下载完成";"failed","error"->"下载失败";"skipped"->"已有文件，已跳过";else->status},color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall);row.get("Error")?.asString?.takeIf {it.isNotBlank()}?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                        if(status in listOf("failed","error")) TextButton(onClick={scope.launch {try {val match=ApiClient.searchSongs("$name $artist",source).firstOrNull {it.name==name && it.artist==artist}?:throw java.io.IOException("没有找到匹配歌曲，请通过搜索重新选择");val result=ApiClient.download(match);load();error=result.notice} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message?:"重试失败"}}}){Text("重试")}
                    };HorizontalDivider()
                }}
                item {Row {val pages=data?.get("total_pages")?.asInt?:1;TextButton(onClick={page--},enabled=page>1&&!busy){Text("上一页")};Text("$page / $pages",Modifier.padding(top=12.dp));TextButton(onClick={page++},enabled=page<pages&&!busy){Text("下一页")}}}
            }
        }
    }
    if(clear) AlertDialog(onDismissRequest={clear=false},title={Text("清空下载记录？")},text={Text("仅清空记录，音乐文件会保留。")},dismissButton={TextButton(onClick={clear=false}){Text("取消")}},confirmButton={TextButton(onClick={scope.launch {try {ApiClient.json("/api/downloads/records",method="DELETE");clear=false;load()} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message?:"清空失败"}}}){Text("清空")}})
}
