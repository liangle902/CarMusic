package com.carmusic.app

import android.content.*
import android.net.Uri
import android.os.IBinder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.SongItem
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PlaylistQueueSelectionTest {
    @get:Rule val ui=createEmptyComposeRule()

    @Test fun playlistReplacesQueueAndSelectedSongMovesFirstWithoutLosingOthers():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        assertFalse("请解锁测试手机",context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
        val stamp=System.nanoTime()
        val pcm=ByteArray(44100*2*30)
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16)
            put("data".toByteArray());putInt(pcm.size)
        }.array()
        val audio=(1..4).map {index->File(context.filesDir,"queue-selection-$stamp-$index.wav").apply {outputStream().use {it.write(header);it.write(pcm)}}}
        val tracks=audio.mapIndexed {index,file->SongItem(id=Uri.fromFile(file).toString(),name="Queue selection ${index+1}",source="local",duration=30,streamUrl=Uri.fromFile(file).toString())}
        val name="Queue playlist $stamp"
        val id=ApiClient.json("/collections",method="POST",body=JsonObject().apply {addProperty("name",name)}).asJsonObject.get("id").asString
        val future=CompletableFuture<PlaybackService>()
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder){future.complete((binder as PlaybackService.LocalBinder).getService())}
            override fun onServiceDisconnected(name:ComponentName){}
        }
        try {
            tracks.take(3).forEach {ApiClient.json("/collections/$id/songs",method="POST",body=ApiClient.gson.toJsonTree(it))}
            val full=ApiClient.decodeSongs(ApiClient.json("/collections/$id/songs"))
            assertEquals(3,full.size)
            instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/com.carmusic.app.MainActivity").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
            assertTrue(context.bindService(Intent(context,PlaybackService::class.java),connection,Context.BIND_AUTO_CREATE))
            val service=future.get(15,TimeUnit.SECONDS)
            ui.runOnIdle {service.setPlayMode(com.carmusic.app.ui.model.PlayMode.SEQUENCE);service.replaceQueue(listOf(tracks.last()))}
            ui.waitUntil(15000){service.isPlaying.value}
            ui.waitUntil(15000){ui.onAllNodesWithText("为每一段旅程，留一首好歌。").fetchSemanticsNodes().isEmpty()}
            ui.onNodeWithContentDescription("歌单列表 导航").performClick()
            ui.waitUntil(10000){ui.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText(name).performClick()
            ui.onNodeWithText("播放全部").performClick()
            ui.waitUntil(15000){service.isPlaying.value&&service.currentSong.value?.key==full.first().key}
            ui.runOnIdle {
                assertEquals(full.map {it.key},service.playlist.value.map {it.key})
                assertEquals(service.playlist.value,AppStore.restoredQueue())
            }
            ui.onNodeWithContentDescription("打开播放队列").performClick()
            full.forEach {ui.onNodeWithContentDescription("拖动排序 ${it.name}").assertExists()}
            ui.runOnIdle {assertEquals(full.first().key,service.currentSong.value?.key)}
            instrumentation.uiAutomation.executeShellCommand("input keyevent 4").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
            ui.waitForIdle()
            ui.onNodeWithContentDescription("歌单列表 导航").performClick()
            ui.waitUntil(10000){ui.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText(name).performClick()
            val selected=full.last()
            ui.onNodeWithTag("song-list").performScrollToNode(hasText(selected.name))
            ui.onNodeWithText(selected.name).performClick()
            val expected=listOf(selected)+full.filterNot {it.key==selected.key}
            ui.waitUntil(15000){service.isPlaying.value&&service.currentSong.value?.key==selected.key}
            ui.runOnIdle {
                assertEquals(expected.map {it.key},service.playlist.value.map {it.key})
                assertEquals(service.playlist.value,AppStore.restoredQueue())
                AppStore.addImport(tracks.last())
            }
            ui.onNodeWithContentDescription("本地音乐 导航").performClick()
            ui.waitUntil(10000){ui.onAllNodesWithText(tracks.last().name).fetchSemanticsNodes().isNotEmpty()}
            ui.onNode(hasText(tracks.last().name) and hasAnyAncestor(hasTestTag("song-list"))).performClick()
            ui.waitUntil(15000){service.isPlaying.value&&service.currentSong.value?.key==tracks.last().key}
            ui.runOnIdle {assertEquals((listOf(tracks.last())+expected).map {it.key},service.playlist.value.map {it.key})}
            ui.onNode(hasText(tracks.last().name) and hasAnyAncestor(hasTestTag("song-list"))).performClick()
            ui.runOnIdle {assertEquals(4,service.playlist.value.size);assertEquals(service.playlist.value,AppStore.restoredQueue())}
            assertEquals("播放队列操作不能修改真实歌单",full.map {it.key},ApiClient.decodeSongs(ApiClient.json("/collections/$id/songs")).map {it.key})
            ui.runOnIdle {service.setPlayMode(com.carmusic.app.ui.model.PlayMode.SHUFFLE);service.replaceQueue(full)}
            ui.waitUntil(15000){service.isPlaying.value&&service.currentSong.value?.key==full.first().key}
            ui.runOnIdle {
                assertEquals(full.map {it.key}.toSet(),service.playlist.value.map {it.key}.toSet())
                assertEquals(full.first().key,service.playlist.value.first().key)
                service.setPlayMode(com.carmusic.app.ui.model.PlayMode.SEQUENCE)
            }
        } finally {
            if(future.isDone) instrumentation.runOnMainSync {future.get().clearQueue();AppStore.removeImport(tracks.last())}
            runCatching {context.unbindService(connection)}
            ApiClient.json("/collections/$id",method="DELETE")
            audio.forEach {it.delete()}
        }
    }
}
