package com.carmusic.app.ui

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.carmusic.app.engine.AudioSpectrum
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PreviewSpectrumSink:TeeAudioProcessor.AudioBufferSink {
    private val spectrum=AudioSpectrum()
    private var rate=44100;private var channels=2;private var encoding=C.ENCODING_PCM_16BIT;private var samples=0
    @Volatile var levels=FloatArray(48);private set
    override fun flush(sampleRateHz:Int,channelCount:Int,encoding:Int){rate=sampleRateHz;channels=channelCount;this.encoding=encoding;samples=0;spectrum.reset();levels=FloatArray(48)}
    override fun handleBuffer(buffer:ByteBuffer){
        if(encoding!=C.ENCODING_PCM_16BIT||channels<=0)return
        val input=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while(input.remaining()>=channels*2){var mono=0f;repeat(channels){mono+=input.short/32768f};spectrum.add(mono/channels);samples++
            if(samples>=rate/20){levels=spectrum.levels(rate);samples=0}
        }
    }
}

/** Streams mono PCM; memory does not grow with song duration. */
internal class VideoPcmSpectrum(context:Context,audio:File):Closeable {
    private val rate=22050
    private val spectrum=AudioSpectrum()
    private val log=File.createTempFile("video-pcm-",".log",context.cacheDir)
    private val process=ProcessBuilder(File(context.applicationInfo.nativeLibraryDir,"libffmpeg.so").absolutePath,"-hide_banner","-loglevel","error","-i",audio.absolutePath,"-vn","-ac","1","-ar",rate.toString(),"-f","s16le","pipe:1").apply {environment()["LD_LIBRARY_PATH"]=context.applicationInfo.nativeLibraryDir;redirectError(log)}.start()
    private val input=process.inputStream.buffered()
    private var ended=false
    fun nextFrame():FloatArray {
        repeat(rate/30){val lo=if(ended) -1 else input.read();val hi=if(lo>=0) input.read() else -1
            if(hi<0&&!ended){ended=true;if(process.waitFor()!=0) throw IOException("频谱音频解码失败：${log.readText().take(200)}")}
            spectrum.add(if(hi<0) 0f else ((hi shl 8) or lo).toShort()/32768f)
        }
        return spectrum.levels(rate)
    }
    override fun close(){process.destroy();input.close();log.delete()}
}
