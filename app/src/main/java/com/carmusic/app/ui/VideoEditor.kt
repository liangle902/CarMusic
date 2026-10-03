package com.carmusic.app.ui

import android.graphics.*
import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.data.AppStore
import com.carmusic.app.ui.model.SongItem
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import androidx.lifecycle.repeatOnLifecycle

internal data class VideoRenderOptions(val ratio:String="9:16",val resolution:Int=720,val backgroundMode:String="cover",val showLyrics:Boolean=true,val vinyl:Boolean=true) {
    val width get()=if(ratio=="16:9") resolution*16/9/2*2 else resolution
    val height get()=when(ratio){"16:9","1:1"->resolution;"3:4"->resolution*4/3;else->resolution*16/9}.let {it/2*2}
}

@Composable
fun VideoEditor(song: SongItem?,service:com.carmusic.app.service.PlaybackService) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var selectedSong by remember(song?.key) {mutableStateOf(song)}
    var audioDialog by remember {mutableStateOf(false)}
    var localAudio by remember {mutableStateOf<List<SongItem>>(emptyList())}
    var title by remember(song?.key) {mutableStateOf(song?.name?:"星河音乐")}
    var options by remember {mutableStateOf(VideoRenderOptions())}
    var settings by remember {mutableStateOf<com.google.gson.JsonObject?>(null)}
    LaunchedEffect(Unit){try {settings=ApiClient.settings()} catch(e:CancellationException){throw e} catch(_:Exception){}}
    var background by remember {mutableStateOf<Bitmap?>(null)};var assetTarget by remember {mutableStateOf("cover")}
    var coverVideo by remember {mutableStateOf<File?>(null)};var backgroundVideo by remember {mutableStateOf<File?>(null)}
    var previewCover by remember {mutableStateOf<Bitmap?>(null)};var previewBackground by remember {mutableStateOf<Bitmap?>(null)}
    var cover by remember {mutableStateOf<Bitmap?>(null)};var audio by remember {mutableStateOf<File?>(null)};var lyric by remember {mutableStateOf("")};var output by remember {mutableStateOf<File?>(null)};var message by remember {mutableStateOf("")};var progress by remember {mutableStateOf(0f)};var job by remember {mutableStateOf<Job?>(null)}
    var customCover by remember {mutableStateOf(false)};var customLyric by remember {mutableStateOf(false)}
    var previewPosition by remember {mutableLongStateOf(0)}
    var previewSpectrum by remember {mutableStateOf(FloatArray(48))}
    val queue by service.playlist.collectAsState()
    fun changePreviewSong(direction:Int){if(queue.isNotEmpty()){val index=queue.indexOfFirst {it.key==selectedSong?.key};selectedSong=queue[(index+direction).mod(queue.size)];audio?.delete();audio=null}}
    val lifecycle=androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(previewCover){val frame=previewCover;onDispose {frame?.recycle()}}
    DisposableEffect(previewBackground){val frame=previewBackground;onDispose {frame?.recycle()}}
    LaunchedEffect(coverVideo,backgroundVideo,options.backgroundMode) {
        previewCover=null;previewBackground=null
        if(coverVideo!=null||backgroundVideo!=null&&options.backgroundMode=="image") lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            var movingCover:VideoFrameSource?=null;var movingBackground:VideoFrameSource?=null
            try {
                withContext(Dispatchers.IO){movingCover=coverVideo?.let {VideoFrameSource(it,360,640)};movingBackground=backgroundVideo?.takeIf {options.backgroundMode=="image"}?.let {VideoFrameSource(it,360,640)}}
                while(isActive) {
                    val time=previewPosition
                    videoPreviewFrame(movingCover,time)?.let {previewCover=it}
                    videoPreviewFrame(movingBackground,time)?.let {previewBackground=it}
                    delay(100)
                }
            } catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"动态素材预览失败"} finally {movingCover?.close();movingBackground?.close()}
        }
    }
    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->uri?.let {val target=assetTarget;scope.launch {try {
        val visual=if(context.contentResolver.getType(it)?.startsWith("video/")==true) importVideoVisual(context,it) else null
        val selected=visual?.preview?:withContext(Dispatchers.IO){decodeVideoImage(context,it)}
        if(target=="background") {backgroundVideo?.delete();backgroundVideo=visual?.file;background?.recycle();background=selected;options=options.copy(backgroundMode="image")} else {customCover=true;coverVideo?.delete();coverVideo=visual?.file;cover?.recycle();cover=selected}
        message=if(visual!=null) "已选择动态${if(target=="background") "背景" else "封面"}" else "已更换${if(target=="background") "背景" else "封面"}"
    } catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"画面素材读取失败"}}}}
    val audioPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->uri?.let {scope.launch {try {val selected=importVideoAudio(context,it);audio?.delete();audio=selected;message="已更换音频"} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"音频读取失败"}}}}
    val lyricPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->uri?.let {scope.launch {try {val imported=importVideoLyrics(context,it);customLyric=true;lyric=imported} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"歌词读取失败"}}}}
    val exportPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) {uri->uri?.let {scope.launch {try {saveVideoOutput(context,output?:throw IOException("请先制作视频"),it);message="视频已保存"} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"保存失败"}}}}
    LaunchedEffect(selectedSong?.key) {selectedSong?.let {current->title=current.name;if(!customLyric) lyric="";if(!customCover){cover?.recycle();cover=null};try {val lines=ApiClient.fetchLyrics(current);if(!customLyric) lyric=com.carmusic.app.engine.serializeTimedLyrics(lines)} catch(e:CancellationException){throw e} catch(_:Exception){message="可手动导入歌词"};try {if(!customCover&&current.cover.isNotBlank()){val file=downloadVideoAsset(context,ApiClient.coverUrl(current.source,current.cover),20L*1024*1024);try {val decoded=withContext(Dispatchers.IO){decodeVideoImage(context,android.net.Uri.fromFile(file))};if(!customCover) cover=decoded else decoded.recycle()} finally {file.delete()}}} catch(e:CancellationException){throw e} catch(_:Exception){message="可手动选择封面"}}}
    if(audioDialog) AlertDialog(onDismissRequest={audioDialog=false},title={Text("更换音频")},text={Column(Modifier.heightIn(max=350.dp).verticalScroll(rememberScrollState())){if(localAudio.isEmpty()) Text("暂无本地音乐，可导入音频文件");localAudio.forEach {item->TextButton(onClick={selectedSong=item;audio?.delete();audio=null;audioDialog=false}){Text("${item.name} · ${item.artist}")}}}},confirmButton={TextButton(onClick={audioDialog=false;audioPicker.launch(arrayOf("audio/*"))}){Text("导入音频文件")}},dismissButton={TextButton(onClick={audioDialog=false}){Text("取消")}})
    DisposableEffect(Unit){onDispose {val task=job;task?.cancel();val artwork=cover;val backdrop=background;val coverFile=coverVideo;val backgroundFile=backgroundVideo;val audioFile=audio;val outputFile=output
        fun cleanup(){audioFile?.delete();outputFile?.delete();coverFile?.delete();backgroundFile?.delete();artwork?.recycle();backdrop?.recycle()}
        if(task!=null&&!task.isCompleted) task.invokeOnCompletion {cleanup()} else cleanup()
    }}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title,{title=it},Modifier.fillMaxWidth(),label={Text("视频标题")},enabled=job?.isActive!=true)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("9:16","1:1","3:4","16:9").forEach {ratio->FilterChip(selected=options.ratio==ratio,onClick={options=options.copy(ratio=ratio)},enabled=job?.isActive!=true,label={Text(ratio)})}}
        val lines=remember(lyric){com.carmusic.app.engine.parseTimedLyrics(lyric)}
        val currentLine=lines.lastOrNull {it.timeMs<=previewPosition}
        val preview=remember(cover,background,previewCover,previewBackground,lyric,title,options,previewPosition,previewSpectrum) {Bitmap.createBitmap(360,options.height*360/options.width,Bitmap.Config.ARGB_8888).also {drawVideoFrame(it,previewCover?:cover,previewBackground?:background,currentLine?.text?:if(lines.isEmpty()) lyric.lineSequence().firstOrNull().orEmpty() else "",title,options,previewPosition,currentLine,previewSpectrum)}}
        DisposableEffect(preview){onDispose {preview.recycle()}}
        Image(preview.asImageBitmap(),"视频画面预览",Modifier.fillMaxWidth().height(300.dp))
        VideoPlaybackPreview(audio,selectedSong,job?.isActive!=true,audio==null&&queue.size>1,{changePreviewSong(-1)},{changePreviewSong(1)},{previewPosition=it},{previewSpectrum=it})
        Row {Text("黑胶与音乐频谱",Modifier.weight(1f));Switch(checked=options.vinyl,onCheckedChange={options=options.copy(vinyl=it)},enabled=job?.isActive!=true)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("cover" to "跟随封面","solid" to "纯色","image" to "独立图片").forEach {(mode,label)->FilterChip(selected=options.backgroundMode==mode,onClick={options=options.copy(backgroundMode=mode)},enabled=job?.isActive!=true&&settings?.get("vgChangeCover")?.asBoolean==true,label={Text(label)})}}
        Row {Text("显示歌词",Modifier.weight(1f));Switch(checked=options.showLyrics,onCheckedChange={options=options.copy(showLyrics=it)},enabled=job?.isActive!=true&&settings?.get("vgChangeLyric")?.asBoolean==true)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(720,1080).forEach {resolution->FilterChip(selected=options.resolution==resolution,onClick={options=options.copy(resolution=resolution)},enabled=job?.isActive!=true,label={Text("${resolution}p")})}}
        Text("MP4 · ${options.width} × ${options.height}")
        Row {if(settings?.get("vgChangeCover")?.asBoolean==true) {TextButton(enabled=job?.isActive!=true,onClick={assetTarget="cover";imagePicker.launch(arrayOf("image/*","video/mp4"))}){Text("更换封面")};TextButton(enabled=job?.isActive!=true,onClick={assetTarget="background";imagePicker.launch(arrayOf("image/*","video/mp4"))}){Text("更换背景")}};if(settings?.get("vgChangeAudio")?.asBoolean==true) TextButton(enabled=job?.isActive!=true,onClick={audioDialog=true;localAudio=AppStore.imports.value;scope.launch {try {localAudio=(localAudio+ApiClient.localSongs()).distinctBy {it.key}} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"本地音乐读取失败"}}}){Text("更换音频")}}
        Text(if(audio!=null) "已选择导入音频" else selectedSong?.let {"${it.name} · ${it.artist}"}?:"请选择音频")
        if(settings?.get("vgChangeLyric")?.asBoolean==true) TextButton(enabled=job?.isActive!=true,onClick={lyricPicker.launch(arrayOf("text/*","application/octet-stream"))}){Text("导入歌词")}
        if(settings?.get("vgChangeLyric")?.asBoolean==true) OutlinedTextField(lyric,{customLyric=true;lyric=it},Modifier.fillMaxWidth().heightIn(min=160.dp,max=240.dp),enabled=job?.isActive!=true,label={Text("歌词（LRC / TXT）")})
        if(job?.isActive==true){LinearProgressIndicator(progress,Modifier.fillMaxWidth());TextButton(onClick={job?.cancel()}){Text("取消制作")}}
        Text(message)
        if(settings?.get("vgExportVideo")?.asBoolean!=true) Text("在系统设置中启用视频导出后即可制作")
        Button(enabled=settings?.get("vgExportVideo")?.asBoolean==true&&job?.isActive!=true&&(audio!=null||selectedSong!=null),onClick={job=scope.launch {
            output?.delete();output=null;message="正在准备音频…";progress=0f
            var temporaryAudio:File?=null
            try {
                val prepared=audio?:run {val current=selectedSong?:throw IOException("请先选择音频")
                    val local=current.streamUrl.takeIf {it.startsWith("file:")||it.startsWith("content:")} ?: current.id.takeIf {current.source=="local" && (it.startsWith("file:")||it.startsWith("content:"))} ?: ApiClient.offlineUri(current)
                    (if(local!=null) importVideoAudio(context,android.net.Uri.parse(local)) else downloadVideoAsset(context,ApiClient.streamUrl(ApiClient.resolvePlayable(current)))).also {temporaryAudio=it}
                }
                message="正在制作视频…"
                output=renderMusicVideo(context,prepared,cover,background,lyric,title,options,coverVideo,backgroundVideo) {value->scope.launch {progress=value}}
                message="视频制作完成，可保存到设备"
            } catch(e:CancellationException){message="已取消制作";throw e} catch(e:Exception){message=e.message?:"视频制作失败"} finally {temporaryAudio?.delete()}
        }}){Text("制作视频")}
        output?.let {Button(onClick={exportPicker.launch("${title.replace(Regex("[\\\\/:*?\"<>|]"),"_")}.mp4")}){Text("保存视频")}}
    }
}

internal suspend fun renderMusicVideo(context:android.content.Context,audio:File,cover:Bitmap?,background:Bitmap?,lyric:String,title:String,options:VideoRenderOptions=VideoRenderOptions(),coverVideo:File?=null,backgroundVideo:File?=null,onProgress:(Float)->Unit):File=withContext(Dispatchers.IO) {
    val retriever=MediaMetadataRetriever();val duration=try {retriever.setDataSource(audio.absolutePath);retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:throw IOException("无法读取音频时长")} finally {retriever.release()}
    val binary=File(context.applicationInfo.nativeLibraryDir,"libffmpeg.so");if(!binary.exists()) throw IOException("视频编码器尚未安装")
    val output=File(context.cacheDir,"music-video-${System.currentTimeMillis()}.mp4")
    val log=File(context.cacheDir,"video-encoder.log")
    val builder=ProcessBuilder(binary.absolutePath,"-y","-hide_banner","-loglevel","error","-f","image2pipe","-framerate","30","-vcodec","mjpeg","-i","pipe:0","-i",audio.absolutePath,"-c:v","libx264","-preset","ultrafast","-c:a","aac","-pix_fmt","yuv420p","-shortest",output.absolutePath)
    builder.environment()["LD_LIBRARY_PATH"]=context.applicationInfo.nativeLibraryDir
    builder.redirectErrorStream(true);builder.redirectOutput(log)
    val process=builder.start();val bitmap=Bitmap.createBitmap(options.width,options.height,Bitmap.Config.ARGB_8888)
    val lines=com.carmusic.app.engine.parseTimedLyrics(lyric);val plainLyric=lyric.lineSequence().firstOrNull().orEmpty();val count=(duration*30/1000).toInt().coerceAtLeast(1)
    var completed=false
    val pcmReader=java.util.concurrent.atomic.AtomicReference<VideoPcmSpectrum?>()
    val cancelWatcher=launch(Dispatchers.IO) {try {awaitCancellation()} finally {process.destroy();pcmReader.get()?.close()}}
    var movingCover:VideoFrameSource?=null;var movingBackground:VideoFrameSource?=null;var spectrum:VideoPcmSpectrum?=null
    try {
        movingCover=coverVideo?.let {VideoFrameSource(it,options.width,options.height)}
        movingBackground=backgroundVideo?.takeIf {options.backgroundMode=="image"}?.let {VideoFrameSource(it,options.width,options.height)}
        if(options.vinyl) spectrum=VideoPcmSpectrum(context,audio).also {pcmReader.set(it)}
        process.outputStream.use {stream-> repeat(count){frame->ensureActive();val time=frame*1000L/30;val text=if(lines.isEmpty()) plainLyric else lines.lastOrNull {it.timeMs<=time}?.text.orEmpty();val coverFrame=movingCover?.frameAt(time);var backgroundFrame:Bitmap?=null
            try {backgroundFrame=movingBackground?.frameAt(time);drawVideoFrame(bitmap,coverFrame?:cover,backgroundFrame?:background,text,title,options,time,lines.lastOrNull {it.timeMs<=time},spectrum?.nextFrame()?:FloatArray(0));if(!bitmap.compress(Bitmap.CompressFormat.JPEG,85,stream)) throw IOException("视频帧生成失败")} finally {coverFrame?.recycle();backgroundFrame?.recycle()}
            if(frame%30==0) onProgress(frame.toFloat()/count)} }
        while(process.isAlive){ensureActive();delay(100)}
        if(process.exitValue()!=0) throw IOException("视频编码失败：${log.readText().take(200)}")
        if(!output.exists()||output.length()==0L) throw IOException("视频未生成")
        completed=true;onProgress(1f);output
    } finally {cancelWatcher.cancel();process.destroy();spectrum?.close();movingCover?.close();movingBackground?.close();bitmap.recycle();if(!completed) output.delete()}
}

internal fun drawVideoFrame(bitmap:Bitmap,cover:Bitmap?,background:Bitmap?,lyric:String,title:String,options:VideoRenderOptions,timeMs:Long=0,line:com.carmusic.app.ui.model.LyricLine?=null,spectrum:FloatArray=FloatArray(0)) {
    val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG);val w=bitmap.width.toFloat();val h=bitmap.height.toFloat()
    fun cropped(image:Bitmap,rect:RectF){val scale=maxOf(rect.width()/image.width,rect.height()/image.height);val sw=rect.width()/scale;val sh=rect.height()/scale;val x=(image.width-sw)/2;val y=(image.height-sh)/2;canvas.drawBitmap(image,Rect(x.toInt(),y.toInt(),(x+sw).toInt(),(y+sh).toInt()),rect,paint)}
    canvas.drawColor(Color.rgb(21,27,35))
    val backdrop=when(options.backgroundMode){"image"->background;"cover"->cover;else->null}
    backdrop?.let {cropped(it,RectF(0f,0f,w,h));paint.color=Color.argb(185,0,0,0);canvas.drawRect(0f,0f,w,h,paint)}
    val size=minOf(w*.78f,h*.55f);val top=h*.12f
    if(options.vinyl){
        val cx=w/2;val cy=top+size/2;val radius=size/2
        paint.color=Color.rgb(16,19,23);canvas.drawCircle(cx,cy,radius,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=maxOf(1f,w*.002f);paint.color=Color.rgb(42,48,57)
        for(ring in 7..12) canvas.drawCircle(cx,cy,radius*ring/12,paint)
        paint.strokeWidth=maxOf(2f,w*.004f);paint.color=Color.rgb(106,199,222);paint.strokeCap=Paint.Cap.ROUND
        spectrum.forEachIndexed {index,level->val angle=index*2*Math.PI/spectrum.size-Math.PI/2;val start=radius+w*.012f;val end=start+level*w*.06f
            canvas.drawLine(cx+(kotlin.math.cos(angle)*start).toFloat(),cy+(kotlin.math.sin(angle)*start).toFloat(),cx+(kotlin.math.cos(angle)*end).toFloat(),cy+(kotlin.math.sin(angle)*end).toFloat(),paint)
        }
        paint.style=Paint.Style.FILL
        cover?.let {canvas.save();canvas.rotate(timeMs*.022918f,cx,cy);canvas.clipPath(Path().apply {addCircle(cx,cy,radius*.65f,Path.Direction.CW)});cropped(it,RectF(cx-radius*.65f,cy-radius*.65f,cx+radius*.65f,cy+radius*.65f));canvas.restore()}
    } else {
        paint.color=Color.rgb(40,54,70);canvas.drawRoundRect(RectF((w-size)/2,top,(w+size)/2,top+size),w*.025f,w*.025f,paint)
        cover?.let {val rect=RectF((w-size)/2,top,(w+size)/2,top+size);canvas.save();canvas.clipPath(Path().apply {addRoundRect(rect,w*.025f,w*.025f,Path.Direction.CW)});cropped(it,rect);canvas.restore()}
    }
    paint.textAlign=Paint.Align.LEFT
    fun rows(text:String,font:Float):List<String>{paint.textSize=font;val output=mutableListOf<String>();var rest=text
        while(rest.isNotEmpty()){val count=paint.breakText(rest,true,w*.88f,null).coerceAtLeast(1);output+=rest.substring(0,count);rest=rest.substring(count)};return output
    }
    var font=minOf(w*.036f,h*.034f)
    val texts=if(options.showLyrics) listOf(title,lyric,line?.romanization.orEmpty(),line?.translation.orEmpty()).filter {it.isNotBlank()} else listOf(title)
    val textTop=top+size+h*.055f;val available=h*.92f-textTop
    while(font>4f&&texts.sumOf {rows(it,font).size}*font*1.35f>available) font*=.9f
    var y=textTop;var highlighted=0f
    line?.words?.forEach {word->highlighted+=word.text.length*((timeMs-word.startMs).toFloat()/(word.endMs-word.startMs).coerceAtLeast(1)).coerceIn(0f,1f)}
    fun drawWrapped(text:String,color:Int,highlight:Boolean=false){var offset=0
        rows(text,font).forEach {part->paint.textSize=font;paint.color=color;val x=(w-paint.measureText(part))/2;canvas.drawText(part,x,y,paint)
            if(highlight&&highlighted>offset){val chars=(highlighted-offset).coerceIn(0f,part.length.toFloat());val whole=chars.toInt();val width=paint.measureText(part.substring(0,whole))+(if(whole<part.length) paint.measureText(part.substring(whole,whole+1))*(chars-whole) else 0f)
                canvas.save();canvas.clipRect(x,y+paint.ascent(),x+width,y+paint.descent());paint.color=Color.rgb(18,189,133);canvas.drawText(part,x,y,paint);canvas.restore()
            };offset+=part.length;y+=font*1.35f
        }
    }
    drawWrapped(title,Color.WHITE)
    if(options.showLyrics){drawWrapped(lyric,Color.rgb(218,232,244),line?.words?.isNotEmpty()==true);line?.romanization?.takeIf {it.isNotBlank()}?.let {drawWrapped(it,Color.LTGRAY)};line?.translation?.takeIf {it.isNotBlank()}?.let {drawWrapped(it,Color.LTGRAY)}}
    paint.textAlign=Paint.Align.CENTER
    paint.color=Color.argb(150,255,255,255);paint.textSize=w*.023f;canvas.drawText("星河音乐",w/2,h*.95f,paint)
}

internal fun decodeVideoImage(context:android.content.Context,uri:android.net.Uri):Bitmap {
    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
    context.contentResolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,bounds)}
    if(bounds.outWidth<=0||bounds.outHeight<=0) throw IOException("图片格式无法读取")
    var sample=1;while(bounds.outWidth/sample>1440 || bounds.outHeight/sample>1920) sample*=2
    val options=BitmapFactory.Options().apply {inSampleSize=sample}
    return context.contentResolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,options)}?:throw IOException("图片读取失败")
}
