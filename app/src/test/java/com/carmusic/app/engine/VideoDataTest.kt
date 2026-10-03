package com.carmusic.app.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class VideoDataTest {
    @Test fun spectrumDistinguishesSilenceAndTwoRealFrequencies(){
        val analyzer=AudioSpectrum();assertTrue(analyzer.levels(22050).all {it==0f})
        fun peak(frequency:Int):Int{analyzer.reset();repeat(1024){analyzer.add(sin(2*PI*frequency*it/22050).toFloat()*.6f)};val bands=analyzer.levels(22050);assertTrue(bands.max()>0.8f);return bands.indices.maxBy {bands[it]}}
        assertTrue(peak(300)<peak(3000))
    }
    @Test fun videoLyricsRoundTripWordsTranslationsAndRomanization(){
        val original=parseTimedLyrics("[00:01.000]好[00:01.500]歌[00:02.000]\n[00:01.000]hao ge\n[00:01.000]Good song")
        // Use a CJK translation to match upstream grouping convention.
        val mixed=original.map {it.copy(translation="一首好歌",romanization="hao ge")}
        val restored=parseTimedLyrics(serializeTimedLyrics(mixed))
        assertEquals(mixed,restored)
    }
}
