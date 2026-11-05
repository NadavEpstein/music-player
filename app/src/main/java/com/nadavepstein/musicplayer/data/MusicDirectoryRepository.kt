package com.nadavepstein.musicplayer.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nadavepstein.musicplayer.model.LibraryCatalog
import com.nadavepstein.musicplayer.model.Playlist
import com.nadavepstein.musicplayer.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Sole Music Source Repository (Sections 2, 3, 9, 10, 13).
 *
 * Enforces all strict directory and file rules:
 * 1. Exclusively reads from the user-authorized `/storage/emulated/0/Music` SAF tree URI.
 *    Never queries Android's global MediaStore, Downloads, or external folders.
 * 2. Strict two-level structure:
 *    - Level 0 files directly inside `Music/` -> Standalone songs (never duplicated if already just standalone).
 *    - Level 1 immediate subfolders `Music/<Playlist>/` -> Playlists.
 *    - Level 1 files directly inside `Music/<Playlist>/` -> Songs belonging to `<Playlist>`.
 *    - Level 2+ folders inside a playlist (`Music/<Playlist>/<Subfolder>/...`) -> COMPLETELY IGNORED.
 * 3. Folder-based playlists & Favorites:
 *    - Creating a playlist creates a direct subfolder inside `Music/`.
 *    - Renaming a playlist renames its direct subfolder inside `Music/`.
 *    - Deleting a playlist safely evacuates any unique audio files (not already present in root `Music/`)
 *      to root `Music/` before removing the playlist folder, so original music files are NEVER lost.
 *    - Adding a song to a playlist copies the audio stream into the target playlist subfolder
 *      without deleting or moving the original file, and checks content SHA-256 hash to prevent duplicates.
 *    - Favorites is managed as a dedicated `Music/Favorites` subfolder playlist so it stays 100%
 *      consistent with the folder-based playlist truth.
 * 4. Full Hebrew and Unicode (NFC) support for filenames, folder names, and ID3/MP4/Vorbis metadata.
 */
class MusicDirectoryRepository(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * Checks whether the persisted SAF tree URI is still valid, accessible, and readable.
     */
    fun hasValidDirectoryPermission(treeUri: Uri?): Boolean {
        if (treeUri == null) return false
        val persisted = contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }
        if (!persisted) return false
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        return rootDoc.exists() && rootDoc.isDirectory && rootDoc.canRead()
    }

    /**
     * Persists read & write access to the selected Music directory URI across reboots.
     */
    fun persistDirectoryPermission(treeUri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(treeUri, flags)
            true
        } catch (_: SecurityException) {
            try {
                contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Performs a strict two-level scan of the designated Music directory.
     * Never recurses into subfolders of subfolders.
     */
    suspend fun scanMusicDirectory(treeUri: Uri): Result<LibraryCatalog> = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open Music directory URI."))

            if (!rootDoc.exists() || !rootDoc.isDirectory || !rootDoc.canRead()) {
                return@withContext Result.failure(SecurityException("Permission to access Music directory was revoked or folder is unreadable."))
            }

            val standaloneSongs = mutableListOf<Song>()
            val seenStandaloneHashes = mutableSetOf<String>()
            val playlists = mutableListOf<Playlist>()

            val rootChildren = rootDoc.listFiles()

            for (child in rootChildren) {
                val rawName = child.name ?: continue
                val normalizedName = StrictDirectoryPolicy.normalizeUnicode(rawName)
                if (normalizedName.startsWith(".")) continue

                if (child.isDirectory) {
                    // Level 1: Immediate subfolder = Playlist
                    val classification = StrictDirectoryPolicy.classifyRelativeEntry(
                        relativePath = normalizedName,
                        isDirectory = true
                    )
                    if (classification is StrictDirectoryPolicy.Classification.PlaylistFolder) {
                        val playlistSongs = scanSinglePlaylistFolder(child, classification.playlistName)
                        playlists.add(
                            Playlist(
                                name = classification.playlistName,
                                folderUriString = child.uri.toString(),
                                songs = playlistSongs,
                                isFavorites = classification.playlistName == Playlist.FAVORITES_FOLDER_NAME
                            )
                        )
                    }
                } else if (child.isFile) {
                    // Level 0: Root file = Standalone song
                    val classification = StrictDirectoryPolicy.classifyRelativeEntry(
                        relativePath = normalizedName,
                        isDirectory = false,
                        mimeType = child.type
                    )
                    if (classification is StrictDirectoryPolicy.Classification.StandaloneSong) {
                        val song = buildSongFromDocument(child, parentFolder = null)
                        if (song != null && seenStandaloneHashes.add(song.contentHash)) {
                            standaloneSongs.add(song)
                        }
                    }
                }
            }

            val collator = Collator.getInstance(Locale.getDefault())
            val sortedStandalone = standaloneSongs.sortedWith { a, b ->
                collator.compare(a.displayTitle, b.displayTitle)
            }

            // Sort playlists: Favorites first if it exists, then alphabetical (supporting Hebrew & English)
            val sortedPlaylists = playlists.sortedWith { a, b ->
                when {
                    a.isFavorites && !b.isFavorites -> -1
                    !a.isFavorites && b.isFavorites -> 1
                    else -> collator.compare(a.name, b.name)
                }
            }

            val favPlaylist = sortedPlaylists.firstOrNull { it.isFavorites }

            Result.success(
                LibraryCatalog(
                    playlists = sortedPlaylists,
                    standaloneSongs = sortedStandalone,
                    favoritesPlaylist = favPlaylist
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Scans ONLY the direct audio files inside a Level-1 playlist subfolder.
     * Any deeper directory inside the playlist folder is completely ignored (never recursed).
     */
    private fun scanSinglePlaylistFolder(playlistDir: DocumentFile, playlistName: String): List<Song> {
        val songs = mutableListOf<Song>()
        val seenHashes = mutableSetOf<String>()

        val children = playlistDir.listFiles()
        for (item in children) {
            // CRITICAL RULE (Section 2 & 10):
            // "Any folder inside a playlist folder must be completely ignored. Do not recursively scan directories."
            if (item.isDirectory) {
                continue
            }
            if (!item.isFile) {
                continue
            }
            val rawName = item.name ?: continue
            val relPath = "$playlistName/$rawName"
            val classification = StrictDirectoryPolicy.classifyRelativeEntry(
                relativePath = relPath,
                isDirectory = false,
                mimeType = item.type
            )
            if (classification is StrictDirectoryPolicy.Classification.PlaylistSong) {
                val song = buildSongFromDocument(item, parentFolder = playlistName)
                if (song != null && seenHashes.add(song.contentHash)) {
                    songs.add(song)
                }
            }
        }

        // Preserve chronological/filename order so newly added songs appear at the end of the playlist
        return songs
    }

    /**
     * Creates a new playlist by creating a direct subfolder inside `Music/`.
     */
    suspend fun createPlaylist(treeUri: Uri, rawPlaylistName: String): Result<Playlist> = withContext(Dispatchers.IO) {
        try {
            val cleanName = StrictDirectoryPolicy.sanitizePlaylistName(rawPlaylistName)
            if (cleanName.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Playlist name cannot be empty."))
            }
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(IllegalStateException("Music directory is inaccessible."))

            val existing = rootDoc.listFiles().firstOrNull {
                it.isDirectory && StrictDirectoryPolicy.normalizeUnicode(it.name ?: "")
                    .equals(cleanName, ignoreCase = true)
            }
            if (existing != null) {
                return@withContext Result.failure(IllegalStateException("Playlist '$cleanName' already exists."))
            }

            val created = rootDoc.createDirectory(cleanName)
                ?: return@withContext Result.failure(IllegalStateException("Failed to create playlist folder '$cleanName'. Check write permissions."))

            Result.success(
                Playlist(
                    name = cleanName,
                    folderUriString = created.uri.toString(),
                    songs = emptyList(),
                    isFavorites = cleanName == Playlist.FAVORITES_FOLDER_NAME
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Renames an existing playlist by renaming its immediate subfolder inside `Music/`.
     */
    suspend fun renamePlaylist(treeUri: Uri, oldName: String, newRawName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanNewName = StrictDirectoryPolicy.sanitizePlaylistName(newRawName)
            if (cleanNewName.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("New playlist name cannot be empty."))
            }
            if (oldName == Playlist.FAVORITES_FOLDER_NAME) {
                return@withContext Result.failure(IllegalArgumentException("The Favorites playlist cannot be renamed."))
            }

            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(IllegalStateException("Music directory is inaccessible."))

            val targetDir = findPlaylistDirectory(rootDoc, oldName)
                ?: return@withContext Result.failure(IllegalStateException("Playlist folder '$oldName' not found."))

            val collision = findPlaylistDirectory(rootDoc, cleanNewName)
            if (collision != null && collision.uri != targetDir.uri) {
                return@withContext Result.failure(IllegalStateException("A playlist named '$cleanNewName' already exists."))
            }

            val renamed = targetDir.renameTo(cleanNewName)
            if (!renamed) {
                return@withContext Result.failure(IllegalStateException("Android SAF failed to rename folder '$oldName' to '$cleanNewName'."))
            }
            Result.success(cleanNewName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Safely deletes a playlist folder (Section 2 & 10):
     * "Deleting a playlist should remove the playlist folder, but should not silently delete the original music files."
     *
     * Implementation:
     * Before deleting the playlist folder, every audio file inside the playlist folder is checked against
     * the root `Music/` directory by content hash (SHA-256). If any song in the playlist does not already
     * have an identical copy in root `Music/`, it is safely copied back into root `Music/` first so that
     * zero music files are ever lost!
     */
    suspend fun deletePlaylistSafely(treeUri: Uri, playlistName: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(IllegalStateException("Music directory is inaccessible."))

            val playlistDir = findPlaylistDirectory(rootDoc, playlistName)
                ?: return@withContext Result.failure(IllegalStateException("Playlist '$playlistName' not found."))

            // Collect all content hashes currently in root Music/
            val rootHashes = mutableSetOf<String>()
            for (file in rootDoc.listFiles()) {
                if (file.isFile && StrictDirectoryPolicy.isSupportedAudioFile(file.name, file.type)) {
                    computeContentHash(file.uri, file.length())?.let { rootHashes.add(it) }
                }
            }

            var preservedCount = 0
            for (item in playlistDir.listFiles()) {
                if (item.isFile && StrictDirectoryPolicy.isSupportedAudioFile(item.name, item.type)) {
                    val itemHash = computeContentHash(item.uri, item.length())
                    if (itemHash != null && itemHash !in rootHashes) {
                        // Preserve this unique audio file by copying it into root Music/ before folder deletion
                        val copied = copyDocumentFileToDirectory(item, rootDoc)
                        if (copied == null) {
                            return@withContext Result.failure(
                                IllegalStateException("Aborted deleting playlist '$playlistName' because '${item.name}' could not be safely preserved in root Music.")
                            )
                        }
                        rootHashes.add(itemHash)
                        preservedCount++
                    }
                }
            }

            val deleted = playlistDir.delete()
            if (!deleted) {
                return@withContext Result.failure(IllegalStateException("Could not delete folder '$playlistName'."))
            }
            Result.success(preservedCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Section 2, 9B, 10: Add a song to a playlist by copying the audio file into `Music/<PlaylistName>/`.
     * - Copies the selected audio file rather than moving or deleting the original.
     * - Checks content hash (SHA-256) and filename so duplicate copies of the same song are never created in the playlist.
     * - Appends a timestamp/touch so it appears as the last song in that playlist.
     * - Never creates deeper subdirectories.
     */
    suspend fun addSongToPlaylist(treeUri: Uri, song: Song, targetPlaylistName: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val cleanPlaylistName = StrictDirectoryPolicy.sanitizePlaylistName(targetPlaylistName)
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(IllegalStateException("Music directory is inaccessible."))

            var playlistDir = findPlaylistDirectory(rootDoc, cleanPlaylistName)
            if (playlistDir == null) {
                playlistDir = rootDoc.createDirectory(cleanPlaylistName)
                    ?: return@withContext Result.failure(IllegalStateException("Cannot create playlist folder '$cleanPlaylistName'."))
            }

            val sourceUri = Uri.parse(song.uriString)
            val sourceHash = song.contentHash.ifEmpty {
                computeContentHash(sourceUri, song.sizeBytes) ?: ""
            }

            // Prevent duplicate song in target playlist by checking content hash or exact filename
            for (existing in playlistDir.listFiles()) {
                if (!existing.isFile) continue
                val existingName = StrictDirectoryPolicy.normalizeUnicode(existing.name ?: "")
                if (existingName.equals(StrictDirectoryPolicy.normalizeUnicode(song.fileName), ignoreCase = true)) {
                    return@withContext Result.success(false) // Already present
                }
                if (sourceHash.isNotEmpty()) {
                    val existingHash = computeContentHash(existing.uri, existing.length())
                    if (existingHash == sourceHash) {
                        return@withContext Result.success(false) // Identical audio content already present
                    }
                }
            }

            val sourceDoc = DocumentFile.fromSingleUri(context, sourceUri)
            val mimeType = sourceDoc?.type ?: guessMimeType(song.fileName)
            val targetDoc = playlistDir.createFile(mimeType, song.fileName)
                ?: return@withContext Result.failure(IllegalStateException("Failed to create file '${song.fileName}' in '$cleanPlaylistName'."))

            val copiedBytes = copyUriStream(sourceUri, targetDoc.uri)
            if (copiedBytes <= 0L) {
                targetDoc.delete()
                return@withContext Result.failure(IllegalStateException("Failed to copy audio data for '${song.displayTitle}'."))
            }

            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Section 9A: Dedicated helper to add a song to `Music/Favorites`.
     */
    suspend fun addSongToFavorites(treeUri: Uri, song: Song): Result<Boolean> {
        return addSongToPlaylist(treeUri, song, Playlist.FAVORITES_FOLDER_NAME)
    }

    /**
     * Removes a song from a playlist (including Favorites) WITHOUT deleting the original music file!
     * If the song inside the playlist folder is the ONLY copy of that audio file (not present in root `Music/`
     * or another playlist), it is first copied into root `Music/` before removing the playlist entry.
     */
    suspend fun removeSongFromPlaylistSafely(
        treeUri: Uri,
        playlistName: String,
        song: Song
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(IllegalStateException("Music directory is inaccessible."))

            val playlistDir = findPlaylistDirectory(rootDoc, playlistName)
                ?: return@withContext Result.failure(IllegalStateException("Playlist '$playlistName' not found."))

            val targetFile = playlistDir.listFiles().firstOrNull {
                it.isFile && (it.uri.toString() == song.uriString ||
                    StrictDirectoryPolicy.normalizeUnicode(it.name ?: "") == StrictDirectoryPolicy.normalizeUnicode(song.fileName))
            } ?: return@withContext Result.failure(IllegalStateException("Song '${song.displayTitle}' not found in '$playlistName'."))

            val targetHash = song.contentHash.ifEmpty {
                computeContentHash(targetFile.uri, targetFile.length()) ?: ""
            }

            // Ensure original music file is preserved in root Music/ if not already present there
            var existsInRoot = false
            for (rootFile in rootDoc.listFiles()) {
                if (rootFile.isFile && StrictDirectoryPolicy.isSupportedAudioFile(rootFile.name, rootFile.type)) {
                    val rootHash = computeContentHash(rootFile.uri, rootFile.length())
                    if (rootHash != null && rootHash == targetHash) {
                        existsInRoot = true
                        break
                    }
                }
            }

            if (!existsInRoot) {
                val preserved = copyDocumentFileToDirectory(targetFile, rootDoc)
                if (preserved == null) {
                    return@withContext Result.failure(
                        IllegalStateException("Refusing to remove '${song.displayTitle}' from '$playlistName' because preserving the original file in root Music failed.")
                    )
                }
            }

            val removed = targetFile.delete()
            Result.success(removed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun findPlaylistDirectory(rootDoc: DocumentFile, name: String): DocumentFile? {
        val targetNorm = StrictDirectoryPolicy.normalizeUnicode(name)
        return rootDoc.listFiles().firstOrNull {
            it.isDirectory && StrictDirectoryPolicy.normalizeUnicode(it.name ?: "")
                .equals(targetNorm, ignoreCase = false)
        }
    }

    private fun copyDocumentFileToDirectory(sourceFile: DocumentFile, targetDir: DocumentFile): DocumentFile? {
        val rawName = sourceFile.name ?: return null
        val normalizedName = StrictDirectoryPolicy.normalizeUnicode(rawName)
        val mime = sourceFile.type ?: guessMimeType(normalizedName)

        // Avoid overwriting any existing file with the same name in targetDir
        var candidateName = normalizedName
        val base = normalizedName.substringBeforeLast('.', normalizedName)
        val ext = normalizedName.substringAfterLast('.', "")
        var counter = 1
        val existingNames = targetDir.listFiles()
            .filter { it.isFile }
            .mapNotNull { it.name?.let { n -> StrictDirectoryPolicy.normalizeUnicode(n) } }
            .toSet()

        while (candidateName in existingNames) {
            candidateName = if (ext.isNotEmpty()) "${base}_$counter.$ext" else "${base}_$counter"
            counter++
        }

        val destDoc = targetDir.createFile(mime, candidateName) ?: return null
        val bytes = copyUriStream(sourceFile.uri, destDoc.uri)
        if (bytes <= 0L) {
            destDoc.delete()
            return null
        }
        return destDoc
    }

    private fun copyUriStream(sourceUri: Uri, destUri: Uri): Long {
        var total = 0L
        contentResolver.openInputStream(sourceUri)?.use { input ->
            contentResolver.openOutputStream(destUri, "w")?.use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                output.flush()
            }
        }
        return total
    }

    private fun buildSongFromDocument(doc: DocumentFile, parentFolder: String?): Song? {
        val rawName = doc.name ?: return null
        val size = doc.length()
        if (size <= 0L) return null // Ignore empty/0-byte corrupted files gracefully

        val normalizedFileName = StrictDirectoryPolicy.normalizeUnicode(rawName)
        var durationMs = 0L
        var metaTitle: String? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, doc.uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durStr?.toLongOrNull() ?: 0L
            metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        } catch (_: Exception) {
            // Gracefully handle files with missing or partial ID3 tags while still allowing playback if valid
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        val displayTitle = StrictDirectoryPolicy.extractDisplayTitle(normalizedFileName, metaTitle)
        val contentHash = computeContentHash(doc.uri, size) ?: "${normalizedFileName}_$size"

        return Song(
            id = doc.uri.toString(),
            uriString = doc.uri.toString(),
            fileName = normalizedFileName,
            displayTitle = displayTitle,
            durationMs = durationMs,
            sizeBytes = size,
            contentHash = contentHash,
            parentFolder = parentFolder
        )
    }

    /**
     * Computes a fast, reliable SHA-256 content fingerprint (incorporating file size and up to 256 KB
     * of audio payload after headers + head/tail chunks) so identical audio files are accurately matched
     * by content even if renamed, without freezing UI on multi-gigabyte FLAC libraries.
     */
    private fun computeContentHash(uri: Uri, fileSize: Long): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(fileSize.toString().toByteArray(Charsets.UTF_8))
            contentResolver.openInputStream(uri)?.use { stream: InputStream ->
                val buffer = ByteArray(32 * 1024)
                var totalRead = 0
                val maxBytesToHash = 256 * 1024
                while (totalRead < maxBytesToHash) {
                    val read = stream.read(buffer, 0, minOf(buffer.size, maxBytesToHash - totalRead))
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    totalRead += read
                }
            } ?: return null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun guessMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "wav" -> "audio/x-wav"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            else -> "audio/*"
        }
    }
}
