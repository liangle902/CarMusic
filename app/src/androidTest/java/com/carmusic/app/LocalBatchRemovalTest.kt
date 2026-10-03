package com.carmusic.app

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LocalBatchRemovalTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun multiSelectDeletesOwnDownloadedFilesButPreservesImportedOriginal():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        assertFalse("请解锁测试手机",context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
        val stamp=System.nanoTime()
        val pcm=ByteArray(88200)
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size)}.array()
        val directory=File(context.filesDir,"data/downloads").apply {mkdirs()}
        val downloads=(1..2).map {index->File(directory,"batch-local-$stamp-$index.wav").apply {writeBytes(header+pcm)}}
        val original=File(context.filesDir,"batch-import-$stamp.wav").apply {writeBytes(header+pcm)}
        val imported=SongItem(id=Uri.fromFile(original).toString(),name="Imported original $stamp",source="local",streamUrl=Uri.fromFile(original).toString())
        try {
            val tracks=ApiClient.localSongs().filter {song->downloads.any {it.name==song.extra?.get("filename")}}
            assertEquals(2,tracks.size)
            instrumentation.runOnMainSync {AppStore.addImport(imported)}
            instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/com.carmusic.app.MainActivity").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
            ui.waitUntil(15000){ui.onAllNodesWithText("为每一段旅程，留一首好歌。").fetchSemanticsNodes().isEmpty()}
            ui.onNodeWithContentDescription("本地音乐 导航").performClick()
            ui.waitUntil(15000){ui.onAllNodesWithText("多选").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("多选").performClick()
            (listOf(imported)+tracks).forEach {song->
                ui.onNodeWithTag("song-list").performScrollToNode(hasText(song.name))
                ui.onNodeWithContentDescription("选择 ${song.name}").performClick()
            }
            ui.onNodeWithText("移除本地音乐").performScrollTo().performClick()
            ui.onNodeWithText("移除 3 首本地音乐？").assertExists()
            ui.onNodeWithText("确认").performClick()
            ui.waitUntil(15000){downloads.none {it.exists()}&&AppStore.imports.value.none {it.key==imported.key}}
            assertTrue("导入记录移除不能删除原始文件",original.isFile)
            assertArrayEquals(header+pcm,original.readBytes())
            assertTrue(ApiClient.localSongs().none {song->downloads.any {it.name==song.extra?.get("filename")}})
        } finally {instrumentation.runOnMainSync {AppStore.removeImport(imported)};downloads.forEach {it.delete()};original.delete()}
    }
}
