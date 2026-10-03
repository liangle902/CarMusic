package com.carmusic.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MaintenanceTools(service:PlaybackService) {
    val scope=rememberCoroutineScope(); val context=LocalContext.current
    var panel by remember {mutableStateOf("")};var page by remember {mutableIntStateOf(1)}
    var data by remember {mutableStateOf<JsonObject?>(null)};var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")};var clear by remember {mutableStateOf(false)}
    var deleteId by remember {mutableStateOf<String?>(null)}
    var clearCache by remember {mutableStateOf(false)}
    var cacheSize by remember {mutableLongStateOf(service.cacheBytes())}
    val update by com.carmusic.app.data.AppStore.upstreamUpdate.collectAsState()
    suspend fun load() {
        busy=true;message=""
        try {data=ApiClient.json(if(panel=="下载记录") "/api/downloads/records" else "/local_music/duplicates",mapOf("page" to page.toString(),"page_size" to "20")).asJsonObject}
        catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"加载失败"} finally {busy=false}
    }
    LaunchedEffect(panel,page){if(panel.isNotEmpty()) load()}
    Text("下载与维护",style=MaterialTheme.typography.titleLarge)
    update?.let {Text("上游最新版本：${it.get("latest_version")?.asString?:"未知"}",style=MaterialTheme.typography.bodySmall)}
    TextButton(onClick={cacheSize=service.cacheBytes();clearCache=true}){Text("清理播放缓存 · ${cacheSize/1024/1024} MB")}
    Row {TextButton(onClick={panel="下载记录";page=1;data=null}){Text("下载记录")};TextButton(onClick={panel="重复文件";page=1;data=null}){Text("重复文件")}}
    TextButton(onClick={scope.launch {try {ApiClient.json("/local_music/reindex",method="POST");message="索引重建已开始，请稍后在本地音乐中刷新"} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"重建失败"}}}){Text("重建本地音乐索引")}
    TextButton(onClick={scope.launch {busy=true;try {val settings=ApiClient.settings();val result=ApiClient.json("/github_proxy/test",mapOf("proxy" to (settings.get("githubProxyUrl")?.asString?:""))).asJsonObject;message=if(result.get("ok")?.asBoolean==true) "代理连接成功 · ${result.get("latency_ms").asLong} ms" else result.get("error")?.asString?:"代理连接失败"} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"测试失败"} finally {busy=false}}},enabled=!busy){Text("测试 GitHub 代理")}
    TextButton(onClick={scope.launch {busy=true;try {val result=ApiClient.json("/app_update/check").asJsonObject;message="上游版本：${result.get("latest_version")?.asString?:"未知"}";val link=result.get("release_url")?.asString;if(!link.isNullOrBlank()) context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link)))} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"检查失败"} finally {busy=false}}},enabled=!busy){Text("查看上游更新")}
    if(panel.isEmpty() && message.isNotEmpty()) Text(message)
    if(panel.isNotEmpty()) AlertDialog(onDismissRequest={panel=""},title={Text(panel)},text={Column(Modifier.heightIn(max=musicDialogContentHeight(440.dp)).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(message.isNotEmpty()) Text(message)
        val entries=data?.getAsJsonArray(if(panel=="下载记录") "records" else "groups")
        if(entries?.size()==0 && !busy) Text("暂无${panel}")
        entries?.forEach {element -> val row=element.asJsonObject
            if(panel=="下载记录") {
                Text("${row.get("Name")?.asString?:""} · ${row.get("Artist")?.asString?:""}")
                val status=row.get("Status")?.asString?:""
                Text(when(status){"success"->"下载完成";"failed","error"->"下载失败";"downloading"->"下载中";else->status},style=MaterialTheme.typography.bodySmall)
                row.get("Error")?.asString?.takeIf {it.isNotBlank()}?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
            } else {
                Text("${row.get("name").asString} · ${row.get("artist").asString}")
                row.getAsJsonArray("songs")?.forEach {song -> val item=song.asJsonObject
                    Text(item.get("rel_path")?.asString?:item.get("id").asString,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={deleteId=item.get("id").asString}){Text("删除此文件 · ${(item.get("size")?.asLong?:0)/1024} KB")}
                }
            }
            HorizontalDivider()
        }
        val pages=data?.get("total_pages")?.asInt?:1
        Row {TextButton(onClick={page--},enabled=page>1&&!busy){Text("上一页")};Text("$page / $pages",Modifier.padding(top=12.dp));TextButton(onClick={page++},enabled=page<pages&&!busy){Text("下一页")}}
    }},dismissButton={if(panel=="下载记录") TextButton(onClick={clear=true}){Text("清空记录")}},confirmButton={TextButton(onClick={panel=""}){Text("关闭")}})
    if(clear || deleteId!=null) AlertDialog(onDismissRequest={clear=false;deleteId=null},title={Text(if(clear) "清空下载记录？" else "删除重复文件？")},text={Text(if(clear) "仅清空历史记录，下载文件保留。" else "将永久删除选中的本地文件。")},dismissButton={TextButton(onClick={clear=false;deleteId=null}){Text("取消")}},confirmButton={TextButton(onClick={scope.launch {try {if(clear) ApiClient.json("/api/downloads/records",method="DELETE") else ApiClient.json("/local_music",mapOf("id" to deleteId!!),"DELETE");clear=false;deleteId=null;load()} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"操作失败"}}}){Text("确认")}})
    if(clearCache) AlertDialog(onDismissRequest={clearCache=false},title={Text("清理播放缓存？")},text={Text("播放将暂停，已下载和导入的音乐文件会保留。")},dismissButton={TextButton(onClick={clearCache=false}){Text("取消")}},confirmButton={TextButton(onClick={try {service.clearPlaybackCache();cacheSize=service.cacheBytes();message="播放缓存已清理"} catch(e:Exception){message=e.message?:"清理失败"};clearCache=false}){Text("清理")}})
}
