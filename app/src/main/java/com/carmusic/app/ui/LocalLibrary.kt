package com.carmusic.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carmusic.app.data.AppStore
import com.carmusic.app.data.LocalMusicReader
import com.carmusic.app.data.LocalMusicScanner
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.*

/** Ask once after the splash; declining never blocks the app or the SAF importer. */
@Composable
internal fun LocalMusicPermissionPrompt(ready: Boolean) {
    val context = LocalContext.current
    val auto by AppStore.autoScanLocal.collectAsState()
    val visible by AppStore.appVisible.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        LocalMusicScanner.requestScan()
    }
    LaunchedEffect(ready, auto, visible) {
        if (ready && auto && visible && !LocalMusicScanner.hasMediaPermission(context) && !AppStore.localPermissionAsked) {
            AppStore.localPermissionAsked = true
            permission.launch(LocalMusicScanner.permission())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalLibrary(service: PlaybackService,
    content: @Composable (List<SongItem>, (SongItem) -> Unit, (List<SongItem>) -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imported by AppStore.imports.collectAsState()
    val scanned by AppStore.scannedLocal.collectAsState()
    val folders by AppStore.localFolders.collectAsState()
    val hidden by AppStore.hiddenLocalKeys.collectAsState()
    val scan by LocalMusicScanner.state.collectAsState()
    var downloaded by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var deleting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var manageFolders by remember { mutableStateOf(false) }
    LaunchedEffect(scan.scanning) { if (scan.scanning) status = "" }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "" else "未授权读取手机音乐，可添加目录或导入文件"
        LocalMusicScanner.requestScan(manual = true)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                AppStore.addLocalFolder(uri.toString())
                status = "已添加 ${LocalMusicScanner.folderName(uri.toString())}"
                LocalMusicScanner.requestScan(manual = true)
            } catch (_: Exception) { status = "目录授权未保存，请重新选择" }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val (songs, failed) = withContext(Dispatchers.IO) {
                val songs = mutableListOf<SongItem>()
                var failed = 0
                for (uri in uris) {
                    ensureActive()
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        songs += LocalMusicReader.read(context, uri)
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { failed++ }
                }
                songs to failed
            }
            AppStore.addImports(songs)
            status = "已导入 ${songs.size} 首" + if (failed > 0) "，$failed 个文件无法读取" else ""
        }
    }
    suspend fun refreshDownloads() {
        try { downloaded = ApiClient.localSongs() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { status = "下载文件暂时无法加载，请重试" }
    }
    LaunchedEffect(Unit) { refreshDownloads(); LocalMusicScanner.requestScan() }
    val recordKeys = (imported + scanned).map { it.key }.toSet()
    fun isReference(song: SongItem) = song.key in recordKeys || song.extra?.get("localOrigin") != null ||
        song.id.startsWith("content:") || song.id.startsWith("file:")

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(enabled = !scan.scanning, onClick = {
                status = ""
                if (LocalMusicScanner.hasMediaPermission(context)) LocalMusicScanner.requestScan(manual = true)
                else { AppStore.localPermissionAsked = true; permission.launch(LocalMusicScanner.permission()) }
                scope.launch { refreshDownloads() }
            }) { Text("扫描音乐", maxLines = 1) }
            TextButton(onClick = { folderPicker.launch(null) }) { Text("添加目录 / U盘", maxLines = 1) }
            TextButton(onClick = { filePicker.launch(arrayOf("audio/*")) }) { Text("导入文件", maxLines = 1) }
            Box {
                TextButton(onClick = { menu = true }) { Text("更多") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("管理扫描目录") }, onClick = { menu = false; manageFolders = true })
                    DropdownMenuItem(text = { Text("重新显示已移除歌曲") }, enabled = hidden.isNotEmpty(), onClick = {
                        menu = false; AppStore.restoreHiddenLocal(); LocalMusicScanner.requestScan(manual = true)
                    })
                    DropdownMenuItem(text = { Text("音频权限设置") }, onClick = {
                        menu = false
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    })
                }
            }
        }
        if (scan.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        val message = if (scan.scanning) "正在扫描 · 已发现 ${scan.found} 首" else status.ifBlank { scan.message }
        if (message.isNotBlank()) Text(message, Modifier.padding(vertical = 6.dp), fontSize = 12.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            content((imported + scanned + downloaded).distinctBy { it.key }, { removing = listOf(it) }, { removing = it })
        }
    }
    if (manageFolders) AlertDialog(
        onDismissRequest = { manageFolders = false }, title = { Text("扫描目录 · ${folders.size}") },
        text = {
            Column {
                Text("连接存储后自动扫描，移除目录会保留原文件。", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(folders.toList(), key = { it }) { folder ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(LocalMusicScanner.folderName(folder), Modifier.weight(1f).padding(top = 12.dp), maxLines = 2)
                            TextButton(onClick = { AppStore.removeLocalFolder(folder); LocalMusicScanner.requestScan(manual = true) }) { Text("移除") }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { manageFolders = false; folderPicker.launch(null) }) { Text("添加目录") } },
        dismissButton = { TextButton(onClick = { manageFolders = false }) { Text("关闭") } }
    )
    if (removing.isNotEmpty()) {
        val records = removing.count { isReference(it) }
        val files = removing.size - records
        AlertDialog(onDismissRequest = { if (!deleting) removing = emptyList() },
            title = { Text("移除 ${removing.size} 首本地音乐？") },
            text = { Text(listOf(
                if (records > 0) "$records 首导入 / 扫描记录将移除，原文件保留。" else "",
                if (files > 0) "$files 首下载文件将永久删除。" else ""
            ).filter { it.isNotBlank() }.joinToString("\n")) },
            dismissButton = { TextButton(enabled = !deleting, onClick = { removing = emptyList() }) { Text("取消") } },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    val targets = removing.toList()
                    deleting = true
                    scope.launch {
                        var success = 0
                        val failures = mutableListOf<String>()
                        try {
                            for (song in targets) {
                                try {
                                    if (isReference(song)) AppStore.removeLocalRecord(song)
                                    else { ApiClient.json("/local_music", mapOf("id" to song.id), "DELETE"); service.removeFromQueue(song) }
                                    success++
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { failures += "${song.name}：${e.message}" }
                            }
                            refreshDownloads()
                            status = "已移除 $success 首" + if (failures.isNotEmpty()) "，${failures.size} 首移除失败" else ""
                            removing = emptyList()
                        } finally { deleting = false }
                    }
                }) { Text(if (deleting) "处理中…" else "确认") }
            }
        )
    }
}
