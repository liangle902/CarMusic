package com.carmusic.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun AlbumArtwork(url:String?,vinyl:Boolean,playing:Boolean,size:androidx.compose.ui.unit.Dp=210.dp) {
    var angle by remember {mutableFloatStateOf(0f)}
    LaunchedEffect(playing,vinyl) {if(playing&&vinyl) while(true){delay(32);angle=(angle+.48f)%360f}}
    val shape=if(vinyl) CircleShape else RoundedCornerShape(24.dp)
    Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) {
        if(vinyl) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color(0xFF15191E))
                for(i in 0..18) drawCircle(Color(0xFF303943),radius=this.size.minDimension*(.27f+i*.011f),style=Stroke(1.5f))
            }
            AsyncImage(url,"黑胶封面",Modifier.fillMaxSize(.48f).rotate(angle).clip(CircleShape),contentScale=ContentScale.Crop)
            Box(Modifier.size(12.dp).background(Color(0xFFC4CDD5),CircleShape))
        } else AsyncImage(url,"专辑封面",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
    }
}
