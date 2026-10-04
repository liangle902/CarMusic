package com.carmusic.app.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

private val videoHttp = com.carmusic.app.engine.ApiClient.httpClient.newBuilder()
    .readTimeout(60, TimeUnit.SECONDS).build()

private suspend fun copyVideoAsset(input: InputStream, output: OutputStream, limit: Long = Long.MAX_VALUE): Long {
    val buffer = ByteArray(64 * 1024)
    var size = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        size += count
        if (size > limit) throw IOException("素材文件过大")
        output.write(buffer, 0, count)
    }
    if (size == 0L) throw IOException("素材内容为空")
    return size
}

internal suspend fun importVideoAudio(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val file = File.createTempFile("video-audio-", ".audio", context.cacheDir)
    try {
        val source = context.contentResolver.openInputStream(uri) ?: throw IOException("无法打开音频")
        source.use { input -> file.outputStream().use { copyVideoAsset(input, it) } }
        file
    } catch (e: Exception) { file.delete(); throw e }
}

internal suspend fun importVideoLyrics(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val source = context.contentResolver.openInputStream(uri) ?: throw IOException("无法打开歌词")
    val bytes = java.io.ByteArrayOutputStream()
    source.use { copyVideoAsset(it, bytes, 1024 * 1024) }
    val content = bytes.toByteArray()
    when {
        content.size >= 2 && content[0] == 0xff.toByte() && content[1] == 0xfe.toByte() -> String(content, Charsets.UTF_16LE).removePrefix("\uFEFF")
        content.size >= 2 && content[0] == 0xfe.toByte() && content[1] == 0xff.toByte() -> String(content, Charsets.UTF_16BE).removePrefix("\uFEFF")
        else -> String(content, Charsets.UTF_8).removePrefix("\uFEFF")
    }
}

internal suspend fun saveVideoOutput(context: Context, file: File, uri: Uri) = withContext(Dispatchers.IO) {
    if (!file.isFile || file.length() == 0L) throw IOException("请先制作视频")
    val destination = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入所选位置")
    destination.use { out -> file.inputStream().use { copyVideoAsset(it, out) } }
}

internal suspend fun downloadVideoAsset(context: Context, url: String, limit: Long = Long.MAX_VALUE): File = withContext(Dispatchers.IO) {
    val call = videoHttp.newCall(Request.Builder().url(url).build())
    val file = File.createTempFile("video-network-", ".asset", context.cacheDir)
    val watcher = launch(Dispatchers.IO) { try { awaitCancellation() } finally { call.cancel() } }
    try {
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("素材获取失败（${response.code}）")
            val body = response.body ?: throw IOException("素材内容为空")
            body.byteStream().use { input -> file.outputStream().use { copyVideoAsset(input, it, limit) } }
        }
        file
    } catch (e: Exception) { file.delete(); ensureActive(); throw e }
    finally { watcher.cancel() }
}
