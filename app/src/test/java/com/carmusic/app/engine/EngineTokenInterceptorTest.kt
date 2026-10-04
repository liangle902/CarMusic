package com.carmusic.app.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class EngineTokenInterceptorTest {
    @Test fun redirectToAnotherOriginCannotReceiveEngineToken() {
        MockWebServer().use { engine -> MockWebServer().use { cdn ->
            engine.start(); cdn.start()
            engine.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/audio")))
            cdn.enqueue(MockResponse().setBody("audio"))
            val client = OkHttpClient.Builder().addNetworkInterceptor(EngineTokenInterceptor { url ->
                if (url.host == engine.hostName && url.port == engine.port) "Bearer runtime-token" else null
            }).build()
            client.newCall(Request.Builder().url(engine.url("/music/download")).build()).execute().use {
                assertEquals("audio", it.body!!.string())
            }
            assertEquals("Bearer runtime-token", engine.takeRequest().getHeader("Authorization"))
            assertNull(cdn.takeRequest().getHeader("Authorization"))
        } }
    }

    @Test fun callerCannotInjectAnAuthorizationHeaderIntoExternalRequests() {
        MockWebServer().use { cdn ->
            cdn.start(); cdn.enqueue(MockResponse().setBody("cover"))
            val client = OkHttpClient.Builder().addNetworkInterceptor(EngineTokenInterceptor { null }).build()
            client.newCall(Request.Builder().url(cdn.url("/cover")).header("Authorization", "Bearer caller-token").build())
                .execute().close()
            assertNull(cdn.takeRequest().getHeader("Authorization"))
        }
    }
}
