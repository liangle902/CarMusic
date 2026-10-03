package com.carmusic.app.engine

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Keep the session alive through scanned/confirmed and temporary network failures. */
internal suspend fun pollQrLogin(
    expiresAt:Long,
    read:suspend ()->JsonObject,
    onResult:(JsonObject)->Unit,
    onError:(Exception)->Unit,
    pause:suspend (Long)->Unit={delay(it)},
    now:()->Long={System.currentTimeMillis()/1000}
) {
    var interval=2500L
    while(now()<expiresAt) {
        pause(interval)
        if(now()>=expiresAt) break
        try {
            val result=read()
            onResult(result)
            if(result.get("status")?.asString in listOf("success","expired","failed")) return
            val extra=result.getAsJsonObject("extra")
            interval=if(extra?.get("rate_limited")?.asString=="true") 60000L else 3000L
        } catch(e:CancellationException){throw e} catch(e:Exception){onError(e);interval=5000L}
    }
    onResult(JsonObject().apply {addProperty("status","expired");addProperty("message","二维码已失效，请重新生成")})
}
