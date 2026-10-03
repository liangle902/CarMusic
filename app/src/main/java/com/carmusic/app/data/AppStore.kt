package com.carmusic.app.data

import android.content.Context
import com.carmusic.app.ui.model.SongItem
import com.carmusic.app.ui.model.PlayMode
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow

/** UI preferences and playback references; never mutates a source playlist. */
object AppStore {
    private val gson = Gson()
    val engineSettings = MutableStateFlow<com.google.gson.JsonObject?>(null)
    val upstreamUpdate = MutableStateFlow<com.google.gson.JsonObject?>(null)
    val playbackChecks=MutableStateFlow<Map<String,String>>(emptyMap())
    val resolvedSources=MutableStateFlow<Map<String,SongItem>>(emptyMap())
    fun markPlayback(song:SongItem,status:String){playbackChecks.value=playbackChecks.value+(song.key to status)}
    fun saveResolved(original:SongItem,resolved:SongItem){
        if(original.key!=resolved.key){prefs.edit().putString("resolved_${original.key}",gson.toJson(resolved)).apply();resolvedSources.value=resolvedSources.value+(original.key to resolved)}
        else {prefs.edit().remove("resolved_${original.key}").apply();resolvedSources.value=resolvedSources.value-original.key}
    }
    fun restoredResolved(song:SongItem):SongItem?=resolvedSources.value[song.key]?:runCatching {prefs.getString("resolved_${song.key}",null)?.let {gson.fromJson(it,SongItem::class.java)}?.takeIf {it.id.isNotBlank()&&it.source.isNotBlank()}}.getOrNull()
    private val prefs by lazy { com.carmusic.app.CarMusicApplication.instance.getSharedPreferences("carmusic", Context.MODE_PRIVATE) }
    val favorites by lazy { MutableStateFlow(readSongs("favorites")) }
    val imports by lazy { MutableStateFlow(readSongs("imports")) }
    val scannedLocal by lazy { MutableStateFlow(readSongs("scannedLocal")) }
    val autoScanLocal by lazy { MutableStateFlow(prefs.getBoolean("autoScanLocal", true)) }
    val localFolders by lazy { MutableStateFlow(prefs.getStringSet("localFolders", emptySet())!!.toSet()) }
    val hiddenLocalKeys by lazy { MutableStateFlow(prefs.getStringSet("hiddenLocalKeys", emptySet())!!.toSet()) }
    var localPermissionAsked: Boolean
        get() = prefs.getBoolean("localPermissionAsked", false)
        set(value) { prefs.edit().putBoolean("localPermissionAsked", value).apply() }
    fun setAutoScanLocal(value: Boolean) { autoScanLocal.value=value; prefs.edit().putBoolean("autoScanLocal",value).apply() }
    fun addLocalFolder(uri: String) { localFolders.value=localFolders.value+uri; prefs.edit().putStringSet("localFolders",localFolders.value).apply() }
    fun removeLocalFolder(uri: String) {
        localFolders.value=localFolders.value-uri
        prefs.edit().putStringSet("localFolders",localFolders.value).apply()
        replaceScannedLocal(scannedLocal.value)
    }
    fun replaceScannedLocal(songs: List<SongItem>) {
        scannedLocal.value=songs.filterNot { it.key in hiddenLocalKeys.value || "local:${it.streamUrl}" in hiddenLocalKeys.value }
            .filter { it.extra?.get("localOrigin")!="folder" || it.extra["localRoot"] in localFolders.value }
            .distinctBy { it.key }
        prefs.edit().putString("scannedLocal",gson.toJson(scannedLocal.value)).apply()
    }
    fun removeLocalRecord(song: SongItem) {
        hiddenLocalKeys.value=hiddenLocalKeys.value+song.key+"local:${song.streamUrl}"
        prefs.edit().putStringSet("hiddenLocalKeys",hiddenLocalKeys.value).apply()
        removeImport(song)
        replaceScannedLocal(scannedLocal.value.filterNot { it.key==song.key })
    }
    fun restoreHiddenLocal() { hiddenLocalKeys.value=emptySet(); prefs.edit().remove("hiddenLocalKeys").apply() }
    val recent by lazy {MutableStateFlow(readSongs("recent"))}
    fun recordRecent(song:SongItem){recent.value=(listOf(song)+recent.value.filterNot {it.key==song.key}).take(100);prefs.edit().putString("recent",gson.toJson(recent.value)).apply()}
    fun clearRecent(){recent.value=emptyList();prefs.edit().remove("recent").apply()}
    val playbackSpeed by lazy {MutableStateFlow(prefs.getFloat("playbackSpeed",1f))}
    fun setPlaybackSpeed(speed:Float){playbackSpeed.value=speed;prefs.edit().putFloat("playbackSpeed",speed).apply()}
    fun removeImport(song: SongItem) { imports.value = imports.value.filterNot {it.key==song.key};prefs.edit().putString("imports",gson.toJson(imports.value)).apply() }
    fun addImport(song: SongItem) = addImports(listOf(song))
    fun addImports(songs: List<SongItem>) { hiddenLocalKeys.value=hiddenLocalKeys.value-songs.flatMap {listOf(it.key,"local:${it.streamUrl}")}.toSet(); imports.value=(imports.value+songs).distinctBy {it.key}; prefs.edit().putString("imports",gson.toJson(imports.value)).putStringSet("hiddenLocalKeys",hiddenLocalKeys.value).apply() }
    fun cachePlaylists(source: String, items: List<com.carmusic.app.ui.model.PlaylistItem>) { prefs.edit().putString("playlists_$source",gson.toJson(items)).apply() }
    fun cachedPlaylists(source: String): List<com.carmusic.app.ui.model.PlaylistItem> = runCatching { gson.fromJson(prefs.getString("playlists_$source","[]"),Array<com.carmusic.app.ui.model.PlaylistItem>::class.java).toList() }.getOrDefault(emptyList())
    val theme by lazy { MutableStateFlow(prefs.getString("theme", "system") ?: "system") }
    val vinyl by lazy { MutableStateFlow(prefs.getBoolean("vinyl", false)) }
    val autoplay by lazy { MutableStateFlow(prefs.getBoolean("autoplay", false)) }
    val notificationControls by lazy {MutableStateFlow(prefs.getBoolean("notificationControls",true))}
    val appVisible=MutableStateFlow(false)
    fun setNotificationControls(value:Boolean){notificationControls.value=value;prefs.edit().putBoolean("notificationControls",value).apply()}

    private fun readSongs(key: String): List<SongItem> = runCatching {
        gson.fromJson<List<SongItem>>(prefs.getString(key, "[]"), object : TypeToken<List<SongItem>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())
    fun isFavorite(song: SongItem) = favorites.value.any { it.key == song.key }
    fun toggleFavorite(song: SongItem) {
        favorites.value = if (isFavorite(song)) favorites.value.filterNot { it.key == song.key } else listOf(song) + favorites.value
        prefs.edit().putString("favorites", gson.toJson(favorites.value)).apply()
    }
    fun setTheme(value: String) { theme.value = value; prefs.edit().putString("theme", value).apply() }
    fun setVinyl(value: Boolean) { vinyl.value = value; prefs.edit().putBoolean("vinyl", value).apply() }
    fun setAutoplay(value: Boolean) { autoplay.value = value; prefs.edit().putBoolean("autoplay", value).apply() }
    fun saveSession(queue: List<SongItem>, current: SongItem?, position: Long, mode: PlayMode) {
        prefs.edit().putString("queue", gson.toJson(queue)).putString("current", current?.key)
            .putLong("position", position.coerceAtLeast(0)).putString("mode", mode.name).apply()
    }
    fun restoredQueue() = readSongs("queue")
    fun restoredSong() = restoredQueue().firstOrNull { it.key == prefs.getString("current", null) }
    fun restoredPosition() = prefs.getLong("position", 0)
    fun restoredMode() = runCatching { PlayMode.valueOf(prefs.getString("mode", "SEQUENCE")!!) }.getOrDefault(PlayMode.SEQUENCE)
    fun saveOffline(song: SongItem, uri: String) { prefs.edit().putString("offline_${song.key}",uri).apply() }
    fun offlineUri(song: SongItem): String? = prefs.getString("offline_${song.key}",null)?.takeIf {
        runCatching { java.io.File(android.net.Uri.parse(it).path ?: "").isFile }.getOrDefault(false)
    }
}
