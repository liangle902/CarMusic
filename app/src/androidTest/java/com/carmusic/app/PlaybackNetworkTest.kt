package com.carmusic.app

import android.content.*
import android.net.Uri
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.SongItem
import com.carmusic.app.ui.model.PlayMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PlaybackNetworkTest {
    @Test fun retriesTransientHttpErrorsAndBoundsFailedQueueTraversal() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val pcm=ByteArray(88200*30)
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size)}.array()
        val wav=header+pcm
        val fallback=File(context.filesDir,"network-fallback.wav").apply {writeBytes(wav)}
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val recoverRequests=AtomicInteger();val missingRequests=AtomicInteger()
        val worker=thread {
            while(!server.isClosed) {
                try {server.accept().use {socket->
                    socket.soTimeout=5000
                    val input=socket.getInputStream().bufferedReader()
                    val request=input.readLine().orEmpty()
                    while(!input.readLine().isNullOrEmpty()) {}
                    val missing=request.contains("/missing-")
                    val status=if(missing){missingRequests.incrementAndGet();404}else if(recoverRequests.incrementAndGet()<=2)503 else 200
                    val bytes=if(status==200) wav else byteArrayOf()
                    socket.getOutputStream().use {out->out.write("HTTP/1.1 $status Test\r\nContent-Type: audio/wav\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());out.write(bytes);out.flush()}
                }} catch(_:java.io.IOException) {if(server.isClosed) break}
            }
        }
        val bound=CompletableFuture<PlaybackService>()
        val connection=object:ServiceConnection {override fun onServiceConnected(name:ComponentName,binder:IBinder){bound.complete((binder as PlaybackService.LocalBinder).getService())};override fun onServiceDisconnected(name:ComponentName) {}}
        val settings=ApiClient.settings().deepCopy()
        try {
            ApiClient.saveSettings(settings.deepCopy().apply {addProperty("autoSwitchInvalidSources",true)})
            instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/com.carmusic.app.MainActivity").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
            assertTrue(context.bindService(Intent(context,PlaybackService::class.java),connection,Context.BIND_AUTO_CREATE))
            val service=bound.get(10,TimeUnit.SECONDS)
            fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
            fun await(label:String,condition:()->Boolean) {val deadline=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<deadline&&!condition())Thread.sleep(100);assertTrue("$label · current=${service.currentSong.value?.id}, playing=${service.isPlaying.value}, position=${service.currentPosition.value}, error=${service.playbackError.value}",condition())}
            val stamp=System.nanoTime()
            val recovering=SongItem(id="recover-$stamp",name="HTTP recovery fixture",source="local",duration=30,streamUrl="http://127.0.0.1:${server.localPort}/recover-$stamp.wav")
            main {service.replaceQueue(listOf(recovering))}
            await("503 后应自动重试并恢复实际播放") {service.isPlaying.value&&service.currentPosition.value>=700}
            assertTrue(recoverRequests.get()>=3)
            assertNull(service.playbackError.value)
            val bad=SongItem(id="missing-$stamp",name="Missing fixture",source="local",streamUrl="http://127.0.0.1:${server.localPort}/missing-$stamp.wav")
            val good=SongItem(id="fallback-$stamp",name="Local fallback",source="local",duration=30,streamUrl=Uri.fromFile(fallback).toString())
            main {service.replaceQueue(listOf(bad,good))}
            await("404 曲目应推进至剩余可播放曲目") {service.currentSong.value?.key==good.key&&service.isPlaying.value}
            assertEquals(listOf(bad.key,good.key),service.playlist.value.map {it.key})
            val original=SongItem(id="resolved-$stamp",name="Saved alternate fixture",source="qq",duration=30)
            main {service.replaceQueue(emptyList());service.playAlternate(original,good)}
            await("实际播放后保存成功换源") {service.isPlaying.value&&AppStore.restoredResolved(original)?.key==good.key}
            assertEquals(original.key,service.currentSong.value?.key)
            main {service.playSong(original)}
            await("原条目复播应使用已保存来源") {service.isPlaying.value&&service.resolvedSong.value?.key==good.key}
            assertEquals(listOf(original.key),service.playlist.value.map {it.key})
            val second=good.copy(id="second-$stamp")
            main {service.setPlayMode(PlayMode.SEQUENCE);service.replaceQueue(listOf(good,second),second)}
            await("顺序播放末曲开始") {service.currentSong.value?.key==second.key&&service.isPlaying.value&&service.duration.value>0}
            main {service.seekTo(service.duration.value-100)}
            await("顺序模式末尾停止") {var ended=false;main {ended=service.player.playbackState==androidx.media3.common.Player.STATE_ENDED};!service.isPlaying.value&&ended}
            main {service.setPlayMode(PlayMode.REPEAT_ALL);service.playSong(second)}
            await("列表循环末曲开始") {service.isPlaying.value}
            main {service.seekTo(service.duration.value-100)}
            await("列表循环回到首曲") {service.currentSong.value?.key==good.key&&service.isPlaying.value}
            main {service.setPlayMode(PlayMode.REPEAT_ONE);service.seekTo(service.duration.value-100)}
            await("单曲循环保持当前曲并回到开头") {service.currentSong.value?.key==good.key&&service.isPlaying.value&&service.currentPosition.value<2000}
            val beforeMode=service.playlist.value.map {it.key}
            main {service.setPlayMode(PlayMode.SEQUENCE)}
            assertEquals("直接选择顺序模式不能打乱队列",beforeMode,service.playlist.value.map {it.key})
            main {service.setPlaybackSpeed(1.5f)}
            main {assertEquals(1.5f,service.player.playbackParameters.speed,0f)}
            main {service.setPlaybackSpeed(1f)}
            val bad2=bad.copy(id="missing2-$stamp",streamUrl="http://127.0.0.1:${server.localPort}/missing-two-$stamp.wav")
            val before=missingRequests.get()
            main {service.replaceQueue(listOf(bad,bad2))}
            await("全部失效时应停止且保留错误") {service.currentSong.value?.key==bad2.key&&!service.isPlaying.value&&service.playbackError.value!=null}
            val stoppedAt=missingRequests.get()
            Thread.sleep(1200)
            assertEquals("全部失效不能循环发起解析",stoppedAt,missingRequests.get())
            assertTrue(stoppedAt-before in 2..8)
            assertEquals(listOf(bad.key,bad2.key),AppStore.restoredQueue().map {it.key})
            main {service.clearQueue()}
        } finally {
            if(bound.isDone) instrumentation.runOnMainSync {bound.get().clearQueue()}
            runCatching {context.unbindService(connection)}
            ApiClient.saveSettings(settings)
            server.close();worker.join(6000);fallback.delete()
        }
    }
}
