package com.carmusic.app.engine

import com.carmusic.app.data.queueWithSongFirst
import com.carmusic.app.ui.model.SongItem
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MusicDataTest {
    @Test fun appendedSelectionRetainsResultOrderAndDoesNotAddExistingTracksAgain() {
        val a = SongItem(id="a", source="qq"); val b = SongItem(id="b", source="qq")
        val c = SongItem(id="c", source="netease")
        assertEquals(listOf(a, c, b), com.carmusic.app.data.queueWithSongsAppended(listOf(a), listOf(c, a, b, c)))
    }
    @Test fun absentOrNullErrorsAreNotShownAsFailures() {
        for (raw in listOf("{}", "{\"error\":null}", "{\"error\":\"\"}"))
            assertEquals("", ApiClient.errorMessage(JsonParser.parseString(raw).asJsonObject))
        assertEquals("音源失效", ApiClient.errorMessage(JsonParser.parseString("{\"error\":\"音源失效\"}").asJsonObject))
    }

    @Test fun singleSongPlaybackKeepsOrderAndRemovesDuplicateKeys() {
        val a = SongItem(id="a", source="qq"); val b = SongItem(id="b", source="qq")
        val c = SongItem(id="c", source="netease")
        assertEquals(listOf(b, a, c), queueWithSongFirst(listOf(a, b, a, c, b), b))
    }

    @Test fun repeatedTimestampsOffsetAndTranslatedLinesStayAligned() {
        val result = parseTimedLyrics("[offset:-500]\n[00:01.00][00:03.00]Hello\n[00:01.00]你好")
        assertEquals(listOf(500L, 2500L), result.map {it.timeMs})
        assertEquals("你好", result[0].translation)
        assertEquals("Hello", result[1].text)
    }

    @Test fun inlineLyricsRetainWordTimingAndAnEndTimeForTheLastWord() {
        val result = parseTimedLyrics("[00:01.000]Hello [00:02.000]world\n[00:04.000]Next")
        assertEquals("Hello world", result[0].text)
        assertEquals(2000L, result[0].words[0].endMs)
        assertEquals(4000L, result[0].words[1].endMs)
    }

    @Test fun silenceAndResetHaveNoSpectrumEnergy() {
        val spectrum = AudioSpectrum()
        repeat(1024) { spectrum.add(0f) }
        assertTrue(spectrum.levels(44100).all {it == 0f})
        repeat(1024) { spectrum.add(kotlin.math.sin(it * .2).toFloat()) }
        assertTrue(spectrum.levels(44100).any {it > .1f})
        spectrum.reset()
        assertTrue(spectrum.levels(44100).all {it == 0f})
    }
}
