package com.carmusic.app.ui.model

import com.google.gson.annotations.SerializedName

data class SongItem(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("artist") val artist: String = "",
    @SerializedName("album") val album: String = "",
    @SerializedName("duration") val duration: Long = 0,
    @SerializedName("size") val size: Long = 0,
    @SerializedName("bitrate") val bitrate: Int = 0,
    @SerializedName("source") val source: String = "",
    @SerializedName("cover") val cover: String = "",
    @SerializedName("url") var streamUrl: String = "",
    @SerializedName("link") val link: String = "",
    @SerializedName("extra") val extra: Map<String, Any>? = null
) {
    val key: String get() = "$source:$id"
}

data class PlaylistItem(
    val id: String = "", val name: String = "", val cover: String = "",
    val source: String = "local", val creator: String = "", val description: String = "",
    @SerializedName(value = "track_count", alternate = ["trackCount"]) val trackCount: Int = 0,
    val link: String = ""
)

data class SourceCapability(val id: String = "", val name: String = "", val qr: Boolean = false, val personal: Boolean = false, val recommend: Boolean = false, val playlist: Boolean = false, val album: Boolean = false,val search:Boolean=true,val playback:Boolean=true)

data class SearchResponse(
    @SerializedName("songs") val songs: List<SongItem>? = emptyList(),
    @SerializedName("keyword") val keyword: String = "",
    @SerializedName("total") val total: Int = 0,
    @SerializedName("error") val error: String = ""
)

data class StreamInfo(
    @SerializedName("valid") val valid: Boolean = false,
    @SerializedName("url") val url: String = "",
    @SerializedName("size") val size: String = "",
    @SerializedName("bitrate") val bitrate: String = ""
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words:List<LyricWord> = emptyList(),
    val translation:String = "",
    val romanization:String = ""
)

data class LyricWord(val startMs:Long,val endMs:Long,val text:String)

enum class PlayMode {
    SEQUENCE,
    SHUFFLE,
    REPEAT_ONE,
    REPEAT_ALL
}
