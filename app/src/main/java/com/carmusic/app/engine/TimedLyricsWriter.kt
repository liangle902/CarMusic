package com.carmusic.app.engine

import com.carmusic.app.ui.model.LyricLine

internal fun serializeTimedLyrics(lines:List<LyricLine>):String {
    fun stamp(ms:Long)="[%02d:%02d.%03d]".format(java.util.Locale.ROOT,ms/60000,ms/1000%60,ms%1000)
    return lines.flatMap {line->
        val primary=if(line.words.isEmpty()) stamp(line.timeMs)+line.text else line.words.joinToString(""){stamp(it.startMs)+it.text}+stamp(line.words.last().endMs)
        listOf(primary)+listOf(line.romanization,line.translation).filter {it.isNotBlank()}.map {stamp(line.timeMs)+it}
    }.joinToString("\n")
}
