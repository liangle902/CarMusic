package com.carmusic.app.engine

import com.google.gson.JsonObject
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QrLoginPollingTest {
    private fun result(status:String,message:String="")=JsonObject().apply {addProperty("status",status);addProperty("message",message)}
    @Test fun temporaryErrorAndConfirmedWithoutCookieContinueUntilSuccess()=runBlocking {
        var count=0;var errors=0;val states=mutableListOf<String>()
        pollQrLogin(100,{
            when(count++) {0->result("scanned","已扫码");1->throw IOException("temporary");2->result("scanned","已扫码确认，等待登录结果");else->result("success")}
        },{states+=it.get("message").asString},{errors++},{},{0})
        assertEquals(4,count);assertEquals(1,errors)
        assertTrue(states.contains("已扫码确认，等待登录结果"))
    }
    @Test fun smsRequiredDoesNotPreventCompletionAndRateLimitBacksOff()=runBlocking {
        var count=0;val waits=mutableListOf<Long>()
        pollQrLogin(100,{
            if(count++==0) result("scanned").apply {add("extra",JsonObject().apply {addProperty("need_sms","true");addProperty("rate_limited","true")})} else result("success")
        },{},{throw AssertionError(it)},{waits+=it},{0})
        assertEquals(listOf(2500L,60000L),waits);assertEquals(2,count)
    }
    @Test fun sessionExpirationDoesNotLeaveScannedMessageForever()=runBlocking {
        var time=0L;val states=mutableListOf<String>()
        pollQrLogin(5,{result("scanned")},{states+=it.get("status").asString},{},{time+=3},{time})
        assertEquals(listOf("scanned","expired"),states)
    }
}
