package com.carmusic.app.data

import android.content.Context
import com.carmusic.app.ui.model.SongItem
import com.carmusic.app.ui.model.PlayMode
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** UI preferences and playback references; never mutates a source playlist. */
object AppStore {
    private val gson = Gson()
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loading = Mutex()
    val loaded = MutableStateFlow(false)
    private val writes = Channel<() -> Unit>(Channel.UNLIMITED)
    private var persistedQueue: List<SongItem>? = null
    private val writeFailure = java.util.concurrent.atomic.AtomicReference<Exception?>()
    private val playlistCache = java.util.concurrent.ConcurrentHashMap<String, List<com.carmusic.app.ui.model.PlaylistItem>>()
    init { storageScope.launch {
        for (write in writes) try { write() } catch (error: Exception) {
            writeFailure.set(error)
            android.util.Log.e("AppStore", "Unable to persist application state")
        }
    } }
    private fun writePreferences(write: android.content.SharedPreferences.Editor.() -> Unit) {
        check(writes.trySend { check(prefs.edit().apply(write).commit()) { "Unable to persist application state" } }.isSuccess)
    }
    private fun writeSongs(key: String, songs: List<SongItem>) = writePreferences { putString(key, gson.toJson(songs)) }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        loading.withLock {
            if (loaded.value) return@withLock
            // Initialize lazy persisted state before UI/service consumers use it.
            favorites; imports; scannedLocal; recent; autoScanLocal; localFolders; hiddenLocalKeys
            theme; vinyl; autoplay; notificationControls
            permissionAsked = prefs.getBoolean("localPermissionAsked", false)
            val mappings = mutableMapOf<String, SongItem>()
            prefs.all.forEach { (key, value) ->
                if (key.startsWith("resolved_") && value is String) {
                    runCatching { gson.fromJson(value, SongItem::class.java) }
                        .getOrNull()?.takeIf { it.id.isNotBlank() && it.source.isNotBlank() }
                        ?.let { song -> mappings[key.removePrefix("resolved_")] = song }
                } else if (key.startsWith("playlists_") && value is String) {
                    runCatching { gson.fromJson(value, Array<com.carmusic.app.ui.model.PlaylistItem>::class.java).toList() }
                        .getOrNull()?.let { playlistCache[key.removePrefix("playlists_")] = it }
                }
            }
            resolvedSources.value = mappings
            loaded.value = true
        }
    }
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        writes.send { val error = writeFailure.getAndSet(null); if (error == null) done.complete(Unit) else done.completeExceptionally(error) }
        done.await()
    }
    val engineSettings = MutableStateFlow<com.google.gson.JsonObject?>(null)
    val upstreamUpdate = MutableStateFlow<com.google.gson.JsonObject?>(null)
    val playbackChecks=MutableStateFlow<Map<String,String>>(emptyMap())
    val resolvedSources=MutableStateFlow<Map<String,SongItem>>(emptyMap())
    fun markPlayback(song:SongItem,status:String){playbackChecks.update { it + (song.key to status) }}
    fun saveResolved(original:SongItem,resolved:SongItem){
        if(original.key!=resolved.key){writePreferences {putString("resolved_${original.key}",gson.toJson(resolved))};resolvedSources.update {it+(original.key to resolved)}}
        else {writePreferences {remove("resolved_${original.key}")};resolvedSources.update {it-original.key}}
    }
    fun restoredResolved(song:SongItem):SongItem?=resolvedSources.value[song.key]
    private val prefs by lazy { com.carmusic.app.CarMusicApplication.instance.getSharedPreferences("carmusic", Context.MODE_PRIVATE) }
    val favorites by lazy { MutableStateFlow(readSongs("favorites")) }
    val imports by lazy { MutableStateFlow(readSongs("imports")) }
    val scannedLocal by lazy { MutableStateFlow(readSongs("scannedLocal")) }
    val autoScanLocal by lazy { MutableStateFlow(prefs.getBoolean("autoScanLocal", true)) }
    val localFolders by lazy { MutableStateFlow(prefs.getStringSet("localFolders", emptySet())!!.toSet()) }
    val hiddenLocalKeys by lazy { MutableStateFlow(prefs.getStringSet("hiddenLocalKeys", emptySet())!!.toSet()) }
    @Volatile private var permissionAsked = false
    var localPermissionAsked: Boolean
        get() = permissionAsked
        set(value) { permissionAsked = value; writePreferences {putBoolean("localPermissionAsked", value)} }
    fun setAutoScanLocal(value: Boolean) { autoScanLocal.value=value; writePreferences {putBoolean("autoScanLocal",value)} }
    fun addLocalFolder(uri: String) { localFolders.update {it+uri}; val folders=localFolders.value; writePreferences {putStringSet("localFolders",folders)} }
    fun removeLocalFolder(uri: String) {
        localFolders.update {it-uri}
        val folders=localFolders.value
        writePreferences {putStringSet("localFolders",folders)}
        replaceScannedLocal(scannedLocal.value)
    }
    fun replaceScannedLocal(songs: List<SongItem>) {
        scannedLocal.value=songs.filterNot { it.key in hiddenLocalKeys.value || "local:${it.streamUrl}" in hiddenLocalKeys.value }
            .filter { it.extra?.get("localOrigin")!="folder" || it.extra["localRoot"] in localFolders.value }
            .distinctBy { it.key }
        writeSongs("scannedLocal",scannedLocal.value)
    }
    fun removeLocalRecord(song: SongItem) {
        hiddenLocalKeys.update {it+song.key+"local:${song.streamUrl}"}
        val hidden=hiddenLocalKeys.value
        writePreferences {putStringSet("hiddenLocalKeys",hidden)}
        removeImport(song)
        replaceScannedLocal(scannedLocal.value.filterNot { it.key==song.key })
    }
    fun restoreHiddenLocal() { hiddenLocalKeys.value=emptySet(); writePreferences {remove("hiddenLocalKeys")} }
    val recent by lazy {MutableStateFlow(readSongs("recent"))}
    fun recordRecent(song:SongItem){recent.update {(listOf(song)+it.filterNot {item->item.key==song.key}).take(100)};writeSongs("recent",recent.value)}
    fun clearRecent(){recent.value=emptyList();writePreferences {remove("recent")}}
    fun removeImport(song: SongItem) { imports.update {it.filterNot {item->item.key==song.key}};writeSongs("imports",imports.value) }
    fun addImport(song: SongItem) = addImports(listOf(song))
    fun addImports(songs: List<SongItem>) { hiddenLocalKeys.update {it-songs.flatMap {song->listOf(song.key,"local:${song.streamUrl}")}.toSet()}; imports.update {(it+songs).distinctBy {song->song.key}}; val snapshot=imports.value;val hidden=hiddenLocalKeys.value;writePreferences {putString("imports",gson.toJson(snapshot));putStringSet("hiddenLocalKeys",hidden)} }
    fun cachePlaylists(source: String, items: List<com.carmusic.app.ui.model.PlaylistItem>) { playlistCache[source] = items; writePreferences {putString("playlists_$source",gson.toJson(items))} }
    fun cachedPlaylists(source: String): List<com.carmusic.app.ui.model.PlaylistItem> = playlistCache[source].orEmpty()
    val theme by lazy { MutableStateFlow(prefs.getString("theme", "system") ?: "system") }
    val vinyl by lazy { MutableStateFlow(prefs.getBoolean("vinyl", false)) }
    val autoplay by lazy { MutableStateFlow(prefs.getBoolean("autoplay", false)) }
    val notificationControls by lazy {MutableStateFlow(prefs.getBoolean("notificationControls",true))}
    fun searchSources(): Set<String> = prefs.getStringSet("searchSources", null)?.toSet()?.takeIf { it.isNotEmpty() } ?: setOf("qq","netease","kuwo")
    fun setSearchSources(value: Set<String>) { val snapshot = value.toSet(); writePreferences {putStringSet("searchSources", snapshot)} }
    val appVisible=MutableStateFlow(false)
    fun setNotificationControls(value:Boolean){notificationControls.value=value;writePreferences {putBoolean("notificationControls",value)}}

    private fun readSongs(key: String): List<SongItem> = runCatching {
        gson.fromJson<List<SongItem>>(prefs.getString(key, "[]"), object : TypeToken<List<SongItem>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList()).distinctBy { it.key }
    fun isFavorite(song: SongItem) = favorites.value.any { it.key == song.key }
    fun toggleFavorite(song: SongItem) {
        favorites.update { existing -> if (existing.any {it.key == song.key}) existing.filterNot {it.key == song.key} else listOf(song) + existing }
        writeSongs("favorites",favorites.value)
    }
    fun setTheme(value: String) { theme.value = value; writePreferences {putString("theme", value)} }
    fun setVinyl(value: Boolean) { vinyl.value = value; writePreferences {putBoolean("vinyl", value)} }
    fun setAutoplay(value: Boolean) { autoplay.value = value; writePreferences {putBoolean("autoplay", value)} }
    fun saveSession(queue: List<SongItem>, current: SongItem?, position: Long, mode: PlayMode) {
        check(writes.trySend {
            val editor = prefs.edit()
            // Progress updates do not serialize an unchanged queue again.
            if (persistedQueue !== queue) editor.putString("queue",gson.toJson(queue))
            editor.putString("current",current?.key).putLong("position",position.coerceAtLeast(0)).putString("mode",mode.name)
            check(editor.commit()) { "Unable to persist playback queue" }
            persistedQueue = queue
        }.isSuccess)
    }
    fun restoredQueue() = readSongs("queue")
    fun restoredSong(queue: List<SongItem>) = queue.firstOrNull { it.key == prefs.getString("current", null) }
    fun restoredPosition() = prefs.getLong("position", 0)
    fun restoredMode() = runCatching { PlayMode.valueOf(prefs.getString("mode", "SEQUENCE")!!) }.getOrDefault(PlayMode.SEQUENCE)
    fun saveOffline(song: SongItem, uri: String) { writePreferences {putString("offline_${song.key}",uri)} }
    fun offlineUri(song: SongItem): String? = prefs.getString("offline_${song.key}",null)?.takeIf {
        runCatching { java.io.File(android.net.Uri.parse(it).path ?: "").isFile }.getOrDefault(false)
    }
}
