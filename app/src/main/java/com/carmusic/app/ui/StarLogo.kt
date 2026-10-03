package com.carmusic.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp

const val MUSIC_SLOGAN = "让好音乐，随心而听。"

@Composable
fun StarLogo(modifier: Modifier = Modifier, animated: Boolean = false) {
    if (!animated) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.carmusic.app.R.drawable.ic_logo),"星河音乐",modifier)
        return
    }
    val transition = rememberInfiniteTransition(label="orbit")
    val angle by transition.animateFloat(0f,360f,infiniteRepeatable(tween(12000,easing=LinearEasing)),label="star")
    val color=MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val scale=size.minDimension/100f
        fun point(x:Float,y:Float)=Offset(x*scale,y*scale)
        val ellipse=Path().apply {moveTo(10f*scale,62f*scale);cubicTo(5f*scale,22f*scale,87f*scale,5f*scale,91f*scale,44f*scale);cubicTo(95f*scale,77f*scale,21f*scale,98f*scale,10f*scale,62f*scale)}
        drawPath(ellipse,color.copy(alpha=.5f),style=androidx.compose.ui.graphics.drawscope.Stroke(2f*scale))
        drawLine(color,point(57f,24f),point(57f,67f),5f*scale,StrokeCap.Round)
        drawLine(color,point(57f,24f),point(76f,30f),5f*scale,StrokeCap.Round)
        drawOval(color,point(35f,60f),androidx.compose.ui.geometry.Size(23f*scale,16f*scale))
        val radians=(if(animated) angle else 310f)*Math.PI/180
        val cx=(50+42*kotlin.math.cos(radians)).toFloat();val cy=(50+34*kotlin.math.sin(radians)).toFloat()
        val star=Path().apply{moveTo(cx*scale,(cy-9)*scale);lineTo((cx+3)*scale,(cy-3)*scale);lineTo((cx+9)*scale,cy*scale);lineTo((cx+3)*scale,(cy+3)*scale);lineTo(cx*scale,(cy+9)*scale);lineTo((cx-3)*scale,(cy+3)*scale);lineTo((cx-9)*scale,cy*scale);lineTo((cx-3)*scale,(cy-3)*scale);close()}
        drawPath(star,color)
    }
}
@Composable
fun FlowingSlogan(modifier: Modifier = Modifier) {
    val progress=remember {Animatable(0f)}
    LaunchedEffect(Unit){progress.animateTo(1f,tween(1900,easing=LinearEasing))}
    Canvas(modifier) {
        drawContext.canvas.nativeCanvas.apply {
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {textSize=size.width/11f;typeface=android.graphics.Typeface.create("serif",android.graphics.Typeface.BOLD);color=android.graphics.Color.rgb(226,199,145);strokeWidth=1.5f;style=android.graphics.Paint.Style.STROKE}
            val path=android.graphics.Path();val text=MUSIC_SLOGAN;val left=(size.width-paint.measureText(text))/2f;paint.getTextPath(text,0,text.length,left,size.height*.7f,path)
            val measure=android.graphics.PathMeasure(path,false);val segment=android.graphics.Path()
            do {measure.getSegment(0f,measure.length*progress.value,segment,true)} while(measure.nextContour())
            drawPath(segment,paint)
            if(progress.value>.85f){paint.style=android.graphics.Paint.Style.FILL;paint.alpha=((progress.value-.85f)/.15f*255).toInt().coerceIn(0,255);drawPath(path,paint)}
        }
    }
}
