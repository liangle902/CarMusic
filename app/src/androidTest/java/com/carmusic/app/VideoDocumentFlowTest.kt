package com.carmusic.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.engine.ApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VideoDocumentFlowTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun systemDocumentPickerImportsAudioCoverAndLyrics():Unit = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val resolver=context.contentResolver
        val created=mutableListOf<Uri>()
        val stamp=System.nanoTime()
        val exportName="CarMusic-export-$stamp"
        fun create(collection:Uri,name:String,mime:String,path:String,bytes:ByteArray):Uri {
            val values=ContentValues().apply {put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime);put(MediaStore.MediaColumns.RELATIVE_PATH,path)}
            return resolver.insert(collection,values)!!.also {uri->created+=uri;resolver.openOutputStream(uri)!!.use {it.write(bytes)}}
        }
        fun selectOwnFile(name:String) {
            val deadline=System.currentTimeMillis()+10000
            var searched=false
            fun find(node:AccessibilityNodeInfo?,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
                if(node==null) return null
                if(predicate(node)) return node
                repeat(node.childCount){index->find(node.getChild(index),predicate)?.let {return it}}
                return null
            }
            while(System.currentTimeMillis()<deadline) {
                val root=instrumentation.uiAutomation.rootInActiveWindow
                if(root!=null) {
                    var node=root.findAccessibilityNodeInfosByText(name).firstOrNull {it.viewIdResourceName?.contains("search_")!=true}
                    if(node?.isEditable==true) node=null
                    if(searched) {
                        val field=find(root){it.viewIdResourceName?.endsWith(":id/search_src_text")==true}
                        if(field?.text?.toString()==name) {
                            val list=find(root){it.viewIdResourceName?.endsWith(":id/dir_list")==true}
                            if(list?.childCount==1&&find(list){it.viewIdResourceName?.endsWith(":id/thumbnail")==true}!=null) {
                                val card=list.getChild(0)
                                    val rect=android.graphics.Rect();card.getBoundsInScreen(rect)
                                    instrumentation.uiAutomation.executeShellCommand("input tap ${rect.centerX()} ${rect.centerY()}").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
                                    val returned=System.currentTimeMillis()+5000
                                    while(System.currentTimeMillis()<returned){if(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName)return;Thread.sleep(100)}
                            }
                        }
                    }
                    while(node!=null&&!node.isClickable) node=node.parent
                    if(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) {
                        val resumedDeadline=System.currentTimeMillis()+10000
                        while(System.currentTimeMillis()<resumedDeadline) {
                            if(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName) return
                            Thread.sleep(100)
                        }
                        fail("选择文件后应用未回到前台")
                    }
                    if(!searched&&deadline-System.currentTimeMillis()<8000) {
                        val search=find(root){it.contentDescription?.toString() in listOf("搜索","Search")}
                        if(find(root){it.isEditable}!=null||search?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) {
                            Thread.sleep(300)
                            val field=find(instrumentation.uiAutomation.rootInActiveWindow){it.isEditable}
                            if(field!=null) {
                                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name)})
                                instrumentation.uiAutomation.executeShellCommand("input keyevent 66").close()
                                searched=true
                            }
                        }
                    }
                }
                Thread.sleep(150)
            }
            val ids=mutableSetOf<String>()
            fun inspect(node:AccessibilityNodeInfo?) {if(node==null)return;node.viewIdResourceName?.let {ids+=it};repeat(node.childCount){inspect(node.getChild(it))}}
            inspect(instrumentation.uiAutomation.rootInActiveWindow)
            val searchField=find(instrumentation.uiAutomation.rootInActiveWindow){it.viewIdResourceName?.endsWith(":id/search_src_text")==true}
            val list=find(instrumentation.uiAutomation.rootInActiveWindow){it.viewIdResourceName?.endsWith(":id/dir_list")==true}
            fail("系统文件选择器未展示本次测试创建的文件：$name，已搜索=$searched，查询匹配=${searchField?.text?.toString()==name}，结果数=${list?.childCount}，当前界面 ${instrumentation.uiAutomation.rootInActiveWindow?.packageName}，控件=$ids")
        }
        val pcm=ByteArray(88200)
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size)}.array()
        val bitmap=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
        val imageBytes=java.io.ByteArrayOutputStream().apply {bitmap.compress(Bitmap.CompressFormat.PNG,100,this)}.toByteArray()
        bitmap.recycle()
        val audioName="CarMusic-audio-$stamp";val imageName="CarMusic-cover-$stamp";val lyricName="CarMusic-lyrics-$stamp"
        try {
            create(MediaStore.Downloads.EXTERNAL_CONTENT_URI,"$audioName.wav","audio/wav","Download/CarMusic-smoke",header+pcm)
            create(MediaStore.Downloads.EXTERNAL_CONTENT_URI,"$imageName.png","image/png","Download/CarMusic-smoke",imageBytes)
            create(MediaStore.Downloads.EXTERNAL_CONTENT_URI,"$lyricName.txt","text/plain","Download/CarMusic-smoke","[00:00]系统选择器歌词".toByteArray())
            val settings=ApiClient.settings()
            listOf("vgChangeCover","vgChangeAudio","vgChangeLyric","vgExportVideo").forEach {settings.addProperty(it,true)}
            ApiClient.saveSettings(settings)
            instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/com.carmusic.app.MainActivity").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
            ui.waitUntil(15000){ui.onAllNodesWithText("为每一段旅程，留一首好歌。").fetchSemanticsNodes().isEmpty()}
            ui.onNodeWithContentDescription("系统设置 导航").performClick()
            ui.onNodeWithText("高级选项").performScrollTo().performClick()
            ui.onNodeWithText("音乐视频制作").performClick()
            ui.waitUntil(15000){ui.onAllNodesWithText("更换音频").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("更换音频").performScrollTo().performClick()
            ui.onNodeWithText("导入音频文件").performClick()
            selectOwnFile(audioName)
            ui.waitUntil(10000){ui.onAllNodesWithText("已选择导入音频").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("更换封面").performScrollTo().performClick()
            selectOwnFile(imageName)
            ui.onNodeWithText("导入歌词").performScrollTo().performClick()
            selectOwnFile(lyricName)
            ui.waitUntil(10000){ui.onAllNodes(hasText("[00:00]系统选择器歌词")).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("视频标题").performScrollTo().performTextReplacement(exportName)
            ui.onNodeWithText("制作视频").performScrollTo().assertIsEnabled().performClick()
            ui.waitUntil(30000){ui.onAllNodesWithText("视频制作完成，可保存到设备").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("保存视频").performScrollTo().assertIsEnabled().performClick()
            val saveDeadline=System.currentTimeMillis()+10000
            var saved=false
            while(System.currentTimeMillis()<saveDeadline&&!saved) {
                val root=instrumentation.uiAutomation.rootInActiveWindow
                if(root?.packageName?.toString()?.contains("documentsui")==true) {
                    val button=(root.findAccessibilityNodeInfosByText("保存")+root.findAccessibilityNodeInfosByText("Save")).firstOrNull {it.isClickable&&it.isEnabled}
                    if(button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) saved=true
                }
                if(!saved) Thread.sleep(100)
            }
            assertTrue("系统保存按钮未出现",saved)
            val resumed=System.currentTimeMillis()+10000
            while(System.currentTimeMillis()<resumed&&instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()!=context.packageName) Thread.sleep(100)
            ui.waitUntil(10000){ui.onAllNodesWithText("视频已保存").fetchSemanticsNodes().isNotEmpty()}
            val findCommand="find /sdcard/Download -name $exportName.mp4"
            val found=instrumentation.uiAutomation.executeShellCommand(findCommand).use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use {it.readText()}}
            val path=found.lineSequence().filter {it.isNotBlank()}.singleOrNull()?:throw AssertionError("保存文件未出现在下载目录")
            assertTrue(path.startsWith("/sdcard/Download/"));assertTrue(path.endsWith("/$exportName.mp4"))
            assertFalse(path.contains(".."));assertFalse(path.contains("'"))
            assertTrue(path.matches(Regex("/sdcard/Download/[A-Za-z0-9_./-]+")))
            val verification=java.io.File(context.cacheDir,"document-export-verification.mp4")
            try {
                instrumentation.uiAutomation.executeShellCommand("cat $path").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {input->verification.outputStream().use {input.copyTo(it)}}}
                val metadata=android.media.MediaMetadataRetriever()
                try {metadata.setDataSource(verification.absolutePath);assertEquals("yes",metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));assertEquals("yes",metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))} finally {metadata.release()}
            } finally {
                verification.delete()
                instrumentation.uiAutomation.executeShellCommand("rm $path").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
            }
        } finally {created.forEach {resolver.delete(it,null,null)}}
    }
}
