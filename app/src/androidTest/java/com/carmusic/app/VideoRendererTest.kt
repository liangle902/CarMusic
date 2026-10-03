package com.carmusic.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.ui.renderMusicVideo
import com.carmusic.app.ui.VideoRenderOptions
import com.carmusic.app.ui.drawVideoFrame
import com.carmusic.app.ui.decodeVideoImage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VideoRendererTest {
    @Test fun previewRespectsBackgroundAndLyricsAndDownsamplesLargeImages() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cover=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
        val bg=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.RED)}
        val frame=Bitmap.createBitmap(360,640,Bitmap.Config.ARGB_8888)
        try {
            drawVideoFrame(frame,cover,bg,"测试歌词","标题",VideoRenderOptions(backgroundMode="image"))
            assertTrue(Color.red(frame.getPixel(0,0))>Color.blue(frame.getPixel(0,0)))
            drawVideoFrame(frame,cover,bg,"测试歌词","标题",VideoRenderOptions(backgroundMode="cover"))
            assertTrue(Color.blue(frame.getPixel(0,0))>Color.red(frame.getPixel(0,0)))
            drawVideoFrame(frame,cover,bg,"测试歌词","标题",VideoRenderOptions(backgroundMode="solid",showLyrics=false))
            val hidden=frame.copy(Bitmap.Config.ARGB_8888,false)
            try {drawVideoFrame(frame,cover,bg,"测试歌词","标题",VideoRenderOptions(backgroundMode="solid",showLyrics=true));assertFalse(frame.sameAs(hidden))} finally {hidden.recycle()}
            val image=File(context.cacheDir,"large-video-image.jpg")
            val large=Bitmap.createBitmap(3200,2400,Bitmap.Config.ARGB_8888)
            try {image.outputStream().use {large.compress(Bitmap.CompressFormat.JPEG,80,it)}} finally {large.recycle()}
            try {val decoded=decodeVideoImage(context,android.net.Uri.fromFile(image));try {assertTrue(decoded.width<=1440);assertTrue(decoded.height<=1920)} finally {decoded.recycle()}} finally {image.delete()}
        } finally {frame.recycle();cover.recycle();bg.recycle()}
    }
    @Test fun rendersCoverBackgroundLyricsAndPlayableAudioVideo()= runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val pcm=ByteArray(44100*2*2)
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size)}.array()
        val audio=File(context.cacheDir,"video-test.wav").apply {outputStream().use {it.write(header);it.write(pcm)}}
        val cover=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
        val background=Bitmap.createBitmap(256,512,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.DKGRAY)}
        var output:File?=null
        try {
            val before=context.cacheDir.listFiles().orEmpty().filter {it.name.startsWith("music-video-")}.map {it.name}.toSet()
            val cancelled=launch {renderMusicVideo(context,audio,cover,background,"[00:00]歌词","取消测试") {cancel()}}
            withTimeout(10000){cancelled.join()}
            assertTrue(cancelled.isCancelled)
            assertEquals(before,context.cacheDir.listFiles().orEmpty().filter {it.name.startsWith("music-video-")}.map {it.name}.toSet())
            listOf(VideoRenderOptions(),VideoRenderOptions(ratio="1:1",resolution=1080,backgroundMode="solid",showLyrics=false),VideoRenderOptions(ratio="3:4",backgroundMode="image"),VideoRenderOptions(ratio="16:9")).forEach {options->
            output=renderMusicVideo(context,audio,cover,background,"[00:00.00]一路星河\n[00:01.00]一路好歌","星河音乐测试",options) {}
            val rendered=output!!
            assertTrue(rendered.length()>1000)
            val metadata=MediaMetadataRetriever()
            try {
                metadata.setDataSource(rendered.absolutePath)
                assertEquals(options.width.toString(),metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                assertEquals(options.height.toString(),metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                assertTrue((metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()?:0)>=1900)
                assertEquals("yes",metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertEquals("yes",metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                assertNotNull(metadata.getFrameAtTime(1000000))
            } finally {metadata.release()}
            rendered.delete()
            }
        } finally {audio.delete();output?.delete();cover.recycle();background.recycle()}
    }
}
