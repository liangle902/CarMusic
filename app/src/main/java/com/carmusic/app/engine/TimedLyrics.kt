package com.carmusic.app.engine

import com.carmusic.app.ui.model.LyricLine
import com.carmusic.app.ui.model.LyricWord

/** Uses the upstream inline timestamp/group convention, retaining ordinary repeated LRC tags. */
internal fun parseTimedLyrics(raw:String):List<LyricLine> {
    val stamps=Regex("\\[(\\d+):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    val offset=Regex("\\[offset:([+-]?\\d+)\\]").find(raw)?.groupValues?.get(1)?.toLongOrNull()?:0L
    fun time(match:MatchResult):Long? {
        val minutes=match.groupValues[1].toLongOrNull()?:return null
        val seconds=match.groupValues[2].toLongOrNull()?:return null
        val fraction=match.groupValues[3].padEnd(3,'0').take(3).toLongOrNull()?:0
        return try {Math.addExact(Math.addExact(Math.multiplyExact(minutes,60000),seconds*1000+fraction),offset).coerceAtLeast(0).takeIf {it<=Long.MAX_VALUE-1200}}
            catch(_:ArithmeticException){null}
    }
    val parsed=raw.lineSequence().flatMap {line->
        val tags=stamps.findAll(line).filter {time(it)!=null}.toList()
        if(tags.isEmpty()) emptySequence() else {
            val inline=tags.zipWithNext().any {(a,b)->line.substring(a.range.last+1,b.range.first).isNotBlank()}
            if(!inline) {
                val text=line.substring(tags.last().range.last+1).trim()
                if(text.isBlank()) emptySequence() else tags.asSequence().map {LyricLine(time(it)!!,text)}
            } else {
                val words=tags.mapIndexedNotNull {index,tag->
                    val next=tags.getOrNull(index+1)
                    val text=line.substring(tag.range.last+1,next?.range?.first?:line.length)
                    if(text.isEmpty()) null else LyricWord(time(tag)!!,next?.let(::time)?:-1,text)
                }
                val text=words.joinToString("") {it.text}.trim()
                if(text.isBlank()) emptySequence() else sequenceOf(LyricLine(time(tags.first())!!,text,words))
            }
        }
    }.groupBy {it.timeMs}.toSortedMap().entries.toList()
    return parsed.mapIndexed {index,entry->
        val primary=entry.value.first()
        val end=parsed.getOrNull(index+1)?.key?:entry.key+1200
        val words=primary.words.map {word->word.copy(endMs=if(word.endMs>word.startMs) word.endMs else end.coerceAtLeast(word.startMs+1))}
        var translation="";var romanization=""
        for(extra in entry.value.drop(1).distinctBy {it.text}) {
            val latin=extra.text.count {it in 'a'..'z'||it in 'A'..'Z'}
            val cjk=extra.text.count {it in '\u3040'..'\u30ff'||it in '\u3400'..'\u9fff'}
            if(romanization.isBlank()&&latin>0&&latin>=cjk) romanization=extra.text
            else if(translation.isBlank()) translation=extra.text
            else if(romanization.isBlank()) romanization=extra.text
        }
        primary.copy(words=words,translation=translation,romanization=romanization)
    }
}
