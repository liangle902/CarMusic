package com.carmusic.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.engine.pollQrLogin
import com.google.gson.JsonObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.*

@Composable
fun AccountSettings() {
    var source by remember { mutableStateOf("qq") }
    var loginSource by remember {mutableStateOf("qq")}
    var linked by remember {mutableStateOf(false)}
    var cookie by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<JsonObject?>(null) }
    var generating by remember {mutableStateOf(false)}
    var sources by remember {mutableStateOf<List<com.carmusic.app.ui.model.SourceCapability>>(emptyList())}
    val scope = rememberCoroutineScope()
    var manual by remember(source) {mutableStateOf(false)}
    val supportsQr=sources.any {it.id==source&&it.qr}
    LaunchedEffect(Unit) {try {sources=ApiClient.sources()} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"无法加载平台列表"}}
    LaunchedEffect(source){loginSource=source;session=null;try {linked=ApiClient.json("/cookies").asJsonObject.get(source)?.asString?.isNotBlank()==true} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"账号状态加载失败"}}
    Text("平台账号")
    Row(Modifier.horizontalScroll(rememberScrollState())) { sources.forEach { item -> FilterChip(source==item.id,{source=item.id;cookie="";message=""},label={Text(item.name)},modifier=Modifier.padding(end=8.dp)) } }
    Text(if(linked) "已关联账号" else "尚未关联账号",style=MaterialTheme.typography.bodySmall)
    if(source=="qq") Row {FilterChip(loginSource=="qq",{loginSource="qq"},label={Text("QQ 扫码")});Spacer(Modifier.width(8.dp));FilterChip(loginSource=="qq_wx",{loginSource="qq_wx"},label={Text("微信扫码")})}
    if(manual||!supportsQr) OutlinedTextField(cookie,{cookie=it},Modifier.fillMaxWidth(),label={Text("Cookie")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
    Row {
        if(manual||!supportsQr) TextButton(onClick={scope.launch {try {ApiClient.json("/cookies",method="POST",body=JsonObject().apply {addProperty(source,cookie)});cookie="";linked=true;message="账号凭据已保存"} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"保存失败"}}},enabled=cookie.isNotBlank()){Text("保存凭据")}
        if(supportsQr) Button(onClick={scope.launch {generating=true;message="正在生成二维码…";val requested=loginSource;try {val created=ApiClient.json("/qr_login/$requested",method="POST").asJsonObject;check(!created.get("key")?.asString.isNullOrBlank()) {"平台没有返回登录会话，请重试"};if(loginSource==requested){session=created;message=""}} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"生成失败"} finally {generating=false}}},enabled=!generating){Text(if(generating) "生成中…" else "扫码关联")}
        TextButton(onClick={scope.launch {try {ApiClient.json("/cookies",method="POST",body=JsonObject().apply {addProperty(source,"")});linked=false;message="已解除关联"} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"解除失败"}}}){Text("解除关联")}
    }
    if(supportsQr) TextButton(onClick={manual=!manual}){Text(if(manual) "收起手动关联" else "手动关联（高级）")}
    if(message.isNotBlank()) Text(message)
    session?.let { data ->
        var status by remember(data) {mutableStateOf("请使用对应平台 App 扫码")}
        val image=data.get("image_url")?.takeUnless {it.isJsonNull}?.asString
        val bitmap=remember(data) {runCatching {decodeLoginQr(data)}.getOrNull()}
        val sessionSource=loginSource
        var sms by remember(data) {mutableStateOf<JsonObject?>(null)}
        var smsCode by remember(data) {mutableStateOf("")}
        var smsBusy by remember(data) {mutableStateOf(false)}
        var checking by remember(data) {mutableStateOf(false)}
        var cooldownUntil by remember(data) {mutableLongStateOf(0L)}
        var cooldownSeconds by remember(data) {mutableIntStateOf(0)}
        val checks=remember(data) {kotlinx.coroutines.sync.Mutex()}
        fun field(extra:JsonObject?,key:String)=extra?.get(key)?.takeIf {!it.isJsonNull}?.asString?:""
        fun needsSMS(extra:JsonObject?)=field(extra,"need_sms") in listOf("true","1")
        fun update(result:JsonObject) {
            when(field(result,"status")) {
                "success"->{linked=true;message="关联成功，平台歌单已可同步";session=null}
                "expired","failed"->status=field(result,"message").ifBlank {"二维码已失效，请重新生成"}
                else->{val extra=result.getAsJsonObject("extra");if(field(extra,"rate_limited")=="true") {cooldownUntil=System.currentTimeMillis()+60000;cooldownSeconds=60};if(sessionSource=="soda"&&needsSMS(extra)) sms=(sms?.deepCopy()?:JsonObject()).apply {extra!!.entrySet().forEach {(key,value)->add(key,value)}}
                    status=if(field(extra,"rate_limited")=="true") "平台请求频繁，稍后自动重试" else field(result,"message").takeUnless {it in listOf("success","ok")}.orEmpty().ifBlank {if(sms!=null) "扫码成功，请完成短信验证" else if(field(result,"status") in listOf("scanned","confirmed")) "已扫码，请在手机确认" else "等待扫码确认"}
                }
            }
        }
        LaunchedEffect(cooldownUntil) {
            while(true) {
                cooldownSeconds=((cooldownUntil-System.currentTimeMillis()+999)/1000).toInt().coerceAtLeast(0)
                if(cooldownSeconds==0) break
                delay(1000)
            }
        }
        suspend fun checkStatus():JsonObject {checks.lock();try {return ApiClient.json("/qr_login/$sessionSource",mapOf("key" to data.get("key").asString)).asJsonObject} finally {checks.unlock()}}
        suspend fun smsAction(action:String) {
            smsBusy=true
            try {
                val parts=mutableListOf(data.get("key").asString,action,field(sms,"encrypt_uid"),field(sms,"verify_params"));if(action=="validate") parts.add(smsCode.trim())
                val result=ApiClient.json("/qr_login/soda",mapOf("key" to parts.joinToString("|"))).asJsonObject
                update(result)
            } catch(e:CancellationException){throw e} catch(e:Exception){status=e.message?:"验证失败"} finally {smsBusy=false}
        }
        LaunchedEffect(data) {
            pollQrLogin(data.get("expires_at")?.asLong?.takeIf {it>0}?:System.currentTimeMillis()/1000+600,{checkStatus()},{update(it)},{status="状态查询暂时失败，正在重试"})
        }
        AlertDialog(onDismissRequest={session=null},title={Text("扫码关联")},text={Column {
            if(bitmap!=null) Image(bitmap.asImageBitmap(),"登录二维码",Modifier.size(240.dp)) else if(!image.isNullOrBlank()&&!image.startsWith("data:")) AsyncImage(image,"登录二维码",Modifier.size(240.dp)) else Text("二维码内容无法解析，请重新生成",color=MaterialTheme.colorScheme.error)
            Text(status)
            if(cooldownSeconds>0) Text("${cooldownSeconds} 秒后自动检查登录状态")
            else if(sms==null) TextButton(onClick={scope.launch {checking=true;try {update(checkStatus())} catch(e:CancellationException){throw e} catch(_:Exception){status="状态查询暂时失败，正在重试"} finally {checking=false}}},enabled=!checking){Text(if(checking) "检查中…" else "检查登录状态")}
            sms?.let {extra ->
                val up=field(extra,"sms_mode")=="up"||field(extra,"need_user_sms") in listOf("true","1")
                if(up){Text("使用绑定手机号发送 ${field(extra,"up_sms_content")} 到 ${field(extra,"up_sms_mobile")}");TextButton(onClick={scope.launch {smsAction("up_sms")}},enabled=!smsBusy){Text("我已发送")}}
                else {TextButton(onClick={scope.launch {smsAction("send_code")}},enabled=!smsBusy){Text("发送验证码")};OutlinedTextField(smsCode,{smsCode=it},label={Text("短信验证码")});TextButton(onClick={scope.launch {smsAction("validate")}},enabled=!smsBusy&&smsCode.isNotBlank()){Text("确认登录")}}
            }
        }},confirmButton={TextButton(onClick={session=null}){Text("关闭")}},dismissButton={TextButton(onClick={session=null;scope.launch {generating=true;try {session=ApiClient.json("/qr_login/$sessionSource",method="POST").asJsonObject} catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"重新生成失败"} finally {generating=false}}}){Text("重新生成")}})

    }
}

internal fun decodeLoginQr(data:JsonObject):Bitmap? {
    val image=data.get("image_url")?.takeUnless {it.isJsonNull}?.asString.orEmpty()
    if(image.startsWith("data:image/")) {
        val bytes=android.util.Base64.decode(image.substringAfter(','),android.util.Base64.DEFAULT)
        return android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:throw IllegalArgumentException("登录二维码图片无法读取")
    }
    val url=data.get("url")?.takeUnless {it.isJsonNull}?.asString.orEmpty()
    if(image.isNotBlank()&&url.isBlank()) return null
    require(url.isNotBlank()) {"平台没有返回二维码内容"}
    val matrix=MultiFormatWriter().encode(url,BarcodeFormat.QR_CODE,400,400)
    return Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply {val pixels=IntArray(400*400) {i->if(matrix[i%400,i/400]) android.graphics.Color.BLACK else android.graphics.Color.WHITE};setPixels(pixels,0,400,0,0,400,400)}
}
