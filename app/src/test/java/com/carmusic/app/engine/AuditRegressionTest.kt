package com.carmusic.app.engine

import com.carmusic.app.ui.LatestRequest
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AuditRegressionTest {
    @Test fun supersededRequestsCannotRestoreResultsAfterClear() {
        val requests=LatestRequest()
        val oldSearch=requests.begin()
        val cleared=requests.begin()
        assertFalse(requests.isCurrent(oldSearch))
        val nextSearch=requests.begin()
        assertFalse(requests.isCurrent(cleared))
        assertTrue(requests.isCurrent(nextSearch))
    }

    @Test fun malformedAndOverflowingLyricTagsDoNotDiscardValidLines() {
        val result=parseTimedLyrics("[999999999999999999999999999:00]bad\n[9223372036854775807:00]overflow\n[00:01.000]one\n[00:02.000]two")
        assertEquals(listOf(1000L,2000L),result.map {it.timeMs})
        assertEquals(listOf("one","two"),result.map {it.text})
        assertTrue(parseTimedLyrics("[offset:9223372036854775807]\n[00:01.000]overflow").isEmpty())
        assertTrue(parseTimedLyrics("[offset:9223372036854775707]\n[00:00.000]overflow at line end").isEmpty())
    }

    @Test fun qrHandlerFailureIsNotRetriedAsNetworkFailure()=runBlocking {
        var reads=0;var networkErrors=0
        try {
            pollQrLogin(100, {reads++;JsonObject().apply {addProperty("status","success")}},
                {throw IllegalStateException("handler failed")},{networkErrors++},pause={},now={0})
            fail("handler error should propagate")
        } catch(_:IllegalStateException) {
            assertEquals(1,reads);assertEquals(0,networkErrors)
        }
    }

    @Test fun qrTemporaryNetworkFailureStillRecovers()=runBlocking {
        var reads=0;var networkErrors=0;var successes=0
        pollQrLogin(100, {reads++;if(reads==1) throw java.io.IOException("temporary")
            JsonObject().apply {addProperty("status","success")}},
            {successes++},{networkErrors++},pause={},now={0})
        assertEquals(2,reads);assertEquals(1,networkErrors);assertEquals(1,successes)
    }
}
