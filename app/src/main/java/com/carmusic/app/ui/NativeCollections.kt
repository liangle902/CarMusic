package com.carmusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.SongItem
import com.carmusic.app.ui.model.PlaylistItem
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

data class LocalCollection(val id:String="",val name:String="",val description:String="",val cover:String="",val kind:String="manual")
private suspend fun collections():List<LocalCollection> = ApiClient.gson.fromJson(ApiClient.json("/collections",mapOf("include_imported" to "1")),Array<LocalCollection>::class.java).toList()

@Composable
fun ImportPlaylistButton(item:PlaylistItem,album:Boolean=false) {
    val scope=rememberCoroutineScope();var busy by remember(item.id,item.source) {mutableStateOf(false)};var message by remember(item.id,item.source) {mutableStateOf("")}
    Column {
        TextButton(enabled=!busy,onClick={scope.launch {busy=true;message="";try {
            val body=ApiClient.gson.toJsonTree(item).asJsonObject.apply {remove("id");addProperty("external_id",item.id);addProperty("content_type",if(album) "album" else "playlist")}
            ApiClient.json("/collections/import",method="POST",body=body);message="已保存到本地歌单"
        } catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"保存失败"} finally {busy=false}}}){Text(if(busy) "保存中…" else "收藏${if(album) "专辑" else "歌单"}")}
        if(message.isNotBlank()) Text(message,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun CollectionPicker(song:SongItem,onClose:()->Unit,onMessage:(String)->Unit) {
    CollectionPicker(listOf(song),onClose,onMessage)
}

@Composable
fun CollectionPicker(songs:List<SongItem>,onClose:()->Unit,onMessage:(String)->Unit) {
    var items by remember {mutableStateOf<List<LocalCollection>>(emptyList())};var error by remember {mutableStateOf<String?>(null)};val scope=rememberCoroutineScope()
    LaunchedEffect(Unit){try {items=collections().filter {it.kind!="imported"}} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message}}
    var busy by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest={if(!busy) onClose()},title={Text("加入本地歌单 · ${songs.size} 首")},text={Column(Modifier.heightIn(max=350.dp).verticalScroll(rememberScrollState())) {error?.let {Text(it)};if(items.isEmpty()) Text("请先创建一个本地歌单");items.forEach {item->TextButton(enabled=!busy,onClick={scope.launch {busy=true;try {for(song in songs.distinctBy {it.key}) ApiClient.json("/collections/${item.id}/songs",method="POST",body=ApiClient.gson.toJsonTree(song));onMessage("已加入 ${item.name}");onClose()} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message} finally {busy=false}}}){Text(item.name)}}}},confirmButton={TextButton(onClick=onClose,enabled=!busy){Text("关闭")}})
}

@Composable
fun NativeCollections(service:PlaybackService,showSongs: @Composable (List<SongItem>, ((SongItem)->Unit)?,((List<SongItem>)->Unit)?,@Composable ()->Unit,@Composable ()->Unit) -> Unit) {
    var items by remember {mutableStateOf<List<LocalCollection>>(emptyList())};var selected by remember {mutableStateOf<LocalCollection?>(null)};var songs by remember {mutableStateOf<List<SongItem>>(emptyList())};var error by remember {mutableStateOf<String?>(null)}
    var editing by remember {mutableStateOf<LocalCollection?>(null)};var dialog by remember {mutableStateOf(false)};var name by remember {mutableStateOf("")};var description by remember {mutableStateOf("")};var deleting by remember {mutableStateOf<LocalCollection?>(null)}
    val scope=rememberCoroutineScope()
    var removing by remember {mutableStateOf<List<SongItem>>(emptyList())}
    var removalBusy by remember {mutableStateOf(false)}
    var coverUrl by remember {mutableStateOf("")}
    val focusManager=androidx.compose.ui.platform.LocalFocusManager.current
    suspend fun refresh(){items=collections()}
    if(removing.isNotEmpty()) AlertDialog(onDismissRequest={if(!removalBusy) removing=emptyList()},title={Text("从歌单移除 ${removing.size} 首歌曲？")},text={Text("音乐文件、收藏和播放队列会保留。")},dismissButton={TextButton(enabled=!removalBusy,onClick={removing=emptyList()}){Text("取消")}},confirmButton={TextButton(enabled=!removalBusy,onClick={val collection=selected?:return@TextButton;val targets=removing.toList();removalBusy=true;scope.launch {
        var success=0;val failures=mutableListOf<String>()
        try {for(song in targets) {try {ApiClient.json("/collections/${collection.id}/songs",params=mapOf("id" to song.id,"source" to song.source),method="DELETE");success++} catch(e:CancellationException){throw e} catch(e:Exception){failures+="${song.name}：${e.message}"}};if(selected?.id==collection.id) songs=ApiClient.decodeSongs(ApiClient.json("/collections/${collection.id}/songs"));error="已移除 $success 首"+if(failures.isNotEmpty()) "\n"+failures.joinToString("\n") else "";removing=emptyList()} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message} finally {removalBusy=false}
    }}){Text(if(removalBusy) "处理中…" else "移除")}})
    LaunchedEffect(Unit){try {refresh()} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message}}
    Column {
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(selected!=null){
            val remove: ((SongItem)->Unit)? = if(selected!!.kind=="imported") null else {song->removing=listOf(song)}
            val batchRemove:((List<SongItem>)->Unit)?=if(selected!!.kind=="imported") null else {targets->removing=targets}
            showSongs(songs,remove,batchRemove,{TextButton(onClick={selected=null}){Text("返回歌单")};Text(selected!!.name,Modifier.padding(vertical=12.dp))},{Button(onClick={service.replaceQueue(songs)},enabled=songs.isNotEmpty()){Text("播放全部")}})
        } else {
            Button(onClick={editing=null;name="";description="";coverUrl="";dialog=true}){Text("新建歌单")}
            LazyColumn {items(items,key={it.id}) {item->Card(modifier=Modifier.fillMaxWidth().padding(vertical=6.dp)){Column(Modifier.padding(16.dp)){TextButton(onClick={scope.launch {try {songs=ApiClient.decodeSongs(ApiClient.json("/collections/${item.id}/songs"));selected=item} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message}}}){Text(item.name)};Row {if(item.kind!="imported") TextButton(modifier=Modifier.semantics {contentDescription="编辑歌单 ${item.name}"},onClick={editing=item;name=item.name;description=item.description;coverUrl=item.cover;dialog=true}){Text("编辑")};TextButton(onClick={deleting=item}){Text("删除歌单")}}}}}}
        }
    }
    if(dialog) AlertDialog(onDismissRequest={dialog=false},title={Text(if(editing==null) "新建歌单" else "编辑歌单")},text={Column {OutlinedTextField(name,{name=it},label={Text("歌单名称")});OutlinedTextField(description,{description=it},label={Text("简介")});OutlinedTextField(coverUrl,{coverUrl=it},label={Text("封面地址（可选）")},singleLine=true)}},confirmButton={TextButton(enabled=name.isNotBlank(),onClick={focusManager.clearFocus();scope.launch {try {val item=editing;ApiClient.json(if(item==null) "/collections" else "/collections/${item.id}",method=if(item==null) "POST" else "PUT",body=JsonObject().apply {addProperty("name",name.trim());addProperty("description",description);addProperty("cover",coverUrl.trim())});refresh();dialog=false} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message}}}){Text("保存")}},dismissButton={TextButton(onClick={dialog=false}){Text("取消")}})
    deleting?.let {item->AlertDialog(onDismissRequest={deleting=null},title={Text("删除 ${item.name}？")},text={Text("会删除本地歌单记录，音乐文件和播放队列会保留。")},confirmButton={TextButton(onClick={scope.launch {try {ApiClient.json("/collections/${item.id}",method="DELETE");refresh();deleting=null} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message}}}){Text("删除")}},dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})}
}
