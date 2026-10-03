package com.carmusic.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.CancellationException

/** Call on IO. A readable audio file can still be playable without readable tags. */
internal object LocalMusicReader {
    fun read(context: Context, uri: Uri, filename: String? = null, size: Long = 0,
             extra: Map<String, Any> = emptyMap()): SongItem {
        var displayName = filename
        var fileSize = size
        if (displayName == null) context.contentResolver.query(uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                displayName = it.getString(0)
                if (!it.isNull(1)) fileSize = it.getLong(1)
            }
        }
        val retriever = MediaMetadataRetriever()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var duration = 0L
        try {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (e: CancellationException) { throw e
        } catch (e: SecurityException) { throw e
        } catch (_: Exception) {
            // Distinguish missing/unreadable files from unsupported metadata formats.
            context.contentResolver.openFileDescriptor(uri, "r")?.use { }
                ?: throw java.io.IOException("音乐文件暂时无法读取")
        } finally { retriever.release() }
        val canonical = canonicalUri(context, uri)
        return SongItem(
            id = canonical ?: uri.toString(), source = "local", streamUrl = uri.toString(),
            name = clean(title) ?: (displayName ?: "本地音乐").substringBeforeLast('.').ifBlank { "本地音乐" },
            artist = clean(artist) ?: "本地音乐", album = clean(album).orEmpty(),
            duration = duration / 1000, size = fileSize, extra = extra + ("filename" to (displayName ?: "本地音乐"))
        )
    }

    fun canonicalUri(context: Context, uri: Uri): String? =
        if (Build.VERSION.SDK_INT >= 29 && LocalMusicScanner.hasMediaPermission(context))
            runCatching { MediaStore.getMediaUri(context, uri)?.toString() }.getOrNull() else null

    private fun clean(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown>" }
}
