package com.carmusic.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.ui.model.SongItem
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaylistAdaptiveTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun longDescriptionDoesNotHideSongsAndInvalidSourceResolvesWithoutPlaying():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val name="Adaptive ${System.nanoTime()}"
        val settings=ApiClient.settings().deepCopy()
        ApiClient.saveSettings(settings.deepCopy().apply {addProperty("autoSwitchInvalidSources",true)})
        val id=ApiClient.json("/collections",method="POST",body=JsonObject().apply {addProperty("name",name);addProperty("description","长歌单简介与提示信息".repeat(100))}).asJsonObject.get("id").asString
        val real=ApiClient.searchSongs("晴天 周杰伦","kuwo").first {it.name=="晴天"&&it.artist=="周杰伦"}
        val broken=real.copy(id="adaptive-${System.nanoTime()}",source="unsupported-smoke",extra=null,streamUrl="",link="")
        try {
            for(index in 20 downTo 2) ApiClient.json("/collections/$id/songs",method="POST",body=ApiClient.gson.toJsonTree(SongItem(id="adaptive-$index",name="Adaptive song $index",source="local")))
            ApiClient.json("/collections/$id/songs",method="POST",body=ApiClient.gson.toJsonTree(broken))
            instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/com.carmusic.app.MainActivity").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
            ui.waitUntil(15000){ui.onAllNodesWithText("为每一段旅程，留一首好歌。").fetchSemanticsNodes().isEmpty()}
            ui.onNodeWithContentDescription("歌单列表 导航").performClick()
            ui.waitUntil(15000){ui.onAllNodesWithTag("playlist-grid").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("playlist-grid").performScrollToNode(hasText(name))
            ui.onNodeWithText(name).performScrollTo().performClick()
            try {ui.waitUntil(15000){ui.onAllNodesWithText("晴天").fetchSemanticsNodes().isNotEmpty()}} catch(e:Exception){println("ADAPTIVE_DIAGNOSTIC\n"+ui.onRoot().printToString());throw e}
            ui.onNodeWithText("晴天").assertIsDisplayed()
            ui.onNodeWithText("简介").assertIsDisplayed()
            listOf("随机播放","加入队列","下载全部","默认顺序","批量操作").forEach {ui.onNodeWithText(it,substring=true).assertDoesNotExist()}
            val playBounds=ui.onNodeWithText("播放全部").getUnclippedBoundsInRoot()
            val selectBounds=ui.onNodeWithText("多选").getUnclippedBoundsInRoot()
            assertTrue("手机首屏操作应共享同一行",kotlin.math.abs((playBounds.top-selectBounds.top).value)<2f)
            ui.waitUntil(60000){AppStore.restoredResolved(broken)!=null&&AppStore.playbackChecks.value[broken.key]=="可播放"}
            val stream=ApiClient.inspectStream(ApiClient.resolvePlayable(broken))
            assertTrue("重新检查来源应继续遵循自动换源：$stream",stream.valid)
            val list=ApiClient.decodeSongs(ApiClient.json("/collections/$id/songs"))
            assertTrue(list.any {it.key==broken.key})
            ui.onNodeWithTag("song-list").performScrollToNode(hasText("Adaptive song 20"))
            ui.onNodeWithText("Adaptive song 20").assertIsDisplayed()
            assertTrue(ui.onAllNodesWithText("返回歌单").fetchSemanticsNodes().isEmpty())
        } finally {ApiClient.json("/collections/$id",method="DELETE");ApiClient.saveSettings(settings)}
    }
}
