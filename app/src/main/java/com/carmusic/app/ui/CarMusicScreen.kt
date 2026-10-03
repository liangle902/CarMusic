package com.carmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarMusicScreen(service: PlaybackService) {
    var page by rememberSaveable { mutableStateOf("首页") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var splash by rememberSaveable { mutableStateOf(true) }
    LocalMusicPermissionPrompt(ready = !splash)
    BackHandler(page != "首页" || queueOpen || splash) {
        if (splash) splash = false else if (queueOpen) queueOpen = false
        else page = if (page in listOf("本地音乐", "视频制作")) "系统设置" else if (page == "本地歌单") "歌单列表" else "首页"
    }
    val current by service.currentSong.collectAsState()
    val playing by service.isPlaying.collectAsState()
    val colors = MaterialTheme.colorScheme
    val view = androidx.compose.ui.platform.LocalView.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    DisposableEffect(splash, colors.background) {
        val window = (view.context as android.app.Activity).window
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, view)
        window.statusBarColor = colors.background.toArgb(); window.navigationBarColor = colors.background.toArgb()
        controller.isAppearanceLightStatusBars = colors.background.luminance() > .5f
        controller.isAppearanceLightNavigationBars = colors.background.luminance() > .5f
        if (splash) controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        else controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(Unit) {
        launch { try { ApiClient.settings() } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
        delay(2400); splash = false
    }
    val engineSettings by AppStore.engineSettings.collectAsState()
    LaunchedEffect(engineSettings?.get("autoCheckUpdate")?.asBoolean) {
        if (engineSettings?.get("autoCheckUpdate")?.asBoolean == true)
            try { AppStore.upstreamUpdate.value = ApiClient.json("/app_update/check").asJsonObject }
            catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val insets = WindowInsets.safeDrawing.asPaddingValues()
            val layout = MusicWindow(maxWidth - insets.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) - insets.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                maxHeight - insets.calculateTopPadding() - insets.calculateBottomPadding())
            CompositionLocalProvider(LocalMusicWindow provides layout) {
                val navigate: (String) -> Unit = { target ->
                    focus.clearFocus(); page = target
                    if (layout.width < 600.dp || layout.compactHeight) expanded = false
                }
                Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Column(Modifier.width(if (expanded) 150.dp else 64.dp).fillMaxHeight().background(colors.surface).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        StarLogo(Modifier.padding(vertical = if (layout.compactHeight) 8.dp else 20.dp).size(if (layout.compactHeight) 32.dp else 44.dp))
                        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = if (expanded) "收起侧栏" else "展开侧栏" }) { Text(if (expanded) "‹ 收起" else "›") }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("首页", "正在播放", "歌单列表", "我的收藏", "系统设置", "全网搜索", "本地音乐", "下载管理").forEach { name ->
                                Surface(onClick = { navigate(name) }, color = if (page == name) colors.primaryContainer else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$name 导航" }) {
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = if (layout.compactHeight) 8.dp else 14.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center) {
                                        MusicIcon(name, Modifier.size(22.dp), if (page == name) colors.primary else colors.onSurfaceVariant)
                                        if (expanded) Text(name, Modifier.padding(start = 10.dp), fontSize = 13.sp, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = if (layout.horizontal && !layout.compactHeight) 24.dp else 18.dp)) {
                        if (page != "全网搜索" || !layout.horizontal) MusicPageHeader(page, when(page) {
                            "首页" -> MUSIC_SLOGAN
                            "歌单列表" -> "一张歌单，一份心情"
                            "我的收藏" -> "喜欢的歌，都在这里"
                            else -> null
                        }) {
                            if (page in listOf("歌单列表", "我的收藏")) IconButton(onClick = { navigate("全网搜索") }, modifier = Modifier.semantics { contentDescription = "搜索音乐" }) { MusicIcon("全网搜索") }
                        }
                        Box(Modifier.weight(1f)) {
                            when (page) {
                                "首页" -> NativeHome(service, navigate)
                                "正在播放" -> NativePlayer(service) { queueOpen = true }
                                "歌单列表" -> NativeLibrary(service, false, navigate) { songs, header, actions -> SongList(songs, service, header = header, actions = actions) }
                                "我的收藏" -> NativeLibrary(service, true, navigate) { songs, header, actions -> SongList(songs, service, "还没有收藏的音乐", header = header, actions = actions) }
                                "全网搜索" -> NativeSearch(service)
                                "系统设置" -> NativeSettings(service, { splash = true }, { navigate("本地音乐") }, { navigate("视频制作") })
                                "本地音乐" -> LocalLibrary(service) { songs, remove, batchRemove -> SongList(songs, service, "导入音乐后可离线播放", remove, batchRemove, "移除本地音乐") }
                                "视频制作" -> VideoEditor(current, service)
                                "本地歌单" -> NativeCollections(service) { songs, remove, batchRemove, header, actions -> SongList(songs, service, remove = remove, batchRemove = batchRemove, header = header, actions = actions) }
                                "最近听过" -> {
                                    val recent by AppStore.recent.collectAsState(); var clear by remember { mutableStateOf(false) }
                                    Column { TextButton(onClick = { clear = true }, enabled = recent.isNotEmpty()) { Text("清空播放历史") }; SongList(recent, service, "播放过的音乐会留在这里") }
                                    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空播放历史？") }, text = { Text("不会修改播放队列、收藏或歌单。") }, confirmButton = { TextButton(onClick = { AppStore.clearRecent(); clear = false }) { Text("清空") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
                                }
                                "下载管理" -> NativeDownloadManager(service) { SongList(it, service, "还没有下载的音乐") }
                            }
                        }
                        if (page !in listOf("正在播放", "首页") && current != null) {
                            Surface(onClick = { navigate("正在播放") }, tonalElevation = 3.dp, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(vertical = if (layout.compactHeight) 4.dp else 12.dp)) {
                                if (layout.compactHeight) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { SongTitle(current!!.name, fontSize = 13.sp); MiniLyric(service, compact = true) }
                                    IconButton(onClick = service::togglePlayPause, modifier = Modifier.semantics { contentDescription = if (playing) "暂停" else "播放" }) { MusicIcon(if (playing) "暂停" else "播放") }
                                    IconButton(onClick = { queueOpen = true }, modifier = Modifier.semantics { contentDescription = "打开播放队列" }) { MusicIcon("播放队列") }
                                } else Column {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        SongTitle(current!!.name, Modifier.weight(1f))
                                        IconButton(onClick = service::togglePlayPause, modifier = Modifier.semantics { contentDescription = if (playing) "暂停" else "播放" }) { MusicIcon(if (playing) "暂停" else "播放") }
                                        IconButton(onClick = { queueOpen = true }, modifier = Modifier.semantics { contentDescription = "打开播放队列" }) { MusicIcon("播放队列") }
                                    }
                                    MiniLyric(service)
                                }
                            }
                        }
                    }
                }
                if (queueOpen) ModalBottomSheet(onDismissRequest = { queueOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { NativeQueue(service) }
                if (splash) Surface(Modifier.fillMaxSize().clickable { splash = false }, color = Color(0xFF0C2029)) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        StarLogo(Modifier.size(if (layout.compactHeight) 64.dp else 120.dp), animated = true)
                        Spacer(Modifier.height(if (layout.compactHeight) 12.dp else 30.dp)); Text("星河音乐", fontSize = if (layout.compactHeight) 28.sp else 36.sp, color = Color.White)
                        Spacer(Modifier.height(if (layout.compactHeight) 12.dp else 28.dp))
                        FlowingSlogan(Modifier.widthIn(max = 480.dp).fillMaxWidth(.9f).height(if (layout.compactHeight) 52.dp else 70.dp))
                        Text("发现、收藏、随时聆听。", color = Color(0xFFADBEC6), modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicPageHeader(title: String, slogan: String? = null, showBrand: Boolean = true, trailing: @Composable RowScope.() -> Unit = {}) {
    val layout = LocalMusicWindow.current
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = if(layout.compactHeader) 4.dp else 22.dp).heightIn(min = if(layout.compactHeader) 40.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        if(layout.compactHeader) {
            if(showBrand && (layout.width >= 760.dp || !layout.horizontal)) Text("星河音乐", fontSize=12.sp,color=colors.primary,modifier=Modifier.padding(end=16.dp))
            Text(title,fontSize=20.sp,maxLines=1)
            if(layout.horizontal && slogan!=null) Text(slogan,Modifier.weight(1f).padding(start=20.dp),fontSize=12.sp,color=colors.onSurfaceVariant,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            else if(title!="全网搜索" || !layout.horizontal) Spacer(Modifier.weight(1f))
        } else Column(Modifier.weight(1f)) {
            Text("星河音乐",fontSize=13.sp,color=colors.primary)
            if(title!="首页") Text(title,fontSize=26.sp)
        }
        trailing()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongList(songs: List<SongItem>, service: PlaybackService, empty: String = "暂无歌曲", remove: ((SongItem)->Unit)? = null,batchRemove:((List<SongItem>)->Unit)?=null,removeLabel:String="从歌单移除",header:(@Composable ()->Unit)?=null,actions:(@Composable ()->Unit)?=null) {
    val favorites by AppStore.favorites.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<SongItem?>(null) }
    var switching by remember { mutableStateOf<SongItem?>(null) }
    var exporting by remember {mutableStateOf<Pair<SongItem,Boolean>?>(null)}
    val context=androidx.compose.ui.platform.LocalContext.current
    var batch by remember {mutableStateOf(false)}
    var selectedKeys by remember {mutableStateOf(setOf<String>())}
    var batchAdding by remember {mutableStateOf(false)}
    var batchJob by remember {mutableStateOf<Job?>(null)}
    val checks by AppStore.playbackChecks.collectAsState()
    val selectedSongs=songs.filter {it.key in selectedKeys}
    LazyColumn(Modifier.fillMaxSize()) {
        header?.let {item(key="playlist-header") {Column(Modifier.fillMaxWidth()) {it()}}}
        if(songs.isNotEmpty()) item {
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                if(!batch) actions?.invoke()
                TextButton(onClick={batch=!batch;if(!batch) selectedKeys=emptySet()}){Text(if(batch) "完成选择 · ${selectedSongs.size} 首" else "多选")}
            }
            if(batch) {
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick={selectedKeys=if(selectedSongs.size==songs.size) emptySet() else songs.map {it.key}.toSet()}){Text("全选 / 取消")}
                    TextButton(onClick={selectedKeys=songs.filter {checks[it.key] in listOf("音源失效","当前源失效","未找到可用音源")}.map {it.key}.toSet()}){Text("选择失效音源")}
                    TextButton(onClick={batchAdding=true},enabled=selectedSongs.isNotEmpty()){Text("加入本地歌单")}
                    if(batchRemove!=null) TextButton(onClick={batchRemove(selectedSongs)},enabled=selectedSongs.isNotEmpty()){Text(removeLabel)}
                    TextButton(enabled=selectedSongs.any {it.source!="local"}&&batchJob?.isActive!=true,onClick={val targets=selectedSongs.filter {it.source!="local"};batchJob=scope.launch {
                        val links=mutableListOf<String>();val failures=mutableListOf<String>()
                        for(target in targets){try {val info=ApiClient.inspectStream(ApiClient.resolvePlayable(target));check(info.valid&&info.url.startsWith("http")) {"当前源失效"};links+=info.url} catch(e:CancellationException){throw e} catch(e:Exception){failures+="${target.name}：${e.message}"};message="已解析 ${links.size+failures.size} / ${targets.size} 首"}
                        if(links.isNotEmpty()) (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("音乐下载链接",links.joinToString("\n")))
                        message="已复制 ${links.size} 条下载链接"+if(failures.isNotEmpty()) "\n"+failures.joinToString("\n") else ""
                    }}){Text("复制下载链接")}
                    TextButton(enabled=selectedSongs.any {it.source!="local"}&&batchJob?.isActive!=true,onClick={val targets=selectedSongs.filter {it.source!="local"};batchJob=scope.launch {var success=0;var failed=0;for(target in targets){try {val current=AppStore.restoredResolved(target)?:target;val replacement=ApiClient.switchSource(current);AppStore.saveResolved(target,replacement);AppStore.markPlayback(target,"可播放");success++} catch(e:CancellationException){throw e} catch(_:Exception){failed++};message="换源 $success 成功，$failed 失败 / ${targets.size} 首"}}}){Text("批量换源")}
                    if(batchJob?.isActive==true) TextButton(onClick={batchJob?.cancel();message="已取消剩余操作"}){Text("取消操作")}
                    BatchDownload(selectedSongs)
                }
            }
        }
        message?.let { item {Text(it,Modifier.padding(vertical=12.dp))} }
        if (songs.isEmpty()) item { Text(empty, Modifier.padding(vertical = 30.dp)) }
        items(songs, key = { it.key }) { song ->
            var menu by remember { mutableStateOf(false) }
            val compact = LocalMusicWindow.current.compactHeight
            Row(Modifier.fillMaxWidth().padding(vertical = if(compact) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if(batch) Checkbox(song.key in selectedKeys,{checked->selectedKeys=if(checked) selectedKeys+song.key else selectedKeys-song.key},modifier=Modifier.semantics {contentDescription="选择 ${song.name}"})
                AsyncImage(ApiClient.coverUrl(song.source,song.cover),"封面",Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).clickable {service.playSongFirst(song)})
                Column(Modifier.weight(1f).padding(start = 12.dp).clickable {service.playSongFirst(song)}) { SongTitle(song.name,fontSize=if(compact) 14.sp else androidx.compose.ui.unit.TextUnit.Unspecified,lineHeight=if(compact) 18.sp else androidx.compose.ui.unit.TextUnit.Unspecified); Text(song.artist, fontSize = 12.sp,lineHeight=if(compact) 16.sp else androidx.compose.ui.unit.TextUnit.Unspecified, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1);SongSourceStatus(song) }
                val liked=favorites.any {it.key==song.key}
                TextButton(onClick = { AppStore.toggleFavorite(song) }) { Text(if(liked) "♥" else "♡", color = if(liked) Color(0xFFE85063) else MaterialTheme.colorScheme.onSurfaceVariant) }
                Box { TextButton(onClick = {menu=true},modifier=Modifier.semantics {contentDescription="歌曲操作 ${song.name}"}) {Text("⋮")}; DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem(text={Text("下一首播放")},onClick={service.addToQueue(song,true);menu=false})
                    DropdownMenuItem(text={Text("加入播放队列")},onClick={service.addToQueue(song);menu=false})
                    DropdownMenuItem(text={Text("加入本地歌单")},onClick={adding=song;menu=false})
                    if(song.source!="local") DropdownMenuItem(text={Text("切换音源")},onClick={switching=song;menu=false})
                    DropdownMenuItem(text={Text("导出歌词")},onClick={exporting=song to false;menu=false})
                    if(song.cover.isNotBlank()) DropdownMenuItem(text={Text("导出封面")},onClick={exporting=song to true;menu=false})
                    if(song.source!="local") DropdownMenuItem(text={Text("复制下载链接")},onClick={menu=false;scope.launch {try {val info=ApiClient.inspectStream(ApiClient.resolvePlayable(song));check(info.valid&&info.url.startsWith("http")) {"当前音源没有可用下载链接"};(context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(song.name,info.url));message="下载链接已复制"} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message}}})
                    if(remove!=null) DropdownMenuItem(text={Text(removeLabel)},onClick={remove(song);menu=false})
                    if(song.source!="local") DropdownMenuItem(text={Text("下载音乐")},onClick={menu=false;scope.launch {message="正在下载 ${song.name}…";try {val result=ApiClient.download(song);message="下载完成：${song.name}"+result.notice.takeIf {it.isNotBlank()}?.let {"\n$it"}.orEmpty()} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message}}})
                } }
            }
            HorizontalDivider()
        }
    }
    adding?.let { CollectionPicker(it,{adding=null},{message=it}) }
    switching?.let { SourcePicker(it,service,{switching=null}) }
    exporting?.let {(song,cover)->SongAssetExport(song,cover,{exporting=null},{message=it})}
    if(batchAdding) CollectionPicker(selectedSongs,{batchAdding=false},{message=it})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NativeSearch(service: PlaybackService) {
    var opened by remember {mutableStateOf<PlaylistItem?>(null)}
    var query by remember { mutableStateOf("") }; var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var kind by remember {mutableStateOf("song")};var resultKind by remember {mutableStateOf("song")};var selected by remember {mutableStateOf(setOf("qq","netease","kuwo"))};var sources by remember {mutableStateOf<List<SourceCapability>>(emptyList())};var groups by remember {mutableStateOf<List<PlaylistItem>>(emptyList())}
    var sourceResults by remember {mutableStateOf<Map<String,String>>(emptyMap())}
    var previousGroups by remember {mutableStateOf<List<PlaylistItem>>(emptyList())}
    var sourceDetails by remember {mutableStateOf(false)}
    var sourcePicker by remember {mutableStateOf(false)}
    var draftSources by remember {mutableStateOf(selected)}
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val eligible by remember {derivedStateOf {sources.filter {it.search&&when(kind){"playlist"->it.playlist;"album"->it.album;else->true}}}}
    val canSearch by remember {derivedStateOf {query.isNotBlank() && eligible.any {it.id in selected} && !busy}}
    LaunchedEffect(Unit){try {sources=ApiClient.sources()} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message}}
    val search: () -> Unit = { scope.launch {
            busy=true;error=null;opened=null
            try {
                val requestedKind=kind;val requestedQuery=query.trim()
                val allowed=selected.filter {id->sources.firstOrNull {it.id==id}?.let {it.search&&when(requestedKind){"playlist"->it.playlist;"album"->it.album;else->true}}?:false}
                require(allowed.isNotEmpty()) {"请选择支持此类搜索的平台"}
                sourceResults=allowed.associateWith {"搜索中…"}
                val responses=coroutineScope {allowed.map {id->async {
                    try {val response=ApiClient.json("/search",mapOf("q" to requestedQuery,"type" to requestedKind,"sources" to id,"format" to "json")).asJsonObject
                        val found=ApiClient.decodeSongs(response.get("songs"));val entries=response.get("playlists");val lists=if(entries==null||entries.isJsonNull) emptyList() else ApiClient.gson.fromJson(entries,Array<PlaylistItem>::class.java).toList()
                        val reason=response.get("error")?.takeUnless {it.isJsonNull}?.asString.orEmpty()
                        sourceResults=sourceResults+(id to if(reason.isNotBlank()) "搜索失败：$reason" else "搜索可用 · ${found.size+lists.size} 条结果")
                        found to lists
                    } catch(e:CancellationException){throw e} catch(e:Exception){sourceResults=sourceResults+(id to "搜索失败：${e.message}");emptyList<SongItem>() to emptyList<PlaylistItem>()}
                }}.awaitAll()}
                songs=responses.flatMap {it.first}.distinctBy {it.key};groups=responses.flatMap {it.second}.distinctBy {it.source+":"+it.id};resultKind=requestedKind
            } catch(e: CancellationException) {throw e} catch(e:Exception) {error=e.message} finally {busy=false}
        } }
    val searchEntry = remember { movableContentOf {
        val horizontal=LocalMusicWindow.current.horizontal
        Row(if(horizontal) Modifier.widthIn(max=420.dp).fillMaxWidth().padding(start=20.dp) else Modifier.fillMaxWidth().padding(bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.weight(1f),color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(24.dp)) {
                Row(Modifier.height(48.dp).padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    MusicIcon("全网搜索",Modifier.size(20.dp),MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.text.BasicTextField(query,{query=it},Modifier.weight(1f),singleLine=true,
                        textStyle=androidx.compose.ui.text.TextStyle(color=MaterialTheme.colorScheme.onSurface,fontSize=14.sp),
                        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSearch={if(canSearch){focus.clearFocus();search()}}),
                        decorationBox={field->Box {if(query.isEmpty()) Text("歌曲、歌手或分享链接",fontSize=13.sp,maxLines=1,color=MaterialTheme.colorScheme.onSurfaceVariant);field()}})
                }
            }
            Button(onClick={focus.clearFocus();search()},enabled=canSearch,modifier=Modifier.heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=16.dp)){Text(if(busy) "搜索中" else "搜索",fontSize=13.sp)}
        }
    } }
    val filterButtons = remember { movableContentOf {
            listOf("song" to "歌曲","playlist" to "歌单","album" to "专辑").forEach {(id,name)->TextButton(onClick={kind=id},colors=ButtonDefaults.textButtonColors(containerColor=if(kind==id) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),modifier=Modifier.heightIn(min=48.dp)){Text(name,color=if(kind==id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp)}}
            TextButton(onClick={draftSources=selected;sourcePicker=true},modifier=Modifier.heightIn(min=48.dp)){Text("音源 · ${eligible.count {it.id in selected}}",fontSize=12.sp)}
            if(sourceResults.isNotEmpty()) TextButton(onClick={sourceDetails=true},modifier=Modifier.heightIn(min=48.dp)){Text("${sourceResults.count {it.value.startsWith("搜索可用")}} / ${sourceResults.size} 可用",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    } }
    val results = remember { movableContentOf {
        if(groups.isEmpty()) SongList(songs,service,if(sourceResults.isEmpty()) "输入歌名、歌手或分享链接开始搜索" else if(busy) "正在搜索…" else "暂无结果，换个关键词或平台试试",actions={filterButtons()})
        else LazyColumn {items(groups,key={it.source+":"+it.id}) {item->Card(onClick={scope.launch {busy=true;try {songs=ApiClient.playlistSongs(item,resultKind=="album");previousGroups=groups;opened=item;groups=emptyList()} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message} finally {busy=false}}},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp)){Row(Modifier.padding(16.dp)){AsyncImage(ApiClient.coverUrl(item.source,item.cover),"封面",Modifier.size(58.dp));Column(Modifier.padding(start=12.dp)){Text(item.name);Text(item.creator,fontSize=12.sp)}}}}}    } }
    Column(Modifier.fillMaxSize()) {
    if(LocalMusicWindow.current.horizontal) MusicPageHeader("全网搜索",showBrand=false) { Box(Modifier.weight(1f),contentAlignment=Alignment.CenterEnd) {searchEntry()} }
    Box(Modifier.weight(1f)) {
        if (opened != null) {
            SongList(songs,service,header={
                TextButton(onClick={opened=null;groups=previousGroups}){Text("返回搜索结果")}
                Text(opened!!.name,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            },actions={Button(onClick={service.replaceQueue(songs)},enabled=songs.isNotEmpty()){Text("播放全部")};ImportPlaylistButton(opened!!,resultKind=="album")})
        } else Column(Modifier.fillMaxSize()) {
            if(!LocalMusicWindow.current.horizontal) searchEntry()
            if(groups.isNotEmpty()||songs.isEmpty()) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){filterButtons()}
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}
            Box(Modifier.weight(1f)) { results() }
        }
    }
    }
    if(sourcePicker) AlertDialog(onDismissRequest={sourcePicker=false},modifier=Modifier.widthIn(max=if(LocalMusicWindow.current.horizontal) 560.dp else 360.dp).fillMaxWidth(.9f),properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false),title={Text("选择搜索音源")},text={Column(Modifier.heightIn(max=musicDialogContentHeight()).verticalScroll(rememberScrollState())) {
        if(eligible.isEmpty()) Text("当前类型暂无可用音源")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {eligible.forEach {item->FilterChip(item.id in draftSources,{draftSources=if(item.id in draftSources) draftSources-item.id else draftSources+item.id},label={Text(item.name)},leadingIcon=if(item.id in draftSources) {{Text("✓")}} else null)}}
        TextButton(onClick={draftSources=draftSources+eligible.map {it.id}}){Text("选择全部")}
    }},confirmButton={TextButton(onClick={selected=draftSources;sourcePicker=false},enabled=eligible.any {it.id in draftSources}){Text("完成")}},dismissButton={TextButton(onClick={sourcePicker=false}){Text("取消")}})
    if(sourceDetails) AlertDialog(onDismissRequest={sourceDetails=false},title={Text("搜索源状态")},text={Column(Modifier.heightIn(max=musicDialogContentHeight(360.dp)).verticalScroll(rememberScrollState())) {sourceResults.forEach {(id,status)->Text("${sourceName(id)} · $status",fontSize=12.sp,color=if(status.startsWith("搜索失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=6.dp))}}},confirmButton={TextButton(onClick={sourceDetails=false}){Text("关闭")}})
}

@Composable
private fun NativeSettings(service:PlaybackService,replay: () -> Unit, library: () -> Unit, video: () -> Unit) {
    var section by rememberSaveable {mutableStateOf("播放与显示")}
    val sections=listOf("播放与显示","平台账号","常规","下载与存储","WebDAV 同步","高级选项","关于")
    val groups=mapOf("常规" to setOf("disableFloatingLyrics","webPageSize","autoSwitchInvalidSources","autoCheckUpdate","updateRepoUrl"),"下载与存储" to setOf("embedDownload","downloadToLocal","downloadDir","downloadFilenameTemplate","downloadTipDuration","downloadConcurrency","autoCacheOnPlay"),"WebDAV 同步" to setOf("webdavEnabled","webdavUrl","webdavUsername","webdavPassword","webdavDir"),"高级选项" to setOf("cliPageSize","githubProxyEnabled","githubProxyUrl","vgChangeCover","vgChangeAudio","vgChangeLyric","vgExportVideo"))
    val theme by AppStore.theme.collectAsState(); val autoplay by AppStore.autoplay.collectAsState(); val vinyl by AppStore.vinyl.collectAsState()
    var config by remember {mutableStateOf<JsonObject?>(null)};var error by remember {mutableStateOf<String?>(null)};var about by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit) {try {config=ApiClient.settings()} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message}}
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide=maxWidth>=560.dp
    Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(if(wide) 24.dp else 0.dp)) {
    if(wide) Column(Modifier.width(138.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){sections.forEach {name->Surface(onClick={section=name},color=if(section==name) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,shape=RoundedCornerShape(12.dp)){Text(name,Modifier.fillMaxWidth().padding(14.dp),fontSize=14.sp)}}}
    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(!wide) Row(Modifier.horizontalScroll(rememberScrollState())) {sections.forEach {name->FilterChip(section==name,{section=name},label={Text(name)},modifier=Modifier.padding(end=8.dp))}}
        Text(section,fontSize=22.sp)
        if(section=="播放与显示") {
        Row(Modifier.horizontalScroll(rememberScrollState())) {listOf("system" to "跟随系统","day" to "日间","night" to "夜间").forEach {(id,name)->FilterChip(theme==id,{AppStore.setTheme(id)},label={Text(name)},modifier=Modifier.padding(end=6.dp))}}
        SettingSwitch("启动后自动播放",autoplay,AppStore::setAutoplay)
        val notificationControls by AppStore.notificationControls.collectAsState()
        SettingSwitch("通知栏播放控制",notificationControls,AppStore::setNotificationControls)
        Text("后台播放时保留系统必需通知",style=MaterialTheme.typography.bodySmall)
        SettingSwitch("黑胶封面",vinyl,AppStore::setVinyl)
        TextButton(onClick=replay){Text("重播开屏动画")}
        Text("恢复上次的队列与进度 · 始终保留",style=MaterialTheme.typography.bodySmall)
        }
        if(section=="下载与存储") {
        val autoScanLocal by AppStore.autoScanLocal.collectAsState()
        SettingSwitch("自动扫描本地音乐与U盘",autoScanLocal,AppStore::setAutoScanLocal)
        TextButton(onClick=library){Text("本地音乐与下载")}
        MaintenanceTools(service)
        }
        if(section=="高级选项") TextButton(onClick=video){Text("音乐视频制作")}
        if(section=="平台账号") AccountSettings()
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(section in groups) config?.let {settings ->
            val labels=mapOf("embedDownload" to "下载嵌入封面与歌词","downloadToLocal" to "下载到本地","downloadDir" to "下载目录","downloadFilenameTemplate" to "文件命名模板","downloadTipDuration" to "下载提示时长","webdavEnabled" to "启用 WebDAV","webdavUrl" to "WebDAV 地址","webdavUsername" to "WebDAV 用户名","webdavPassword" to "WebDAV 密码","webdavDir" to "WebDAV 目录","disableFloatingLyrics" to "关闭悬浮歌词","webPageSize" to "网页每页数量","cliPageSize" to "命令行每页数量","downloadConcurrency" to "同时下载数量","autoCheckUpdate" to "自动检查更新","autoSwitchInvalidSources" to "失效音源自动切换","autoCacheOnPlay" to "播放时自动缓存","updateRepoUrl" to "更新项目地址","githubProxyEnabled" to "启用 GitHub 代理","githubProxyUrl" to "GitHub 代理地址","vgChangeCover" to "视频制作：更换封面","vgChangeAudio" to "视频制作：更换音频","vgChangeLyric" to "视频制作：更换歌词","vgExportVideo" to "视频制作：导出视频")
            settings.entrySet().filter {it.key in groups.getValue(section)}.forEach { (key,value) ->
                if(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) SettingSwitch(labels[key]?:key,value.asBoolean) {checked->config=settings.deepCopy().apply {addProperty(key,checked)}}
                else if(value.isJsonPrimitive) OutlinedTextField(value.asString,{input-> config=settings.deepCopy().apply { if(value.asJsonPrimitive.isNumber) input.toIntOrNull()?.let {addProperty(key,it)} else addProperty(key,input)}},Modifier.fillMaxWidth(),label={Text(labels[key]?:key)},singleLine=true,visualTransformation=if(key.contains("Password")) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
            }
            Button(onClick={scope.launch {try {ApiClient.saveSettings(settings);error="已保存"} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message}}}) {Text("保存上游配置")}
        }
        if(section=="关于") {Text("让好音乐，随心而听。");Text("显示方式 · 横竖屏自适应");Text("功能来源 · GoMusicDll");TextButton(onClick={about=true}){Text("关于星河音乐")}}
    }
    }
    }
    if(about) AlertDialog(onDismissRequest={about=false},title={Text("星河音乐 · ${com.carmusic.app.BuildConfig.VERSION_NAME}")},text={Column(Modifier.heightIn(max=musicDialogContentHeight(440.dp)).verticalScroll(rememberScrollState())) {Text("让好音乐，随心而听。");Text("功能来源：GoMusicDll");Text("Go Music DL 是一个音乐搜索与下载工具，支持 Web 界面、TUI 终端和桌面应用。除单曲搜索与下载外，还支持歌单搜索与解析、分类浏览、我的歌单、专辑搜索与解析，以及批量处理。",Modifier.padding(vertical=12.dp));val context=androidx.compose.ui.platform.LocalContext.current;TextButton(onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/guohuiyuan/go-music-dl")))}){Text("GoMusicDll · GitHub")};Text("星河音乐项目");TextButton(onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/liangle902/CarMusic")))}){Text("星河音乐 · GitHub")};Text("引擎许可证：GNU AGPL v3",Modifier.padding(top=12.dp));TextButton(onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/guohuiyuan/go-music-dl/blob/main/LICENSE")))}){Text("查看上游许可证")}}},confirmButton={TextButton(onClick={about=false}){Text("关闭")}})
}
@Composable
private fun SettingSwitch(title:String,checked:Boolean,onChange:(Boolean)->Unit) {Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f));Switch(checked,onChange)}}
