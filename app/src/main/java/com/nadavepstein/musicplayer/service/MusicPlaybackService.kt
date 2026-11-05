package com.nadavepstein.musicplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.nadavepstein.musicplayer.R
import com.nadavepstein.musicplayer.data.AppPreferences
import com.nadavepstein.musicplayer.model.QueueSnapshot
import com.nadavepstein.musicplayer.model.Song
import com.nadavepstein.musicplayer.queue.QueueManager
import com.nadavepstein.musicplayer.ui.MainActivity
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Native Android Background Music Playback Service optimized for Xiaomi POCO X7 Pro
 * (Android 15 / API 35, HyperOS 2).
 *
 * Features:
 * - Uses native Android [MediaPlayer] with `PARTIAL_WAKE_LOCK` so playback continues smoothly
 *   when the screen is locked, in Completely Black Screen Mode, or when another app is in front.
 * - Full [AudioFocusRequest] handling: pauses on incoming phone calls or transient loss,
 *   ducks volume gracefully on transient notification sounds, and resumes when focus returns.
 * - Handles `ACTION_AUDIO_BECOMING_NOISY` (headphone unplug / Bluetooth disconnect) by pausing safely.
 * - Persists queue order, playback order, shuffle state, and playback position automatically.
 */
class MusicPlaybackService : Service(), AudioManager.OnAudioFocusChangeListener {

    inner class LocalBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
    }

    interface PlaybackListener {
        fun onQueueAndStateChanged(
            snapshot: QueueSnapshot,
            isPlaying: Boolean,
            currentPositionMs: Long,
            durationMs: Long
        )
        fun onPlaybackError(message: String)
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<PlaybackListener>()

    private lateinit var prefs: AppPreferences
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaSession: MediaSessionCompat? = null
    private var mediaPlayer: MediaPlayer? = null

    val queueManager = QueueManager()

    private var isPlayingState = false
    private var isPrepared = false
    private var resumeOnFocusGain = false
    private var currentDurationMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            if (player != null && isPrepared && isPlayingState) {
                val pos = try {
                    player.currentPosition.toLong()
                } catch (_: Exception) {
                    0L
                }
                queueManager.updatePlaybackPosition(pos)
                notifyListeners()
            }
            mainHandler.postDelayed(this, 250L)
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pausePlayback()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        initMediaSession()

        registerReceiver(
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        )

        // Restore persisted queue state on startup
        prefs.loadQueueSnapshot()?.let { saved ->
            queueManager.restoreSnapshot(saved)
        }

        mainHandler.post(progressRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNextInOrder()
            ACTION_PAUSE -> pausePlayback()
            ACTION_PLAY -> resumePlayback()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressRunnable)
        persistCurrentState()
        try {
            unregisterReceiver(becomingNoisyReceiver)
        } catch (_: Exception) {
        }
        abandonAudioFocus()
        releaseMediaPlayer()
        mediaSession?.release()
        super.onDestroy()
    }

    fun registerListener(listener: PlaybackListener) {
        listeners.add(listener)
        listener.onQueueAndStateChanged(
            snapshot = queueManager.getSnapshot(),
            isPlaying = isPlayingState,
            currentPositionMs = getCurrentPositionMs(),
            durationMs = getDurationMs()
        )
    }

    fun unregisterListener(listener: PlaybackListener) {
        listeners.remove(listener)
    }

    fun playSongs(songs: List<Song>, startIndex: Int = 0) {
        val firstSong = queueManager.setQueueAndPlay(songs, startIndex)
        persistCurrentState()
        if (firstSong != null) {
            prepareAndStartSong(firstSong, startPositionMs = 0L, playImmediately = true)
        } else {
            notifyListeners()
        }
    }

    fun selectQueueEntry(queueEntryId: String) {
        val targetSong = queueManager.selectSongInQueue(queueEntryId)
        persistCurrentState()
        if (targetSong != null) {
            prepareAndStartSong(targetSong, startPositionMs = 0L, playImmediately = true)
        } else {
            notifyListeners()
        }
    }

    fun togglePlayPause() {
        if (isPlayingState) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    fun resumePlayback() {
        val current = queueManager.currentSong()
        if (current == null) {
            val next = queueManager.advanceToNextSong()
            if (next != null) {
                prepareAndStartSong(next, 0L, true)
            }
            return
        }

        if (!isPrepared || mediaPlayer == null) {
            val savedPos = queueManager.getSnapshot().lastPlaybackPositionMs
            prepareAndStartSong(current, savedPos, true)
            return
        }

        if (requestAudioFocus()) {
            try {
                mediaPlayer?.start()
                isPlayingState = true
                startForegroundWithNotification()
                updateMediaSessionState()
                persistCurrentState()
                notifyListeners()
            } catch (e: Exception) {
                notifyError("Failed to resume playback: ${e.localizedMessage}")
            }
        }
    }

    fun pausePlayback() {
        resumeOnFocusGain = false
        val player = mediaPlayer
        if (player != null && isPrepared && isPlayingState) {
            try {
                queueManager.updatePlaybackPosition(player.currentPosition.toLong())
                player.pause()
            } catch (_: Exception) {
            }
        }
        isPlayingState = false
        updateMediaSessionState()
        updateNotification()
        persistCurrentState()
        notifyListeners()
    }

    fun seekTo(positionMs: Long) {
        val player = mediaPlayer
        val safePos = positionMs.coerceIn(0L, getDurationMs().coerceAtLeast(0L))
        queueManager.updatePlaybackPosition(safePos)
        if (player != null && isPrepared) {
            try {
                player.seekTo(safePos.toInt())
            } catch (_: Exception) {
            }
        }
        persistCurrentState()
        notifyListeners()
    }

    fun toggleShuffle() {
        queueManager.toggleShuffle()
        persistCurrentState()
        notifyListeners()
    }

    fun playNextInQueue(songs: List<Song>) {
        val hadCurrent = queueManager.currentSong() != null
        queueManager.playNext(songs)
        persistCurrentState()
        if (!hadCurrent) {
            queueManager.currentSong()?.let { prepareAndStartSong(it, 0L, true) }
        } else {
            notifyListeners()
        }
    }

    fun addToEndOfQueue(songs: List<Song>) {
        val hadCurrent = queueManager.currentSong() != null
        queueManager.addToQueue(songs)
        persistCurrentState()
        if (!hadCurrent) {
            queueManager.currentSong()?.let { prepareAndStartSong(it, 0L, true) }
        } else {
            notifyListeners()
        }
    }

    fun reorderUpcomingSong(fromIndex: Int, toIndex: Int): Boolean {
        val moved = queueManager.moveUpcomingSong(fromIndex, toIndex)
        if (moved) {
            persistCurrentState()
            notifyListeners()
        }
        return moved
    }

    fun removeEntryFromQueue(queueEntryId: String) {
        val wasPlayingBefore = isPlayingState
        val result = queueManager.removeFromQueue(queueEntryId)
        persistCurrentState()
        if (result.wasCurrentSongRemoved) {
            if (result.newCurrentSong != null) {
                prepareAndStartSong(result.newCurrentSong, 0L, wasPlayingBefore)
            } else {
                releaseMediaPlayer()
                isPlayingState = false
                updateMediaSessionState()
                updateNotification()
                notifyListeners()
            }
        } else {
            notifyListeners()
        }
    }

    fun playNextInOrder() {
        val nextSong = queueManager.advanceToNextSong()
        persistCurrentState()
        if (nextSong != null) {
            prepareAndStartSong(nextSong, 0L, true)
        } else {
            // End of queue reached: stop playback cleanly while keeping all songs in visible queue
            releaseMediaPlayer()
            isPlayingState = false
            updateMediaSessionState()
            updateNotification()
            notifyListeners()
        }
    }

    fun getCurrentPositionMs(): Long {
        val player = mediaPlayer
        return if (player != null && isPrepared) {
            try {
                player.currentPosition.toLong()
            } catch (_: Exception) {
                queueManager.getSnapshot().lastPlaybackPositionMs
            }
        } else {
            queueManager.getSnapshot().lastPlaybackPositionMs
        }
    }

    fun getDurationMs(): Long {
        val player = mediaPlayer
        return if (player != null && isPrepared && currentDurationMs > 0L) {
            currentDurationMs
        } else {
            queueManager.currentSong()?.durationMs ?: 0L
        }
    }

    private fun prepareAndStartSong(song: Song, startPositionMs: Long, playImmediately: Boolean) {
        releaseMediaPlayer()
        isPrepared = false
        isPlayingState = false

        val uri = Uri.parse(song.uriString)
        val player = MediaPlayer()
        mediaPlayer = player

        try {
            player.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(applicationContext, uri)
            player.setOnPreparedListener { mp ->
                isPrepared = true
                currentDurationMs = mp.duration.toLong().coerceAtLeast(song.durationMs)
                if (startPositionMs > 0L && startPositionMs < currentDurationMs) {
                    mp.seekTo(startPositionMs.toInt())
                }
                if (playImmediately && requestAudioFocus()) {
                    mp.start()
                    isPlayingState = true
                    startForegroundWithNotification()
                }
                updateMediaSessionMetadata(song)
                updateMediaSessionState()
                persistCurrentState()
                notifyListeners()
            }
            player.setOnCompletionListener {
                // Automatically advance to the next song in effective playback order
                playNextInOrder()
            }
            player.setOnErrorListener { _, what, extra ->
                isPrepared = false
                isPlayingState = false
                notifyError("Skipped unreadable or corrupted audio file '${song.displayTitle}' (code $what/$extra).")
                // Gracefully advance to the next song if a file is missing or corrupted
                mainHandler.post { playNextInOrder() }
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            notifyError("Cannot play '${song.displayTitle}': ${e.localizedMessage ?: "File missing or inaccessible"}")
            mainHandler.post { playNextInOrder() }
        }
    }

    private fun releaseMediaPlayer() {
        isPrepared = false
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (_: Exception) {
            }
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(this, mainHandler)
            .build()

        audioFocusRequest = req
        val res = audioManager.requestAudioFocus(req)
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = isPlayingState
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mediaPlayer?.setVolume(0.25f, 0.25f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1.0f, 1.0f)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resumePlayback()
                }
            }
        }
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "PocoX7ProOfflineMusicPlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = playNextInOrder()
                override fun onSeekTo(pos: Long) = seekTo(pos)
            })
            isActive = true
        }
    }

    private fun updateMediaSessionMetadata(song: Song) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.displayTitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDurationMs())
            .build()
        mediaSession?.setMetadata(metadata)
    }

    private fun updateMediaSessionState() {
        val state = if (isPlayingState) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, getCurrentPositionMs(), 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val songTitle = queueManager.currentSong()?.displayTitle ?: getString(R.string.app_name)
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, MusicPlaybackService::class.java).apply {
            action = ACTION_TOGGLE_PLAY_PAUSE
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlayingState) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseLabel = if (isPlayingState) "Pause" else "Play"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(songTitle)
            .setContentIntent(contentPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlayingState)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(playPauseIcon, playPauseLabel, togglePendingIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Offline Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Minimalist background music playback notification"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun persistCurrentState() {
        prefs.saveQueueSnapshot(queueManager.getSnapshot())
    }

    private fun notifyListeners() {
        val snap = queueManager.getSnapshot()
        val pos = getCurrentPositionMs()
        val dur = getDurationMs()
        listeners.forEach {
            it.onQueueAndStateChanged(snap, isPlayingState, pos, dur)
        }
    }

    private fun notifyError(msg: String) {
        listeners.forEach { it.onPlaybackError(msg) }
    }

    companion object {
        private const val CHANNEL_ID = "poco_x7_pro_music_playback"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_TOGGLE_PLAY_PAUSE = "com.nadavepstein.musicplayer.ACTION_TOGGLE_PLAY_PAUSE"
        const val ACTION_PLAY = "com.nadavepstein.musicplayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.nadavepstein.musicplayer.ACTION_PAUSE"
        const val ACTION_NEXT = "com.nadavepstein.musicplayer.ACTION_NEXT"
    }
}
