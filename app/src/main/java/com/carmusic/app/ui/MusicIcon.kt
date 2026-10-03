package com.carmusic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

private val musicPaths=mapOf(
    "首页" to "M3 10 L12 3 L21 10 V20 H15 V13 H9 V20 H3 Z",
    "正在播放" to "M8 4 L21 12 L8 20 Z",
    "歌单列表" to "M8 5 H21 M8 12 H21 M8 19 H21 M3 5 H3.1 M3 12 H3.1 M3 19 H3.1",
    "播放队列" to "M8 5 H21 M8 12 H21 M8 19 H21 M3 5 H3.1 M3 12 H3.1 M3 19 H3.1",
    "我的收藏" to "M20.5 5.5 A5 5 0 0 0 13.5 5.5 L12 7 L10.5 5.5 A5 5 0 0 0 3.5 12.5 L12 21 L20.5 12.5 A5 5 0 0 0 20.5 5.5 Z",
    "系统设置" to "M12 3 V6 M12 18 V21 M3 12 H6 M18 12 H21 M5.6 5.6 L7.7 7.7 M16.3 16.3 L18.4 18.4 M18.4 5.6 L16.3 7.7 M7.7 16.3 L5.6 18.4 M18 12 A6 6 0 1 0 6 12 A6 6 0 1 0 18 12 M14 12 A2 2 0 1 0 10 12 A2 2 0 1 0 14 12",
    "全网搜索" to "M17 10.5 A6.5 6.5 0 1 0 4 10.5 A6.5 6.5 0 1 0 17 10.5 M16 16 L21 21",
    "本地音乐" to "M9 18 V5 L20 3 V16 M9 8 L20 6 M9 18 A3 2 0 1 0 3 18 A3 2 0 1 0 9 18 M20 16 A3 2 0 1 0 14 16 A3 2 0 1 0 20 16",
    "下载管理" to "M12 3 V15 M7 10 L12 15 L17 10 M4 16 V21 H20 V16",
    "空白与异常状态" to "M21 12 A9 9 0 1 0 3 12 A9 9 0 1 0 21 12 M12 11 V17 M12 7 V7.1",
    "箭头" to "M9 5 L16 12 L9 19",
    "播放" to "M8 4 L21 12 L8 20 Z",
    "暂停" to "M7 4 V20 M17 4 V20",
    "上一首" to "M5 4 V20 M19 4 L7 12 L19 20 Z",
    "下一首" to "M19 4 V20 M5 4 L17 12 L5 20 Z",
    "拖动" to "M4 6 H20 M4 12 H20 M4 18 H20",
    "音量" to "M3 9 H7 L12 5 V19 L7 15 H3 Z M16 8 A6 6 0 0 1 16 16 M19 5 A10 10 0 0 1 19 19",
    "静音" to "M3 9 H7 L12 5 V19 L7 15 H3 Z M16 9 L22 15 M22 9 L16 15",
    "顺序播放" to "M3 7 H21 M17 3 L21 7 L17 11 M3 17 H21 M17 13 L21 17 L17 21",
    "随机播放" to "M3 5 H6 L17 19 H21 M17 15 L21 19 L17 23 M3 19 H6 L17 5 H21 M17 1 L21 5 L17 9",
    "单曲循环" to "M20 7 A9 9 0 0 0 5 5 L3 8 M3 3 V8 H8 M4 17 A9 9 0 0 0 19 19 L21 16 M16 16 H21 V21 M10 10 L12 9 V15 M10 15 H14",
    "列表循环" to "M20 7 A9 9 0 0 0 5 5 L3 8 M3 3 V8 H8 M4 17 A9 9 0 0 0 19 19 L21 16 M16 16 H21 V21"
)

@Composable
fun MusicIcon(name:String,modifier:Modifier=Modifier.size(24.dp),color:Color=MaterialTheme.colorScheme.primary) {
    val path=androidx.core.graphics.PathParser.createPathFromPathData(musicPaths[name]?:musicPaths.getValue("本地音乐"))!!.asComposePath()
    Canvas(modifier) {withTransform({scale(size.width/24f,size.height/24f,pivot=Offset.Zero)}) {drawPath(path,color,style=Stroke(1.65f,cap=androidx.compose.ui.graphics.StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round))}}
}
