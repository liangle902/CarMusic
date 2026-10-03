package com.carmusic.app

import android.content.*
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BulkCollectionTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun bulkAddAndRemovePreserveFavoritesAndPlaybackQueue():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val stamp=System.nanoTime();val firstName="Bulk source $stamp";val secondName="Bulk target $stamp"
        suspend fun create(name:String)=ApiClient.json("/collections",method="POST",body=JsonObject().apply {addProperty("name",name)}).asJsonObject.get("id").asString
        val first=create(firstName);val second=create(secondName)
        val tracks=listOf(SongItem(id="bulk-a-$stamp",name="Bulk A",source="local",duration=120),SongItem(id="bulk-b-$stamp",name="Bulk B",source="local",duration=240))
        val future=CompletableFuture<PlaybackService>()
        val connection=object:ServiceConnection {override fun onServiceConnected(name:ComponentName,binder:IBinder){future.complete((binder as PlaybackService.LocalBinder).getService())};override fun onServiceDisconnected(name:ComponentName) {}}
        try {
            for(song in tracks) ApiClient.json("/collections/$first/songs",method="POST",body=ApiClient.gson.toJsonTree(song))
            instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/com.carmusic.app.MainActivity").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
            assertTrue(context.bindService(Intent(context,PlaybackService::class.java),connection,Context.BIND_AUTO_CREATE))
            val service=future.get(10,TimeUnit.SECONDS)
            ui.runOnIdle {AppStore.toggleFavorite(tracks.first());tracks.forEach {service.addToQueue(it)}}
            ui.waitUntil(15000){ui.onAllNodesWithText("为每一段旅程，留一首好歌。").fetchSemanticsNodes().isEmpty()}
            ui.onNodeWithContentDescription("歌单列表 导航").performClick()
            ui.onNodeWithText("新建歌单").performClick()
            ui.waitUntil(10000){ui.onAllNodesWithText(firstName).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithContentDescription("编辑歌单 $firstName").performClick()
            ui.onNodeWithText("封面地址（可选）").performTextReplacement("https://example.com/cover.jpg")
            ui.onNodeWithText("保存").performClick()
            ui.waitUntil(10000){ui.onAllNodesWithText("封面地址（可选）").fetchSemanticsNodes().isEmpty()}
            val collection=ApiClient.json("/collections",mapOf("include_imported" to "1")).asJsonArray.first {it.asJsonObject.get("id").asString==first}.asJsonObject
            assertEquals("https://example.com/cover.jpg",collection.get("cover").asString)
            ui.onNodeWithText(firstName).performClick()
            ui.waitUntil(10000){ui.onAllNodesWithText("Bulk A").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("多选").performClick()
            ui.onNodeWithText("全选 / 取消").performClick()
            ui.onNodeWithText("加入本地歌单").performScrollTo().performClick()
            try {ui.waitUntil(10000){ui.onAllNodesWithText(secondName).fetchSemanticsNodes().isNotEmpty()}} catch(e:Exception){println("BULK_UI_DIAGNOSTIC\n"+ui.onRoot().printToString());throw e}
            ui.onNodeWithText(secondName).performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithText("已加入 $secondName").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(tracks.map {it.key}.toSet(),ApiClient.decodeSongs(ApiClient.json("/collections/$second/songs")).map {it.key}.toSet())
            ui.onNodeWithText("从歌单移除").performScrollTo().performClick()
            ui.onNodeWithText("从歌单移除 2 首歌曲？").assertExists()
            ui.onNodeWithText("移除").performClick()
            ui.waitUntil(10000){ui.onAllNodesWithText("已移除 2 首").fetchSemanticsNodes().isNotEmpty()}
            assertTrue(ApiClient.decodeSongs(ApiClient.json("/collections/$first/songs")).isEmpty())
            ui.runOnIdle {assertTrue(AppStore.isFavorite(tracks.first()));assertEquals(tracks.map {it.key},service.playlist.value.map {it.key})}
        } finally {
            if(future.isDone) instrumentation.runOnMainSync {future.get().clearQueue()}
            if(AppStore.isFavorite(tracks.first())) instrumentation.runOnMainSync {AppStore.toggleFavorite(tracks.first())}
            runCatching {context.unbindService(connection)}
            ApiClient.json("/collections/$first",method="DELETE");ApiClient.json("/collections/$second",method="DELETE")
        }
    }
}
