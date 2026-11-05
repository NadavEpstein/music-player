package com.nadavepstein.musicplayer.ui

import android.graphics.Color
import com.nadavepstein.musicplayer.model.AppTheme

/**
 * Defines the exact two appearance palettes required by Section 11:
 *
 * A. Dark Mode (Default):
 *    - Predominantly black backgrounds (#000000).
 *    - White text and icons (#FFFFFF).
 *    - Subtle dark blue / dark purple (#182038 / #2A1F4A) for frames, boxes, highlights, and accents.
 *
 * B. Light Mode:
 *    - Predominantly white backgrounds (#FFFFFF).
 *    - Black text and icons (#000000).
 *    - Subtle light blue (#DCEBFF / #2B6CB0) for frames, boxes, highlights, and accents.
 *
 * Note: The special Completely Black Screen Mode (Section 12) ALWAYS remains pure #000000
 * regardless of the selected theme.
 */
data class ThemePalette(
    val backgroundColor: Int,
    val surfaceBoxColor: Int,
    val primaryTextColor: Int,
    val secondaryTextColor: Int,
    val mutedPlayedTextColor: Int,
    val accentColor: Int,
    val currentRowHighlightColor: Int,
    val queueHandleColor: Int
) {
    companion object {
        fun forTheme(theme: AppTheme): ThemePalette {
            return when (theme) {
                AppTheme.DARK -> ThemePalette(
                    backgroundColor = Color.parseColor("#000000"),
                    surfaceBoxColor = Color.parseColor("#111626"),     // Subtle dark blue frame
                    primaryTextColor = Color.parseColor("#FFFFFF"),
                    secondaryTextColor = Color.parseColor("#A0AAB8"),
                    mutedPlayedTextColor = Color.parseColor("#5C6470"),
                    accentColor = Color.parseColor("#6B5B95"),          // Subtle dark purple/blue accent
                    currentRowHighlightColor = Color.parseColor("#1A2238"),
                    queueHandleColor = Color.parseColor("#FFFFFF")      // Thin white horizontal line at bottom
                )
                AppTheme.LIGHT -> ThemePalette(
                    backgroundColor = Color.parseColor("#FFFFFF"),
                    surfaceBoxColor = Color.parseColor("#EDF4FC"),     // Subtle light blue frame/box
                    primaryTextColor = Color.parseColor("#000000"),
                    secondaryTextColor = Color.parseColor("#4A5568"),
                    mutedPlayedTextColor = Color.parseColor("#8C98A8"),
                    accentColor = Color.parseColor("#2B6CB0"),          // Clean light-mode blue accent
                    currentRowHighlightColor = Color.parseColor("#DCEBFF"),
                    queueHandleColor = Color.parseColor("#1E3A5F")      // High-contrast handle in light mode (white on dark)
                )
            }
        }
    }
}
