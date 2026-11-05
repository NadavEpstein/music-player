package com.nadavepstein.musicplayer.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.UUID

/**
 * Supported appearance modes (Section 11).
 * Strictly two modes: DARK (default) and LIGHT.
 */
enum class AppTheme {
    DARK,
    LIGHT
}

/**
 * Playback status of an item inside the persistent YouTube Music-style queue (Sections 5, 6, 7, 8).
 */
enum class QueueItemStatus {
    PLAYED,
    SKIPPED,
    CURRENT,
    UPCOMING_PRIORITY, // Added via "Play Next" — takes priority over shuffle
    UPCOMING           // Normal upcoming song
}

/**
 * Represents a local audio file located either directly inside root Music/
 * or directly inside an immediate subfolder Music/<PlaylistName>/ (Section 2).
 */
data class Song(
    val id: String,               // Unique URI string
    val uriString: String,        // SAF DocumentFile URI string
    val fileName: String,         // Full filename with extension (supports Hebrew/Unicode)
    val displayTitle: String,     // Clean song title (supports Hebrew/Unicode NFC)
    val durationMs: Long,         // Duration in milliseconds
    val sizeBytes: Long,          // File size in bytes
    val contentHash: String,      // Content fingerprint (SHA-256) for accurate duplicate detection
    val parentFolder: String?     // null if standalone song in root Music; folder name if in playlist
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("uriString", uriString)
        put("fileName", fileName)
        put("displayTitle", displayTitle)
        put("durationMs", durationMs)
        put("sizeBytes", sizeBytes)
        put("contentHash", contentHash)
        put("parentFolder", parentFolder ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(obj: JSONObject): Song = Song(
            id = obj.getString("id"),
            uriString = obj.getString("uriString"),
            fileName = obj.getString("fileName"),
            displayTitle = Normalizer.normalize(obj.getString("displayTitle"), Normalizer.Form.NFC),
            durationMs = obj.optLong("durationMs", 0L),
            sizeBytes = obj.optLong("sizeBytes", 0L),
            contentHash = obj.optString("contentHash", ""),
            parentFolder = if (obj.isNull("parentFolder")) null else obj.optString("parentFolder")
        )
    }
}

/**
 * Represents an immediate subfolder inside Music/ (Section 2 & 3).
 * Never recursive; deeper folders are completely ignored.
 */
data class Playlist(
    val name: String,             // Immediate subfolder name (Unicode NFC preserved)
    val folderUriString: String,  // SAF DocumentFile URI of the subfolder
    val songs: List<Song>,        // Only songs directly inside this subfolder
    val isFavorites: Boolean = name.equals(FAVORITES_FOLDER_NAME, ignoreCase = false)
) {
    companion object {
        const val FAVORITES_FOLDER_NAME = "Favorites"
    }
}

/**
 * Complete snapshot of the strict two-level Music directory scan (Section 2 & 3).
 */
data class LibraryCatalog(
    val playlists: List<Playlist>,        // Section 1 of Library: Immediate subfolders (Favorites prioritized/accessible)
    val standaloneSongs: List<Song>,      // Section 2 of Library: Only root-level audio files inside Music/
    val favoritesPlaylist: Playlist?      // Direct reference to the dedicated Favorites subfolder playlist
)

/**
 * Represents an entry inside the persistent playback queue (Sections 5, 6, 7, 8).
 * A song can exist in the queue with a unique queueEntryId and a status that tracks
 * whether it was already played, skipped, currently playing, or upcoming.
 */
data class QueueEntry(
    val queueEntryId: String = UUID.randomUUID().toString(),
    val song: Song,
    val status: QueueItemStatus = QueueItemStatus.UPCOMING
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("queueEntryId", queueEntryId)
        put("song", song.toJson())
        put("status", status.name)
    }

    companion object {
        fun fromJson(obj: JSONObject): QueueEntry = QueueEntry(
            queueEntryId = obj.getString("queueEntryId"),
            song = Song.fromJson(obj.getJSONObject("song")),
            status = QueueItemStatus.valueOf(obj.optString("status", QueueItemStatus.UPCOMING.name))
        )
    }
}

/**
 * Immutable snapshot of the Queue and Shuffle state (Sections 5, 6, 7, 8).
 * Strictly separates:
 * - [queueOrder]: The persistent, user-visible order of songs (including PLAYED, SKIPPED, CURRENT, and UPCOMING).
 * - [playbackOrderIds]: The effective upcoming playback sequence of queueEntryIds (which differs when shuffle is enabled).
 */
data class QueueSnapshot(
    val queueOrder: List<QueueEntry>,
    val currentEntryId: String?,
    val isShuffleEnabled: Boolean,
    val playbackOrderIds: List<String>,
    val lastPlaybackPositionMs: Long = 0L
) {
    val currentEntry: QueueEntry?
        get() = queueOrder.firstOrNull { it.queueEntryId == currentEntryId }

    val currentIndexInQueue: Int
        get() = queueOrder.indexOfFirst { it.queueEntryId == currentEntryId }

    fun toJson(): JSONObject = JSONObject().apply {
        val qArr = JSONArray()
        queueOrder.forEach { qArr.put(it.toJson()) }
        put("queueOrder", qArr)
        put("currentEntryId", currentEntryId ?: JSONObject.NULL)
        put("isShuffleEnabled", isShuffleEnabled)
        val pArr = JSONArray()
        playbackOrderIds.forEach { pArr.put(it) }
        put("playbackOrderIds", pArr)
        put("lastPlaybackPositionMs", lastPlaybackPositionMs)
    }

    companion object {
        fun fromJson(obj: JSONObject): QueueSnapshot {
            val qArr = obj.optJSONArray("queueOrder") ?: JSONArray()
            val entries = mutableListOf<QueueEntry>()
            for (i in 0 until qArr.length()) {
                entries.add(QueueEntry.fromJson(qArr.getJSONObject(i)))
            }
            val pArr = obj.optJSONArray("playbackOrderIds") ?: JSONArray()
            val pIds = mutableListOf<String>()
            for (i in 0 until pArr.length()) {
                pIds.add(pArr.getString(i))
            }
            val curId = if (obj.isNull("currentEntryId")) null else obj.optString("currentEntryId")
            return QueueSnapshot(
                queueOrder = entries,
                currentEntryId = curId,
                isShuffleEnabled = obj.optBoolean("isShuffleEnabled", false),
                playbackOrderIds = pIds,
                lastPlaybackPositionMs = obj.optLong("lastPlaybackPositionMs", 0L)
            )
        }
    }
}
