package com.carmusic.app

import android.content.*
import android.net.Uri
import android.os.IBinder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.SongItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class VideoPreviewTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun previewPlaysSeeksMutesAndChangesTrackWithoutReplacingMainQueue() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        assertFalse("请解锁测试手机",context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
        val pcm=ByteArray(44100*2*30)
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size)
        }.array()
        val files=(1..2).map {index->File(context.filesDir,"video-preview-$index.wav").apply {writeBytes(header+pcm)}}
        val songs=files.mapIndexed {index,file->SongItem(id=Uri.fromFile(file).toString(),name="Video preview ${index+1}",source="local",streamUrl=Uri.fromFile(file).toString(),duration=30)}
        val future=CompletableFuture<PlaybackService>()
        val connection=object:ServiceConnection {override fun onServiceConnected(name:ComponentName,binder:IBinder){future.complete((binder as PlaybackService.LocalBinder).getService())};override fun onServiceDisconnected(name:ComponentName){}}
        try {
            instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/com.carmusic.app.MainActivity").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
            assertTrue(context.bindService(Intent(context,PlaybackService::class.java),connection,Context.BIND_AUTO_CREATE))
            val service=future.get(15,TimeUnit.SECONDS)
            ui.runOnIdle {service.replaceQueue(songs)}
            ui.waitUntil(15000){service.isPlaying.value}
            ui.waitUntil(15000){ui.onAllNodesWithText("为每一段旅程，留一首好歌。").fetchSemanticsNodes().isEmpty()}
            ui.onNodeWithContentDescription("系统设置 导航").performClick()
            ui.onNodeWithText("高级选项").performScrollTo().performClick()
            ui.onNodeWithText("音乐视频制作").performClick()
            ui.waitUntil(15000){ui.onAllNodesWithContentDescription("播放预览").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithContentDescription("播放预览").performScrollTo().assertIsEnabled().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithContentDescription("暂停预览").fetchSemanticsNodes().isNotEmpty()&&!service.isPlaying.value}
            ui.waitUntil(10000){ui.onAllNodesWithText("0:01").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithContentDescription("暂停预览").performScrollTo().performClick()
            ui.onNodeWithContentDescription("视频预览进度").performScrollTo().performTouchInput {click(center)}
            ui.waitUntil(10000){ui.onAllNodesWithText("0:15").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithContentDescription("静音预览").performScrollTo().performClick()
            ui.onNodeWithContentDescription("恢复预览音量").assertExists().performClick()
            ui.onNodeWithContentDescription("静音预览").assertExists()
            ui.onNodeWithContentDescription("预览下一首").performClick()
            ui.waitUntil(10000){ui.onAllNodesWithText("Video preview 2").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithContentDescription("预览上一首").performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithText("Video preview 2").fetchSemanticsNodes().isEmpty()}
            ui.runOnIdle {assertEquals(songs.map {it.key},service.playlist.value.map {it.key});assertEquals(songs.first().key,service.currentSong.value?.key)}
        } finally {if(future.isDone) instrumentation.runOnMainSync {future.get().clearQueue()};runCatching {context.unbindService(connection)};files.forEach {it.delete()}}
    }
}
