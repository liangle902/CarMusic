package com.carmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.*
import kotlinx.coroutines.*

@Composable
fun NativeLibrary(service:PlaybackService,favoritesOnly:Boolean,navigate:(String)->Unit,songList:@Composable (List<SongItem>,@Composable ()->Unit,@Composable ()->Unit)->Unit) {
    var source by remember {mutableStateOf("local")};var mode by remember {mutableStateOf("mine")}
    var capabilities by remember {mutableStateOf<List<SourceCapability>>(emptyList())};var linked by remember {mutableStateOf(false)}
    var own by remember {mutableStateOf<List<PlaylistItem>>(emptyList())};var recommended by remember {mutableStateOf<List<PlaylistItem>>(emptyList())}
    var manual by remember {mutableStateOf<List<LocalCollection>>(emptyList())};var categoryLists by remember {mutableStateOf<List<PlaylistItem>?>(null)}
    var selected by remember {mutableStateOf<PlaylistItem?>(null)};var songs by remember {mutableStateOf<List<SongItem>?>(null)}
    var busy by remember {mutableStateOf(false)};var error by remember {mutableStateOf("")};var categories by remember {mutableStateOf(false)};var sort by remember {mutableStateOf(false)}
    val favorites by AppStore.favorites.collectAsState();val scope=rememberCoroutineScope();val colors=MaterialTheme.colorScheme
    suspend fun load(){busy=true;error="";try {
        if(source=="local") {manual=ApiClient.gson.fromJson(ApiClient.json("/collections",mapOf("include_imported" to "1")),Array<LocalCollection>::class.java).toList()}
        else {linked=ApiClient.json("/cookies").asJsonObject.get(source)?.asString?.isNotBlank()==true
            val capability=capabilities.firstOrNull {it.id==source}
            val personal=if(linked&&capability?.personal==true) ApiClient.playlists(source,true) else emptyList<PlaylistItem>() to ""
            val discover=if(!favoritesOnly&&capability?.recommend==true) ApiClient.playlists(source,false) else emptyList<PlaylistItem>() to ""
            if(personal.second.isBlank()||personal.first.isNotEmpty()) own=personal.first
            if(discover.second.isBlank()||discover.first.isNotEmpty()) recommended=discover.first
            mode=if(own.isNotEmpty()||favoritesOnly) "mine" else "recommended"
            error=personal.second.ifBlank {discover.second}
            if(own.isNotEmpty()) AppStore.cachePlaylists("${source}_personal",own)
            if(recommended.isNotEmpty()) AppStore.cachePlaylists("${source}_recommended",recommended)
        }
    } catch(e:CancellationException){throw e} catch(e:Exception){error=e.message?:"歌单加载失败"} finally {busy=false}}
    LaunchedEffect(Unit){try {capabilities=ApiClient.sources().filter {it.personal||(!favoritesOnly&&it.recommend)}} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message?:"平台列表加载失败"}}
    LaunchedEffect(source,capabilities){songs=null;selected=null;categoryLists=null;own=AppStore.cachedPlaylists("${source}_personal");recommended=AppStore.cachedPlaylists("${source}_recommended");load()}
    LaunchedEffect(source,mode){if(source=="local"&&mode=="recommended"){busy=true;try {val result=ApiClient.playlists("qq",false);recommended=result.first;error=result.second} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message?:"推荐加载失败"} finally {busy=false}}}
    fun open(item:PlaylistItem,localSongs:List<SongItem>?=null){scope.launch {busy=true;error="";try {songs=localSongs?:if(item.source=="local") ApiClient.decodeSongs(ApiClient.json("/collections/${item.id}/songs")) else ApiClient.playlistSongs(item);selected=item} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message?:"歌单读取失败"} finally {busy=false}}}
    Column(Modifier.fillMaxSize()) {
        if(songs==null) {
        Text(if(favoritesOnly) "喜欢的歌，都在这里" else "一张歌单，一份心情",fontSize=12.sp,color=colors.onSurfaceVariant,modifier=Modifier.padding(bottom=8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {FilterChip(source=="local",{source="local"},label={Text(if(favoritesOnly) "APP 收藏" else "本地歌单")},modifier=Modifier.padding(end=8.dp));capabilities.forEach {item->FilterChip(source==item.id,{source=item.id},label={Text(item.name)},modifier=Modifier.padding(end=8.dp))}}
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(error.isNotBlank()){Row(verticalAlignment=Alignment.CenterVertically){Text(error,Modifier.weight(1f),color=colors.error,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);TextButton(onClick={scope.launch {load()}}){Text("重试")}}}
        }
        if(songs!=null) {
            val list=songs!!
            songList(list,{
            TextButton(onClick={songs=null;selected=null}){Text("返回歌单")}
            selected?.let {LibraryHero(it,list.size)}
            },{
            Button(onClick={service.replaceQueue(list);navigate("正在播放")},enabled=list.isNotEmpty()){Text("播放全部")}
            selected?.takeIf {it.source!="local"}?.let {ImportPlaylistButton(it)}
            })
        } else if(favoritesOnly && source=="local") {
            songList(if(sort) favorites.sortedBy {it.name} else favorites,{
            LibraryHero(PlaylistItem(name="我喜欢的音乐",description="收藏保存在当前 APP"),favorites.size,true)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("歌曲 · ${favorites.size}",fontSize=12.sp);TextButton(onClick={sort=!sort}){Text(if(sort) "按歌名排序" else "按收藏时间",fontSize=12.sp)}}
            },{Button(onClick={service.replaceQueue(favorites);navigate("正在播放")},enabled=favorites.isNotEmpty()){Text("播放全部")}})
        } else {
            if(source!="local") Row(Modifier.fillMaxWidth().padding(vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(if(linked) "已关联平台账号" else "尚未关联平台账号",fontSize=11.sp,color=colors.onSurfaceVariant);TextButton(onClick={navigate("系统设置")}){Text(if(linked) "账号管理" else "关联账号",fontSize=11.sp)}}
            Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                if(!favoritesOnly) Column(Modifier.width(78.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("mine" to "我的歌单","recommended" to "推荐歌单").forEach {(id,label)->Surface(onClick={mode=id;categoryLists=null},color=if(mode==id) colors.primaryContainer else colors.background,shape=RoundedCornerShape(10.dp)){Text(label,fontSize=12.sp,modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=14.dp))}}
                    if(source=="local") {TextButton(onClick={navigate("本地音乐")}){Text("本地音乐",fontSize=11.sp)};TextButton(onClick={navigate("下载管理")}){Text("下载管理",fontSize=11.sp)};TextButton(onClick={navigate("本地歌单")}){Text("新建歌单",fontSize=11.sp)}}
                    else if(mode=="recommended") TextButton(onClick={categories=true}){Text("分类",fontSize=11.sp)}
                }
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(if(mode=="mine") "我的歌单" else "推荐歌单",fontSize=17.sp);TextButton(onClick={scope.launch {load()}}){Text("刷新",fontSize=11.sp)}}
                    val lists=if(source=="local"&&mode=="mine") listOf(PlaylistItem(id="app-favorites",name="我喜欢的音乐",creator="APP 收藏",trackCount=favorites.size))+manual.map {PlaylistItem(id=it.id,name=it.name,description=it.description,cover=it.cover,creator="本地歌单")} else categoryLists?:if(mode=="mine") own else recommended
                    if(lists.isEmpty()&&!busy) {Text(if(source!="local"&&!linked&&mode=="mine") "关联账号后查看你的歌单" else "暂无歌单",fontSize=13.sp,color=colors.onSurfaceVariant,modifier=Modifier.padding(vertical=24.dp));if(!linked&&source!="local"&&mode=="mine") Button(onClick={navigate("系统设置")}){Text("关联账号")}}
                    LazyVerticalGrid(GridCells.Fixed(2),modifier=Modifier,horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {items(lists,key={it.source+":"+it.id}) {item->Column(Modifier.clickable {open(item,if(item.id=="app-favorites") favorites else null)}) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant),contentAlignment=Alignment.Center) {
                            if(item.id=="app-favorites") MusicIcon("我的收藏",Modifier.fillMaxSize(.4f)) else if(item.cover.isBlank()) StarLogo(Modifier.fillMaxSize(.6f)) else AsyncImage(ApiClient.coverUrl(item.source,item.cover),item.name,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                        }
                        Text(item.name,fontSize=13.sp,maxLines=2,modifier=Modifier.padding(top=8.dp));Text(item.creator.ifBlank {"${item.trackCount} 首歌曲"},fontSize=10.sp,color=colors.onSurfaceVariant,maxLines=1,modifier=Modifier.padding(top=5.dp))
                    }}}
                }
            }
        }
    }
    if(categories) PlaylistCategories(source,{categories=false}) {_,items->categoryLists=items}
}

@Composable
private fun LibraryHero(item:PlaylistItem,count:Int,heart:Boolean=false) {
    val compact=androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp<600
    var detail by remember(item.id) {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().padding(vertical=if(compact) 6.dp else 16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(if(compact) 48.dp else 84.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){if(heart) MusicIcon("我的收藏",Modifier.fillMaxSize(.5f)) else if(item.cover.isBlank()) StarLogo(Modifier.fillMaxSize(.6f)) else AsyncImage(ApiClient.coverUrl(item.source,item.cover),item.name,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
        Column(Modifier.weight(1f)){Text(item.name,fontSize=if(compact) 16.sp else 22.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);Text("$count 首歌曲",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);if(!compact&&item.description.isNotBlank()) Text(item.description,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)}
        if(item.description.isNotBlank()) TextButton(onClick={detail=true}){Text("简介")}
    }
    if(detail) AlertDialog(onDismissRequest={detail=false},title={Text(item.name)},text={Text(item.description)},confirmButton={TextButton(onClick={detail=false}){Text("关闭")}})
}
