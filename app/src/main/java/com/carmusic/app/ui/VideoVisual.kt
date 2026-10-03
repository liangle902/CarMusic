package com.carmusic.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal data class VideoVisual(val file:File,val preview:Bitmap)

/** Muted visual track. It loops independently of the selected music track. */
internal class VideoFrameSource(file:File,maxWidth:Int=1440,maxHeight:Int=1920):AutoCloseable {
    private val metadata=MediaMetadataRetriever()
    private val duration:Long
    private val width:Int
    private val height:Int
    init {
        try {
            metadata.setDataSource(file.absolutePath)
            if(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)!="yes") throw IOException("所选文件不包含视频画面")
            duration=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf {it>0}?:throw IOException("无法读取视频时长")
            val rawWidth=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:throw IOException("无法读取视频尺寸")
            val rawHeight=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:throw IOException("无法读取视频尺寸")
            require(rawWidth>0&&rawHeight>0&&maxWidth>0&&maxHeight>0) {"视频尺寸无效"}
            val scale=minOf(1f,maxWidth.toFloat()/rawWidth,maxHeight.toFloat()/rawHeight)
            width=(rawWidth*scale).toInt().coerceAtLeast(1);height=(rawHeight*scale).toInt().coerceAtLeast(1)
        } catch(e:Exception){metadata.release();throw e}
    }
    fun frameAt(timeMs:Long):Bitmap {
        val time=timeMs.coerceAtLeast(0)%duration*1000
        val frame=(if(Build.VERSION.SDK_INT>=27) metadata.getScaledFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST,width,height) else metadata.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST))?:throw IOException("视频画面无法解码")
        return if(frame.width>width||frame.height>height) Bitmap.createScaledBitmap(frame,width,height,true).also {if(it!==frame) frame.recycle()} else frame
    }
    override fun close(){metadata.release()}
}

internal suspend fun videoPreviewFrame(source:VideoFrameSource?,timeMs:Long):Bitmap? {
    var frame:Bitmap?=null
    try {return withContext(Dispatchers.IO){source?.frameAt(timeMs).also {frame=it}}} catch(e:Exception){frame?.recycle();throw e}
}

internal suspend fun importVideoVisual(context:Context,uri:Uri):VideoVisual {
    var visual:VideoVisual?=null
    try {return withContext(Dispatchers.IO) {
        val file=importVideoAudio(context,uri)
        try {VideoFrameSource(file).use {VideoVisual(file,it.frameAt(0)).also {value->visual=value}}} catch(e:Exception){file.delete();throw e}
    }} catch(e:Exception){visual?.file?.delete();visual?.preview?.recycle();throw e}
}
