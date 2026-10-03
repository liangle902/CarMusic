package com.carmusic.app.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object DaemonManager {
    private const val TAG = "DaemonManager"
    val PORT = if (com.carmusic.app.BuildConfig.APPLICATION_ID.endsWith(".smoke")) 37779 else 37777
    val BASE_URL = "http://127.0.0.1:$PORT/music"
    private var process: Process? = null
    private val starting = AtomicBoolean(false)

    suspend fun awaitReady() = withContext(Dispatchers.IO) {
        if(isDaemonAlive()) return@withContext
        ensureDaemonRunning(com.carmusic.app.CarMusicApplication.instance)
        repeat(30) { delay(400);if(isDaemonAlive()) return@withContext }
        throw java.io.IOException("音乐引擎启动失败，请重新打开应用后重试")
    }

    fun ensureDaemonRunning(context: Context) {
        if(!starting.compareAndSet(false,true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
            if (isDaemonAlive()) {
                Log.i(TAG, "Daemon is already running on port $PORT")
                return@launch
            }

            try {
                // 利用 Android 系统 nativeLibraryDir 的标准可执行权限 (避免 SELinux W^X 拦截)
                val binaryFile = File(context.applicationInfo.nativeLibraryDir, "libcarmusic.so")
                val dbFile = File(context.filesDir, "settings.db")
                val logFile = File(context.filesDir, "daemon.log")

                if (!binaryFile.exists()) {
                    Log.e(TAG, "Native library binary not found at: ${binaryFile.absolutePath}")
                    return@launch
                }

                val pb = ProcessBuilder(
                    binaryFile.absolutePath,
                    "web",
                    "--port", PORT.toString(),
                    "--no-browser",
                    "--base-path", "/music"
                )
                pb.directory(context.filesDir)
                pb.redirectOutput(ProcessBuilder.Redirect.to(logFile))
                pb.redirectError(ProcessBuilder.Redirect.to(logFile))

                val env = pb.environment()
                env["MUSIC_DL_CONFIG_DB"] = dbFile.absolutePath
                env["MUSIC_DL_PORT"] = PORT.toString()
                // Go targets Linux; explicitly use Android's maintained trust store.
                env["SSL_CERT_DIR"] = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
                env["TMPDIR"] = context.cacheDir.absolutePath
                env["MUSIC_DL_FFMPEG"] = File(context.applicationInfo.nativeLibraryDir,"libffmpeg.so").absolutePath
                env["MUSIC_DL_FFPROBE"] = File(context.applicationInfo.nativeLibraryDir,"libffprobe.so").absolutePath
                env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir

                Log.i(TAG, "Starting daemon: ${binaryFile.absolutePath} on port $PORT...")
                process = pb.start()

                // Wait up to 10 seconds for health check
                var attempts = 0
                while (attempts < 20) {
                    Thread.sleep(500)
                    if (isDaemonAlive()) {
                        Log.i(TAG, "Daemon is healthy and responsive on port $PORT!")
                        break
                    }
                    attempts++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start daemon engine", e)
            }
            } finally {starting.set(false)}
        }
    }

    fun isDaemonAlive(): Boolean {
        return try {
            val url = URL("$BASE_URL/healthz")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    fun stopDaemon() {
        try {
            process?.destroy()
            process = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping daemon", e)
        }
    }
}
