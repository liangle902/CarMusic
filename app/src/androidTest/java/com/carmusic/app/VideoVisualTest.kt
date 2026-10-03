package com.carmusic.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.ui.VideoFrameSource
import com.carmusic.app.ui.VideoRenderOptions
import com.carmusic.app.ui.renderMusicVideo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VideoVisualTest {
    @Test fun visualLoopsAndCoverAndBackgroundAreEncodedWithSelectedAudio():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val visual=File(context.cacheDir,"dynamic-visual-test.mp4")
        val audio=File(context.cacheDir,"dynamic-audio-test.wav")
        val pcm=ByteArray(44100*2*3)
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size)}.array()
        audio.outputStream().use {it.write(header);it.write(pcm)}
        val still=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.GREEN)}
        var output:File?=null
        try {
            val builder=ProcessBuilder(File(context.applicationInfo.nativeLibraryDir,"libffmpeg.so").absolutePath,"-y","-loglevel","error","-f","lavfi","-i","color=c=red:s=64x64:r=10:d=1","-f","lavfi","-i","color=c=blue:s=64x64:r=10:d=1","-filter_complex","[0:v][1:v]concat=n=2:v=1:a=0","-c:v","libx264","-pix_fmt","yuv420p",visual.absolutePath)
            builder.environment()["LD_LIBRARY_PATH"]=context.applicationInfo.nativeLibraryDir
            val process=builder.redirectErrorStream(true).start();val log=process.inputStream.bufferedReader().readText()
            assertEquals(log,0,process.waitFor())
            fun checkColor(bitmap:Bitmap,red:Boolean,x:Int=0,y:Int=0){try {val pixel=bitmap.getPixel(x,y);assertTrue(if(red) Color.red(pixel)>Color.blue(pixel) else Color.blue(pixel)>Color.red(pixel))} finally {bitmap.recycle()}}
            VideoFrameSource(visual,32,32).use {source->
                val first=source.frameAt(200);assertTrue(first.width<=32);checkColor(first,true)
                checkColor(source.frameAt(1200),false);checkColor(source.frameAt(2200),true)
            }
            for(background in listOf(false,true)) {
                output=renderMusicVideo(context,audio,still,null,"","动态测试",VideoRenderOptions(ratio="1:1",resolution=320,backgroundMode=if(background) "image" else "solid"),coverVideo=if(background) null else visual,backgroundVideo=if(background) visual else null) {}
                val metadata=MediaMetadataRetriever()
                try {
                    metadata.setDataSource(output!!.absolutePath)
                    assertEquals("yes",metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                    val x=if(background) 0 else 160;val y=if(background) 0 else 120
                    checkColor(requireNotNull(metadata.getFrameAtTime(200000,MediaMetadataRetriever.OPTION_CLOSEST)),true,x,y)
                    checkColor(requireNotNull(metadata.getFrameAtTime(1200000,MediaMetadataRetriever.OPTION_CLOSEST)),false,x,y)
                    checkColor(requireNotNull(metadata.getFrameAtTime(2200000,MediaMetadataRetriever.OPTION_CLOSEST)),true,x,y)
                } finally {metadata.release()}
                output!!.delete();output=null
            }
        } finally {still.recycle();visual.delete();audio.delete();output?.delete()}
    }
}
