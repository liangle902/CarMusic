package com.carmusic.app

import android.graphics.Bitmap
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.engine.parseTimedLyrics
import com.carmusic.app.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoEffectsTest {
    @Test fun livePreviewSpectrumPreservesDecodedAudioBytes(){
        val sink=PreviewSpectrumSink();val processor=TeeAudioProcessor(sink)
        processor.configure(AudioProcessor.AudioFormat(22050,1,C.ENCODING_PCM_16BIT));processor.flush()
        val bytes=ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN).apply {repeat(2048){putShort((sin(2*PI*1000*it/22050)*20000).toInt().toShort())}}.array()
        try {val input=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {put(bytes);flip()};processor.queueInput(input)
            val buffer=processor.output;val output=ByteArray(buffer.remaining()).also {buffer.get(it)}
            assertArrayEquals(bytes,output);assertTrue(sink.levels.max()>.8f)
        } finally {processor.reset()}
    }
    @Test fun exportedEffectsUseRealPcmAndKeepTimedAndLongLyrics():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val audio=File(context.cacheDir,"effects-audio.wav")
        val pcm=ByteBuffer.allocate(22050*2*2).order(ByteOrder.LITTLE_ENDIAN).apply {repeat(22050*2){putShort(if(it<22050) 0 else (sin(2*PI*1000*it/22050)*20000).toInt().toShort())}}.array()
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(22050);putInt(44100);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size)}.array()
        audio.outputStream().use {it.write(header);it.write(pcm)}
        val a=Bitmap.createBitmap(360,640,Bitmap.Config.ARGB_8888);val b=Bitmap.createBitmap(360,640,Bitmap.Config.ARGB_8888)
        try {
            VideoPcmSpectrum(context,audio).use {analyzer->repeat(20){assertTrue(analyzer.nextFrame().all {it==0f})};repeat(20){analyzer.nextFrame()};val levels=analyzer.nextFrame();assertTrue(levels.max()>.8f)
                drawVideoFrame(a,null,null,"","频谱测试",VideoRenderOptions(),spectrum=FloatArray(48));drawVideoFrame(b,null,null,"","频谱测试",VideoRenderOptions(),spectrum=levels);assertFalse(a.sameAs(b))
            }
            val line=parseTimedLyrics("[00:00.000]好[00:00.500]歌[00:01.000]\n[00:00.000]hao ge\n[00:00.000]一首好歌").single()
            drawVideoFrame(a,null,null,line.text,"歌词测试",VideoRenderOptions(vinyl=false),0,line);drawVideoFrame(b,null,null,line.text,"歌词测试",VideoRenderOptions(vinyl=false),750,line);assertFalse(a.sameAs(b))
            val long="歌词内容".repeat(15)
            drawVideoFrame(a,null,null,long+"甲","标题".repeat(20)+"甲",VideoRenderOptions(vinyl=false));drawVideoFrame(b,null,null,long+"乙","标题".repeat(20)+"乙",VideoRenderOptions(vinyl=false));assertFalse("Long text endings must remain visible",a.sameAs(b))
            assertFalse(context.cacheDir.listFiles().orEmpty().any {it.name.startsWith("video-pcm-")})
        } finally {a.recycle();b.recycle();audio.delete()}
    }
}
