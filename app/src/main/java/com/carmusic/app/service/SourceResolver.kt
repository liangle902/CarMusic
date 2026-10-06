package com.carmusic.app.service

import com.carmusic.app.engine.ApiClient
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

/** 一首歌在某个音源上的可播放版本；bitrate 单位 kbps，未知为 0。 */
data class SourceCandidate(val song: SongItem, val bitrate: Int)

internal object SourceResolver {
    // 引擎不允许把这些音源作为换源目标。
    private val NOT_SWITCHABLE = setOf("soda", "fivesing", "local")
    private const val PER_SOURCE_TIMEOUT_MS = 20000L
    private const val TOTAL_TIMEOUT_MS = 30000L
    // 已有可播放结果后，再等其余音源这么久，避免个别慢音源拖住起播。
    private const val GRACE_AFTER_FIRST_MS = 6000L

    /** 检测单个版本是否可播放。引擎算不出码率时退回歌曲自带的码率信息，避免把同码率的版本误判成更低。 */
    suspend fun probe(song: SongItem): SourceCandidate? = try {
        ApiClient.inspectStream(song).takeIf { it.valid }?.let { info ->
            val declared = song.bitrate.let { if (it >= 10000) (it + 500) / 1000 else it }
            SourceCandidate(song, info.bitrate.filter(Char::isDigit).toIntOrNull()?.takeIf { it > 0 } ?: declared)
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /**
     * 并行向各音源解析，返回可播放的版本，码率高的在前，码率相同时原音源优先。
     * 第一个可播放的版本出现时立即回调 onFirst；waitAll 为 true 时等所有音源返回，否则有结果后只再等一小段时间。
     */
    suspend fun candidates(song: SongItem, allSources: Boolean, waitAll: Boolean = false, onFirst: (SourceCandidate) -> Unit = {}): List<SourceCandidate> = coroutineScope {
        val others = if (!allSources) emptyList() else try {
            ApiClient.sources().filter { it.playback && it.id != song.source && it.id !in NOT_SWITCHABLE }.map { it.id }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        val found = Collections.synchronizedList(mutableListOf<SourceCandidate>())
        val first = CompletableDeferred<Unit>()
        val jobs = (listOf(song.source) + others).map { source ->
            launch {
                val candidate = withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                    val version = if (source == song.source) song else try { ApiClient.switchSource(song, source) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { return@withTimeoutOrNull null }
                    probe(version)
                } ?: return@launch
                found.add(candidate)
                if (first.complete(Unit)) onFirst(candidate)
            }
        }
        val all = launch { jobs.joinAll() }
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            select<Unit> { first.onAwait {}; all.onJoin {} }
            if (waitAll) all.join() else withTimeoutOrNull(GRACE_AFTER_FIRST_MS) { all.join() }
        }
        jobs.forEach { it.cancel() }
        all.cancel()
        synchronized(found) { found.toList() }.sortedWith(compareByDescending<SourceCandidate> { it.bitrate }.thenBy { it.song.source != song.source })
    }
}
