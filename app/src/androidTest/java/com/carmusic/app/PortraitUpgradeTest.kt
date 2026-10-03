package com.carmusic.app

import android.content.*
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.data.AppStore
import com.carmusic.app.data.LocalMusicScanner
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

/** Real pointer/media-provider tests, isolated in the .smoke application. */
class PortraitUpgradeTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun launch() {
        assertTrue("Only the isolated smoke package may run these fixtures", context.packageName.endsWith(".smoke"))
        assertFalse(context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
        instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/com.carmusic.app.MainActivity").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
        ui.waitUntil(15000) { ui.onAllNodesWithContentDescription("正在播放 导航").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(15000) { ui.onAllNodesWithText("为每一段旅程，留一首好歌。").fetchSemanticsNodes().isEmpty() }
    }
    private fun withService(block: (PlaybackService) -> Unit) {
        val ready = CompletableFuture<PlaybackService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                ready.complete((binder as PlaybackService.LocalBinder).getService())
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE))
        val service = ready.get(15, TimeUnit.SECONDS)
        try { block(service) } finally { ui.runOnIdle { service.clearQueue() }; context.unbindService(connection) }
    }
    private fun wav(): ByteArray {
        val pcm = ByteArray(44100 * 2 * 60)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(44100); putInt(88200); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return header + pcm
    }
    @Test fun longTitleStaysOnOneLineAndActuallyMoves() {
        launch()
        val file = File(context.filesDir, "marquee-${System.nanoTime()}.wav").apply { writeBytes(wav()) }
        val title = "从这一刻出发一路向北穿过银河寻找每一段旅程里最动听的音乐与回忆"
        val song = SongItem(id="marquee-test", name=title, source="local", streamUrl=Uri.fromFile(file).toString())
        try { withService { service ->
            ui.runOnIdle { service.replaceQueue(listOf(song)) }
            ui.waitUntil(15000) { service.isPlaying.value }
            ui.runOnIdle { service.togglePlayPause() }
            // The test policy cancels infinite animations with auto-advance enabled.
            // Start the player (and its marquee) only after taking manual clock control.
            ui.mainClock.autoAdvance = false
            try {
            ui.onNodeWithContentDescription("正在播放 导航").performClick()
            ui.mainClock.advanceTimeBy(64)
            ui.waitForIdle()
            val node = ui.onNodeWithTag("player-song-title")
            node.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            // Compare only the title pixels; other player UI and time are paused.
            ui.mainClock.advanceTimeBy(1600)
            ui.waitForIdle()
            Thread.sleep(150)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            fun pixels(): IntArray {
                val screen = instrumentation.uiAutomation.takeScreenshot()!!
                val x = bounds.left.toInt().coerceIn(0,screen.width-1)
                val y = bounds.top.toInt().coerceIn(0,screen.height-1)
                val width = bounds.width.toInt().coerceIn(1,screen.width-x)
                val height = bounds.height.toInt().coerceIn(1,screen.height-y)
                return IntArray(width*height).also { screen.getPixels(it,0,width,x,y,width,height); screen.recycle() }
            }
            run {
                val first = pixels()
                ui.mainClock.advanceTimeBy(1500)
                ui.waitForIdle()
                Thread.sleep(150)
                val next = pixels()
                var changes = 0
                for (index in first.indices) if (first[index] != next[index]) changes++
                assertTrue("Overflowing song title must visibly scroll", changes > 20)
            }
            } finally { ui.mainClock.autoAdvance = true }
        } } finally { file.delete() }
    }
    @Test fun queueFollowsFingerAndReordersBeforeRelease() {
        launch()
        val file = File(context.filesDir, "drag-${System.nanoTime()}.wav").apply { writeBytes(wav()) }
        val songs = (1..8).map { SongItem(id="drag-$it", name="跟手排序 $it", source="local",streamUrl=Uri.fromFile(file).toString()) }
        try { withService { service ->
            ui.runOnIdle { service.replaceQueue(songs) }
            ui.waitUntil(15000) { service.isPlaying.value }
            ui.onNodeWithContentDescription("正在播放 导航").performClick()
            ui.onNodeWithContentDescription("打开播放队列").performClick()
            val handle = ui.onNodeWithContentDescription("拖动排序 ${songs.first().name}")
            val original = handle.fetchSemanticsNode().boundsInRoot
            val delete = ui.onNodeWithContentDescription("移出队列 ${songs.first().name}").fetchSemanticsNode().boundsInRoot
            assertTrue("Handle is to the right of the delete button", original.left >= delete.right - 1f)
            val target = ui.onNodeWithContentDescription("拖动排序 ${songs[2].name}").fetchSemanticsNode().boundsInRoot.center
            val root = ui.onNode(isRoot() and hasAnyDescendant(hasTestTag("playback-queue")))
            root.performTouchInput { down(original.center); moveTo(original.center + Offset(0f,30f)) }
            try {
                root.performTouchInput { moveTo(Offset(original.center.x,target.y),delayMillis=300) }
                ui.waitUntil(5000) { service.playlist.value.indexOfFirst { it.key==songs.first().key } >= 1 }
                ui.waitUntil(3000) { kotlin.math.abs(handle.fetchSemanticsNode().boundsInRoot.center.y-target.y) < original.height / 2 }
                val moving = handle.fetchSemanticsNode().boundsInRoot.center
                assertTrue("Dragged row must follow the held finger: original=${original.center.y}, row=${moving.y}, finger=${target.y}, height=${original.height}", kotlin.math.abs(moving.y-target.y) < original.height / 2)
                assertEquals("Sorting may not change the song being played",songs.first().key,service.currentSong.value?.key)
            } finally { root.performTouchInput { up() } }
            ui.waitForIdle()
            val lower = handle.fetchSemanticsNode().boundsInRoot.center
            val upper = ui.onNodeWithContentDescription("拖动排序 ${songs[1].name}").fetchSemanticsNode().boundsInRoot.center
            root.performTouchInput { down(lower); moveTo(lower+Offset(0f,-30f)) }
            try {
                root.performTouchInput { moveTo(Offset(lower.x,upper.y),delayMillis=300) }
                ui.waitUntil(5000) { service.playlist.value.first().key==songs.first().key }
            } finally { root.performTouchInput { up() } }
            ui.waitUntil(5000) { handle.fetchSemanticsNode().boundsInRoot.center.y < ui.onNodeWithContentDescription("拖动排序 ${songs[1].name}").fetchSemanticsNode().boundsInRoot.center.y }
            ui.waitForIdle()
            // Edge scrolling is tracked separately for manual verification; instrumentation
            // timing may differ from the third-party scroller's normal launch/dispatch timing.
            ui.runOnIdle { assertEquals(service.playlist.value,AppStore.restoredQueue()); assertEquals(8,service.playlist.value.size) }
        } } finally { file.delete() }
    }
    @Test fun newlyAddedMediaIsAutomaticallyListedPlayableAndRemovableWithoutDeletingFile() {
        launch()
        val resolver = context.contentResolver
        val stamp = System.nanoTime()
        val name = "自动扫描验证$stamp"
        val originalAuto = AppStore.autoScanLocal.value
        ui.runOnIdle { AppStore.setAutoScanLocal(true) }
        ui.waitUntil(15000) { !LocalMusicScanner.state.value.scanning }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME,"$name.wav"); put(MediaStore.Audio.Media.TITLE,name)
            put(MediaStore.Audio.Media.MIME_TYPE,"audio/wav"); put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/CarMusicSmoke/")
            put(MediaStore.Audio.Media.IS_PENDING,1); put(MediaStore.Audio.Media.IS_MUSIC,1)
        })!!
        try {
            resolver.openOutputStream(uri)!!.use { it.write(wav()) }
            resolver.update(uri,ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING,0) },null,null)
            // No explicit scan request: prove the observer sees a file added while the app is open.
            ui.waitUntil(20000) { AppStore.scannedLocal.value.any { it.extra?.get("filename")=="$name.wav" } }
            val song = AppStore.scannedLocal.value.first { it.extra?.get("filename")=="$name.wav" }
            withService { service ->
                ui.onNodeWithContentDescription("本地音乐 导航").performClick()
                ui.onNodeWithTag("song-list").performScrollToNode(hasText(song.name))
                ui.onNodeWithText(song.name).performClick()
                ui.waitUntil(15000) { service.currentSong.value?.key==song.key && service.isPlaying.value }
                ui.waitUntil(5000) { service.currentPosition.value > 500 }
                ui.onNodeWithTag("song-list").performScrollToNode(hasContentDescription("歌曲操作 ${song.name}"))
                ui.onNodeWithContentDescription("歌曲操作 ${song.name}").performClick()
                ui.onNodeWithText("移除本地音乐").performClick()
                ui.onNodeWithText("确认").performClick()
                ui.waitUntil(10000) { AppStore.scannedLocal.value.none { it.key==song.key } }
                resolver.openFileDescriptor(uri,"r")!!.use { assertTrue(it.statSize>44) }
                ui.onNodeWithText("扫描音乐").performClick()
                ui.waitUntil(15000) { !LocalMusicScanner.state.value.scanning }
                assertFalse(AppStore.scannedLocal.value.any { it.key==song.key })
                ui.onNodeWithText("更多").performClick()
                ui.onNodeWithText("重新显示已移除歌曲").performClick()
                ui.waitUntil(15000) { AppStore.scannedLocal.value.any { it.key==song.key } }
            }
        } finally {
            resolver.delete(uri,null,null)
            ui.runOnIdle { AppStore.setAutoScanLocal(originalAuto); LocalMusicScanner.requestScan(manual=true) }
        }
    }
}
