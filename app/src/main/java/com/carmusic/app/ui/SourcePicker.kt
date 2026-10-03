package com.carmusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.*
import kotlinx.coroutines.*

@Composable
fun SourcePicker(song:SongItem,service:PlaybackService,onClose:()->Unit) {
    val scope=rememberCoroutineScope();var sources by remember {mutableStateOf<List<SourceCapability>>(emptyList())};var error by remember {mutableStateOf<String?>(null)};var busy by remember {mutableStateOf(false)}
    LaunchedEffect(Unit){try {val currentSource=com.carmusic.app.data.AppStore.restoredResolved(song)?.source?:song.source;sources=ApiClient.sources().filter {it.id!=currentSource&&it.playback}} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message}}
    AlertDialog(onDismissRequest=onClose,title={Text("切换音源")},text={Column {SongTitle(song.name);error?.let {Text(it,color=MaterialTheme.colorScheme.error)};if(busy) LinearProgressIndicator(Modifier.fillMaxWidth());LazyColumn(Modifier.heightIn(max=musicDialogContentHeight(350.dp))){items(sources,key={it.id}) {source->TextButton(enabled=!busy,onClick={scope.launch {busy=true;try {service.playAlternate(song,ApiClient.switchSource(song,source.id));onClose()} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message} finally {busy=false}}}){Text(source.name)}}}}},confirmButton={TextButton(onClick=onClose){Text("关闭")}})
}
