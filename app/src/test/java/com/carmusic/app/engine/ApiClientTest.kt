package com.carmusic.app.engine

import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class ApiClientTest {
    @Test fun completedDownloadsKeepMetadataAndWebDavWarningsVisible() {
        val response=com.google.gson.JsonParser.parseString("""{"saved":true,"warning":"歌词未嵌入","webdav_error":"认证失败"}""").asJsonObject
        val notice=ApiClient.downloadNotice(response)
        assertTrue(notice.contains("歌词未嵌入"))
        assertTrue(notice.contains("本地文件已保存，WebDAV 同步失败：认证失败"))
        assertEquals("",ApiClient.downloadNotice(com.google.gson.JsonParser.parseString("""{"saved":true,"warning":null}""").asJsonObject))
    }
    @Test fun timedLyricsKeepEveryTimestampAndOffset() {
        val result=ApiClient.parseLrc("[ar:歌手]\n[offset:-500]\n[01:02.3][02:03.45]重复歌词\n[00:00.250]开头\n无时间歌词")
        assertEquals(listOf(0L,61800L,122950L),result.map {it.timeMs})
        assertEquals(listOf("开头","重复歌词","重复歌词"),result.map {it.text})
    }
    @Test fun sourcesAreRepeatedAndShareLinksStayEncoded() {
        val link="https://y.qq.com/playlist?id=123&foo=歌单"
        val url=ApiClient.url("/search",mapOf("sources" to "qq,netease","q" to link)).toHttpUrl()
        assertEquals(listOf("qq","netease"),url.queryParameterValues("sources"))
        assertEquals(link,url.queryParameter("q"))
    }
    @Test fun emptyAndMetadataOnlyLyricsAreEmpty() {
        assertTrue(ApiClient.parseLrc("[ti:歌名]\n[offset:300]\n[00:01.00]").isEmpty())
    }
}
