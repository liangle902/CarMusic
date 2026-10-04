package com.carmusic.app.engine

import com.carmusic.app.ui.model.*
import com.google.gson.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.carmusic.app.data.AppStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DownloadOutcome(val offlineUri:String?,val notice:String)

object ApiClient {
    // Runs for every network hop, including redirects. Only the current engine
    // origin receives its token; external artwork/CDN hosts never receive it.
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .addNetworkInterceptor(EngineTokenInterceptor { url ->
            if (url.scheme == "http") DaemonManager.authorizationFor(url.host, url.port) else null
        }).build()
    val gson = Gson()
    internal fun errorMessage(response: JsonObject): String = response.get("error")
        ?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString.orEmpty()
    private suspend fun execute(request:Request,discardBody:Boolean=false):String = suspendCancellableCoroutine {continuation ->
        val call=httpClient.newCall(request)
        continuation.invokeOnCancellation {call.cancel()}
        call.enqueue(object:okhttp3.Callback {
            override fun onFailure(call:okhttp3.Call,e:IOException) {if(continuation.isActive) continuation.resumeWithException(e)}
            override fun onResponse(call:okhttp3.Call,response:okhttp3.Response) {
                try {val result=response.use {
                    if(!it.isSuccessful) {val error=it.body?.string().orEmpty();val reason=runCatching {JsonParser.parseString(error).asJsonObject.get("error")?.asString}.getOrNull();throw IOException(reason?:"请求失败（${it.code}）")}
                    val body=it.body?:throw IOException("服务未返回内容")
                    if(discardBody){body.source().readAll(okio.blackholeSink());""} else body.string()
                };if(continuation.isActive) continuation.resume(result)} catch(e:Exception){if(continuation.isActive) continuation.resumeWithException(e)}
            }
        })
    }
    fun url(path: String, params: Map<String, String> = emptyMap()): String =
        (DaemonManager.BASE_URL + path).toHttpUrl().newBuilder().apply { params.forEach { (k,v) -> if (k == "sources") v.split(',').filter { it.isNotBlank() }.forEach { addQueryParameter(k,it) } else addQueryParameter(k,v) } }.build().toString()
    private suspend fun request(path: String, params: Map<String,String> = emptyMap(), method: String = "GET", body: JsonElement? = null): String = withContext(Dispatchers.IO) {
        DaemonManager.awaitReady()
        val builder = Request.Builder().url(url(path, params)).header("Accept", "application/json").header("X-Requested-With", "XMLHttpRequest")
        if (method != "GET") builder.method(method, (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
        execute(builder.build())
    }
    suspend fun json(path: String, params: Map<String,String> = emptyMap(), method: String = "GET", body: JsonElement? = null): JsonElement = JsonParser.parseString(request(path,params,method,body))
    private fun songParams(song: SongItem) = mapOf("id" to song.id,"source" to song.source,"name" to song.name,"artist" to song.artist,"album" to song.album,"cover" to song.cover,"duration" to song.duration.toString(),"extra" to gson.toJson(song.extra ?: emptyMap<String,Any>()))
    suspend fun searchSongs(query: String, sources: String = ""): List<SongItem> {
        val response = json("/search",mapOf("q" to query,"sources" to sources,"format" to "json")).asJsonObject
        val songs = decodeSongs(response.get("songs"))
        if (songs.isEmpty() && errorMessage(response).isNotBlank()) throw IOException(errorMessage(response))
        return songs
    }
    suspend fun inspectStream(song: SongItem): StreamInfo = gson.fromJson(json("/inspect",songParams(song)),StreamInfo::class.java)
    suspend fun resolvePlayable(song:SongItem):SongItem {
        val current=AppStore.restoredResolved(song)?:song
        val valid=try {inspectStream(current).valid} catch(e:CancellationException){throw e} catch(_:Exception){false}
        if(valid) return current
        if((AppStore.engineSettings.value?:settings()).get("autoSwitchInvalidSources")?.asBoolean==false) throw IOException("当前源失效，自动换源已关闭")
        AppStore.markPlayback(song,"自动换源中")
        val replacement=switchSource(current)
        check(inspectStream(replacement).valid) {"未找到可用音源"}
        AppStore.saveResolved(song,replacement)
        return replacement
    }
    fun streamUrl(song: SongItem) = url("/download",songParams(song) + ("stream" to "1"))
    fun playbackCacheKey(uri: android.net.Uri): String = if (uri.host == "127.0.0.1" && uri.path == "/music/download") {
        "carmusic:" + uri.encodedPath + "?" + uri.encodedQuery.orEmpty()
    } else uri.toString()
    fun lyricDownloadUrl(song:SongItem)=url("/download_lrc",songParams(song)+("format" to "auto"))
    private val lyricCache=java.util.concurrent.ConcurrentHashMap<String,List<LyricLine>>()
    fun invalidateLyrics(song:SongItem){lyricCache.remove(song.key)}
    fun coverUrl(source: String, cover: String): String = when {
        cover.startsWith("/") -> "http://127.0.0.1:${DaemonManager.PORT}$cover"
        cover.startsWith("http") -> if (DaemonManager.PORT == 0) cover else url("/cover_proxy",mapOf("url" to cover,"source" to source))
        else -> cover
    }
    suspend fun fetchLyrics(song: SongItem): List<LyricLine> {
        lyricCache[song.key]?.let {return it}
        val lines=kotlinx.coroutines.withTimeoutOrNull(30000) {fetchLyricsUncached(song)}?:throw IOException("歌词加载超时，请重试")
        if(lines.isNotEmpty()) {if(lyricCache.size>=100) lyricCache.clear();lyricCache[song.key]=lines}
        return lines
    }
    private suspend fun fetchLyricsUncached(song:SongItem):List<LyricLine> {
        var responded=false;var lastError:Exception?=null
        suspend fun lines(item:SongItem):List<LyricLine> {val raw=request("/lyric",songParams(item)+("format" to "auto"));responded=true;return parseTimedLyrics(raw).filterNot {it.text.contains("纯音乐 / 无歌词")||it.text=="暂无歌词"}}
        val direct=try {lines(song)} catch(e:CancellationException){throw e} catch(e:Exception){lastError=e;emptyList()}
        if(direct.isNotEmpty()) return direct
        if(song.name.isBlank()) return emptyList()
        fun normalized(value:String)=value.trim().lowercase().replace(" ","")
        for(source in listOf("netease","qq","kuwo","migu").filterNot {it==song.source}) {
            try {
                val candidates=searchSongs("${song.name} ${song.artist}",source).take(8).filter {normalized(it.name)==normalized(song.name)&&(song.artist.isBlank()||song.artist=="未知歌手"||normalized(it.artist)==normalized(song.artist))&&(song.duration<=0||it.duration<=0||kotlin.math.abs(song.duration-it.duration)<=12)}
                for(candidate in candidates.take(2)) {val found=lines(candidate);if(found.isNotEmpty()) return found}
            } catch(e:CancellationException){throw e} catch(e:Exception){lastError=e}
        }
        if(!responded&&lastError!=null) throw IOException("歌词接口暂时不可用，请重试",lastError)
        return emptyList()
    }
    fun decodeSongs(data: JsonElement?): List<SongItem> = if (data == null || data.isJsonNull) emptyList() else gson.fromJson(data,Array<SongItem>::class.java).toList()
    suspend fun playlists(source: String, personal: Boolean): Pair<List<PlaylistItem>,String> {
        val data = json(if (personal) "/user_playlists" else "/recommend",mapOf("sources" to source,"format" to "json")).asJsonObject
        val entries = data.get("playlists")
        return (if (entries == null || entries.isJsonNull) emptyList() else gson.fromJson(entries,Array<PlaylistItem>::class.java).toList()) to errorMessage(data)
    }
    suspend fun playlistSongs(item: PlaylistItem, album: Boolean = false): List<SongItem> {
        val response = json(if (album) "/album" else "/playlist",mapOf("id" to item.id,"source" to item.source,"format" to "json")).asJsonObject
        if (errorMessage(response).isNotBlank()) throw IOException(errorMessage(response))
        return decodeSongs(response.get("songs")).distinctBy { it.key }
    }
    suspend fun localSongs(): List<SongItem> {
        var result = json("/local_music",mapOf("refresh" to "1")).asJsonObject
        repeat(40) {
            val songs = decodeSongs(result.get("tracks"))
            if (songs.isNotEmpty() || result.get("refreshing")?.asBoolean != true) return songs
            delay(300)
            result = json("/local_music").asJsonObject
        }
        return decodeSongs(result.get("tracks"))
    }
    suspend fun offlineUri(song: SongItem): String? {
        AppStore.offlineUri(song)?.let { return it }
        return try {
            val result = json("/native/offline",mapOf("name" to song.name,"artist" to song.artist)).asJsonObject
            if (result.get("valid")?.asBoolean == true) result.get("url").asString.also { AppStore.saveOffline(song,it) } else null
        } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
    }
    suspend fun download(song: SongItem) = withContext(Dispatchers.IO) {
        DaemonManager.awaitReady()
        val embed=(AppStore.engineSettings.value ?: settings()).get("embedDownload")?.asBoolean!=false
        val playable=if(song.source!="local") resolvePlayable(song) else song
        val request = Request.Builder().url(url("/download",songParams(playable) + mapOf("save_local" to "1","embed" to if(embed) "1" else "0")))
            .header("X-Requested-With","XMLHttpRequest").post("{}".toRequestBody("application/json".toMediaType())).build()
        val result=JsonParser.parseString(execute(request)).asJsonObject
        DownloadOutcome(offlineUri(song),downloadNotice(result))
    }
    internal fun downloadNotice(result:JsonObject):String {
        fun value(key:String)=result.get(key)?.takeUnless {it.isJsonNull}?.asString.orEmpty()
        return listOf(value("warning"),value("webdav_error").takeIf {it.isNotBlank()}?.let {"本地文件已保存，WebDAV 同步失败：$it"}.orEmpty()).filter {it.isNotBlank()}.joinToString("\n")
    }
    suspend fun settings(): JsonObject = json("/settings").asJsonObject.also { AppStore.engineSettings.value = it }
    suspend fun sources(): List<SourceCapability> = gson.fromJson(json("/native/sources"),Array<SourceCapability>::class.java).toList()
    suspend fun saveSettings(settings: JsonObject) { AppStore.engineSettings.value = json("/settings",method="POST",body=settings).asJsonObject }
    suspend fun switchSource(song: SongItem, target: String = ""): SongItem = gson.fromJson(json("/switch_source",songParams(song) + ("target" to target)),SongItem::class.java)
    fun parseLrc(text: String): List<LyricLine> {
        var offset = 0L
        Regex("\\[offset:([+-]?\\d+)\\]").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { offset = it }
        val stamp = Regex("\\[(\\d+):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
        return text.lineSequence().flatMap { line ->
            val matches = stamp.findAll(line).toList()
            val words = line.substringAfterLast(']').trim()
            if (words.isBlank()) emptySequence() else matches.asSequence().map {
                val fraction = it.groupValues[3].padEnd(3,'0').take(3).toLongOrNull() ?: 0L
                LyricLine((it.groupValues[1].toLong()*60000 + it.groupValues[2].toLong()*1000 + fraction + offset).coerceAtLeast(0),words)
            }
        }.sortedBy { it.timeMs }.toList()
    }
}
