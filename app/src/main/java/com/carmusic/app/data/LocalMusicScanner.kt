package com.carmusic.app.data

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

internal data class LocalScanState(
    val scanning: Boolean = false,
    val found: Int = 0,
    val message: String = "",
    val needsPermission: Boolean = false
)

/** Application-owned discovery: mounted volumes, MediaStore changes and persisted SAF trees. */
internal object LocalMusicScanner {
    val state = MutableStateFlow(LocalScanState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = Channel<Boolean>(Channel.CONFLATED)
    private lateinit var context: Context
    private var scanJob: Job? = null
    private var debounceJob: Job? = null
    private val extensions = setOf("mp3", "flac", "wav", "ogg", "opus", "m4a", "m4b", "aac", "aif", "aiff", "wma", "ape", "alac", "dsf", "dff")

    fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun hasMediaPermission(context: Context) = ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    fun start(application: Context) {
        if (::context.isInitialized) return
        context = application.applicationContext
        scope.launch {
            for (manual in requests) {
                if (!manual && !AppStore.autoScanLocal.value) continue
                scanJob = launch { scan() }
                scanJob?.join()
            }
        }
        scope.launch {
            combine(AppStore.autoScanLocal, AppStore.appVisible) { auto, visible -> auto to visible }.collect { (auto, visible) ->
                if (!auto) { debounceJob?.cancel(); scanJob?.cancel() }
                else if (visible) requestScan()
            }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { requestScan(delayMs = 1000) }
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (uri == null || uri.pathSegments.size < 2 || "audio" in uri.pathSegments) requestScan(delayMs = 1000)
            }
        }
        runCatching { context.contentResolver.registerContentObserver(Uri.parse("content://${MediaStore.AUTHORITY}"), true, observer) }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT); addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL); addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED)
            addDataScheme("file")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                requestScan(delayMs = 1200)
                if (intent?.action in listOf(Intent.ACTION_MEDIA_MOUNTED, UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                    // Some car firmwares expose the directory only after the first mount signal.
                    scope.launch { delay(4500); requestScan() }
                }
            }
        }
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                context.getSystemService(StorageManager::class.java).registerStorageVolumeCallback(context.mainExecutor,
                    object : StorageManager.StorageVolumeCallback() {
                        override fun onStateChanged(volume: StorageVolume) { requestScan(delayMs = 1200) }
                    })
            }
        }
    }

    fun requestScan(manual: Boolean = false, delayMs: Long = 0) {
        if (!::context.isInitialized || (!manual && !AppStore.autoScanLocal.value)) return
        debounceJob?.cancel()
        debounceJob = scope.launch { delay(delayMs); requests.trySend(manual) }
    }

    fun folderName(uri: String) = runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfter(':').ifBlank {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringBefore(':')
            if (id == "primary") "手机存储" else "U盘 / 存储卡 $id"
        }
    }.getOrDefault("音乐目录")

    private suspend fun scan() {
        state.value = LocalScanState(scanning = true, needsPermission = !hasMediaPermission(context))
        try {
            val (songs, notes) = withContext(Dispatchers.IO) {
                val found = mutableListOf<SongItem>()
                val notes = mutableListOf<String>()
                val old = AppStore.scannedLocal.value.associateBy { it.streamUrl }
                if (hasMediaPermission(context)) {
                    val volumes = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context) else setOf("external")
                    for (volume in volumes) {
                        ensureActive()
                        try { readMediaVolume(volume, found) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            notes += "部分存储暂时无法读取"
                            found += old.values.filter { it.extra?.get("localOrigin") == "media" && it.extra["localRoot"] == volume }
                        }
                    }
                }
                for (folder in AppStore.localFolders.value.toList()) {
                    ensureActive()
                    try {
                        val skipped = readFolder(Uri.parse(folder), old, found)
                        if (skipped > 0) notes += "已跳过 $skipped 个无法读取的音频文件"
                    }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notes += "${folderName(folder)}暂不可读，请检查连接或目录授权" }
                }
                found.distinctBy { it.key } to notes.distinct()
            }
            currentCoroutineContext().ensureActive()
            AppStore.replaceScannedLocal(songs)
            val permissionNote = if (!hasMediaPermission(context)) "未授权读取手机音乐，可添加目录或导入文件" else ""
            state.value = LocalScanState(found = AppStore.scannedLocal.value.size,
                needsPermission = !hasMediaPermission(context),
                message = (listOf("扫描完成 · ${AppStore.scannedLocal.value.size} 首", permissionNote) + notes).filter { it.isNotBlank() }.joinToString("；"))
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            state.value = state.value.copy(message = "扫描未完成，请检查存储连接或重新授权")
        } finally { state.value = state.value.copy(scanning = false) }
    }

    private suspend fun readMediaVolume(volume: String, songs: MutableList<SongItem>) {
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(volume) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.ALBUM_ID)
        val selection = "${MediaStore.Audio.Media.SIZE}>0 AND ${MediaStore.Audio.Media.IS_RINGTONE}=0 AND ${MediaStore.Audio.Media.IS_ALARM}=0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION}=0"
        val cursor = context.contentResolver.query(collection, columns, selection, null, "${MediaStore.Audio.Media.TITLE} ASC")
            ?: throw java.io.IOException("媒体库暂不可读")
        cursor.use {
            while (it.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val uri = ContentUris.withAppendedId(collection, it.number(MediaStore.Audio.Media._ID))
                val filename = it.text(MediaStore.Audio.Media.DISPLAY_NAME)
                val albumId = it.number(MediaStore.Audio.Media.ALBUM_ID)
                songs += SongItem(id = uri.toString(), source = "local", streamUrl = uri.toString(),
                    name = it.text(MediaStore.Audio.Media.TITLE).ifBlank { filename.substringBeforeLast('.') },
                    artist = it.text(MediaStore.Audio.Media.ARTIST).ifBlank { "本地音乐" },
                    album = it.text(MediaStore.Audio.Media.ALBUM), duration = it.number(MediaStore.Audio.Media.DURATION) / 1000,
                    size = it.number(MediaStore.Audio.Media.SIZE),
                    cover = if (albumId > 0) "content://media/$volume/audio/albumart/$albumId" else "",
                    extra = mapOf("localOrigin" to "media", "localRoot" to volume, "filename" to filename))
                progress(songs.size)
            }
        }
    }

    private suspend fun readFolder(tree: Uri, old: Map<String, SongItem>, songs: MutableList<SongItem>): Int {
        val root = DocumentsContract.getTreeDocumentId(tree)
        // Query the granted provider directly: OEM USB roots need not appear in StorageManager.
        var skipped = 0
        val knownIds = songs.mapTo(mutableSetOf()) { it.id }
        val directories = java.util.ArrayDeque<String>().apply { add(root) }
        val visited = mutableSetOf<String>()
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        while (directories.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = directories.removeFirst()
            if (!visited.add(directory)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory)
            context.contentResolver.query(children, columns, null, null, null)?.use {
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = it.text(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val name = it.text(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mime = it.text(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    if (name.startsWith('.')) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (name !in setOf("Android", "LOST.DIR", "System Volume Information")) directories.add(id)
                    } else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in extensions) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        val canonical = LocalMusicReader.canonicalUri(context, uri)
                        if ((canonical ?: uri.toString()) in knownIds) continue
                        val size = it.number(DocumentsContract.Document.COLUMN_SIZE)
                        val modified = it.number(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        val cached = old[uri.toString()]
                        val extra = mapOf("localOrigin" to "folder", "localRoot" to tree.toString(), "modifiedMs" to modified)
                        try {
                            val song = if (cached != null && modified > 0 && cached.size == size &&
                                (cached.extra?.get("modifiedMs") as? Number)?.toLong() == modified) cached.copy(extra = cached.extra.orEmpty() + extra)
                            else LocalMusicReader.read(context, uri, name, size, extra)
                            songs += song
                            knownIds += song.id
                        } catch (e: CancellationException) { throw e }
                        catch (e: SecurityException) { throw e }
                        catch (_: Exception) { skipped++ }
                        progress(songs.size)
                    }
                }
            } ?: throw java.io.IOException("目录暂不可读")
        }
        return skipped
    }

    private fun progress(count: Int) { if (count % 20 == 0) state.value = state.value.copy(found = count) }
    private fun Cursor.text(column: String): String = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }
        ?.let { getString(it) }?.takeUnless { it == "<unknown>" }.orEmpty()
    private fun Cursor.number(column: String): Long = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) } ?: 0L
}
