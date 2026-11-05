package com.nadavepstein.musicplayer.queue

import com.nadavepstein.musicplayer.model.QueueEntry
import com.nadavepstein.musicplayer.model.QueueItemStatus
import com.nadavepstein.musicplayer.model.QueueSnapshot
import com.nadavepstein.musicplayer.model.Song
import java.util.Collections
import java.util.Random

/**
 * Core Queue & Non-Destructive Shuffle Engine implementing Sections 5, 6, 7, 8, and 9:
 *
 * Key architectural guarantees:
 * 1. Separate [queueOrder] (persistent user-visible list) and [playbackOrder] (effective play sequence).
 * 2. Songs do NOT disappear after finishing playback — they transition to [QueueItemStatus.PLAYED]
 *    and remain visible in the queue (YouTube Music style).
 * 3. Direct song selection in the queue ([selectSongInQueue]) starts playing the selected song immediately,
 *    marks any intervening UPCOMING/CURRENT songs between the previous current song and the newly selected
 *    song as [QueueItemStatus.SKIPPED] without deleting them, and continues from the selected song.
 * 4. "Play Next" ([playNext]) inserts songs immediately after the currently playing song with status
 *    [QueueItemStatus.UPCOMING_PRIORITY]. These songs ALWAYS play before any normal/shuffled upcoming songs,
 *    taking strict priority over shuffle mode while preserving their relative order.
 * 5. "Add to Queue" ([addToQueue]) appends songs to the very end of [queueOrder] and, if shuffle is enabled,
 *    incorporates them into the remaining shuffled playback order without replaying already played songs
 *    or interrupting the current song.
 * 6. Manual Drag-and-Drop Reordering ([moveUpcomingSong]) reorders upcoming items in [queueOrder] while
 *    keeping the currently playing song fixed above them. If shuffle is disabled, playback follows the new
 *    manual order immediately. If shuffle is enabled, the underlying [queueOrder] is updated without
 *    interrupting playback, and disabling shuffle restores the exact manually arranged [queueOrder].
 */
class QueueManager(
    private val randomGenerator: Random = Random()
) {
    private val queueEntries = mutableListOf<QueueEntry>()
    private var currentEntryId: String? = null
    private var shuffleEnabled: Boolean = false

    // Holds the randomized sequence of non-priority UPCOMING queueEntryIds for the current shuffle cycle.
    private val shuffledNormalUpcomingIds = mutableListOf<String>()

    private var lastPlaybackPositionMs: Long = 0L

    @Synchronized
    fun getSnapshot(): QueueSnapshot {
        return QueueSnapshot(
            queueOrder = queueEntries.toList(),
            currentEntryId = currentEntryId,
            isShuffleEnabled = shuffleEnabled,
            playbackOrderIds = computeEffectiveUpcomingPlaybackOrderIds(),
            lastPlaybackPositionMs = lastPlaybackPositionMs
        )
    }

    @Synchronized
    fun restoreSnapshot(snapshot: QueueSnapshot) {
        queueEntries.clear()
        queueEntries.addAll(snapshot.queueOrder)
        currentEntryId = snapshot.currentEntryId
        shuffleEnabled = snapshot.isShuffleEnabled
        lastPlaybackPositionMs = snapshot.lastPlaybackPositionMs

        shuffledNormalUpcomingIds.clear()
        if (shuffleEnabled) {
            val validNormalUpcomingIds = queueEntries
                .filter { it.status == QueueItemStatus.UPCOMING }
                .map { it.queueEntryId }
                .toSet()
            snapshot.playbackOrderIds.forEach { id ->
                if (id in validNormalUpcomingIds && id !in shuffledNormalUpcomingIds) {
                    shuffledNormalUpcomingIds.add(id)
                }
            }
            validNormalUpcomingIds.forEach { id ->
                if (id !in shuffledNormalUpcomingIds) {
                    shuffledNormalUpcomingIds.add(id)
                }
            }
        }
    }

    @Synchronized
    fun updatePlaybackPosition(positionMs: Long) {
        lastPlaybackPositionMs = positionMs.coerceAtLeast(0L)
    }

    /**
     * Starts a new queue from a list of songs (e.g. tapping a song in Library or playing a Playlist).
     */
    @Synchronized
    fun setQueueAndPlay(songs: List<Song>, startIndex: Int = 0): Song? {
        if (songs.isEmpty()) return null
        val safeStart = startIndex.coerceIn(0, songs.lastIndex)
        queueEntries.clear()
        shuffledNormalUpcomingIds.clear()

        songs.forEachIndexed { index, song ->
            val status = when {
                index < safeStart -> QueueItemStatus.SKIPPED
                index == safeStart -> QueueItemStatus.CURRENT
                else -> QueueItemStatus.UPCOMING
            }
            queueEntries.add(QueueEntry(song = song, status = status))
        }

        val current = queueEntries[safeStart]
        currentEntryId = current.queueEntryId
        lastPlaybackPositionMs = 0L

        if (shuffleEnabled) {
            rebuildShuffledNormalUpcoming()
        }
        return current.song
    }

    /**
     * Toggles or sets shuffle mode (Section 7).
     * - Never interrupts the currently playing song.
     * - Never replays songs that have already played or been skipped in the current cycle.
     * - Never alters the underlying [queueEntries] order.
     */
    @Synchronized
    fun setShuffleEnabled(enabled: Boolean): QueueSnapshot {
        if (shuffleEnabled == enabled) return getSnapshot()
        shuffleEnabled = enabled
        if (shuffleEnabled) {
            rebuildShuffledNormalUpcoming()
        } else {
            shuffledNormalUpcomingIds.clear()
        }
        return getSnapshot()
    }

    @Synchronized
    fun toggleShuffle(): QueueSnapshot {
        return setShuffleEnabled(!shuffleEnabled)
    }

    /**
     * Section 6: Direct song selection from the expanded queue.
     * - Immediately makes the tapped song the CURRENT song.
     * - Skips over all songs positioned between the previous current song and the selected song
     *   (marks them as SKIPPED rather than deleting them).
     * - Preserve the relative order of the other songs.
     */
    @Synchronized
    fun selectSongInQueue(targetQueueEntryId: String): Song? {
        val targetIdx = queueEntries.indexOfFirst { it.queueEntryId == targetQueueEntryId }
        if (targetIdx == -1) return null

        val prevIdx = queueEntries.indexOfFirst { it.queueEntryId == currentEntryId }
        if (prevIdx == targetIdx && queueEntries[targetIdx].status == QueueItemStatus.CURRENT) {
            return queueEntries[targetIdx].song
        }

        if (prevIdx != -1) {
            if (targetIdx > prevIdx) {
                queueEntries[prevIdx] = queueEntries[prevIdx].copy(status = QueueItemStatus.PLAYED)
                for (i in (prevIdx + 1) until targetIdx) {
                    val item = queueEntries[i]
                    if (item.status == QueueItemStatus.UPCOMING || item.status == QueueItemStatus.UPCOMING_PRIORITY) {
                        queueEntries[i] = item.copy(status = QueueItemStatus.SKIPPED)
                        shuffledNormalUpcomingIds.remove(item.queueEntryId)
                    }
                }
            } else {
                queueEntries[prevIdx] = queueEntries[prevIdx].copy(status = QueueItemStatus.PLAYED)
            }
        }

        val selected = queueEntries[targetIdx].copy(status = QueueItemStatus.CURRENT)
        queueEntries[targetIdx] = selected
        currentEntryId = selected.queueEntryId
        shuffledNormalUpcomingIds.remove(selected.queueEntryId)
        lastPlaybackPositionMs = 0L

        return selected.song
    }

    /**
     * Advances to the next song according to the effective playback order (Section 7 & 8).
     * Returns the new current Song, or null if the queue has reached the end.
     */
    @Synchronized
    fun advanceToNextSong(): Song? {
        val prevIdx = queueEntries.indexOfFirst { it.queueEntryId == currentEntryId }
        val effectiveUpcoming = computeEffectiveUpcomingPlaybackOrderIds()

        if (effectiveUpcoming.isEmpty()) {
            if (prevIdx != -1) {
                queueEntries[prevIdx] = queueEntries[prevIdx].copy(status = QueueItemStatus.PLAYED)
            }
            currentEntryId = null
            lastPlaybackPositionMs = 0L
            return null
        }

        val nextEntryId = effectiveUpcoming.first()
        if (prevIdx != -1) {
            queueEntries[prevIdx] = queueEntries[prevIdx].copy(status = QueueItemStatus.PLAYED)
        }

        val nextIdx = queueEntries.indexOfFirst { it.queueEntryId == nextEntryId }
        if (nextIdx == -1) return null

        val nextEntry = queueEntries[nextIdx].copy(status = QueueItemStatus.CURRENT)
        queueEntries[nextIdx] = nextEntry
        currentEntryId = nextEntry.queueEntryId
        shuffledNormalUpcomingIds.remove(nextEntry.queueEntryId)
        lastPlaybackPositionMs = 0L

        return nextEntry.song
    }

    /**
     * Section 8A: Play Next
     * Inserts the selected song or playlist immediately after the currently playing song.
     */
    @Synchronized
    fun playNext(songs: List<Song>): QueueSnapshot {
        if (songs.isEmpty()) return getSnapshot()

        if (queueEntries.isEmpty() || currentEntryId == null) {
            setQueueAndPlay(songs, 0)
            return getSnapshot()
        }

        val curIdx = queueEntries.indexOfFirst { it.queueEntryId == currentEntryId }
        val insertIndex = if (curIdx != -1) curIdx + 1 else queueEntries.size

        val newEntries = songs.map { song ->
            QueueEntry(song = song, status = QueueItemStatus.UPCOMING_PRIORITY)
        }

        queueEntries.addAll(insertIndex, newEntries)
        return getSnapshot()
    }

    /**
     * Section 8B: Add to Queue
     * Appends the selected song or playlist to the very end of the underlying queue.
     */
    @Synchronized
    fun addToQueue(songs: List<Song>): QueueSnapshot {
        if (songs.isEmpty()) return getSnapshot()

        if (queueEntries.isEmpty() || currentEntryId == null) {
            setQueueAndPlay(songs, 0)
            return getSnapshot()
        }

        val newEntries = songs.map { song ->
            QueueEntry(song = song, status = QueueItemStatus.UPCOMING)
        }
        queueEntries.addAll(newEntries)

        if (shuffleEnabled) {
            newEntries.forEach { entry ->
                val bound = shuffledNormalUpcomingIds.size + 1
                val pos = randomGenerator.nextInt(bound)
                shuffledNormalUpcomingIds.add(pos, entry.queueEntryId)
            }
        }

        return getSnapshot()
    }

    /**
     * Section 8C: Manually Reorder Songs Using Drag and Drop.
     */
    @Synchronized
    fun moveUpcomingSong(fromQueueIndex: Int, toQueueIndex: Int): Boolean {
        if (fromQueueIndex !in queueEntries.indices || toQueueIndex !in queueEntries.indices) {
            return false
        }
        if (fromQueueIndex == toQueueIndex) return true

        val fromEntry = queueEntries[fromQueueIndex]
        val toEntry = queueEntries[toQueueIndex]

        if (!isUpcomingStatus(fromEntry.status) || !isUpcomingStatus(toEntry.status)) {
            return false
        }

        val curIdx = queueEntries.indexOfFirst { it.queueEntryId == currentEntryId }
        if (curIdx != -1 && (fromQueueIndex <= curIdx || toQueueIndex <= curIdx)) {
            return false
        }

        val removed = queueEntries.removeAt(fromQueueIndex)
        queueEntries.add(toQueueIndex, removed)
        return true
    }

    /**
     * Section 8D & 9C: Remove song from queue manually.
     */
    data class RemovalResult(
        val wasCurrentSongRemoved: Boolean,
        val newCurrentSong: Song?,
        val snapshot: QueueSnapshot
    )

    @Synchronized
    fun removeFromQueue(targetQueueEntryId: String): RemovalResult {
        val idx = queueEntries.indexOfFirst { it.queueEntryId == targetQueueEntryId }
        if (idx == -1) {
            return RemovalResult(false, currentSong(), getSnapshot())
        }

        val wasCurrent = (targetQueueEntryId == currentEntryId)
        shuffledNormalUpcomingIds.remove(targetQueueEntryId)

        if (!wasCurrent) {
            queueEntries.removeAt(idx)
            return RemovalResult(false, currentSong(), getSnapshot())
        }

        val nextUpcomingIds = computeEffectiveUpcomingPlaybackOrderIds()
        queueEntries.removeAt(idx)
        if (nextUpcomingIds.isEmpty()) {
            currentEntryId = null
            lastPlaybackPositionMs = 0L
            return RemovalResult(true, null, getSnapshot())
        }

        val nextId = nextUpcomingIds.first()
        val nextIdx = queueEntries.indexOfFirst { it.queueEntryId == nextId }
        val nextEntry = queueEntries[nextIdx].copy(status = QueueItemStatus.CURRENT)
        queueEntries[nextIdx] = nextEntry
        currentEntryId = nextEntry.queueEntryId
        shuffledNormalUpcomingIds.remove(nextEntry.queueEntryId)
        lastPlaybackPositionMs = 0L

        return RemovalResult(true, nextEntry.song, getSnapshot())
    }

    @Synchronized
    fun currentSong(): Song? {
        return queueEntries.firstOrNull { it.queueEntryId == currentEntryId }?.song
    }

    /**
     * Computes the exact order in which upcoming songs will play:
     * 1. All [QueueItemStatus.UPCOMING_PRIORITY] songs ("Play Next") in their queue order.
     * 2. Followed by all [QueueItemStatus.UPCOMING] songs:
     *    - When [shuffleEnabled] is true: in [shuffledNormalUpcomingIds] order (preserving all
     *      unplayed UPCOMING songs regardless of where the currently playing song sits in [queueEntries]!).
     *    - When [shuffleEnabled] is false: in their natural/manual [queueEntries] order starting after
     *      [currentEntryId] (plus any remaining UPCOMING songs earlier in [queueEntries]).
     */
    private fun computeEffectiveUpcomingPlaybackOrderIds(): List<String> {
        val curIdx = queueEntries.indexOfFirst { it.queueEntryId == currentEntryId }
        val orderedUpcoming = if (curIdx != -1) {
            val afterCurrent = queueEntries.subList(curIdx + 1, queueEntries.size)
                .filter { isUpcomingStatus(it.status) }
            val beforeCurrent = queueEntries.subList(0, curIdx)
                .filter { isUpcomingStatus(it.status) }
            afterCurrent + beforeCurrent
        } else {
            queueEntries.filter { isUpcomingStatus(it.status) }
        }

        val priorityIds = orderedUpcoming
            .filter { it.status == QueueItemStatus.UPCOMING_PRIORITY }
            .map { it.queueEntryId }

        val normalIdsInQueueOrder = orderedUpcoming
            .filter { it.status == QueueItemStatus.UPCOMING }
            .map { it.queueEntryId }

        return if (!shuffleEnabled) {
            priorityIds + normalIdsInQueueOrder
        } else {
            val validNormalSet = normalIdsInQueueOrder.toSet()
            val cleanedShuffle = shuffledNormalUpcomingIds.filter { it in validNormalSet }.toMutableList()
            normalIdsInQueueOrder.forEach { id ->
                if (id !in cleanedShuffle) {
                    cleanedShuffle.add(id)
                }
            }
            shuffledNormalUpcomingIds.clear()
            shuffledNormalUpcomingIds.addAll(cleanedShuffle)
            priorityIds + shuffledNormalUpcomingIds
        }
    }

    private fun rebuildShuffledNormalUpcoming() {
        val curIdx = queueEntries.indexOfFirst { it.queueEntryId == currentEntryId }
        val normalIds = if (curIdx != -1) {
            val after = queueEntries.subList(curIdx + 1, queueEntries.size)
                .filter { it.status == QueueItemStatus.UPCOMING }
                .map { it.queueEntryId }
            val before = queueEntries.subList(0, curIdx)
                .filter { it.status == QueueItemStatus.UPCOMING }
                .map { it.queueEntryId }
            (after + before).toMutableList()
        } else {
            queueEntries.filter { it.status == QueueItemStatus.UPCOMING }
                .map { it.queueEntryId }
                .toMutableList()
        }

        if (normalIds.size > 1) {
            Collections.shuffle(normalIds, randomGenerator)
        }
        shuffledNormalUpcomingIds.clear()
        shuffledNormalUpcomingIds.addAll(normalIds)
    }

    private fun isUpcomingStatus(status: QueueItemStatus): Boolean {
        return status == QueueItemStatus.UPCOMING || status == QueueItemStatus.UPCOMING_PRIORITY
    }
}
