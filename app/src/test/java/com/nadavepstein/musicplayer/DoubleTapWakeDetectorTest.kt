package com.nadavepstein.musicplayer

import com.nadavepstein.musicplayer.util.DoubleTapWakeDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapWakeDetectorTest {

    @Test
    fun testSingleTapNeverWakesScreen() {
        val detector = DoubleTapWakeDetector()
        assertFalse(detector.onTapUp(1000L, 500f, 800f))
        // Second tap much later (1.5 seconds later) is treated as another single tap, NOT a double tap
        assertFalse(detector.onTapUp(2500L, 505f, 802f))
    }

    @Test
    fun testValidDoubleTapWakesScreen() {
        val detector = DoubleTapWakeDetector()
        assertFalse(detector.onTapUp(1000L, 500f, 800f))
        assertTrue(detector.onTapUp(1210L, 512f, 795f))
    }

    @Test
    fun testDistantTapsOrNoiseDoNotWakeScreen() {
        val detector = DoubleTapWakeDetector()
        assertFalse(detector.onTapUp(1000L, 100f, 100f))
        // Far away tap within time window does not wake
        assertFalse(detector.onTapUp(1200L, 800f, 1400f))
    }
}
