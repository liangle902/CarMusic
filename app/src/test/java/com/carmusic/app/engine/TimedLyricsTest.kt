package com.carmusic.app.engine

import org.junit.Assert.*
import org.junit.Test

class TimedLyricsTest {
    @Test fun inlineWordsAndSameTimeTranslationsStayInOneGroup() {
        val result=parseTimedLyrics("[offset:100]\n[00:01.00]你[00:01.50]好[00:02.00]\n[00:01.00]hello[00:02.00]\n[00:01.00]你好世界\n[00:03.00]世界[00:04.00]")
        assertEquals(2,result.size)
        assertEquals("你好",result[0].text)
        assertEquals(listOf(1100L,1600L),result[0].words.map {it.startMs})
        assertEquals(listOf(1600L,2100L),result[0].words.map {it.endMs})
        assertEquals("hello",result[0].romanization)
        assertEquals("你好世界",result[0].translation)
    }
    @Test fun repeatedLeadingTagsRemainRepeatedLinesAndMissingEndUsesNextGroup() {
        val result=parseTimedLyrics("[00:01.00][00:03.00]重复\n[00:04.00]天[00:04.50]空\n[00:06.00]下一行")
        assertEquals(listOf(1000L,3000L,4000L,6000L),result.map {it.timeMs})
        assertEquals(6000L,result[2].words.last().endMs)
        assertEquals("天空",result[2].text)
    }
}
