package com.carmusic.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.LyricLine
import kotlinx.coroutines.CancellationException

@Composable
fun MiniLyric(service:PlaybackService,compact:Boolean=false) {
    val song by service.currentSong.collectAsState();val position by service.currentPosition.collectAsState()
    val resolved by service.resolvedSong.collectAsState()
    val settings by AppStore.engineSettings.collectAsState()
    val disabled=settings?.get("disableFloatingLyrics")?.asBoolean==true
    var lines by remember {mutableStateOf<List<LyricLine>>(emptyList())}
    LaunchedEffect(song?.key,resolved?.key,disabled){lines=emptyList();if(!disabled) (resolved?:song)?.let {try {lines=ApiClient.fetchLyrics(it)} catch(e:CancellationException){throw e} catch(_:Exception){}}}
    if(!disabled) lines.lastOrNull {it.timeMs<=position}?.let {Text(it.text,Modifier.padding(start=if(compact) 0.dp else 14.dp,end=if(compact) 0.dp else 14.dp,bottom=if(compact) 0.dp else 12.dp),color=MaterialTheme.colorScheme.primary,maxLines=1,style=if(compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)}
}
