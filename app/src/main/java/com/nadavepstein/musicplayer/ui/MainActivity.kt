package com.nadavepstein.musicplayer.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nadavepstein.musicplayer.R
import com.nadavepstein.musicplayer.data.AppPreferences
import com.nadavepstein.musicplayer.data.MusicDirectoryRepository
import com.nadavepstein.musicplayer.model.AppTheme
import com.nadavepstein.musicplayer.model.LibraryCatalog
import com.nadavepstein.musicplayer.model.Playlist
import com.nadavepstein.musicplayer.model.QueueEntry
import com.nadavepstein.musicplayer.model.QueueSnapshot
import com.nadavepstein.musicplayer.model.Song
import com.nadavepstein.musicplayer.service.MusicPlaybackService
import com.nadavepstein.musicplayer.util.DoubleTapWakeDetector
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : AppCompatActivity(), MusicPlaybackService.PlaybackListener {

    private lateinit var prefs: AppPreferences
    private lateinit var repository: MusicDirectoryRepository

    private var playbackService: MusicPlaybackService? = null
    private var isServiceBound = false

    // Root & Main Playback Views
    private lateinit var rootContainer: FrameLayout
    private lateinit var topNavigationRow: LinearLayout
    private lateinit var btnOpenLibrary: TextView
    private lateinit var btnOpenFavorites: TextView
    private lateinit var btnToggleTheme: TextView
    private lateinit var topCenteringSpacer: View
    private lateinit var bottomCenteringSpacer: View
    private lateinit var playbackControlsCluster: LinearLayout
    private lateinit var tvCurrentSongTitle: TextView
    private lateinit var playbackProgressBar: ProgressBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var bottomUtilityRow: LinearLayout
    private lateinit var btnBlackScreenMode: TextView

    // Expandable Queue Views (Section 5, 6, 8, 9)
    private lateinit var queueHandleTouchArea: LinearLayout
    private lateinit var queueHandleLine: View
    private lateinit var expandedQueueContainer: LinearLayout
    private lateinit var tvQueueHeader: TextView
    private lateinit var tvQueueCount: TextView
    private lateinit var rvQueue: RecyclerView
    private lateinit var queueAdapter: QueueAdapter
    private lateinit var queueItemTouchHelper: ItemTouchHelper
    private var isQueueExpanded = false

    // Dedicated Library & Playlist Views (Section 3 & 10)
    private lateinit var libraryScreenOverlay: LinearLayout
    private lateinit var btnCloseLibraryOrBack: TextView
    private lateinit var tvLibraryScreenTitle: TextView
    private lateinit var btnCreatePlaylist: TextView
    private lateinit var btnRefreshLibrary: TextView
    private lateinit var etLibrarySearch: EditText
    private lateinit var playlistDetailActionsRow: LinearLayout
    private lateinit var btnPlayEntirePlaylist: TextView
    private lateinit var btnPlaylistPlayNext: TextView
    private lateinit var btnPlaylistAddToQueue: TextView
    private lateinit var rvLibraryContent: RecyclerView
    private lateinit var libraryAdapter: LibraryAdapter

    // Completely Black Screen Mode Views & State (Section 12)
    private lateinit var blackoutScreenOverlay: View
    private val doubleTapWakeDetector = DoubleTapWakeDetector()
    private var isBlackoutModeActive = false

    // Current application state
    private var currentTheme: AppTheme = AppTheme.DARK
    private var currentCatalog: LibraryCatalog = LibraryCatalog(emptyList(), emptyList(), null)
    private var currentlyOpenPlaylist: Playlist? = null
    private var currentSearchQuery: String = ""

    // SAF Directory Picker for `/storage/emulated/0/Music`
    private val selectMusicDirectoryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val treeUri = result.data?.data
        if (treeUri != null) {
            val persisted = repository.persistDirectoryPermission(treeUri)
            if (persisted) {
                prefs.musicTreeUri = treeUri
                refreshLibraryFromDisk(showToastOnSuccess = true)
            } else {
                showMessage("Could not persist folder permission. Please select /storage/emulated/0/Music again.")
            }
        } else if (!repository.hasValidDirectoryPermission(prefs.musicTreeUri)) {
            promptForMusicDirectoryAccess()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MusicPlaybackService.LocalBinder ?: return
            playbackService = binder.getService()
            isServiceBound = true
            playbackService?.registerListener(this@MainActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService?.unregisterListener(this@MainActivity)
            playbackService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPreferences(this)
        repository = MusicDirectoryRepository(this)
        currentTheme = prefs.theme

        bindViews()
        setupQueueRecyclerViewAndGestures()
        setupLibraryRecyclerViewAndActions()
        setupBlackoutOverlayGesture()
        setupBackNavigation()

        applyTheme(currentTheme)

        // Start & bind background playback service
        val serviceIntent = Intent(this, MusicPlaybackService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Verify designated Music folder permission on launch
        checkOrRequestMusicDirectoryAccess()
    }

    override fun onResume() {
        super.onResume()
        // Automatically refresh library when application is reopened (Section 3: Library updates)
        if (repository.hasValidDirectoryPermission(prefs.musicTreeUri)) {
            refreshLibraryFromDisk(showToastOnSuccess = false)
        }
    }

    override fun onDestroy() {
        if (isServiceBound) {
            playbackService?.unregisterListener(this)
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }

    private fun bindViews() {
        rootContainer = findViewById(R.id.rootContainer)
        topNavigationRow = findViewById(R.id.topNavigationRow)
        btnOpenLibrary = findViewById(R.id.btnOpenLibrary)
        btnOpenFavorites = findViewById(R.id.btnOpenFavorites)
        btnToggleTheme = findViewById(R.id.btnToggleTheme)
        topCenteringSpacer = findViewById(R.id.topCenteringSpacer)
        bottomCenteringSpacer = findViewById(R.id.bottomCenteringSpacer)
        playbackControlsCluster = findViewById(R.id.playbackControlsCluster)
        tvCurrentSongTitle = findViewById(R.id.tvCurrentSongTitle)
        playbackProgressBar = findViewById(R.id.playbackProgressBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnShuffle = findViewById(R.id.btnShuffle)
        bottomUtilityRow = findViewById(R.id.bottomUtilityRow)
        btnBlackScreenMode = findViewById(R.id.btnBlackScreenMode)

        queueHandleTouchArea = findViewById(R.id.queueHandleTouchArea)
        queueHandleLine = findViewById(R.id.queueHandleLine)
        expandedQueueContainer = findViewById(R.id.expandedQueueContainer)
        tvQueueHeader = findViewById(R.id.tvQueueHeader)
        tvQueueCount = findViewById(R.id.tvQueueCount)
        rvQueue = findViewById(R.id.rvQueue)

        libraryScreenOverlay = findViewById(R.id.libraryScreenOverlay)
        btnCloseLibraryOrBack = findViewById(R.id.btnCloseLibraryOrBack)
        tvLibraryScreenTitle = findViewById(R.id.tvLibraryScreenTitle)
        btnCreatePlaylist = findViewById(R.id.btnCreatePlaylist)
        btnRefreshLibrary = findViewById(R.id.btnRefreshLibrary)
        etLibrarySearch = findViewById(R.id.etLibrarySearch)
        playlistDetailActionsRow = findViewById(R.id.playlistDetailActionsRow)
        btnPlayEntirePlaylist = findViewById(R.id.btnPlayEntirePlaylist)
        btnPlaylistPlayNext = findViewById(R.id.btnPlaylistPlayNext)
        btnPlaylistAddToQueue = findViewById(R.id.btnPlaylistAddToQueue)
        rvLibraryContent = findViewById(R.id.rvLibraryContent)

        blackoutScreenOverlay = findViewById(R.id.blackoutScreenOverlay)

        // Main playback controls
        btnPlayPause.setOnClickListener {
            playbackService?.togglePlayPause()
        }
        btnShuffle.setOnClickListener {
            playbackService?.toggleShuffle()
        }

        // Top navigation controls
        btnOpenLibrary.setOnClickListener {
            openLibraryScreen(initialPlaylist = null)
        }
        btnOpenFavorites.setOnClickListener {
            openFavoritesScreen()
        }
        btnToggleTheme.setOnClickListener {
            val nextTheme = if (currentTheme == AppTheme.DARK) AppTheme.LIGHT else AppTheme.DARK
            currentTheme = nextTheme
            prefs.theme = nextTheme
            applyTheme(nextTheme)
        }

        // Completely Black Screen Mode button (Section 12)
        btnBlackScreenMode.setOnClickListener {
            enterBlackoutScreenMode()
        }
    }

    /**
     * Section 5, 6, 8, 9: Expandable Queue Gesture, Drag-and-Drop Reordering, and Long-Press Menu.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupQueueRecyclerViewAndGestures() {
        queueAdapter = QueueAdapter(
            onSongTap = { entry ->
                // Section 6: Tapping any song in the expanded queue immediately makes it CURRENT
                // and skips over intervening songs without deleting them.
                playbackService?.selectQueueEntry(entry.queueEntryId)
            },
            onSongLongPress = { anchorView, entry ->
                // Section 9: Long-Press Song Actions in Expanded Queue
                showQueueItemContextualMenu(anchorView, entry)
            },
            onStartDrag = { viewHolder ->
                queueItemTouchHelper.startDrag(viewHolder)
            }
        )

        rvQueue.layoutManager = LinearLayoutManager(this)
        rvQueue.adapter = queueAdapter

        // Section 8C: Drag-and-drop manual reordering of upcoming songs
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            private var dragFromIndex = -1
            private var dragToIndex = -1

            override fun isLongPressDragEnabled(): Boolean = false // Uses dedicated drag handle so long-press opens the contextual menu!

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val pos = viewHolder.bindingAdapterPosition
                return if (queueAdapter.isPositionDraggableUpcoming(pos)) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    0
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (!queueAdapter.isPositionDraggableUpcoming(fromPos) ||
                    !queueAdapter.isPositionDraggableUpcoming(toPos)
                ) {
                    return false
                }
                if (dragFromIndex == -1) {
                    dragFromIndex = fromPos
                }
                dragToIndex = toPos

                val movedPreview = queueAdapter.onItemMovePreview(fromPos, toPos)
                if (movedPreview) {
                    playbackService?.reorderUpcomingSong(fromPos, toPos)
                }
                return movedPreview
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // No swipe-to-delete; removal is explicit via long-press menu
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.78f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                dragFromIndex = -1
                dragToIndex = -1
            }
        }

        queueItemTouchHelper = ItemTouchHelper(callback)
        queueItemTouchHelper.attachToRecyclerView(rvQueue)

        // Section 5: Smooth upward/downward drag on the bottom white horizontal line handle
        var touchStartY = 0f
        var isDraggingHandle = false

        queueHandleTouchArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartY = event.rawY
                    isDraggingHandle = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingHandle) return@setOnTouchListener false
                    val deltaY = event.rawY - touchStartY
                    // Dragging upwards by >= 36px expands queue; dragging downwards by >= 36px collapses queue
                    if (!isQueueExpanded && deltaY < -36f) {
                        isDraggingHandle = false
                        setQueueExpandedState(expanded = true, animate = true)
                    } else if (isQueueExpanded && deltaY > 36f) {
                        isDraggingHandle = false
                        setQueueExpandedState(expanded = false, animate = true)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingHandle = false
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Smoothly transitions between the minimalist centered playback layout (collapsed)
     * and the YouTube Music-style expanded queue layout where playback controls sit at the top
     * and the queue occupies the space below.
     */
    private fun setQueueExpandedState(expanded: Boolean, animate: Boolean) {
        if (isQueueExpanded == expanded) return
        isQueueExpanded = expanded

        val totalHeight = rootContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val targetQueueHeight = if (expanded) (totalHeight * 0.66f).toInt() else 0

        if (expanded) {
            expandedQueueContainer.visibility = View.VISIBLE
            topCenteringSpacer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0f
            )
            bottomCenteringSpacer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0f
            )
        } else {
            topCenteringSpacer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            bottomCenteringSpacer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        if (!animate) {
            val lp = expandedQueueContainer.layoutParams
            lp.height = targetQueueHeight
            expandedQueueContainer.layoutParams = lp
            if (!expanded) {
                expandedQueueContainer.visibility = View.GONE
            }
            return
        }

        val startHeight = expandedQueueContainer.height
        ValueAnimator.ofInt(startHeight, targetQueueHeight).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val h = anim.animatedValue as Int
                val lp = expandedQueueContainer.layoutParams
                lp.height = h
                expandedQueueContainer.layoutParams = lp
                if (!expanded && h == 0) {
                    expandedQueueContainer.visibility = View.GONE
                }
            }
            start()
        }
    }

    /**
     * Section 9: Long-Press Song Actions in the Expanded Queue.
     * Displays a contextual menu containing strictly:
     * 1. Add to Favorites
     * 2. Add to Playlist
     * 3. Remove from Queue
     */
    private fun showQueueItemContextualMenu(anchorView: View, entry: QueueEntry) {
        val popup = PopupMenu(this, anchorView, Gravity.END)
        popup.menu.add(0, MENU_QUEUE_ADD_FAVORITES, 0, getString(R.string.add_to_favorites))
        popup.menu.add(0, MENU_QUEUE_ADD_PLAYLIST, 1, getString(R.string.add_to_playlist))
        popup.menu.add(0, MENU_QUEUE_REMOVE, 2, getString(R.string.remove_from_queue))

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_QUEUE_ADD_FAVORITES -> {
                    addSongToFavoritesFolder(entry.song)
                    true
                }
                MENU_QUEUE_ADD_PLAYLIST -> {
                    showSelectPlaylistDialogForSong(entry.song)
                    true
                }
                MENU_QUEUE_REMOVE -> {
                    playbackService?.removeEntryFromQueue(entry.queueEntryId)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * Section 3 & 10: Music Library and Playlist Management Interface.
     */
    private fun setupLibraryRecyclerViewAndActions() {
        libraryAdapter = LibraryAdapter(
            onPlaylistClick = { playlist ->
                openPlaylistDetailView(playlist)
            },
            onPlaylistOptionsClick = { anchor, playlist ->
                showPlaylistManagementMenu(anchor, playlist)
            },
            onSongClick = { _, contextSongs, index ->
                // Tapping a song in Library or Playlist starts playing immediately
                playbackService?.playSongs(contextSongs, index)
                closeLibraryScreen()
            },
            onSongOptionsClick = { anchor, song, enclosingPlaylist ->
                showLibrarySongActionsMenu(anchor, song, enclosingPlaylist)
            }
        )

        rvLibraryContent.layoutManager = LinearLayoutManager(this)
        rvLibraryContent.adapter = libraryAdapter

        btnCloseLibraryOrBack.setOnClickListener {
            if (currentlyOpenPlaylist != null) {
                // Return from Playlist Detail view to main Library overview
                currentlyOpenPlaylist = null
                renderLibraryContent()
            } else {
                closeLibraryScreen()
            }
        }

        btnCreatePlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }

        btnRefreshLibrary.setOnClickListener {
            refreshLibraryFromDisk(showToastOnSuccess = true)
        }

        etLibrarySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                renderLibraryContent()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Playlist Detail bulk actions (Play All, Play Next, Add to Queue)
        btnPlayEntirePlaylist.setOnClickListener {
            val pl = currentlyOpenPlaylist ?: return@setOnClickListener
            if (pl.songs.isEmpty()) {
                showMessage("Playlist '${pl.name}' is empty.")
            } else {
                playbackService?.playSongs(pl.songs, 0)
                closeLibraryScreen()
            }
        }

        btnPlaylistPlayNext.setOnClickListener {
            val pl = currentlyOpenPlaylist ?: return@setOnClickListener
            if (pl.songs.isNotEmpty()) {
                playbackService?.playNextInQueue(pl.songs)
                showMessage("Added '${pl.name}' to Play Next.")
            }
        }

        btnPlaylistAddToQueue.setOnClickListener {
            val pl = currentlyOpenPlaylist ?: return@setOnClickListener
            if (pl.songs.isNotEmpty()) {
                playbackService?.addToEndOfQueue(pl.songs)
                showMessage("Added '${pl.name}' to Queue.")
            }
        }
    }

    private fun openLibraryScreen(initialPlaylist: Playlist?) {
        currentlyOpenPlaylist = initialPlaylist
        libraryScreenOverlay.visibility = View.VISIBLE
        renderLibraryContent()
    }

    private fun openFavoritesScreen() {
        val treeUri = prefs.musicTreeUri
        if (!repository.hasValidDirectoryPermission(treeUri)) {
            promptForMusicDirectoryAccess()
            return
        }
        val fav = currentCatalog.favoritesPlaylist ?: Playlist(
            name = Playlist.FAVORITES_FOLDER_NAME,
            folderUriString = "",
            songs = emptyList(),
            isFavorites = true
        )
        openLibraryScreen(initialPlaylist = fav)
    }

    private fun openPlaylistDetailView(playlist: Playlist) {
        currentlyOpenPlaylist = playlist
        renderLibraryContent()
    }

    private fun closeLibraryScreen() {
        libraryScreenOverlay.visibility = View.GONE
    }

    private fun renderLibraryContent() {
        val query = currentSearchQuery
        val openPl = currentlyOpenPlaylist

        if (openPl == null) {
            btnCloseLibraryOrBack.text = "← Player"
            tvLibraryScreenTitle.text = "LIBRARY"
            playlistDetailActionsRow.visibility = View.GONE

            val filteredPlaylists = if (query.isEmpty()) {
                currentCatalog.playlists
            } else {
                currentCatalog.playlists.filter { it.name.contains(query, ignoreCase = true) }
            }

            val filteredStandalone = if (query.isEmpty()) {
                currentCatalog.standaloneSongs
            } else {
                currentCatalog.standaloneSongs.filter {
                    it.displayTitle.contains(query, ignoreCase = true) ||
                        it.fileName.contains(query, ignoreCase = true)
                }
            }

            libraryAdapter.submitRootLibrary(filteredPlaylists, filteredStandalone, currentTheme)
        } else {
            // Refresh open playlist reference from latest catalog
            val latestPl = currentCatalog.playlists.firstOrNull { it.name == openPl.name } ?: openPl
            currentlyOpenPlaylist = latestPl

            btnCloseLibraryOrBack.text = getString(R.string.back_to_library)
            tvLibraryScreenTitle.text = latestPl.name
            playlistDetailActionsRow.visibility = View.VISIBLE

            val filteredSongs = if (query.isEmpty()) {
                latestPl.songs
            } else {
                latestPl.songs.filter {
                    it.displayTitle.contains(query, ignoreCase = true) ||
                        it.fileName.contains(query, ignoreCase = true)
                }
            }

            libraryAdapter.submitPlaylistDetail(latestPl, filteredSongs, currentTheme)
        }
    }

    private fun showPlaylistManagementMenu(anchor: View, playlist: Playlist) {
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menu.add(0, MENU_PL_PLAY_ALL, 0, getString(R.string.play_all))
        popup.menu.add(0, MENU_PL_PLAY_NEXT, 1, getString(R.string.play_next))
        popup.menu.add(0, MENU_PL_ADD_QUEUE, 2, getString(R.string.add_to_queue))
        if (!playlist.isFavorites) {
            popup.menu.add(0, MENU_PL_RENAME, 3, getString(R.string.rename_playlist))
            popup.menu.add(0, MENU_PL_DELETE, 4, getString(R.string.delete_playlist))
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_PL_PLAY_ALL -> {
                    if (playlist.songs.isNotEmpty()) {
                        playbackService?.playSongs(playlist.songs, 0)
                        closeLibraryScreen()
                    } else {
                        showMessage("Playlist '${playlist.name}' has no songs.")
                    }
                    true
                }
                MENU_PL_PLAY_NEXT -> {
                    if (playlist.songs.isNotEmpty()) {
                        playbackService?.playNextInQueue(playlist.songs)
                        showMessage("Added '${playlist.name}' to Play Next.")
                    }
                    true
                }
                MENU_PL_ADD_QUEUE -> {
                    if (playlist.songs.isNotEmpty()) {
                        playbackService?.addToEndOfQueue(playlist.songs)
                        showMessage("Added '${playlist.name}' to Queue.")
                    }
                    true
                }
                MENU_PL_RENAME -> {
                    showRenamePlaylistDialog(playlist)
                    true
                }
                MENU_PL_DELETE -> {
                    showDeletePlaylistConfirmationDialog(playlist)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showLibrarySongActionsMenu(anchor: View, song: Song, enclosingPlaylist: Playlist?) {
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menu.add(0, MENU_SONG_PLAY_NEXT, 0, getString(R.string.play_next))
        popup.menu.add(0, MENU_SONG_ADD_QUEUE, 1, getString(R.string.add_to_queue))
        popup.menu.add(0, MENU_SONG_ADD_FAVORITES, 2, getString(R.string.add_to_favorites))
        popup.menu.add(0, MENU_SONG_ADD_PLAYLIST, 3, getString(R.string.add_to_playlist))
        if (enclosingPlaylist != null) {
            val label = if (enclosingPlaylist.isFavorites) "Remove from Favorites" else getString(R.string.remove_from_playlist)
            popup.menu.add(0, MENU_SONG_REMOVE_FROM_PLAYLIST, 4, label)
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SONG_PLAY_NEXT -> {
                    playbackService?.playNextInQueue(listOf(song))
                    showMessage("Playing '${song.displayTitle}' next.")
                    true
                }
                MENU_SONG_ADD_QUEUE -> {
                    playbackService?.addToEndOfQueue(listOf(song))
                    showMessage("Added '${song.displayTitle}' to Queue.")
                    true
                }
                MENU_SONG_ADD_FAVORITES -> {
                    addSongToFavoritesFolder(song)
                    true
                }
                MENU_SONG_ADD_PLAYLIST -> {
                    showSelectPlaylistDialogForSong(song)
                    true
                }
                MENU_SONG_REMOVE_FROM_PLAYLIST -> {
                    if (enclosingPlaylist != null) {
                        removeSongFromPlaylistFolderSafely(enclosingPlaylist.name, song)
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showCreatePlaylistDialog() {
        val treeUri = prefs.musicTreeUri
        if (!repository.hasValidDirectoryPermission(treeUri) || treeUri == null) {
            promptForMusicDirectoryAccess()
            return
        }

        val input = EditText(this).apply {
            hint = "Playlist folder name (English / עברית)"
            textDirection = View.TEXT_DIRECTION_LOCALE
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Create New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text?.toString().orEmpty()
                lifecycleScope.launch {
                    val res = repository.createPlaylist(treeUri, name)
                    res.onSuccess { created ->
                        showMessage("Created playlist '${created.name}'.")
                        refreshLibraryFromDisk(showToastOnSuccess = false)
                    }.onFailure { err ->
                        showMessage(err.localizedMessage ?: "Failed to create playlist.")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenamePlaylistDialog(playlist: Playlist) {
        val treeUri = prefs.musicTreeUri ?: return
        val input = EditText(this).apply {
            setText(playlist.name)
            textDirection = View.TEXT_DIRECTION_LOCALE
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename Playlist '${playlist.name}'")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text?.toString().orEmpty()
                lifecycleScope.launch {
                    val res = repository.renamePlaylist(treeUri, playlist.name, newName)
                    res.onSuccess { updatedName ->
                        if (currentlyOpenPlaylist?.name == playlist.name) {
                            currentlyOpenPlaylist = playlist.copy(name = updatedName)
                        }
                        showMessage("Renamed to '$updatedName'.")
                        refreshLibraryFromDisk(showToastOnSuccess = false)
                    }.onFailure { err ->
                        showMessage(err.localizedMessage ?: "Failed to rename playlist.")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeletePlaylistConfirmationDialog(playlist: Playlist) {
        val treeUri = prefs.musicTreeUri ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete Playlist '${playlist.name}'?")
            .setMessage(
                "This removes the '${playlist.name}' playlist subfolder. Any unique audio files inside it that are not already in your root Music folder will be safely preserved in root Music so no songs are lost."
            )
            .setPositiveButton("Delete Playlist") { _, _ ->
                lifecycleScope.launch {
                    val res = repository.deletePlaylistSafely(treeUri, playlist.name)
                    res.onSuccess { preservedCount ->
                        if (currentlyOpenPlaylist?.name == playlist.name) {
                            currentlyOpenPlaylist = null
                        }
                        val note = if (preservedCount > 0) {
                            "Deleted '${playlist.name}' ($preservedCount unique song(s) preserved in Music)."
                        } else {
                            "Deleted playlist '${playlist.name}'."
                        }
                        showMessage(note)
                        refreshLibraryFromDisk(showToastOnSuccess = false)
                    }.onFailure { err ->
                        showMessage(err.localizedMessage ?: "Failed to delete playlist.")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSelectPlaylistDialogForSong(song: Song) {
        val treeUri = prefs.musicTreeUri
        if (!repository.hasValidDirectoryPermission(treeUri) || treeUri == null) {
            promptForMusicDirectoryAccess()
            return
        }

        val availablePlaylists = currentCatalog.playlists.filter { !it.isFavorites }
        if (availablePlaylists.isEmpty()) {
            showMessage("No playlists found. Create a playlist in Library first.")
            return
        }

        val names = availablePlaylists.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add '${song.displayTitle}' to Playlist")
            .setItems(names) { _, which ->
                val chosenPlaylistName = names[which]
                lifecycleScope.launch {
                    val res = repository.addSongToPlaylist(treeUri, song, chosenPlaylistName)
                    res.onSuccess { added ->
                        if (added) {
                            showMessage("Added '${song.displayTitle}' to '$chosenPlaylistName'.")
                            refreshLibraryFromDisk(showToastOnSuccess = false)
                        } else {
                            showMessage("'${song.displayTitle}' is already in '$chosenPlaylistName'.")
                        }
                    }.onFailure { err ->
                        showMessage(err.localizedMessage ?: "Could not copy song to playlist.")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addSongToFavoritesFolder(song: Song) {
        val treeUri = prefs.musicTreeUri
        if (!repository.hasValidDirectoryPermission(treeUri) || treeUri == null) {
            promptForMusicDirectoryAccess()
            return
        }

        lifecycleScope.launch {
            val res = repository.addSongToFavorites(treeUri, song)
            res.onSuccess { added ->
                if (added) {
                    showMessage("Added '${song.displayTitle}' to Favorites.")
                    refreshLibraryFromDisk(showToastOnSuccess = false)
                } else {
                    showMessage("'${song.displayTitle}' is already in Favorites.")
                }
            }.onFailure { err ->
                showMessage(err.localizedMessage ?: "Failed to add song to Favorites.")
            }
        }
    }

    private fun removeSongFromPlaylistFolderSafely(playlistName: String, song: Song) {
        val treeUri = prefs.musicTreeUri ?: return
        lifecycleScope.launch {
            val res = repository.removeSongFromPlaylistSafely(treeUri, playlistName, song)
            res.onSuccess {
                showMessage("Removed '${song.displayTitle}' from '$playlistName' (original file preserved).")
                refreshLibraryFromDisk(showToastOnSuccess = false)
            }.onFailure { err ->
                showMessage(err.localizedMessage ?: "Could not remove song from playlist.")
            }
        }
    }

    /**
     * Section 2: Requesting and Retaining Access to `/storage/emulated/0/Music` via SAF.
     */
    private fun checkOrRequestMusicDirectoryAccess() {
        val savedUri = prefs.musicTreeUri
        if (repository.hasValidDirectoryPermission(savedUri) && savedUri != null) {
            refreshLibraryFromDisk(showToastOnSuccess = false)
        } else {
            promptForMusicDirectoryAccess()
        }
    }

    private fun promptForMusicDirectoryAccess() {
        AlertDialog.Builder(this)
            .setTitle(R.string.select_music_folder_title)
            .setMessage(R.string.select_music_folder_message)
            .setCancelable(false)
            .setPositiveButton(R.string.select_folder_action) { _, _ ->
                launchMusicDirectoryPicker()
            }
            .show()
    }

    private fun launchMusicDirectoryPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            // Pre-point SAF directly to `/storage/emulated/0/Music` (primary:Music) on POCO X7 Pro
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic")
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
        selectMusicDirectoryLauncher.launch(intent)
    }

    private fun refreshLibraryFromDisk(showToastOnSuccess: Boolean) {
        val treeUri = prefs.musicTreeUri ?: return
        lifecycleScope.launch {
            val res = repository.scanMusicDirectory(treeUri)
            res.onSuccess { catalog ->
                currentCatalog = catalog
                renderLibraryContent()
                if (showToastOnSuccess) {
                    showMessage(
                        "Scanned Music: ${catalog.playlists.size} playlists, ${catalog.standaloneSongs.size} standalone songs."
                    )
                }
            }.onFailure { err ->
                showMessage(err.localizedMessage ?: "Music folder permission was revoked. Please reauthorize.")
                promptForMusicDirectoryAccess()
            }
        }
    }

    /**
     * Section 11: Apply Dark Mode or Light Mode consistently across the entire application.
     */
    private fun applyTheme(theme: AppTheme) {
        val palette = ThemePalette.forTheme(theme)

        window.statusBarColor = palette.backgroundColor
        window.navigationBarColor = palette.backgroundColor

        rootContainer.setBackgroundColor(palette.backgroundColor)
        libraryScreenOverlay.setBackgroundColor(palette.backgroundColor)

        val pillDrawable = { strokeColor: Int, fillColor: Int ->
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f
                setColor(fillColor)
                setStroke(2, strokeColor)
            }
        }

        btnOpenLibrary.background = pillDrawable(palette.accentColor, palette.surfaceBoxColor)
        btnOpenLibrary.setTextColor(palette.primaryTextColor)

        btnOpenFavorites.background = pillDrawable(palette.accentColor, palette.surfaceBoxColor)
        btnOpenFavorites.setTextColor(palette.primaryTextColor)

        btnToggleTheme.background = pillDrawable(palette.accentColor, palette.surfaceBoxColor)
        btnToggleTheme.setTextColor(palette.primaryTextColor)
        btnToggleTheme.text = if (theme == AppTheme.DARK) {
            getString(R.string.theme_dark)
        } else {
            getString(R.string.theme_light)
        }

        tvCurrentSongTitle.setTextColor(palette.primaryTextColor)
        playbackProgressBar.progressTintList = ColorStateList.valueOf(palette.primaryTextColor)
        playbackProgressBar.progressBackgroundTintList = ColorStateList.valueOf(palette.surfaceBoxColor)

        btnPlayPause.setColorFilter(palette.primaryTextColor)
        updateShuffleButtonAppearance(playbackService?.queueManager?.getSnapshot()?.isShuffleEnabled == true)

        btnBlackScreenMode.background = pillDrawable(palette.secondaryTextColor, palette.surfaceBoxColor)
        btnBlackScreenMode.setTextColor(palette.secondaryTextColor)

        // Bottom horizontal queue handle line: white in Dark Mode, high-contrast dark blue in Light Mode
        val handleShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f
            setColor(palette.queueHandleColor)
        }
        queueHandleLine.background = handleShape

        tvQueueHeader.setTextColor(palette.primaryTextColor)
        tvQueueCount.setTextColor(palette.secondaryTextColor)

        // Library header & buttons
        btnCloseLibraryOrBack.setTextColor(palette.primaryTextColor)
        tvLibraryScreenTitle.setTextColor(palette.primaryTextColor)
        btnCreatePlaylist.background = pillDrawable(palette.accentColor, palette.surfaceBoxColor)
        btnCreatePlaylist.setTextColor(palette.primaryTextColor)
        btnRefreshLibrary.background = pillDrawable(palette.accentColor, palette.surfaceBoxColor)
        btnRefreshLibrary.setTextColor(palette.primaryTextColor)

        etLibrarySearch.background = pillDrawable(palette.accentColor, palette.surfaceBoxColor)
        etLibrarySearch.setTextColor(palette.primaryTextColor)
        etLibrarySearch.setHintTextColor(palette.secondaryTextColor)

        btnPlayEntirePlaylist.background = pillDrawable(palette.accentColor, palette.surfaceBoxColor)
        btnPlayEntirePlaylist.setTextColor(palette.primaryTextColor)
        btnPlaylistPlayNext.background = pillDrawable(palette.accentColor, palette.surfaceBoxColor)
        btnPlaylistPlayNext.setTextColor(palette.primaryTextColor)
        btnPlaylistAddToQueue.background = pillDrawable(palette.accentColor, palette.surfaceBoxColor)
        btnPlaylistAddToQueue.setTextColor(palette.primaryTextColor)

        // Refresh adapters with new theme
        playbackService?.queueManager?.getSnapshot()?.let { snap ->
            queueAdapter.submitList(snap.queueOrder, theme)
        }
        renderLibraryContent()

        // Ensure Blackout Screen Overlay ALWAYS remains pure #000000 regardless of theme (Section 11 & 12)
        blackoutScreenOverlay.setBackgroundColor(Color.BLACK)
    }

    private fun updateShuffleButtonAppearance(isShuffleEnabled: Boolean) {
        val palette = ThemePalette.forTheme(currentTheme)
        if (isShuffleEnabled) {
            val activeBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.surfaceBoxColor)
                setStroke(2, palette.accentColor)
            }
            btnShuffle.background = activeBg
            btnShuffle.setColorFilter(palette.accentColor)
            btnShuffle.alpha = 1.0f
        } else {
            btnShuffle.setBackgroundColor(Color.TRANSPARENT)
            btnShuffle.setColorFilter(palette.secondaryTextColor)
            btnShuffle.alpha = 0.55f
        }
    }

    /**
     * Section 12: Completely Black Screen Mode.
     * - Covers the entire display with pure #000000 (on POCO X7 Pro's AMOLED panel, #000000 pixels
     *   turn off completely and emit zero visible light).
     * - Hides Android status bar and navigation bar completely using immersive sticky mode.
     * - Sets window screenBrightness to lowest possible (0.0f).
     * - Wakes ONLY on two consecutive taps verified by [DoubleTapWakeDetector]; single taps are ignored.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupBlackoutOverlayGesture() {
        blackoutScreenOverlay.setOnTouchListener { _, event ->
            if (!isBlackoutModeActive) return@setOnTouchListener false
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val shouldWake = doubleTapWakeDetector.onTapUp(
                    eventTimeMs = event.eventTime,
                    x = event.rawX,
                    y = event.rawY
                )
                if (shouldWake) {
                    exitBlackoutScreenMode()
                }
            }
            true
        }
    }

    private fun enterBlackoutScreenMode() {
        isBlackoutModeActive = true
        doubleTapWakeDetector.reset()
        blackoutScreenOverlay.setBackgroundColor(Color.BLACK)
        blackoutScreenOverlay.visibility = View.VISIBLE

        // Lower window brightness to minimum hardware level (0.0f)
        val params = window.attributes
        params.screenBrightness = 0.0f
        window.attributes = params

        // Hide system status bar & navigation bar completely so zero indicators emit light
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun exitBlackoutScreenMode() {
        isBlackoutModeActive = false
        doubleTapWakeDetector.reset()
        blackoutScreenOverlay.visibility = View.GONE

        // Restore default system brightness control
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params

        // Restore system bars
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isBlackoutModeActive -> {
                        // Do not exit blackout mode on accidental single back tap; require double tap
                    }
                    libraryScreenOverlay.visibility == View.VISIBLE -> {
                        if (currentlyOpenPlaylist != null) {
                            currentlyOpenPlaylist = null
                            renderLibraryContent()
                        } else {
                            closeLibraryScreen()
                        }
                    }
                    isQueueExpanded -> {
                        setQueueExpandedState(expanded = false, animate = true)
                    }
                    else -> {
                        moveTaskToBack(true)
                    }
                }
            }
        })
    }

    override fun onQueueAndStateChanged(
        snapshot: QueueSnapshot,
        isPlaying: Boolean,
        currentPositionMs: Long,
        durationMs: Long
    ) {
        runOnUiThread {
            val currentSong = snapshot.currentEntry?.song
            tvCurrentSongTitle.text = currentSong?.displayTitle ?: getString(R.string.no_song_selected)

            // Progress bar fills proportionally and reaches 1000/1000 when finished
            if (durationMs > 0L) {
                val progressPermille = ((currentPositionMs.toDouble() / durationMs.toDouble()) * 1000.0)
                    .toInt()
                    .coerceIn(0, 1000)
                playbackProgressBar.progress = progressPermille
            } else {
                playbackProgressBar.progress = 0
            }

            btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            updateShuffleButtonAppearance(snapshot.isShuffleEnabled)

            tvQueueCount.text = "${snapshot.queueOrder.size} songs"
            queueAdapter.submitList(snapshot.queueOrder, currentTheme)
        }
    }

    override fun onPlaybackError(message: String) {
        runOnUiThread {
            showMessage(message)
        }
    }

    private fun showMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MENU_QUEUE_ADD_FAVORITES = 101
        private const val MENU_QUEUE_ADD_PLAYLIST = 102
        private const val MENU_QUEUE_REMOVE = 103

        private const val MENU_PL_PLAY_ALL = 201
        private const val MENU_PL_PLAY_NEXT = 202
        private const val MENU_PL_ADD_QUEUE = 203
        private const val MENU_PL_RENAME = 204
        private const val MENU_PL_DELETE = 205

        private const val MENU_SONG_PLAY_NEXT = 301
        private const val MENU_SONG_ADD_QUEUE = 302
        private const val MENU_SONG_ADD_FAVORITES = 303
        private const val MENU_SONG_ADD_PLAYLIST = 304
        private const val MENU_SONG_REMOVE_FROM_PLAYLIST = 305
    }
}
