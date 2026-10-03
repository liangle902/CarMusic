package com.carmusic.app.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import com.carmusic.app.data.AppStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import android.net.Uri
import com.carmusic.app.MainActivity
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.ui.model.PlayMode
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val binder = LocalBinder()
    private var mediaSession: MediaSession? = null
    lateinit var player: ExoPlayer
        private set

    private var simpleCache: SimpleCache? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private var progressJob: Job? = null
    private var resolveJob: Job? = null
    private var notificationLyrics:Job?=null
    private var notificationLyric=""
    private val failedTracks = mutableSetOf<String>()
    private val switchedTracks = mutableSetOf<String>()
    val playingSource = MutableStateFlow("")
    val resolvedSong=MutableStateFlow<SongItem?>(null)
    val playbackError = MutableStateFlow<String?>(null)

    // Reactive states for UI observation
    val currentSong = MutableStateFlow<SongItem?>(null)
    val isPlaying = MutableStateFlow(false)
    val currentPosition = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val playlist = MutableStateFlow<List<SongItem>>(emptyList())
    val playMode = MutableStateFlow(PlayMode.SEQUENCE)
    val isFavorite = MutableStateFlow(false)

    fun cacheBytes():Long = simpleCache?.cacheSpace ?: 0L

    fun clearPlaybackCache() {
        currentPosition.value=player.currentPosition.coerceAtLeast(0)
        player.pause()
        player.clearMediaItems()
        simpleCache?.let {cache -> cache.keys.toList().forEach {cache.removeResource(it)}}
        AppStore.saveSession(playlist.value,currentSong.value,currentPosition.value,playMode.value)
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == MediaSessionService.SERVICE_INTERFACE || intent?.action == "android.media.browse.MediaBrowserService") super.onBind(intent) else binder
    }

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(object:DefaultMediaNotificationProvider(this) {
            override fun getNotificationContentText(metadata:MediaMetadata):CharSequence = notificationLyric.takeIf {it.isNotBlank()}?:metadata.artist?:"星河音乐"
        })
        initializePlayer()
        initializeMediaSession()
        startProgressTracking()
        serviceScope.launch { try {ApiClient.settings()} catch(e:CancellationException){throw e} catch(_:Exception){} }
        playlist.value = AppStore.restoredQueue()
        currentSong.value = AppStore.restoredSong() ?: playlist.value.firstOrNull()
        duration.value = (currentSong.value?.duration ?: 0L) * 1000
        currentPosition.value = AppStore.restoredPosition()
        playMode.value = AppStore.restoredMode()
        serviceScope.launch { AppStore.favorites.collect { isFavorite.value = currentSong.value?.let(AppStore::isFavorite) ?: false } }
        serviceScope.launch {kotlinx.coroutines.flow.combine(AppStore.notificationControls,AppStore.appVisible){enabled,visible->enabled to visible}.collect {updateNotificationMetadata();mediaSession?.let {onUpdateNotification(it,player.isPlaying)}}}
        if (AppStore.autoplay.value) currentSong.value?.let { loadSong(it, currentPosition.value) }
    }

    override fun onUpdateNotification(session:MediaSession,startInForegroundRequired:Boolean) {
        if(!AppStore.notificationControls.value&&AppStore.appVisible.value) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(android.app.NotificationManager::class.java).cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
        } else super.onUpdateNotification(session,startInForegroundRequired)
    }

    private fun updateNotificationMetadata() {
        val song=currentSong.value?:return
        val item=player.currentMediaItem?.takeIf {it.mediaId==song.key}?:return
        val lyric=notificationLyric.take(180).takeIf {AppStore.notificationControls.value}.orEmpty()
        val artist=if(lyric.isBlank()) song.artist else listOf(song.artist,lyric).filter {it.isNotBlank()}.joinToString(" · ")
        if(item.mediaMetadata.artist?.toString()==artist&&item.mediaMetadata.subtitle?.toString().orEmpty()==lyric) return
        player.replaceMediaItem(player.currentMediaItemIndex,item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtist(artist).setSubtitle(lyric).build()).build())
    }

    private fun initializePlayer() {
        // 1. 初始化车载缓存目录（边听边存）
        val cacheDir = File(cacheDir, "media_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val evictor = LeastRecentlyUsedCacheEvictor(2L * 1024 * 1024 * 1024) // 2GB 离线缓存池
        val databaseProvider = StandaloneDatabaseProvider(this)
        simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)

        val upstreamFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(simpleCache!!)
            .setCacheKeyFactory { spec -> spec.key ?: ApiClient.playbackCacheKey(spec.uri) }
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)

        // 2. 车载音频属性配置 (自动避让高德导航与蓝牙通话)
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true) // true = 自动管理 AudioFocus
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setPlaybackSpeed(1f)
        AppStore.setPlaybackSpeed(1f)

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
                if (!playing) { currentPosition.value = player.currentPosition; persistSession() }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    duration.value = player.duration.coerceAtLeast(0L)
                    currentSong.value?.let {original->resolvedSong.value?.let {resolved->AppStore.saveResolved(original,resolved)};AppStore.markPlayback(original,"可播放")}
                } else if (state == Player.STATE_ENDED) {
                    // 清除旧媒体时也可能收到 ENDED；只有真实曲目结束才推进队列。
                    if(player.currentMediaItem==null) return
                    if (playMode.value == PlayMode.REPEAT_ONE) currentSong.value?.let { loadSong(it) }
                    else if(playMode.value==PlayMode.SEQUENCE&&playlist.value.lastOrNull()?.key==currentSong.value?.key) {player.pause();currentPosition.value=player.currentPosition;persistSession()}
                    else playNext()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("PlaybackService", "ExoPlayer playback error: ${error.message}", error)
                // 车载抗弱网容错：播放失败自动切歌，保证音乐不中断
                handleFailure("播放失败：${error.errorCodeName}")
            }
        })
    }

    private fun initializeMediaSession() {
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val forwardingPlayer = object : ForwardingPlayer(player) {
            override fun play() {
                if (player.currentMediaItem == null) currentSong.value?.let { loadSong(it,this@PlaybackService.currentPosition.value) }
                else player.play()
            }
            override fun pause() { player.pause(); persistSession() }
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun seekToNext() {
                playNext()
            }
            override fun seekToNextMediaItem() {
                playNext()
            }
            override fun seekToPrevious() {
                playPrevious()
            }
            override fun seekToPreviousMediaItem() {
                playPrevious()
            }
        }

        val sessionCallback = object : MediaSession.Callback {
            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent
            ): Boolean {
                @Suppress("DEPRECATION")
                val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            playNext()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            playPrevious()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK -> {
                            togglePlayPause()
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(session, controllerInfo, intent)
            }
        }

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(sessionCallback)
            .build()
        addSession(requireNotNull(mediaSession))
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            var savedAt = 0L
            while (isActive) {
                if (player.isPlaying) {
                    currentPosition.value = player.currentPosition
                    duration.value = player.duration.coerceAtLeast(0L)
                }
                if (player.isPlaying && android.os.SystemClock.elapsedRealtime() - savedAt >= 10000) {
                    persistSession(); savedAt = android.os.SystemClock.elapsedRealtime()
                }
                delay(500)
            }
        }
    }

    private fun persistSession() = AppStore.saveSession(playlist.value, currentSong.value, currentPosition.value, playMode.value)

    fun playSong(song: SongItem) {
        failedTracks.clear()
        switchedTracks.clear()
        if (playlist.value.none { it.key == song.key }) playlist.value += song
        loadSong(song)
    }
    fun playSongFirst(song:SongItem) {
        playlist.value=com.carmusic.app.data.queueWithSongFirst(playlist.value,song)
        playSong(song)
    }

    fun playAlternate(song: SongItem, alternative: SongItem) {
        if(playlist.value.none {it.key==song.key}) playlist.value+=song
        failedTracks.clear();switchedTracks.clear()
        loadSong(song,if(currentSong.value?.key==song.key) currentPosition.value else 0L,alternative)
    }
    private fun loadSong(song: SongItem, resume: Long = 0L, alternative: SongItem? = null) {
        resolveJob?.cancel()
        resolveJob = serviceScope.launch {
            player.pause()
            player.clearMediaItems()
            currentSong.value = song
            currentPosition.value = resume
            duration.value = song.duration * 1000
            isFavorite.value = AppStore.isFavorite(song)
            playbackError.value = null
            persistSession()
            AppStore.markPlayback(song,"正在解析")
            val resolved = alternative ?: AppStore.restoredResolved(song) ?: song
            resolvedSong.value=resolved
            notificationLyrics?.cancel();notificationLyric=""
            notificationLyrics=serviceScope.launch {
                try {
                    val lines=ApiClient.fetchLyrics(resolved)
                    var previous=""
                    while(isActive&&currentSong.value?.key==song.key) {
                        val text=lines.lastOrNull {it.timeMs<=player.currentPosition}?.text.orEmpty()
                        if(text!=previous) {previous=text;notificationLyric=text;updateNotificationMetadata();mediaSession?.let {onUpdateNotification(it,player.isPlaying)}}
                        delay(500)
                    }
                } catch(e:CancellationException){throw e} catch(_:Exception){}
            }
            playingSource.value = resolved.source
            val direct = resolved.streamUrl.takeIf { it.isNotBlank() && (resolved.source == "local" || it.startsWith("content:") || it.startsWith("file:")) }
                ?: resolved.id.takeIf { resolved.source == "local" && (it.startsWith("content:") || it.startsWith("file:")) }
                ?: if(alternative==null) ApiClient.offlineUri(song) else null
            val cachedUrl=ApiClient.streamUrl(resolved).takeIf {url -> simpleCache?.let {cache -> val key=ApiClient.playbackCacheKey(Uri.parse(url));val length=ContentMetadata.getContentLength(cache.getContentMetadata(key));length>0 && cache.isCached(key,0,length)} == true}
            val info = try { if (direct == null && cachedUrl == null) ApiClient.inspectStream(resolved) else null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { handleFailure(e.message ?: "音源解析失败"); return@launch }
            ensureActive()
            val streamUrl = direct ?: cachedUrl ?: info?.takeIf { it.valid }?.let { ApiClient.streamUrl(resolved) }
            if (streamUrl == null) { handleFailure("无法解析 ${song.name} 的音源"); return@launch }
            val metadata = MediaMetadata.Builder().setTitle(song.name).setArtist(song.artist).setAlbumTitle(song.album)
                .apply { if (song.cover.isNotBlank()) setArtworkUri(Uri.parse(song.cover)) }.build()
            player.setMediaItem(MediaItem.Builder().setUri(streamUrl).setMediaId(song.key).setMediaMetadata(metadata).build(), resume)
            updateNotificationMetadata()
            persistSession()
            player.prepare()
            player.play()
            AppStore.recordRecent(song)
            if (direct == null) serviceScope.launch {
                try { ApiClient.json("/local_music/auto_cache",method="POST",body=ApiClient.gson.toJsonTree(resolved)) }
                catch(e: CancellationException) {throw e} catch(_: Exception) { }
            }
            persistSession()
        }
    }

    private fun handleFailure(message: String) {
        playbackError.value = message
        val song=currentSong.value
        song?.let {AppStore.markPlayback(it,"音源失效")}
        if(song!=null && song.source!="local" && song.key !in switchedTracks && AppStore.engineSettings.value?.get("autoSwitchInvalidSources")?.asBoolean!=false) {
            switchedTracks.add(song.key)
            resolveJob?.cancel()
            resolveJob=serviceScope.launch {
                try {val alternative=ApiClient.switchSource(resolvedSong.value?:song);ensureActive();loadSong(song,currentPosition.value,alternative)}
                catch(e:CancellationException){throw e} catch(_:Exception){advanceAfterFailure(message)}
            }
        } else advanceAfterFailure(message)
    }
    private fun advanceAfterFailure(message: String) {
        currentSong.value?.let { failedTracks.add(it.key) }
        playbackError.value = message
        if(AppStore.engineSettings.value?.get("autoSwitchInvalidSources")?.asBoolean==false) {player.stop();persistSession();return}
        val list = playlist.value
        val index = list.indexOfFirst { it.key == currentSong.value?.key }
        val next = (1..list.size).map { list[(index.coerceAtLeast(0) + it) % list.size] }.firstOrNull { it.key !in failedTracks }
        if (next == null) { player.stop(); isPlaying.value = false; persistSession() } else loadSong(next)
    }

    fun replaceQueue(songs: List<SongItem>, start: SongItem? = songs.firstOrNull()) {
        val tracks=songs.distinctBy {it.key}
        playlist.value = if(playMode.value==PlayMode.SHUFFLE&&start!=null) listOf(start)+tracks.filterNot {it.key==start.key}.shuffled() else tracks
        failedTracks.clear()
        if (start != null) playSong(start) else clearQueue()
    }
    fun addToQueue(song: SongItem, next: Boolean = false) {
        if (song.key == currentSong.value?.key && next) return
        val list = playlist.value.filterNot { it.key == song.key }.toMutableList()
        if (next) list.add((list.indexOfFirst { it.key == currentSong.value?.key } + 1).coerceIn(0, list.size), song) else list.add(song)
        playlist.value = list
        persistSession()
    }
    fun moveQueue(from: Int, to: Int) {
        val list = playlist.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from)); playlist.value = list; persistSession()
    }
    fun removeFromQueue(song: SongItem) {
        val old = playlist.value
        val index = old.indexOfFirst { it.key == song.key }
        playlist.value = old.filterNot { it.key == song.key }
        if (currentSong.value?.key == song.key) {
            if (playlist.value.isEmpty()) clearQueue() else playSong(playlist.value.getOrElse(index) { playlist.value.first() })
        }
        persistSession()
    }
    fun clearQueue() {
        resolveJob?.cancel(); player.stop(); player.clearMediaItems()
        playlist.value = emptyList(); currentSong.value = null;resolvedSong.value=null; currentPosition.value = 0; duration.value = 0; isPlaying.value = false
        persistSession()
    }

    fun togglePlayPause() {
        if (player.currentMediaItem == null) {
            val song = currentSong.value ?: playlist.value.firstOrNull()
            if (song != null) {
                failedTracks.clear(); switchedTracks.clear(); loadSong(song, currentPosition.value)
            }
            return
        }
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
            return
        }
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
            player.play()
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun setPlaybackSpeed(speed:Float) {
        require(speed in listOf(.5f,.75f,1f,1.25f,1.5f,1.75f,2f,2.25f,2.5f,2.75f,3f))
        player.setPlaybackSpeed(speed)
        AppStore.setPlaybackSpeed(speed)
    }
    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0, duration.value.coerceAtLeast(0))
        player.seekTo(target)
        currentPosition.value = target
        persistSession()
    }

    fun playNext() {
        val list = playlist.value
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.key == currentSong.value?.key }
        playSong(list[(index + 1).mod(list.size)])
    }
    fun playPrevious() {
        val list = playlist.value
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.key == currentSong.value?.key }
        playSong(list[(index - 1).mod(list.size)])
    }
    fun togglePlayMode() {
        setPlayMode(when (playMode.value) {
            PlayMode.SEQUENCE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.REPEAT_ALL
            PlayMode.REPEAT_ALL -> PlayMode.SEQUENCE
        })
    }
    fun setPlayMode(mode:PlayMode) {
        if(playMode.value==mode) return
        playMode.value=mode
        if (playMode.value == PlayMode.SHUFFLE) {
            val current = currentSong.value
            playlist.value = listOfNotNull(current) + playlist.value.filterNot { it.key == current?.key }.shuffled()
        }
        persistSession()
    }
    fun toggleFavorite() {
        currentSong.value?.let { AppStore.toggleFavorite(it); isFavorite.value = AppStore.isFavorite(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        persistSession()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        simpleCache?.release()
        simpleCache = null
        progressJob?.cancel()
        super.onDestroy()
    }
}
