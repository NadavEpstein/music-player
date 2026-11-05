package com.nadavepstein.musicplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nadavepstein.musicplayer.R
import com.nadavepstein.musicplayer.model.AppTheme
import com.nadavepstein.musicplayer.model.Playlist
import com.nadavepstein.musicplayer.model.Song

/**
 * Renders the two distinct Library modes (Section 3 & 10):
 * 1. Root Library Overview:
 *    - Section 1: "Playlists" (all immediate subfolders inside Music/, with Favorites clearly marked)
 *    - Section 2: "Songs" (all standalone audio files directly inside root Music/)
 * 2. Playlist Detail View:
 *    - Displays all songs directly inside the selected playlist subfolder.
 */
class LibraryAdapter(
    private val onPlaylistClick: (Playlist) -> Unit,
    private val onPlaylistOptionsClick: (View, Playlist) -> Unit,
    private val onSongClick: (Song, List<Song>, Int) -> Unit,
    private val onSongOptionsClick: (View, Song, Playlist?) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class LibraryRow {
        data class SectionHeader(val title: String) : LibraryRow()
        data class PlaylistItem(val playlist: Playlist) : LibraryRow()
        data class SongItem(
            val song: Song,
            val contextList: List<Song>,
            val indexInContext: Int,
            val enclosingPlaylist: Playlist?
        ) : LibraryRow()
    }

    private val rows = mutableListOf<LibraryRow>()
    private var currentTheme: AppTheme = AppTheme.DARK

    fun submitRootLibrary(
        playlists: List<Playlist>,
        standaloneSongs: List<Song>,
        theme: AppTheme
    ) {
        currentTheme = theme
        rows.clear()

        rows.add(LibraryRow.SectionHeader("Playlists"))
        playlists.forEach { playlist ->
            rows.add(LibraryRow.PlaylistItem(playlist))
        }

        rows.add(LibraryRow.SectionHeader("Songs"))
        standaloneSongs.forEachIndexed { index, song ->
            rows.add(
                LibraryRow.SongItem(
                    song = song,
                    contextList = standaloneSongs,
                    indexInContext = index,
                    enclosingPlaylist = null
                )
            )
        }
        notifyDataSetChanged()
    }

    fun submitPlaylistDetail(playlist: Playlist, filteredSongs: List<Song>, theme: AppTheme) {
        currentTheme = theme
        rows.clear()
        rows.add(LibraryRow.SectionHeader("${playlist.name} (${filteredSongs.size})"))
        filteredSongs.forEachIndexed { index, song ->
            rows.add(
                LibraryRow.SongItem(
                    song = song,
                    contextList = filteredSongs,
                    indexInContext = index,
                    enclosingPlaylist = playlist
                )
            )
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is LibraryRow.SectionHeader -> VIEW_TYPE_HEADER
            is LibraryRow.PlaylistItem -> VIEW_TYPE_PLAYLIST
            is LibraryRow.SongItem -> VIEW_TYPE_SONG
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_library_header, parent, false))
            VIEW_TYPE_PLAYLIST -> PlaylistVH(inflater.inflate(R.layout.item_library_playlist, parent, false))
            else -> SongVH(inflater.inflate(R.layout.item_library_song, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val palette = ThemePalette.forTheme(currentTheme)
        when (val row = rows[position]) {
            is LibraryRow.SectionHeader -> {
                val vh = holder as HeaderVH
                vh.tvHeader.text = row.title
                vh.tvHeader.setTextColor(palette.secondaryTextColor)
            }
            is LibraryRow.PlaylistItem -> {
                val vh = holder as PlaylistVH
                val pl = row.playlist
                vh.tvTitle.text = pl.name
                vh.tvTitle.setTextColor(palette.primaryTextColor)
                vh.tvCount.text = "${pl.songs.size}"
                vh.tvCount.setTextColor(palette.secondaryTextColor)
                vh.ivIcon.setImageResource(if (pl.isFavorites) R.drawable.ic_favorite else R.drawable.ic_folder)
                vh.ivIcon.setColorFilter(if (pl.isFavorites) palette.accentColor else palette.secondaryTextColor)
                vh.btnOptions.setColorFilter(palette.secondaryTextColor)

                vh.root.setOnClickListener { onPlaylistClick(pl) }
                vh.btnOptions.setOnClickListener { v -> onPlaylistOptionsClick(v, pl) }
                vh.root.setOnLongClickListener { v ->
                    onPlaylistOptionsClick(v, pl)
                    true
                }
            }
            is LibraryRow.SongItem -> {
                val vh = holder as SongVH
                vh.tvTitle.text = row.song.displayTitle
                vh.tvTitle.setTextColor(palette.primaryTextColor)
                vh.btnOptions.setColorFilter(palette.secondaryTextColor)

                vh.root.setOnClickListener {
                    onSongClick(row.song, row.contextList, row.indexInContext)
                }
                vh.btnOptions.setOnClickListener { v ->
                    onSongOptionsClick(v, row.song, row.enclosingPlaylist)
                }
                vh.root.setOnLongClickListener { v ->
                    onSongOptionsClick(v, row.song, row.enclosingPlaylist)
                    true
                }
            }
        }
    }

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvHeader: TextView = view.findViewById(R.id.tvSectionHeaderTitle)
    }

    class PlaylistVH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.playlistItemRoot)
        val ivIcon: ImageView = view.findViewById(R.id.ivPlaylistIcon)
        val tvTitle: TextView = view.findViewById(R.id.tvPlaylistTitle)
        val tvCount: TextView = view.findViewById(R.id.tvPlaylistSongCount)
        val btnOptions: ImageButton = view.findViewById(R.id.btnPlaylistOptions)
    }

    class SongVH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.songItemRoot)
        val tvTitle: TextView = view.findViewById(R.id.tvSongTitle)
        val btnOptions: ImageButton = view.findViewById(R.id.btnSongOptions)
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_PLAYLIST = 1
        private const val VIEW_TYPE_SONG = 2
    }
}
