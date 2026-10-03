package com.carmusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.ui.model.PlaylistItem
import com.google.gson.JsonArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PlaylistCategories(source:String,onClose:()->Unit,onSelected:(String,List<PlaylistItem>)->Unit) {
    var sources by remember {mutableStateOf<JsonArray?>(null)};var error by remember {mutableStateOf("")};var busy by remember {mutableStateOf(true)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(source){try {val result=ApiClient.json("/playlist_categories",mapOf("sources" to source,"format" to "json")).asJsonObject;sources=result.getAsJsonArray("categorySources");error=result.get("error")?.asString?:""} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message?:"分类加载失败"} finally {busy=false}}
    AlertDialog(onDismissRequest=onClose,title={Text("歌单分类")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(error.isNotBlank()) Text(error)
        if(!busy && sources?.size()==0) Text("此平台暂无可用分类")
        sources?.forEach {entry -> val platform=entry.asJsonObject
            if(platform.get("Source")?.asString==source) platform.getAsJsonArray("Groups")?.forEach {group -> val item=group.asJsonObject
                Text(item.get("Name").asString,style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=12.dp))
                item.getAsJsonArray("Categories")?.forEach {category -> val row=category.asJsonObject
                    TextButton(enabled=!busy,onClick={scope.launch {busy=true;try {val result=ApiClient.json("/category_playlists",mapOf("source" to source,"category_id" to row.get("ID").asString,"category_name" to row.get("Name").asString,"format" to "json")).asJsonObject;val failure=result.get("error")?.asString?:"";if(failure.isNotBlank()) error=failure else {val lists=result.get("playlists");onSelected(row.get("Name").asString,if(lists==null||lists.isJsonNull) emptyList() else ApiClient.gson.fromJson(lists,Array<PlaylistItem>::class.java).toList());onClose()}} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message?:"歌单加载失败"} finally {busy=false}}}){Text(row.get("Name").asString)}
                }
            }
        }
    }},confirmButton={TextButton(onClick=onClose){Text("关闭")}})
}
