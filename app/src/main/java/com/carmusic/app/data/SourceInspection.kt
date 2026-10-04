package com.carmusic.app.data

import android.os.SystemClock
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Visible rows share one bounded probe per song across page changes. */
internal object SourceInspection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(3)
    private val active = mutableMapOf<String, Job>()
    private val checkedAt = mutableMapOf<String, Long>()

    @Synchronized fun request(song: SongItem) {
        if (song.source == "local" || song.key in active || active.size >= 12) return
        val status = AppStore.playbackChecks.value[song.key]
        if (status in listOf("正在解析", "正在播放", "自动换源中")) return
        val ttl = if (status in listOf("可播放", "已下载，可离线")) 300_000 else 30_000
        if (SystemClock.elapsedRealtime() - (checkedAt[song.key] ?: -ttl.toLong()) < ttl) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                slots.withPermit {
                    // Playback may have taken over while this probe was queued.
                    if (AppStore.playbackChecks.value[song.key] in listOf("正在解析", "正在播放", "自动换源中")) return@withPermit
                    AppStore.markPlayback(song, "检测中")
                    val offline = ApiClient.offlineUri(song) != null
                    if (!offline) ApiClient.resolvePlayable(song)
                    if (AppStore.playbackChecks.value[song.key] in listOf("检测中", "自动换源中"))
                        AppStore.markPlayback(song, if (offline) "已下载，可离线" else "可播放")
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (AppStore.playbackChecks.value[song.key] in listOf("检测中", "自动换源中"))
                    AppStore.markPlayback(song, "未找到可用音源")
            } finally {
                synchronized(this@SourceInspection) {
                    active.remove(song.key); checkedAt[song.key] = SystemClock.elapsedRealtime()
                }
            }
        }
        active[song.key] = job
        job.start()
    }
}
