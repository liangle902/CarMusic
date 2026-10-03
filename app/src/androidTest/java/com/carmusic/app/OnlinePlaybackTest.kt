package com.carmusic.app

import android.content.*
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class OnlinePlaybackTest {
    @Test fun freshSearchAndFailedSourceAutoSwitchPlayWithRealLyrics():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val settings=ApiClient.settings().deepCopy()
        val future=CompletableFuture<PlaybackService>()
        val connection=object:ServiceConnection {override fun onServiceConnected(name:ComponentName,binder:IBinder){future.complete((binder as PlaybackService.LocalBinder).getService())};override fun onServiceDisconnected(name:ComponentName) {}}
        try {
            ApiClient.saveSettings(settings.deepCopy().apply {addProperty("autoSwitchInvalidSources",true);addProperty("autoCacheOnPlay",false)})
            val song=ApiClient.searchSongs("晴天 周杰伦","kuwo").first {it.name=="晴天"&&it.artist=="周杰伦"}
            val playable=ApiClient.resolvePlayable(song)
            val stream=ApiClient.inspectStream(playable)
            assertTrue("搜索结果应通过自动换源获得可播放来源：$stream",stream.valid)
            assertTrue("真实源端应返回歌词",ApiClient.fetchLyrics(playable).size>5)
            instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/com.carmusic.app.MainActivity").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
            assertTrue(context.bindService(Intent(context,PlaybackService::class.java),connection,Context.BIND_AUTO_CREATE))
            val service=future.get(10,TimeUnit.SECONDS)
            fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
            fun await(label:String,condition:()->Boolean) {val deadline=System.currentTimeMillis()+60000;while(System.currentTimeMillis()<deadline&&!condition())Thread.sleep(150);assertTrue("$label · ${service.playbackError.value}",condition())}
            main {service.replaceQueue(listOf(song))}
            await("真实在线播放应开始且进度前进") {service.isPlaying.value&&service.currentPosition.value>1500}
            val broken=song.copy(id="missing-native-${System.nanoTime()}",source="unsupported-smoke",extra=null,streamUrl="",link="")
            assertFalse("此测试初始音源必须确实不可解析",ApiClient.inspectStream(broken).valid)
            main {service.replaceQueue(listOf(broken))}
            await("失效音源应自动搜索同曲并播放替代来源") {service.currentSong.value?.key==broken.key&&service.isPlaying.value&&service.currentPosition.value>1500&&service.resolvedSong.value?.key!=broken.key}
            val actual=requireNotNull(service.resolvedSong.value)
            assertEquals(actual.key,AppStore.restoredResolved(broken)?.key)
            assertEquals(listOf(broken.key),service.playlist.value.map {it.key})
            assertTrue("换源后真实歌词仍可读取",ApiClient.fetchLyrics(actual).size>5)
        } finally {
            if(future.isDone) instrumentation.runOnMainSync {future.get().clearQueue()}
            runCatching {context.unbindService(connection)}
            ApiClient.saveSettings(settings)
        }
    }
}
