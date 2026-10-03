package com.carmusic.app.data

import com.carmusic.app.ui.model.SongItem

internal fun queueWithSongFirst(queue:List<SongItem>,song:SongItem):List<SongItem> =
    listOf(song)+queue.filterNot {it.key==song.key}.distinctBy {it.key}
