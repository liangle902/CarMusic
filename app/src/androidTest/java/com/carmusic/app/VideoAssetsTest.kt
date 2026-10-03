package com.carmusic.app

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.ui.*
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class VideoAssetsTest {
    @Test fun songCoverExportsAsRealJpegAndLocalLyricsExportTheirContent():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap=android.graphics.Bitmap.createBitmap(20,20,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.BLUE)}
        val png=java.io.ByteArrayOutputStream().apply {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,this)}.toByteArray()
        val jpeg=java.io.ByteArrayOutputStream().apply {bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,this)}.toByteArray()
        bitmap.recycle()
        val server=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
        val worker=thread {
            try {while(!server.isClosed) server.accept().use {socket->
                socket.soTimeout=5000
                val reader=socket.getInputStream().bufferedReader()
                val request=reader.readLine().orEmpty()
                while(!reader.readLine().isNullOrEmpty()){}
                val bytes=if(request.contains("cover.jpg")) jpeg else png
                val type=if(request.contains("cover.jpg")) "image/jpeg" else "image/png"
                socket.getOutputStream().use {it.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());it.write(bytes)}
            }} catch(_:java.net.SocketException){}
        }
        val files=mutableListOf<File>()
        val directory=File(context.filesDir,"data/downloads").apply {mkdirs()}
        val audio=File(directory,"asset-export-test.wav")
        val lrc=File(directory,"asset-export-test.lrc")
        try {
            val song=SongItem(id="asset-export-test",source="local",cover="http://127.0.0.1:${server.localPort}/cover.png")
            val converted=prepareSongAssetExport(context,song,true).also {files+=it}
            assertEquals(0xff,converted.readBytes()[0].toInt() and 0xff)
            assertEquals(0xd8,converted.readBytes()[1].toInt() and 0xff)
            val decoded=android.graphics.BitmapFactory.decodeFile(converted.absolutePath)
            assertNotNull(decoded);assertEquals(20,decoded.width);assertEquals(20,decoded.height);decoded.recycle()
            val original=prepareSongAssetExport(context,song.copy(cover="http://127.0.0.1:${server.localPort}/cover.jpg"),true).also {files+=it}
            assertArrayEquals("JPEG 封面应保留原始内容",jpeg,original.readBytes())
            val pcm=ByteArray(88200)
            val header=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1)
                putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size)
            }.array()
            audio.writeBytes(header+pcm);lrc.writeText("[00:00]独立导出验证歌词")
            val local=ApiClient.localSongs().first {it.extra?.get("filename")==audio.name}
            val lyrics=prepareSongAssetExport(context,local,false).also {files+=it}
            assertTrue(lyrics.readText().contains("独立导出验证歌词"))
        } finally {files.forEach {it.delete()};audio.delete();lrc.delete();server.close();worker.join(6000)}
    }

    @Test fun contentUrisImportExportAndRejectEmptyOrOversizedLyrics() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val resolver=context.contentResolver
        val uris=mutableListOf<Uri>()
        fun document(bytes:ByteArray):Uri {
            val values=ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,"CarMusic-smoke-${System.nanoTime()}.bin")
                put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/CarMusic-smoke")
            }
            val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
            uris+=uri
            resolver.openOutputStream(uri)!!.use {it.write(bytes)}
            return uri
        }
        try {
            val bytes=ByteArray(180000){(it%127).toByte()}
            val imported=importVideoAudio(context,document(bytes))
            try {
                assertArrayEquals(bytes,imported.readBytes())
                val destination=document(byteArrayOf(9))
                saveVideoOutput(context,imported,destination)
                assertArrayEquals(bytes,resolver.openInputStream(destination)!!.use {it.readBytes()})
            } finally {imported.delete()}
            assertEquals("[00:01]星河",importVideoLyrics(context,document("\uFEFF[00:01]星河".toByteArray(Charsets.UTF_16LE))))
            val before=context.cacheDir.listFiles().orEmpty().filter {it.name.startsWith("video-audio-")}.map {it.name}.toSet()
            try {importVideoAudio(context,document(byteArrayOf()));fail("空音频应拒绝")} catch(_:IOException) {}
            assertEquals(before,context.cacheDir.listFiles().orEmpty().filter {it.name.startsWith("video-audio-")}.map {it.name}.toSet())
            try {importVideoLyrics(context,document(ByteArray(1024*1024+1)));fail("超大歌词应拒绝")} catch(_:IOException) {}
        } finally {uris.forEach {resolver.delete(it,null,null)}}
    }

    @Test fun stalledNetworkCancellationClosesConnectionAndRemovesPartialAsset() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val before=context.cacheDir.listFiles().orEmpty().filter {it.name.startsWith("video-network-")}.map {it.name}.toSet()
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val started=CountDownLatch(1);val closed=CountDownLatch(1)
        val worker=thread {
            try {server.accept().use {socket->
                socket.soTimeout=5000
                val reader=socket.getInputStream().bufferedReader()
                while(!reader.readLine().isNullOrEmpty()) {}
                socket.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\nx".toByteArray());flush()}
                started.countDown()
                assertEquals(-1,socket.getInputStream().read())
            }} finally {closed.countDown()}
        }
        val job=launch(Dispatchers.IO) {downloadVideoAsset(context,"http://127.0.0.1:${server.localPort}/stalled")}
        try {
            assertTrue(started.await(5,TimeUnit.SECONDS))
            withTimeout(2000){job.cancelAndJoin()}
            assertTrue(job.isCancelled)
            assertTrue(closed.await(2,TimeUnit.SECONDS))
            assertEquals(before,context.cacheDir.listFiles().orEmpty().filter {it.name.startsWith("video-network-")}.map {it.name}.toSet())
        } finally {job.cancel();server.close();worker.join(6000)}
    }
}
