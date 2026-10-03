package com.carmusic.app

import android.content.*
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class QueueSourceAuditTest {
    @Test fun everyReportedQueueTrackPlaysAndLoadsMatchingLyrics():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val tracks=instrumentation.context.assets.open("playback-audit-queue.json").bufferedReader().use {ApiClient.gson.fromJson(it,Array<SongItem>::class.java).toList()}
        assertTrue(tracks.isNotEmpty())
        val settings=ApiClient.settings().deepCopy();val future=CompletableFuture<PlaybackService>()
        val connection=object:ServiceConnection {override fun onServiceConnected(name:ComponentName,binder:IBinder){future.complete((binder as PlaybackService.LocalBinder).getService())};override fun onServiceDisconnected(name:ComponentName) {}}
        val failures=mutableListOf<String>()
        try {
            ApiClient.saveSettings(settings.deepCopy().apply {addProperty("autoSwitchInvalidSources",true);addProperty("autoCacheOnPlay",false)})
            instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/com.carmusic.app.MainActivity").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
            assertTrue(context.bindService(Intent(context,PlaybackService::class.java),connection,Context.BIND_AUTO_CREATE))
            val service=future.get(10,TimeUnit.SECONDS)
            for(song in tracks) {
                instrumentation.runOnMainSync {service.replaceQueue(listOf(song))}
                val deadline=System.currentTimeMillis()+60000
                while(System.currentTimeMillis()<deadline&&!(service.currentSong.value?.key==song.key&&service.isPlaying.value&&service.currentPosition.value>=2000)) Thread.sleep(200)
                val playing=service.currentSong.value?.key==song.key&&service.isPlaying.value&&service.currentPosition.value>=2000
                if(!playing) {failures+="${song.name}：${service.playbackError.value?:"60 秒内未开始播放"}";continue}
                val actual=service.resolvedSong.value?:song
                val lyrics=try {ApiClient.fetchLyrics(actual)} catch(e:Exception){failures+="${song.name} 歌词：${e.message}";emptyList()}
                if(lyrics.isEmpty()) failures+="${song.name}：未获取到匹配歌词"
                println("QUEUE_AUDIT ${song.name}: played=true, source=${actual.source}, switched=${actual.key!=song.key}, lyricLines=${lyrics.size}")
                instrumentation.runOnMainSync {service.clearQueue()}
            }
            assertTrue(failures.joinToString("\n"),failures.isEmpty())
        } finally {
            if(future.isDone) instrumentation.runOnMainSync {future.get().clearQueue()}
            runCatching {context.unbindService(connection)};ApiClient.saveSettings(settings)
        }
    }
}
