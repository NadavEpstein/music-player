package com.nadavepstein.musicplayer

import com.nadavepstein.musicplayer.data.StrictDirectoryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictDirectoryPolicyTest {

    @Test
    fun testRootStandaloneSongsAreAccepted() {
        val c1 = StrictDirectoryPolicy.classifyRelativeEntry("Song 1.mp3", isDirectory = false)
        assertTrue(c1 is StrictDirectoryPolicy.Classification.StandaloneSong)

        val c2 = StrictDirectoryPolicy.classifyRelativeEntry("שיר ישראלי.flac", isDirectory = false)
        assertTrue(c2 is StrictDirectoryPolicy.Classification.StandaloneSong)
    }

    @Test
    fun testImmediateSubfoldersAreRecognizedAsPlaylists() {
        val c1 = StrictDirectoryPolicy.classifyRelativeEntry("Classical", isDirectory = true)
        assertEquals(StrictDirectoryPolicy.Classification.PlaylistFolder("Classical"), c1)

        val c2 = StrictDirectoryPolicy.classifyRelativeEntry("שירים בעברית", isDirectory = true)
        assertEquals(StrictDirectoryPolicy.Classification.PlaylistFolder("שירים בעברית"), c2)
    }

    @Test
    fun testSongsInsideImmediateSubfoldersBelongToPlaylist() {
        val c1 = StrictDirectoryPolicy.classifyRelativeEntry("Rock/Song 6.mp3", isDirectory = false)
        assertEquals(StrictDirectoryPolicy.Classification.PlaylistSong("Rock"), c1)

        val c2 = StrictDirectoryPolicy.classifyRelativeEntry("מוזיקה מזרחית/אייל גולן - צליל מיתר.m4a", isDirectory = false)
        assertEquals(StrictDirectoryPolicy.Classification.PlaylistSong("מוזיקה מזרחית"), c2)
    }

    @Test
    fun testDeeperSubfoldersAndSongsAreCompletelyIgnored() {
        // Folder inside a playlist folder (depth 1 directory)
        val deeperFolder = StrictDirectoryPolicy.classifyRelativeEntry("Rock/Unwanted Subfolder", isDirectory = true)
        assertEquals(StrictDirectoryPolicy.Classification.IgnoredDeeperOrUnsupported, deeperFolder)

        // Song inside a deeper folder (depth 2 file)
        val deeperSong = StrictDirectoryPolicy.classifyRelativeEntry("Rock/Unwanted Subfolder/Song 8.mp3", isDirectory = false)
        assertEquals(StrictDirectoryPolicy.Classification.IgnoredDeeperOrUnsupported, deeperSong)

        // Even deeper song (depth 3 file)
        val depth3Song = StrictDirectoryPolicy.classifyRelativeEntry("Rock/Sub1/Sub2/Deep.opus", isDirectory = false)
        assertEquals(StrictDirectoryPolicy.Classification.IgnoredDeeperOrUnsupported, depth3Song)
    }

    @Test
    fun testHebrewAndMixedUnicodeSupport() {
        val mixedTitle = StrictDirectoryPolicy.extractDisplayTitle("01 - עידן רייכל - Idan Raichel - ממעמקים.mp3")
        assertEquals("01 - עידן רייכל - Idan Raichel - ממעמקים", mixedTitle)
        assertTrue(StrictDirectoryPolicy.containsHebrewOrRtl(mixedTitle))
        assertFalse(StrictDirectoryPolicy.containsHebrewOrRtl("Pure English Song"))
    }
}
