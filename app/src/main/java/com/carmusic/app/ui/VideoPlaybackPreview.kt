package com.carmusic.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoPlaybackPreview(audio:File?,song:SongItem?,enabled:Boolean,canChangeTrack:Boolean,onPrevious:()->Unit,onNext:()->Unit,onPosition:(Long)->Unit,onSpectrum:(FloatArray)->Unit) {
    val context=LocalContext.current
    val lifecycle=androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val spectrum=remember {PreviewSpectrumSink()}
    val player=remember {val factory=object:androidx.media3.exoplayer.DefaultRenderersFactory(context){
        override fun buildAudioSink(context:android.content.Context,enableFloatOutput:Boolean,enableAudioTrackPlaybackParams:Boolean):androidx.media3.exoplayer.audio.AudioSink=
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(androidx.media3.exoplayer.audio.TeeAudioProcessor(spectrum))).setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).build()
    };ExoPlayer.Builder(context,factory).setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true).setHandleAudioBecomingNoisy(true).build()}
    var playing by remember {mutableStateOf(false)}
    var ready by remember {mutableStateOf(false)}
    var duration by remember {mutableLongStateOf(0)}
    var position by remember {mutableLongStateOf(0)}
    var seeking by remember {mutableStateOf<Float?>(null)}
    var volume by remember {mutableFloatStateOf(.7f)}
    var previousVolume by remember {mutableFloatStateOf(.7f)}
    var error by remember {mutableStateOf("")}
    val latestPosition by rememberUpdatedState(onPosition)
    val latestSpectrum by rememberUpdatedState(onSpectrum)
    DisposableEffect(player) {
        val listener=object:Player.Listener {
            override fun onIsPlayingChanged(isPlaying:Boolean){playing=isPlaying}
            override fun onPlaybackStateChanged(state:Int){ready=state==Player.STATE_READY||state==Player.STATE_ENDED;duration=player.duration.coerceAtLeast(0)}
            override fun onPlayerError(failure:PlaybackException){error="预览播放失败：${failure.errorCodeName}"}
        }
        player.addListener(listener);player.volume=volume
        onDispose {player.removeListener(listener);player.release()}
    }
    LaunchedEffect(audio,song?.key) {
        val resume=player.isPlaying
        player.stop();player.clearMediaItems();position=0;duration=0;ready=false;seeking=null;error="";latestPosition(0)
        try {
            val uri=audio?.let {Uri.fromFile(it).toString()}?:song?.let {current->
                current.streamUrl.takeIf {it.startsWith("file:")||it.startsWith("content:")}
                    ?:current.id.takeIf {current.source=="local"&&(it.startsWith("file:")||it.startsWith("content:"))}
                    ?:ApiClient.offlineUri(current)
                    ?:ApiClient.streamUrl(ApiClient.resolvePlayable(current))
            }
            if(uri!=null){player.setMediaItem(MediaItem.fromUri(uri));player.prepare();if(resume&&enabled&&lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player.play()}
        } catch(e:CancellationException){throw e} catch(e:Exception){error=e.message?:"音频预览加载失败"}
    }
    LaunchedEffect(enabled){if(!enabled) player.pause()}
    LaunchedEffect(player,lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {while(isActive){position=player.currentPosition.coerceAtLeast(0);duration=player.duration.coerceAtLeast(0);latestPosition(position);latestSpectrum(if(player.isPlaying) spectrum.levels else FloatArray(48));delay(100)}}
            finally {withContext(NonCancellable+Dispatchers.Main.immediate){player.pause()}}
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Slider(value=seeking?:if(duration>0) (position.toFloat()/duration).coerceIn(0f,1f) else 0f,onValueChange={seeking=it},onValueChangeFinished={seeking?.let {player.seekTo((it*duration).toLong());latestPosition((it*duration).toLong())};seeking=null},enabled=enabled&&duration>0,modifier=Modifier.semantics {contentDescription="视频预览进度"})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(previewTime(position));Text(previewTime(duration))}
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
            IconButton(onClick=onPrevious,enabled=enabled&&canChangeTrack,modifier=Modifier.semantics {contentDescription="预览上一首"}){MusicIcon("上一首")}
            IconButton(onClick={if(player.isPlaying) player.pause() else {if(player.playbackState==Player.STATE_ENDED) player.seekTo(0);player.play()}},enabled=enabled&&ready,modifier=Modifier.semantics {contentDescription=if(playing) "暂停预览" else "播放预览"}){MusicIcon(if(playing) "暂停" else "播放")}
            IconButton(onClick=onNext,enabled=enabled&&canChangeTrack,modifier=Modifier.semantics {contentDescription="预览下一首"}){MusicIcon("下一首")}
            IconButton(onClick={volume=if(volume==0f) previousVolume.takeIf {it>0f}?:.7f else {previousVolume=volume;0f};player.volume=volume},enabled=enabled,modifier=Modifier.semantics {contentDescription=if(volume==0f) "恢复预览音量" else "静音预览"}){MusicIcon(if(volume==0f) "静音" else "音量")}
            Slider(value=volume,onValueChange={volume=it;player.volume=it},enabled=enabled,modifier=Modifier.weight(1f).semantics {contentDescription="视频预览音量"})
        }
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    }
}

private fun previewTime(ms:Long)="%d:%02d".format(ms/60000,ms/1000%60)
