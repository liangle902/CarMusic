package com.carmusic.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carmusic.app.ui.model.LyricLine

@Composable
internal fun TimedLyricText(line:LyricLine,position:Long,active:Boolean,onClick:()->Unit) {
    val colors=MaterialTheme.colorScheme
    val base=colors.onSurfaceVariant
    val text=buildAnnotatedString {
        if(line.words.isEmpty()) append(line.text)
        else for(word in line.words) {
            val progress=((position-word.startMs).toFloat()/(word.endMs-word.startMs).coerceAtLeast(1)).coerceIn(0f,1f)
            word.text.forEachIndexed {index,char->
                val fill=(progress*word.text.length-index).coerceIn(0f,1f)
                withStyle(SpanStyle(color=if(active) lerp(base,colors.primary,fill) else base)){append(char)}
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact=maxWidth<160.dp
    Column(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=12.dp)) {
        Text(text,color=if(active) colors.primary else base,fontSize=if(compact) {if(active) 16.sp else 14.sp} else {if(active) 22.sp else 18.sp},textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
        if(line.romanization.isNotBlank()) Text(line.romanization,color=base,fontSize=12.sp,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(top=5.dp))
        if(line.translation.isNotBlank()) Text(line.translation,color=base,fontSize=14.sp,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(top=5.dp))
    }
    }
}
