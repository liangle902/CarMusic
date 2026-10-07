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

/**
 * 一首歌在某个音源上的可播放版本；bitrate 单位 kbps，未知为 0。
 * exact 表示确认是同一个录音；为 false 的只是同名同歌手的近似版本，仅在没有确切版本可播时兜底。
 */
data class SourceCandidate(val song: SongItem, val bitrate: Int, val exact: Boolean = true)

internal object SourceResolver {
    // 引擎不允许把这些音源作为换源目标。
    private val NOT_SWITCHABLE = setOf("soda", "fivesing", "local")
    private const val PER_SOURCE_TIMEOUT_MS = 20000L
    private const val TOTAL_TIMEOUT_MS = 30000L
    // 已有可播放结果后，再等其余音源这么久，避免个别慢音源拖住起播。
    private const val GRACE_AFTER_FIRST_MS = 6000L

    private const val MAX_DURATION_DIFF_SECONDS = 5
    private val BRACKETED = Regex("[(（\\[【].*?[)）\\]】]")
    private val ARTIST_SEPARATOR = Regex("[&/、,，;；|]|\\sfeat\\.?\\s|\\sft\\.?\\s|\\swith\\s", RegexOption.IGNORE_CASE)
    // 这些版本标记必须两边一致，否则不是同一个录音。
    private val VERSION_MARKS = listOf("live", "现场", "remix", "dj", "伴奏", "instrumental", "翻唱", "cover", "demo", "纯音乐", "acoustic")

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private fun sameTitle(original: SongItem, other: SongItem) =
        normalize(BRACKETED.replace(original.name, "")) == normalize(BRACKETED.replace(other.name, ""))

    private fun sharesArtist(original: SongItem, other: SongItem): Boolean {
        val a = ARTIST_SEPARATOR.split(original.artist).map(::normalize).filter { it.isNotEmpty() }
        val b = ARTIST_SEPARATOR.split(other.artist).map(::normalize).filter { it.isNotEmpty() }
        return a.any { x -> b.any { y -> x == y || (minOf(x.length, y.length) >= 2 && (x in y || y in x)) } }
    }

    /** 近似版本：歌名一致且至少有一位歌手相同。同名但歌手完全不同的翻唱不算。 */
    fun sameSong(original: SongItem, other: SongItem): Boolean =
        other.key == original.key || (sameTitle(original, other) && sharesArtist(original, other))

    /**
     * 判断另一个音源返回的版本是不是同一个录音。引擎的匹配以歌名为主，同名翻唱也会被当成结果，
     * 所以这里再从严核对：歌名一致、至少有一位歌手相同、版本标记一致、时长相差不超过几秒。
     */
    fun sameRecording(original: SongItem, other: SongItem): Boolean {
        if (other.key == original.key) return true
        if (!sameSong(original, other)) return false
        val a = original.name.lowercase(); val b = other.name.lowercase()
        if (VERSION_MARKS.any { (it in a) != (it in b) }) return false
        if (original.duration > 0 && other.duration > 0 && kotlin.math.abs(original.duration - other.duration) > MAX_DURATION_DIFF_SECONDS) return false
        return true
    }

    /** 检测单个版本是否可播放。引擎算不出码率时退回歌曲自带的码率信息，避免把同码率的版本误判成更低。 */
    suspend fun probe(song: SongItem): SourceCandidate? = try {
        ApiClient.inspectStream(song).takeIf { it.valid }?.let { info ->
            val declared = song.bitrate.let { if (it >= 10000) (it + 500) / 1000 else it }
            SourceCandidate(song, info.bitrate.filter(Char::isDigit).toIntOrNull()?.takeIf { it > 0 } ?: declared)
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /**
     * 并行向各音源解析，返回可播放的版本：确切版本在前，其次码率高的在前，码率相同时原音源优先。
     * 第一个可播放的确切版本出现时立即回调 onFirst；如果最终只有近似版本，则在全部返回后用其中最好的回调。
     * waitAll 为 true 时等所有音源返回，否则有结果后只再等一小段时间。
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
                    if (!sameSong(song, version)) return@withTimeoutOrNull null
                    probe(version)?.copy(exact = sameRecording(song, version))
                } ?: return@launch
                found.add(candidate)
                if (candidate.exact && first.complete(Unit)) onFirst(candidate)
            }
        }
        val all = launch { jobs.joinAll() }
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            select<Unit> { first.onAwait {}; all.onJoin {} }
            if (waitAll) all.join() else withTimeoutOrNull(GRACE_AFTER_FIRST_MS) { all.join() }
        }
        jobs.forEach { it.cancel() }
        all.cancel()
        val sorted = synchronized(found) { found.toList() }
            .sortedWith(compareByDescending<SourceCandidate> { it.exact }.thenByDescending { it.bitrate }.thenBy { it.song.source != song.source })
        if (first.complete(Unit)) sorted.firstOrNull()?.let(onFirst)
        sorted
    }
}
