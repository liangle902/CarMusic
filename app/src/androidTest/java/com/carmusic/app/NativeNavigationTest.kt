package com.carmusic.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import com.carmusic.app.data.AppStore
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import com.carmusic.app.ui.model.SongItem
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.carmusic.app.service.PlaybackService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import com.carmusic.app.engine.ApiClient
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.geometry.Offset

class NativeNavigationTest {
    @get:Rule val ui = createEmptyComposeRule()
    @Before fun launchFromShell() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assertFalse("手机已锁屏，请解锁后运行界面测试",(instrumentation.targetContext.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceLocked)
        val id=instrumentation.targetContext.packageName
        listOf("input keyevent 224","wm dismiss-keyguard","am start -W -f 0x10008000 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $id/com.carmusic.app.MainActivity").forEach {command -> instrumentation.uiAutomation.executeShellCommand(command).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }}
    }
    @Test fun homeNavigationThemesAndSidebarWork() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val pcm=ByteArray(44100*2*30)
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size)
        }.array()
        val file=File(context.filesDir,"ui-test.wav").apply {outputStream().use {it.write(header);it.write(pcm)}}
        val fixture=SongItem(id="ui-test",name="CarMusic Test Audio",artist="UI Test",duration=30,source="local",streamUrl=Uri.fromFile(file).toString())
        ui.runOnIdle {AppStore.addImport(fixture)}
        ui.waitUntil(15000) {ui.onAllNodesWithText("歌单列表").fetchSemanticsNodes().isNotEmpty()}
        ui.waitUntil(15000) {ui.onAllNodesWithText("为每一段旅程，留一首好歌。").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithText("让好音乐，陪你一路。").assertIsDisplayed()
        listOf("全网搜索","本地音乐","下载管理").forEach {name->ui.onNodeWithContentDescription("$name 导航").assertExists()}
        listOf("播放队列","空白与异常状态").forEach {name->ui.onNodeWithContentDescription("$name 导航").assertDoesNotExist()}
        ui.onNodeWithText("›").performClick()
        ui.onNodeWithText("‹ 收起").assertIsDisplayed()
        ui.onNodeWithContentDescription("系统设置 导航").performClick()
        val originalTheme=AppStore.theme.value
        try {
            ui.onNodeWithText("日间").performClick()
            ui.runOnIdle {assertEquals("day",AppStore.theme.value)}
            ui.onNodeWithText("夜间").performClick()
            ui.runOnIdle {assertEquals("night",AppStore.theme.value)}
            ui.onNodeWithText("跟随系统").performClick()
            ui.runOnIdle {assertEquals("system",AppStore.theme.value)}
        } finally {ui.runOnIdle {AppStore.setTheme(originalTheme)}}
        ui.onNodeWithText("下载与存储").performScrollTo().performClick()
        ui.onNodeWithText("本地音乐与下载").performClick()
        ui.onNodeWithText("导入本地音乐").assertIsDisplayed()
        ui.waitUntil(15000) {ui.onAllNodesWithText("CarMusic Test Audio").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("CarMusic Test Audio").performClick()
        ui.waitUntil(15000) {ui.onAllNodesWithContentDescription("暂停").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("暂停").performClick()
        ui.onNodeWithContentDescription("播放").assertIsDisplayed()
        ui.runOnIdle {AppStore.removeImport(fixture);file.delete()}
    }

    @Test fun queueOperationsPreserveLibraryAndProgress() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val future=CompletableFuture<PlaybackService>()
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName?,binder:IBinder?) {future.complete((binder as PlaybackService.LocalBinder).getService())}
            override fun onServiceDisconnected(name:ComponentName?) {}
        }
        assertTrue(context.bindService(Intent(context,PlaybackService::class.java),connection,Context.BIND_AUTO_CREATE))
        val service=future.get(15,TimeUnit.SECONDS)
        val pcm=ByteArray(44100*2*30)
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size)}.array()
        val file=File(context.filesDir,"queue-test.wav").apply {outputStream().use {it.write(header);it.write(pcm)}}
        val first=SongItem(id="queue-one",name="Queue One",duration=30,source="local",streamUrl=Uri.fromFile(file).toString())
        val second=first.copy(id="queue-two",name="Queue Two")
        try {
            ui.runOnIdle {AppStore.addImport(first);AppStore.addImport(second);if(!AppStore.isFavorite(first)) AppStore.toggleFavorite(first);service.replaceQueue(listOf(first,second))}
            ui.waitUntil(15000) {service.isPlaying.value}
            ui.runOnIdle {service.seekTo(12000);service.togglePlayPause()}
            ui.runOnIdle {assertTrue(AppStore.restoredPosition()>=12000);assertEquals(first.key,AppStore.restoredSong()?.key);service.moveQueue(1,0);assertEquals(listOf(second.key,first.key),service.playlist.value.map {it.key});assertEquals(service.playlist.value,AppStore.restoredQueue())}
            ui.waitUntil(15000) {ui.onAllNodesWithText("为每一段旅程，留一首好歌。").fetchSemanticsNodes().isEmpty()}
            ui.onNodeWithContentDescription("正在播放 导航").performClick()
            ui.runOnIdle {service.setPlayMode(com.carmusic.app.ui.model.PlayMode.SEQUENCE)}
            val modes=listOf("顺序播放","随机播放","单曲循环","列表循环")
            modes.forEachIndexed {index,label->
                ui.onNodeWithContentDescription(label).performClick()
                ui.waitUntil(5000){service.playMode.value==com.carmusic.app.ui.model.PlayMode.entries[(index+1)%4]}
            }
            ui.onAllNodesWithText("1.0×").assertCountEquals(0)
            ui.runOnIdle {service.replaceQueue(listOf(second,first),first)}
            ui.onNodeWithContentDescription("打开播放队列").performClick()
            ui.onNodeWithContentDescription("拖动排序 Queue One").assertExists()
            ui.onNodeWithContentDescription("上移 Queue One").assertDoesNotExist()
            ui.onNode(hasText("Queue One") and hasAnyAncestor(hasScrollAction())).performTouchInput {down(center);advanceEventTime(700);moveBy(Offset(0f,-220f));up()}
            ui.waitUntil(5000) {service.playlist.value.firstOrNull()?.key==first.key}
            ui.runOnIdle {assertEquals(service.playlist.value,AppStore.restoredQueue());service.removeFromQueue(first)}
            ui.waitUntil(15000) {service.currentSong.value?.key==second.key && service.isPlaying.value}
            ui.runOnIdle {assertTrue(AppStore.isFavorite(first));assertEquals(2,AppStore.imports.value.count {it.key in listOf(first.key,second.key)});service.clearQueue();assertNull(service.currentSong.value);assertTrue(service.playlist.value.isEmpty());assertTrue(AppStore.restoredQueue().isEmpty());assertTrue(file.isFile);assertTrue(AppStore.isFavorite(first))}
        } finally {
            ui.runOnIdle {service.clearQueue();AppStore.removeImport(first);AppStore.removeImport(second);if(AppStore.isFavorite(first)) AppStore.toggleFavorite(first);file.delete()}
            context.unbindService(connection)
        }
    }

    @Test fun clickingLyricsSeeksToTheirTimestamp() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val future=CompletableFuture<PlaybackService>()
        val connection=object:ServiceConnection {override fun onServiceConnected(name:ComponentName?,binder:IBinder?){future.complete((binder as PlaybackService.LocalBinder).getService())};override fun onServiceDisconnected(name:ComponentName?) {}}
        assertTrue(context.bindService(Intent(context,PlaybackService::class.java),connection,Context.BIND_AUTO_CREATE))
        val service=future.get(15,TimeUnit.SECONDS)
        val pcm=ByteArray(44100*2*30)
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size)}.array()
        val directory=File(context.filesDir,"data/downloads").apply {mkdirs()}
        val audio=File(directory,"lyric-test.wav").apply {outputStream().use {it.write(header);it.write(pcm)}}
        val lyric=File(directory,"lyric-test.lrc").apply {writeText((0..14).joinToString("\n") {index->if(index==0) "[00:00.00]Lyric [00:00.50]Start[00:01.00]\n[00:00.00]歌词起点\n[00:00.00]ge ci qi dian" else "[00:${(index*2).toString().padStart(2,'0')}.00]"+when(index){10->"Lyric Twenty";else->"Lyric ${index*2}"}})}
        try {
            val song=runBlocking {ApiClient.localSongs().first {it.extra?.get("filename")=="lyric-test.wav"}}
            assertEquals((0..14).map {it*2000L},runBlocking {ApiClient.fetchLyrics(song)}.map {it.timeMs})
            val firstLine=runBlocking {ApiClient.fetchLyrics(song)}.first()
            assertEquals(2,firstLine.words.size)
            assertEquals("歌词起点",firstLine.translation)
            assertEquals("ge ci qi dian",firstLine.romanization)
            ui.runOnIdle {service.replaceQueue(listOf(song))}
            ui.waitUntil(15000) {service.isPlaying.value}
            ui.runOnIdle {service.togglePlayPause()}
            ui.waitUntil(15000) {ui.onAllNodesWithText("为每一段旅程，留一首好歌。").fetchSemanticsNodes().isEmpty()}
            ui.onNodeWithContentDescription("正在播放 导航").performClick()
            ui.waitUntil(15000) {ui.onAllNodesWithText("Lyric Start").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("歌词起点").assertExists()
            ui.onNodeWithText("ge ci qi dian").assertExists()
            ui.onNodeWithTag("player-lyrics").performScrollToNode(hasText("Lyric Twenty"))
            ui.onNodeWithText("Lyric Twenty").performClick()
            ui.runOnIdle {assertEquals(20000L,service.currentPosition.value);assertEquals(20000L,AppStore.restoredPosition())}
            ui.runOnIdle {service.seekTo(0)}
            ui.waitForIdle()
            ui.onNodeWithTag("player-lyrics").performTouchInput {swipeUp(durationMillis=800)}
            ui.runOnIdle {assertEquals("滑动只能浏览，不能跳转播放进度",0L,service.currentPosition.value)}
            ui.waitUntil(6000) {ui.onAllNodesWithText("Lyric Start").fetchSemanticsNodes().isNotEmpty()}
            ui.runOnIdle {assertEquals(0L,AppStore.restoredPosition())}
        } finally {ui.runOnIdle {service.clearQueue();audio.delete();lyric.delete()};context.unbindService(connection)}
    }

    @Test fun platformAccountButtonsDisplayQqAndNeteaseQrImages() {
        ui.waitUntil(15000){ui.onAllNodesWithText("为每一段旅程，留一首好歌。").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithContentDescription("系统设置 导航").performClick()
        ui.onNode(hasText("平台账号") and hasClickAction()).performScrollTo().performClick()
        ui.waitUntil(15000){ui.onAllNodesWithText("扫码关联").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("扫码关联").performScrollTo().performClick()
        ui.waitUntil(20000){ui.onAllNodesWithContentDescription("登录二维码").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("登录二维码").assertIsDisplayed()
        ui.onNodeWithText("关闭").performClick()
        ui.onNodeWithText("网易云音乐").performScrollTo().performClick()
        ui.onNodeWithText("扫码关联").performScrollTo().performClick()
        ui.waitUntil(20000){ui.onAllNodesWithContentDescription("登录二维码").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("登录二维码").assertIsDisplayed()
        ui.onNodeWithText("关闭").performClick()
    }
}
