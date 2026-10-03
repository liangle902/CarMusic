package com.carmusic.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.*
import java.io.File
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import java.io.IOException

internal suspend fun prepareSongAssetExport(context:Context,song:SongItem,cover:Boolean):File {
    var downloaded:File?=null;var converted:File?=null
    try {
        val actual=AppStore.restoredResolved(song)?:song
        val url=if(cover) {require(actual.cover.isNotBlank()) {"这首歌曲没有封面"};ApiClient.coverUrl(actual.source,actual.cover)} else ApiClient.lyricDownloadUrl(actual)
        downloaded=downloadVideoAsset(context,url,if(cover) 20L*1024*1024 else 1024L*1024)
        if(!cover) return downloaded
        return withContext(Dispatchers.IO) {
            val input=requireNotNull(downloaded)
            val jpeg=input.inputStream().use {it.read()==0xff&&it.read()==0xd8}
            if(jpeg) input else {
                val image=decodeVideoImage(context,Uri.fromFile(input))
                try {
                    val output=File.createTempFile("song-cover-",".jpg",context.cacheDir).also {converted=it}
                    output.outputStream().use {if(!image.compress(Bitmap.CompressFormat.JPEG,95,it)) throw IOException("封面图片转换失败")}
                    input.delete();output
                } finally {image.recycle()}
            }
        }
    } catch(e:Exception){downloaded?.delete();converted?.delete();throw e}
}

@Composable
fun SongAssetExport(song:SongItem,cover:Boolean,onClose:()->Unit,onMessage:(String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var file by remember {mutableStateOf<File?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var launched by remember {mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if(cover) "image/jpeg" else "application/octet-stream")) {uri->
        if(uri==null) onClose() else scope.launch {
            try {saveVideoOutput(context,requireNotNull(file),uri);onMessage(if(cover) "封面已保存" else "歌词已保存");onClose()}
            catch(e:CancellationException){throw e} catch(e:Exception){error=e.message?:"保存失败"}
        }
    }
    DisposableEffect(Unit){onDispose {file?.delete()}}
    LaunchedEffect(song.key,cover) {
        try {
            file=prepareSongAssetExport(context,song,cover)
            launched=true;picker.launch("${song.name} - ${song.artist}.${if(cover) "jpg" else "lrc"}".replace(Regex("[\\\\/:*?\"<>|]"),"_"))
        } catch(e:CancellationException){throw e} catch(e:Exception){error=e.message?:"获取失败"}
    }
    AlertDialog(onDismissRequest=onClose,title={Text(if(cover) "导出封面" else "导出歌词")},text={Text(error?:if(launched) "选择保存位置" else "正在获取文件…")},confirmButton={TextButton(onClick=onClose){Text("关闭")}})
}
