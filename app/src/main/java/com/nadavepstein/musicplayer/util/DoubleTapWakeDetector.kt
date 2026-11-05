package com.nadavepstein.musicplayer.util

import kotlin.math.hypot

/**
 * Pure, deterministic double-tap wake detector for Section 12 (Completely Black Screen Mode).
 *
 * Requirements enforced:
 * - A single tap NEVER wakes the screen.
 * - Accidental screen brush / noise (< minIntervalMs or > maxDistancePx) does NOT wake the screen.
 * - Only two consecutive deliberate taps within [minIntervalMs..maxIntervalMs] and within
 *   [maxDistancePx] spatial proximity trigger wake.
 */
class DoubleTapWakeDetector(
    private val minIntervalMs: Long = 60L,
    private val maxIntervalMs: Long = 380L,
    private val maxDistancePx: Float = 140f
) {
    private var lastTapTimeMs: Long = -1L
    private var lastTapX: Float = 0f
    private var lastTapY: Float = 0f

    /**
     * Processes a tap-up gesture at coordinates ([x], [y]) and timestamp [eventTimeMs].
     * Returns `true` ONLY when a valid second consecutive tap completes a double-tap.
     */
    fun onTapUp(eventTimeMs: Long, x: Float, y: Float): Boolean {
        if (lastTapTimeMs < 0L) {
            lastTapTimeMs = eventTimeMs
            lastTapX = x
            lastTapY = y
            return false
        }

        val deltaMs = eventTimeMs - lastTapTimeMs
        val distance = hypot((x - lastTapX).toDouble(), (y - lastTapY).toDouble()).toFloat()

        if (deltaMs in minIntervalMs..maxIntervalMs && distance <= maxDistancePx) {
            reset()
            return true
        }

        // If the second tap was too slow or too far away, treat it as a fresh first tap
        // (unless it was instantaneous noise < minIntervalMs, in which case ignore noise).
        if (deltaMs > maxIntervalMs || distance > maxDistancePx) {
            lastTapTimeMs = eventTimeMs
            lastTapX = x
            lastTapY = y
        }
        return false
    }

    fun reset() {
        lastTapTimeMs = -1L
        lastTapX = 0f
        lastTapY = 0f
    }
}
