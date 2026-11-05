package com.nadavepstein.musicplayer

import com.nadavepstein.musicplayer.model.QueueItemStatus
import com.nadavepstein.musicplayer.model.QueueSnapshot
import com.nadavepstein.musicplayer.model.Song
import com.nadavepstein.musicplayer.queue.QueueManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class QueueAndShuffleEngineTest {

    private fun makeSong(index: Int, title: String = "Song $index"): Song = Song(
        id = "uri://$index",
        uriString = "content://music/$index",
        fileName = "$title.mp3",
        displayTitle = title,
        durationMs = 180_000L,
        sizeBytes = 4_000_000L,
        contentHash = "hash_$index",
        parentFolder = null
    )

    @Test
    fun testPlayedSongsRemainVisibleInQueue() {
        val qm = QueueManager(Random(42))
        val songs = (1..4).map { makeSong(it) }
        val first = qm.setQueueAndPlay(songs, 0)
        assertEquals("Song 1", first?.displayTitle)

        val second = qm.advanceToNextSong()
        assertEquals("Song 2", second?.displayTitle)

        val snap = qm.getSnapshot()
        assertEquals(4, snap.queueOrder.size)
        assertEquals(QueueItemStatus.PLAYED, snap.queueOrder[0].status)
        assertEquals(QueueItemStatus.CURRENT, snap.queueOrder[1].status)
        assertEquals(QueueItemStatus.UPCOMING, snap.queueOrder[2].status)
        assertEquals(QueueItemStatus.UPCOMING, snap.queueOrder[3].status)
    }

    @Test
    fun testDirectSelectionSkipsInterveningSongsWithoutDeletingThem() {
        val qm = QueueManager(Random(42))
        val songs = (1..5).map { makeSong(it) }
        qm.setQueueAndPlay(songs, 0)

        // Tap Song 4 directly (index 3)
        val targetId = qm.getSnapshot().queueOrder[3].queueEntryId
        val selected = qm.selectSongInQueue(targetId)
        assertEquals("Song 4", selected?.displayTitle)

        val snap = qm.getSnapshot()
        // All 5 songs must still be in the visible queue!
        assertEquals(5, snap.queueOrder.size)
        assertEquals(QueueItemStatus.PLAYED, snap.queueOrder[0].status)
        assertEquals(QueueItemStatus.SKIPPED, snap.queueOrder[1].status) // Song 2 skipped
        assertEquals(QueueItemStatus.SKIPPED, snap.queueOrder[2].status) // Song 3 skipped
        assertEquals(QueueItemStatus.CURRENT, snap.queueOrder[3].status) // Song 4 playing
        assertEquals(QueueItemStatus.UPCOMING, snap.queueOrder[4].status) // Song 5 upcoming

        // Advancing plays Song 5 and does NOT automatically replay skipped Song 2 or Song 3
        val next = qm.advanceToNextSong()
        assertEquals("Song 5", next?.displayTitle)
        assertNull(qm.advanceToNextSong())
    }

    @Test
    fun testShuffleNeverDestroysUnderlyingQueueOrderAndPlaysOncePerCycle() {
        val qm = QueueManager(Random(12345))
        val songs = (1..8).map { makeSong(it) }
        qm.setQueueAndPlay(songs, 0)

        // Advance once so Song 1 is PLAYED and Song 2 is CURRENT
        qm.advanceToNextSong()

        // Enable shuffle while Song 2 is playing
        val snapShuffled = qm.setShuffleEnabled(true)
        assertTrue(snapShuffled.isShuffleEnabled)
        assertEquals("Song 2", snapShuffled.currentEntry?.song?.displayTitle)

        // Underlying queueOrder must still be Song 1..Song 8 in exact original order
        assertEquals(songs.map { it.displayTitle }, snapShuffled.queueOrder.map { it.song.displayTitle })

        // PlayNext must take priority over shuffle!
        val prioritySongs = listOf(makeSong(99, "עברי לידר - זכיתי לאהוב"))
        qm.playNext(prioritySongs)

        val nextPlayed = qm.advanceToNextSong()
        assertEquals("עברי לידר - זכיתי לאהוב", nextPlayed?.displayTitle)

        // Play remaining shuffled songs and verify each remaining song plays once without repeating Song 1 or Song 2
        val playedTitles = mutableListOf<String>()
        while (true) {
            val s = qm.advanceToNextSong() ?: break
            playedTitles.add(s.displayTitle)
        }
        assertEquals(6, playedTitles.size)
        assertEquals((3..8).map { "Song $it" }.toSet(), playedTitles.toSet())
    }

    @Test
    fun testManualReorderUnderShufflePreservesManualOrderWhenShuffleDisabled() {
        val qm = QueueManager(Random(77))
        val songs = (1..5).map { makeSong(it) }
        qm.setQueueAndPlay(songs, 0)
        qm.setShuffleEnabled(true)

        // Move Song 5 (index 4) to index 1 (right after Song 1)
        val moved = qm.moveUpcomingSong(4, 1)
        assertTrue(moved)

        // Disable shuffle: playback order must now follow the manual queue order (Song 5, Song 2, Song 3, Song 4)
        qm.setShuffleEnabled(false)
        assertEquals("Song 5", qm.advanceToNextSong()?.displayTitle)
        assertEquals("Song 2", qm.advanceToNextSong()?.displayTitle)
        assertEquals("Song 3", qm.advanceToNextSong()?.displayTitle)
        assertEquals("Song 4", qm.advanceToNextSong()?.displayTitle)
    }

    @Test
    fun testQueuePersistenceRoundTrip() {
        val qm = QueueManager(Random(99))
        val songs = listOf(
            makeSong(1, "שיר ראשון"),
            makeSong(2, "Second Song"),
            makeSong(3, "שיר שלישי")
        )
        qm.setQueueAndPlay(songs, 0)
        qm.setShuffleEnabled(true)
        qm.updatePlaybackPosition(45_200L)

        val json = qm.getSnapshot().toJson()
        val restoredSnap = QueueSnapshot.fromJson(json)

        val qm2 = QueueManager(Random(99))
        qm2.restoreSnapshot(restoredSnap)
        val verify = qm2.getSnapshot()

        assertEquals(3, verify.queueOrder.size)
        assertEquals("שיר ראשון", verify.currentEntry?.song?.displayTitle)
        assertTrue(verify.isShuffleEnabled)
        assertEquals(45_200L, verify.lastPlaybackPositionMs)
    }
}
