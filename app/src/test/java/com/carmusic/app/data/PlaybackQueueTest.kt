package com.carmusic.app.data

import com.carmusic.app.ui.model.SongItem
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    private val a=SongItem(id="a",source="local")
    private val b=SongItem(id="b",source="local")
    private val c=SongItem(id="c",source="local")
    @Test fun selectingExistingTrackMovesItToFrontAndPreservesRemainingOrder(){assertEquals(listOf(b,a,c),queueWithSongFirst(listOf(a,b,c),b))}
    @Test fun selectingNewTrackKeepsOldQueueAndDoesNotDuplicateReselection(){val queue=queueWithSongFirst(listOf(a,b),c);assertEquals(listOf(c,a,b),queue);assertEquals(queue,queueWithSongFirst(queue,c))}
    @Test fun latestSelectedMetadataReplacesOldEntryWithoutChangingOtherSources(){val updated=b.copy(name="updated");val other=b.copy(source="qq");assertEquals(listOf(updated,a,other),queueWithSongFirst(listOf(a,b,b,other),updated))}
}
