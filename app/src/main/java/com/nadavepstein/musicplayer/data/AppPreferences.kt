package com.nadavepstein.musicplayer.data

import android.content.Context
import android.net.Uri
import com.nadavepstein.musicplayer.model.AppTheme
import com.nadavepstein.musicplayer.model.QueueSnapshot
import org.json.JSONObject

/**
 * Local offline storage for:
 * - Granted Storage Access Framework (SAF) tree URI for `/storage/emulated/0/Music` (Section 2).
 * - Selected appearance theme: DARK (default) or LIGHT (Section 11).
 * - Persistent Queue & Shuffle state across application restarts (Section 8E).
 */
class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var musicTreeUri: Uri?
        get() {
            val raw = prefs.getString(KEY_MUSIC_TREE_URI, null) ?: return null
            return try {
                Uri.parse(raw)
            } catch (_: Exception) {
                null
            }
        }
        set(value) {
            prefs.edit().putString(KEY_MUSIC_TREE_URI, value?.toString()).apply()
        }

    var theme: AppTheme
        get() {
            val raw = prefs.getString(KEY_THEME, AppTheme.DARK.name) ?: AppTheme.DARK.name
            return try {
                AppTheme.valueOf(raw)
            } catch (_: Exception) {
                AppTheme.DARK
            }
        }
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
        }

    fun saveQueueSnapshot(snapshot: QueueSnapshot) {
        try {
            prefs.edit().putString(KEY_QUEUE_SNAPSHOT, snapshot.toJson().toString()).apply()
        } catch (_: Exception) {
            // Ignore serialization edge cases
        }
    }

    fun loadQueueSnapshot(): QueueSnapshot? {
        val raw = prefs.getString(KEY_QUEUE_SNAPSHOT, null) ?: return null
        return try {
            QueueSnapshot.fromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "poco_x7_pro_offline_music_prefs"
        private const val KEY_MUSIC_TREE_URI = "music_tree_uri"
        private const val KEY_THEME = "app_theme"
        private const val KEY_QUEUE_SNAPSHOT = "queue_snapshot_json"
    }
}
