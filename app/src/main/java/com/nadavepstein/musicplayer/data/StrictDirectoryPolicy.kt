package com.nadavepstein.musicplayer.data

import java.text.Normalizer
import java.util.Locale

/**
 * Pure, deterministic policy enforcing Section 2, 3, 10, and 13 requirements:
 * - Strictly 2 directory levels:
 *     Level 0: Files directly inside root `Music/` -> Standalone songs
 *     Level 1: Immediate subfolders `Music/<Playlist>/` -> Playlists, and audio files directly inside them
 *     Level 2+: Anything deeper (`Music/<Playlist>/<Subfolder>/...`) -> COMPLETELY IGNORED.
 * - Supported native Android audio formats (MP3, M4A/AAC, FLAC, WAV, OGG, OPUS).
 * - Full Hebrew and Unicode NFC filename normalization and RTL awareness.
 */
object StrictDirectoryPolicy {

    val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "mp4", "3gp", "amr"
    )

    val SUPPORTED_MIME_PREFIXES = setOf(
        "audio/"
    )

    val SUPPORTED_EXACT_MIMES = setOf(
        "application/ogg",
        "application/opus",
        "application/x-flac"
    )

    /**
     * Checks whether a filename or MIME type represents a supported audio file.
     * Hidden/system files starting with '.' are ignored.
     */
    fun isSupportedAudioFile(fileName: String?, mimeType: String? = null): Boolean {
        if (fileName.isNullOrBlank()) return false
        val trimmed = fileName.trim()
        if (trimmed.startsWith(".")) return false
        val ext = trimmed.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in SUPPORTED_EXTENSIONS) {
            return true
        }
        if (mimeType != null) {
            val lowerMime = mimeType.lowercase(Locale.ROOT)
            if (SUPPORTED_MIME_PREFIXES.any { lowerMime.startsWith(it) } || lowerMime in SUPPORTED_EXACT_MIMES) {
                // Still require a known extension so non-audio containers or corrupt temp files aren't misclassified
                return ext in SUPPORTED_EXTENSIONS
            }
        }
        return false
    }

    /**
     * Validates whether a relative path inside the designated Music directory is permitted
     * under the strict two-level hierarchy rule.
     *
     * Examples:
     * - "Song 1.mp3" -> depth 0 file -> STANDALONE_SONG
     * - "Classical" (directory) -> depth 0 folder -> PLAYLIST_FOLDER
     * - "Classical/Song 4.mp3" -> depth 1 file -> PLAYLIST_SONG("Classical")
     * - "Rock/Unwanted Subfolder" -> depth 1 folder -> IGNORED_DEEPER_ENTRY
     * - "Rock/Unwanted Subfolder/Song 8.mp3" -> depth 2 file -> IGNORED_DEEPER_ENTRY
     */
    sealed class Classification {
        object StandaloneSong : Classification()
        data class PlaylistFolder(val playlistName: String) : Classification()
        data class PlaylistSong(val playlistName: String) : Classification()
        object IgnoredDeeperOrUnsupported : Classification()
    }

    fun classifyRelativeEntry(
        relativePath: String,
        isDirectory: Boolean,
        mimeType: String? = null
    ): Classification {
        val cleaned = relativePath.trim().trimStart('/', '\').replace('\', '/')
        if (cleaned.isEmpty()) return Classification.IgnoredDeeperOrUnsupported

        val segments = cleaned.split('/').filter { it.isNotEmpty() }
        // Reject hidden segments
        if (segments.any { it.startsWith(".") }) {
            return Classification.IgnoredDeeperOrUnsupported
        }

        return when (segments.size) {
            1 -> {
                val name = normalizeUnicode(segments[0])
                if (isDirectory) {
                    Classification.PlaylistFolder(name)
                } else if (isSupportedAudioFile(name, mimeType)) {
                    Classification.StandaloneSong
                } else {
                    Classification.IgnoredDeeperOrUnsupported
                }
            }
            2 -> {
                // Depth 1 inside an immediate subfolder: ONLY direct audio files are allowed.
                // Any directory at depth 1 (i.e. a subfolder inside a playlist folder) is strictly ignored!
                if (isDirectory) {
                    Classification.IgnoredDeeperOrUnsupported
                } else {
                    val playlistName = normalizeUnicode(segments[0])
                    val fileName = normalizeUnicode(segments[1])
                    if (isSupportedAudioFile(fileName, mimeType)) {
                        Classification.PlaylistSong(playlistName)
                    } else {
                        Classification.IgnoredDeeperOrUnsupported
                    }
                }
            }
            else -> {
                // Any depth >= 2 is strictly ignored!
                Classification.IgnoredDeeperOrUnsupported
            }
        }
    }

    /**
     * Normalizes Hebrew and mixed Unicode strings to canonical NFC form and strips extension for display.
     */
    fun normalizeUnicode(input: String): String {
        return Normalizer.normalize(input.trim(), Normalizer.Form.NFC)
    }

    fun extractDisplayTitle(fileName: String, metadataTitle: String? = null): String {
        val candidate = if (!metadataTitle.isNullOrBlank()) {
            metadataTitle.trim()
        } else {
            fileName.substringBeforeLast('.', fileName).trim()
        }
        return normalizeUnicode(candidate)
    }

    /**
     * Detects whether a string starts with or predominantly contains Hebrew/RTL characters.
     */
    fun containsHebrewOrRtl(text: String): Boolean {
        for (ch in text) {
            val block = Character.UnicodeBlock.of(ch)
            if (block == Character.UnicodeBlock.HEBREW ||
                block == Character.UnicodeBlock.ARABIC ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Sanitizes a user-entered playlist folder name while preserving Hebrew, English, numbers, and spaces.
     */
    fun sanitizePlaylistName(rawName: String): String {
        val normalized = normalizeUnicode(rawName)
        // Disallow path separators and reserved characters
        val cleaned = normalized.replace(Regex("[/\\:*?"<>|]"), "").trim()
        return cleaned
    }
}
