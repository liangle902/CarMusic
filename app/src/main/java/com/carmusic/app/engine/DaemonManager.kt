package com.carmusic.app.engine

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The OS binds an available loopback port; the host publishes it after binding. */
object DaemonManager {
    private data class Endpoint(val port: Int, val pid: Int, val instance: String)
    private const val TAG = "DaemonManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    @Volatile private var endpoint: Endpoint? = null
    private var process: Process? = null
    val PORT: Int get() = endpoint?.port ?: 0
    val BASE_URL: String get() = "http://127.0.0.1:$PORT/music"
    private fun endpointFile(context: Context) = File(context.filesDir, "engine-endpoint.json")

    suspend fun awaitReady() = withContext(Dispatchers.IO) {
        lifecycle.withLock { startIfNeeded(com.carmusic.app.CarMusicApplication.instance) }
    }

    fun ensureDaemonRunning(context: Context) {
        scope.launch {
            try { lifecycle.withLock { startIfNeeded(context.applicationContext) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e(TAG, "Music engine startup failed", e) }
        }
    }

    private fun readEndpoint(context: Context): Endpoint? = runCatching {
        val value = JsonParser.parseString(endpointFile(context).readText()).asJsonObject
        Endpoint(value.get("port").asInt, value.get("pid").asInt, value.get("instance").asString)
            .takeIf { it.port in 1024..65535 && it.pid > 0 && it.instance.isNotBlank() }
    }.getOrNull()

    private fun healthy(value: Endpoint?): Boolean {
        if (value == null) return false
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("http://127.0.0.1:${value.port}/music/healthz").openConnection() as HttpURLConnection
            connection.connectTimeout = 800; connection.readTimeout = 800
            if (connection.responseCode != 200) false else {
                val reply = connection.inputStream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
                reply.get("app")?.asString == "go-music-dl" && reply.get("status")?.asString == "ok" && reply.get("instance")?.asString == value.instance
            }
        } catch (_: Exception) { false } finally { connection?.disconnect() }
    }

    fun isDaemonAlive(): Boolean = healthy(endpoint)

    private suspend fun startIfNeeded(context: Context) {
        if (healthy(endpoint)) return
        val previous = readEndpoint(context)
        if (healthy(previous)) { endpoint = previous; return }
        process?.let { if (it.isAlive) { it.destroy(); it.waitFor(3, TimeUnit.SECONDS); if (it.isAlive) it.destroyForcibly() } }
        endpoint = null
        val descriptor = endpointFile(context)
        descriptor.delete()
        val executable = File(context.applicationInfo.nativeLibraryDir, "libcarmusic.so")
        check(executable.isFile) { "音乐引擎文件缺失，请重新安装应用" }
        val instance = UUID.randomUUID().toString()
        val log = File(context.filesDir, "daemon.log")
        val builder = ProcessBuilder(executable.absolutePath).directory(context.filesDir)
            .redirectOutput(ProcessBuilder.Redirect.to(log)).redirectError(ProcessBuilder.Redirect.to(log))
        builder.environment().apply {
            put("MUSIC_DL_CONFIG_DB", File(context.filesDir, "settings.db").absolutePath)
            put("MUSIC_DL_ENDPOINT_FILE", descriptor.absolutePath)
            put("MUSIC_DL_INSTANCE", instance)
            put("SSL_CERT_DIR", "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts")
            put("TMPDIR", context.cacheDir.absolutePath)
            put("MUSIC_DL_FFMPEG", File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath)
            put("MUSIC_DL_FFPROBE", File(context.applicationInfo.nativeLibraryDir, "libffprobe.so").absolutePath)
            put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
        }
        val launched = builder.start()
        process = launched
        repeat(50) {
            val announced = readEndpoint(context)?.takeIf { it.instance == instance }
            if (healthy(announced)) { endpoint = announced; return }
            if (!launched.isAlive) throw java.io.IOException("音乐引擎启动失败，请重新打开应用")
            delay(300)
        }
        launched.destroy(); descriptor.delete()
        throw java.io.IOException("音乐引擎启动超时，请重新打开应用")
    }

    private suspend fun stopLocked(context: Context) {
        val owned = endpoint ?: readEndpoint(context)
        val child = process
        if (child != null && child.isAlive) {
            child.destroy()
            if (!child.waitFor(3, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS) }
            check(!child.isAlive) { "音乐引擎尚未停止" }
        } else if (healthy(owned)) {
            Os.kill(owned!!.pid, OsConstants.SIGTERM)
            repeat(30) {
                val alive = runCatching { Os.kill(owned.pid, 0); true }.getOrDefault(false)
                if (!alive) { endpoint = null; process = null; endpointFile(context).delete(); return }
                delay(100)
            }
            throw java.io.IOException("音乐引擎尚未停止，请重新打开应用后重试")
        }
        endpoint = null; process = null; endpointFile(context).delete()
    }

    fun stopDaemon() { scope.launch { lifecycle.withLock { stopLocked(com.carmusic.app.CarMusicApplication.instance) } } }
}
