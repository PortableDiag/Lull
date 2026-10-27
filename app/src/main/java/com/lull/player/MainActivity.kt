package com.lull.player

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.common.util.concurrent.ListenableFuture
import com.lull.player.api.ApiService
import com.lull.player.api.ApiSettingsDialog
import com.lull.player.databinding.ActivityMainBinding
import com.lull.player.databinding.DialogCrossfadeBinding
import com.lull.player.databinding.DialogTempoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The library. Seven browse axes — tracks, folders, artists, albums, genres, playlists and rated —
 * over one MediaStore load, plus multi-selection and the playlist manager.
 *
 * Two list shapes share one RecyclerView: a [GroupAdapter] of folders/artists/albums/genres/
 * playlists/ratings, and a [TrackAdapter] of the tracks inside one of them. Tapping a group drills in;
 * Back comes out. [LibraryTab.TRACKS] is the flat list and has no group step.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var trackAdapter: TrackAdapter
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    /** The full device library, loaded once per resume. */
    private var library: List<AudioItem> = emptyList()
    private var libraryLoaded = false

    private var tab: LibraryTab = LibraryTab.TRACKS
    /** The group we have drilled into, or null while the group list itself is showing. */
    private var drill: Group? = null
    private var query: String = ""

    /** The groups of the current tab, and the tracks currently listed, both after the filter. */
    private var shownGroups: List<Group> = emptyList()
    private var shownTracks: List<AudioItem> = emptyList()

    private var restoredView = false
    private var reorderEnabled = false
    private var actionMode: ActionMode? = null

    /** An external open (file manager / share sheet) waiting for the controller and the library. */
    private var pendingOpen: Intent? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private companion object {
        /** No particular track was asked for — play the collection from its own start. */
        const val NO_START = -1
    }

    private val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private val requestPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) { maybeRequestNotifications(); loadTracks() } else showPermissionPrompt()
        }

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Deleting files, with the confirmation and the system's dialogs behind it.
     *
     * A field rather than something built on demand: it registers activity-result launchers, and
     * those have to exist before the activity is started.
     */
    private val deleteFlow = DeleteFlow(this, { controller }, {
        // The group we are standing in can have been emptied by its own delete.
        if (drill != null && currentGroups().none { it.key == drill!!.key }) drill = null
        loadTracks()
    })

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { bindMini() }
    }

    /** Now Playing can rate the track we are listing, so mirror the store rather than our copy. */
    private val ratingListener: () -> Unit = {
        refresh()
        trackAdapter.notifyRatingsChanged()
    }

    /** The API can edit playlists while nobody has touched the phone; redraw when it does. */
    private val changeListener: () -> Unit = { refresh() }

    // ---------------- Lifecycle ----------------

    /** The palette this screen was built with; a different saved one means it is out of date. */
    private var palette = Palette.DEFAULT
    private val themeListener: () -> Unit = { if (ThemeManager.savedPalette(this) != palette) recreate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applySaved(this)
        palette = ThemeManager.savedPalette(this)
        super.onCreate(savedInstanceState)
        ThemeManager.reapply(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(binding.miniPlayer) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = sb.bottom + (10 * resources.displayMetrics.density).toInt()
            }
            insets
        }

        trackAdapter = TrackAdapter(
            lifecycleScope,
            onClick = { item, pos -> onTrackClick(item, pos) },
            onLongClick = { item, _ -> onTrackLongPress(item) },
            onStartDrag = { vh -> itemTouchHelper.startDrag(vh) }
        )
        groupAdapter = GroupAdapter(
            lifecycleScope,
            onClick = { group -> onGroupClick(group) },
            onLongClick = { group -> onGroupLongPress(group) },
            onMenu = { group, anchor -> showPlaylistRowMenu(group, anchor) }
        )

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = trackAdapter
        itemTouchHelper = ItemTouchHelper(reorderCallback())
        itemTouchHelper.attachToRecyclerView(binding.recycler)

        buildTabs()

        binding.grantButton.setOnClickListener { requestPerm.launch(permission) }
        binding.miniPlayer.setOnClickListener { openNowPlaying() }
        binding.miniPlayPause.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        binding.miniNext.setOnClickListener { controller?.seekToNext() }
        binding.newPlaylistFab.setOnClickListener { promptNewPlaylist(emptyList()) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    actionMode != null -> actionMode?.finish()
                    drill != null -> { drill = null; refresh() }
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        if (OpenIntent.isExternal(intent)) pendingOpen = intent

        // The API survives the app being closed but not the process being killed, so opening the
        // library is the natural moment to put it back if it is meant to be listening.
        ApiService.startIfEnabled(this)
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        if (OpenIntent.isExternal(newIntent)) {
            pendingOpen = newIntent
            maybeHandleExternalOpen()
        }
    }

    override fun onStart() {
        super.onStart()
        connectController()
        RatingStore.addListener(ratingListener)
        Changes.addListener(changeListener)
        ThemeManager.addListener(themeListener)
        themeListener() // changed while this screen was out of sight
        if (hasPermission()) loadTracks() else showPermissionPrompt()
    }

    override fun onStop() {
        super.onStop()
        RatingStore.removeListener(ratingListener)
        Changes.removeListener(changeListener)
        ThemeManager.removeListener(themeListener)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            // Guard against a stale/cancelled connection (e.g. the activity was recreated by a
            // theme change before the controller finished connecting — get() would throw).
            if (controllerFuture !== future) return@addListener
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(playerListener)
            bindMini()
            maybeHandleExternalOpen()
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------- Opened from another app ----------------

    /**
     * Plays a file handed to us by a file manager or the share sheet.
     *
     * Held until the controller is up, and — only when we actually hold media permission — until
     * the library has loaded, because the library is what turns one file into its whole folder.
     * Without permission there is nothing to wait for: the queue comes from the intent's own
     * `ClipData` or it is a single file, and both work with no permission at all.
     */
    private fun maybeHandleExternalOpen() {
        val open = pendingOpen ?: return
        val c = controller ?: return
        if (hasPermission() && !libraryLoaded) return
        pendingOpen = null

        val resolved = OpenIntent.resolve(this, open, library)
        if (resolved == null || resolved.items.isEmpty()) {
            toast(getString(R.string.open_failed))
            return
        }

        c.shuffleModeEnabled = false
        c.repeatMode = Prefs.repeatMode(this)
        c.setMediaItems(resolved.items, resolved.startIndex, 0L)
        c.prepare()
        c.play()
        bindMini()

        if (!resolved.folderResolved && resolved.items.size == 1) {
            toast(getString(R.string.opened_single))
        }
        openNowPlaying()
    }

    // ---------------- Tabs ----------------

    private fun buildTabs() {
        val labels = listOf(
            R.string.tab_tracks, R.string.tab_folders, R.string.tab_artists,
            R.string.tab_albums, R.string.tab_genres, R.string.tab_playlists,
            R.string.tab_rated
        )
        LibraryTab.values().forEachIndexed { i, _ ->
            binding.tabs.addTab(binding.tabs.newTab().setText(labels[i]))
        }
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                val picked = LibraryTab.values()[t.position]
                if (picked == tab && drill == null) return
                tab = picked
                drill = null
                clearSelections()
                PlaylistStore.setLastTab(this@MainActivity, tab.name)
                PlaylistStore.setLastCollection(this@MainActivity, tab.prefix)
                refresh()
                binding.recycler.scrollToPosition(0)
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            // Re-tapping the current tab is the quickest way back out of a group.
            override fun onTabReselected(t: TabLayout.Tab) {
                if (drill != null) { drill = null; refresh() }
            }
        })
    }

    private fun iconFor(t: LibraryTab): Int = when (t) {
        LibraryTab.FOLDERS -> R.drawable.ic_folder
        LibraryTab.ARTISTS -> R.drawable.ic_artist
        LibraryTab.ALBUMS -> R.drawable.ic_album
        LibraryTab.GENRES -> R.drawable.ic_genre
        LibraryTab.PLAYLISTS -> R.drawable.ic_queue
        LibraryTab.RATED -> R.drawable.ic_star
        LibraryTab.TRACKS -> R.drawable.ic_music_note
    }

    // ---------------- Loading and rebuilding ----------------

    private fun loadTracks() {
        binding.permissionView.visibility = View.GONE
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { MediaLibrary.query(this@MainActivity) }
            library = items
            libraryLoaded = true
            if (!restoredView) { restoreLastView(); restoredView = true }
            refresh()
            bindMini()
            maybeHandleExternalOpen()
        }
    }

    /** Reopens the tab, and the folder/artist/album/genre/playlist, the library was left on. */
    private fun restoreLastView() {
        tab = runCatching { LibraryTab.valueOf(PlaylistStore.lastTab(this)) }
            .getOrDefault(LibraryTab.TRACKS)
        binding.tabs.getTabAt(tab.ordinal)?.select()

        val key = PlaylistStore.lastCollection(this)
        if (tab != LibraryTab.TRACKS && key.startsWith("${tab.prefix}:")) {
            drill = currentGroups().firstOrNull { it.key == key }
        }
    }

    private fun currentGroups(): List<Group> = Browse.groups(
        tab = tab,
        library = library,
        playlists = PlaylistStore.all(this),
        unknownArtist = getString(R.string.unknown_artist),
        unknownGenre = getString(R.string.unknown_genre),
        unknownAlbum = getString(R.string.unknown_album),
        trackCount = { n -> if (n == 1) getString(R.string.track_count_one) else getString(R.string.track_count, n) },
        ratingOf = { id -> RatingStore.of(this, id) }
    )

    /** True while the list is showing tracks rather than groups. */
    private fun showingTracks(): Boolean = tab == LibraryTab.TRACKS || drill != null

    /**
     * Recomputes the visible list from tab + drill + search and puts the right adapter on screen.
     * Everything that changes what is listed funnels through here, so there is one place where the
     * empty state, the title, the drag handles and the FAB are decided.
     */
    private fun refresh() {
        val q = query.trim().lowercase()

        if (showingTracks()) {
            // A drilled-in group re-reads its tracks from the library, so an edit made inside it
            // (a playlist removal, a reorder) is reflected without leaving the view.
            val base = when {
                drill != null -> currentGroups().firstOrNull { it.key == drill!!.key }
                    ?.also { drill = it }?.tracks ?: emptyList()
                else -> library
            }
            shownTracks = if (q.isEmpty()) base else base.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
            }
            if (binding.recycler.adapter !== trackAdapter) binding.recycler.adapter = trackAdapter
            trackAdapter.submitList(shownTracks)

            // Reorder is only coherent on an unfiltered playlist, where row position maps 1:1 to
            // the stored order.
            reorderEnabled = inPlaylist() && q.isEmpty() && shownTracks.isNotEmpty()
            trackAdapter.dragHandles = reorderEnabled
        } else {
            val base = currentGroups()
            shownGroups = if (q.isEmpty()) base else base.filter {
                it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q)
            }
            groupAdapter.iconRes = iconFor(tab)
            groupAdapter.rowMenus = tab == LibraryTab.PLAYLISTS
            if (binding.recycler.adapter !== groupAdapter) binding.recycler.adapter = groupAdapter
            groupAdapter.submitList(shownGroups)
            reorderEnabled = false
        }

        updateEmptyState(q)
        updateTitle()
        binding.newPlaylistFab.visibility =
            if (tab == LibraryTab.PLAYLISTS && drill == null && actionMode == null) View.VISIBLE else View.GONE
        invalidateOptionsMenu()
        actionMode?.let { updateActionModeTitle(it) }
    }

    private fun updateEmptyState(q: String) {
        val empty = if (showingTracks()) shownTracks.isEmpty() else shownGroups.isEmpty()
        binding.emptyText.setText(
            when {
                q.isNotEmpty() -> R.string.no_results
                inPlaylist() -> R.string.playlist_empty
                showingTracks() -> R.string.no_audio
                tab == LibraryTab.FOLDERS -> R.string.no_folders
                tab == LibraryTab.ARTISTS -> R.string.no_artists
                tab == LibraryTab.ALBUMS -> R.string.no_albums
                tab == LibraryTab.GENRES -> R.string.no_genres
                tab == LibraryTab.PLAYLISTS -> R.string.no_playlists
                tab == LibraryTab.RATED -> R.string.no_rated
                else -> R.string.no_audio
            }
        )
        binding.recycler.visibility = if (empty) View.GONE else View.VISIBLE
        binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun updateTitle() {
        supportActionBar?.title = drill?.title ?: getString(R.string.app_name)
        supportActionBar?.setDisplayHomeAsUpEnabled(drill != null)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (drill != null) { drill = null; refresh(); return true }
        return super.onSupportNavigateUp()
    }

    private fun inPlaylist(): Boolean =
        drill != null && drill!!.key.startsWith("${LibraryTab.PLAYLISTS.prefix}:")

    private fun currentPlaylistId(): Long? =
        if (inPlaylist()) drill!!.key.removePrefix("${LibraryTab.PLAYLISTS.prefix}:").toLongOrNull()
        else null

    // ---------------- Row interaction ----------------

    private fun onTrackClick(item: AudioItem, position: Int) {
        if (trackAdapter.selectionMode) { trackAdapter.toggle(item.id); onSelectionChanged(); return }
        playAt(position)
    }

    private fun onTrackLongPress(item: AudioItem) {
        trackAdapter.toggle(item.id)
        onSelectionChanged()
    }

    private fun onGroupClick(group: Group) {
        if (groupAdapter.selectionMode) { groupAdapter.toggle(group.key); onSelectionChanged(); return }
        drill = group
        query = ""
        PlaylistStore.setLastCollection(this, group.key)
        refresh()
        binding.recycler.scrollToPosition(0)
    }

    private fun onGroupLongPress(group: Group) {
        groupAdapter.toggle(group.key)
        onSelectionChanged()
    }

    // ---------------- Multi-selection ----------------

    private fun selectedTracks(): List<AudioItem> =
        if (showingTracks()) trackAdapter.selectedTracks() else groupAdapter.selectedTracks()

    private fun selectionCount(): Int =
        if (showingTracks()) trackAdapter.selectedCount else groupAdapter.selectedCount

    private fun clearSelections() {
        trackAdapter.clearSelection()
        groupAdapter.clearSelection()
        actionMode?.finish()
    }

    private fun onSelectionChanged() {
        if (selectionCount() == 0) { actionMode?.finish(); return }
        if (actionMode == null) actionMode = startSupportActionMode(selectionCallback)
        actionMode?.let { updateActionModeTitle(it) }
        binding.newPlaylistFab.visibility = View.GONE
        // Drag and select are different gestures on the same row; the handles go away while a
        // selection is running.
        trackAdapter.dragHandles = reorderEnabled && !trackAdapter.selectionMode
    }

    private fun updateActionModeTitle(mode: ActionMode) {
        mode.title = getString(R.string.selected_count, selectionCount())
        mode.menu.findItem(R.id.sel_remove)?.isVisible = inPlaylist() && showingTracks()
    }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_selection, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updateActionModeTitle(mode)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val tracks = selectedTracks()
            if (tracks.isEmpty() && item.itemId != R.id.sel_select_all) {
                toast(getString(R.string.nothing_selected)); return true
            }
            when (item.itemId) {
                R.id.sel_play -> { playTracks(tracks); mode.finish() }
                R.id.sel_queue -> { queueTracks(tracks); mode.finish() }
                R.id.sel_add_playlist -> showAddToPlaylist(tracks) { mode.finish() }
                R.id.sel_rate -> showRatingDialog(tracks) { mode.finish() }
                R.id.sel_share -> { shareTracks(tracks); mode.finish() }
                R.id.sel_delete -> { deleteFlow.confirm(tracks); mode.finish() }
                R.id.sel_select_all -> {
                    if (showingTracks()) trackAdapter.selectAll() else groupAdapter.selectAll()
                    onSelectionChanged()
                }
                R.id.sel_remove -> {
                    val id = currentPlaylistId()
                    if (id != null) {
                        val name = drill?.title.orEmpty()
                        PlaylistStore.removeTracks(this@MainActivity, id, tracks.map { it.id })
                        toast(getString(R.string.removed_count_from_playlist, tracks.size, name))
                        mode.finish()
                        refresh()
                    }
                }
                else -> return false
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            trackAdapter.clearSelection()
            groupAdapter.clearSelection()
            trackAdapter.dragHandles = reorderEnabled
            binding.newPlaylistFab.visibility =
                if (tab == LibraryTab.PLAYLISTS && drill == null) View.VISIBLE else View.GONE
        }
    }

    // ---------------- Playback ----------------

    private fun playAt(position: Int) {
        if (position !in shownTracks.indices) return
        if (hasPermission()) maybeRequestNotifications()
        startQueue(shownTracks, position)
    }

    private fun playTracks(tracks: List<AudioItem>) = startQueue(tracks, NO_START)

    /**
     * Starts a new session on [tracks], honouring the saved repeat and shuffle settings — so a
     * white-noise loop left on "repeat one" comes back that way.
     *
     * [startIndex] is the track the user actually tapped, or [NO_START] when they asked for a whole
     * collection. Under Favourites shuffle a tapped track still plays *first* — tapping a row is a
     * request to hear that row, the same call already made for an external open — and the rest of
     * the list queues behind it in weighted order. Plain shuffle stays the player's own flag.
     */
    private fun startQueue(tracks: List<AudioItem>, startIndex: Int) {
        val c = controller ?: return
        if (tracks.isEmpty()) return

        val mode = Prefs.shuffleMode(this)
        var items = tracks
        var index = startIndex.coerceIn(0, tracks.size - 1)

        if (mode == Prefs.SHUFFLE_FAVOURITES) {
            val first = tracks.getOrNull(startIndex)
            val rest = Shuffle.weighted(
                if (first == null) tracks else tracks.filterNot { it.id == first.id }
            ) { RatingStore.of(this, it.id) }
            items = if (first == null) rest else listOf(first) + rest
            index = 0
        }

        c.shuffleModeEnabled = mode == Prefs.SHUFFLE_ALL
        c.repeatMode = Prefs.repeatMode(this)
        c.setMediaItems(items.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
        bindMini()
    }

    /** Appends to what is already playing rather than replacing it. */
    private fun queueTracks(tracks: List<AudioItem>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        if (c.mediaItemCount == 0) { playTracks(tracks); return }
        c.addMediaItems(tracks.map { it.toMediaItem() })
        toast(getString(R.string.queued_count, tracks.size))
    }

    private fun bindMini() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            binding.miniPlayer.visibility = View.GONE
            trackAdapter.nowPlayingId = -1
            return
        }
        binding.miniPlayer.visibility = View.VISIBLE
        val md = item.mediaMetadata
        binding.miniTitle.text = md.title ?: ""
        binding.miniArtist.text = md.artist ?: getString(R.string.unknown_artist)
        binding.miniPlayPause.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        trackAdapter.nowPlayingId = item.mediaId.toLongOrNull() ?: -1

        val track = library.firstOrNull { it.id.toString() == item.mediaId }
        if (track != null) {
            val cached = ArtLoader.cached(track)
            if (cached != null) binding.miniArt.setImageBitmap(cached)
            else {
                binding.miniArt.setImageResource(R.drawable.bg_art_placeholder)
                lifecycleScope.launch {
                    ArtLoader.load(this@MainActivity, track, 160)?.let { binding.miniArt.setImageBitmap(it) }
                }
            }
        } else {
            binding.miniArt.setImageResource(R.drawable.bg_art_placeholder)
        }
    }

    // ---------------- Share ----------------

    /**
     * Sends the selected files themselves, with their metadata alongside — the same handoff a file
     * manager makes when it opens a track *into* Lull, in the other direction. Works on a group
     * selection too, since an album long-pressed resolves to its tracks before it gets here.
     */
    private fun shareTracks(tracks: List<AudioItem>) {
        if (tracks.isEmpty()) { toast(getString(R.string.nothing_selected)); return }
        val intent = Sharing.intentFor(this, tracks)
        if (intent == null) { toast(getString(R.string.share_failed)); return }
        runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.share_failed)) }
    }

    // ---------------- Ratings ----------------

    /**
     * Rates a whole selection at once. The dialog pre-selects the rating they already share, so
     * re-rating a handful of tracks starts from where they are rather than from nothing.
     */
    private fun showRatingDialog(tracks: List<AudioItem>, onDone: () -> Unit = {}) {
        if (tracks.isEmpty()) { toast(getString(R.string.nothing_selected)); return }
        val labels = (RatingStore.MAX downTo RatingStore.UNRATED).map { stars ->
            if (stars == RatingStore.UNRATED) getString(R.string.rating_unrated)
            else RatingStore.glyphs(stars)
        }
        val shared = tracks.map { RatingStore.of(this, it.id) }.distinct().singleOrNull()
        val checked = if (shared == null) -1 else RatingStore.MAX - shared

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rate_track)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                d.dismiss()
                val stars = RatingStore.MAX - which
                RatingStore.setAll(this, tracks.map { it.id }, stars)
                toast(
                    if (stars == RatingStore.UNRATED)
                        getString(R.string.rating_cleared_count, tracks.size)
                    else getString(R.string.rated_count, tracks.size, RatingStore.glyphs(stars))
                )
                onDone()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDone() }
            .show()
    }

    private fun openNowPlaying() {
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    // ---------------- Options menu ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        searchView.queryHint = getString(
            if (showingTracks()) R.string.search_hint else R.string.search_groups_hint
        )
        if (query.isNotEmpty()) {
            menu.findItem(R.id.action_search).expandActionView()
            searchView.setQuery(query, false)
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean = true
            override fun onQueryTextChange(text: String?): Boolean {
                query = text.orEmpty(); refresh(); return true
            }
        })
        menu.findItem(R.id.action_mix_audio).isChecked = Prefs.mixAudio(this)
        menu.findItem(R.id.action_skip_silence).isChecked = Prefs.skipSilence(this)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Rename/Delete only apply while a playlist is open.
        val inPlaylist = inPlaylist()
        menu.findItem(R.id.action_rename_playlist)?.isVisible = inPlaylist
        menu.findItem(R.id.action_delete_playlist)?.isVisible = inPlaylist
        menu.findItem(R.id.action_new_playlist)?.isVisible = tab == LibraryTab.PLAYLISTS

        // Re-read each time the overflow opens, so a running timer shows what's left on it.
        menu.findItem(R.id.action_sleep_timer)?.title =
            if (SleepTimer.isArmed)
                getString(R.string.sleep_timer_menu, TrackAdapter.formatDuration(SleepTimer.remainingMs()))
            else getString(R.string.sleep_timer)

        // Same reasoning: a tempo being forced is a thing the music is audibly doing, so the menu
        // says which one rather than making you open the panel to find out.
        menu.findItem(R.id.action_force_tempo)?.title =
            if (Prefs.forceTempo(this))
                getString(R.string.force_tempo_menu, Prefs.tempoBpm(this))
            else getString(R.string.force_tempo)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_theme -> { ThemeDialog.show(this); true }
            R.id.action_new_playlist -> { promptNewPlaylist(emptyList()); true }
            R.id.action_rename_playlist -> { currentPlaylist()?.let { showRenameDialog(it) }; true }
            R.id.action_delete_playlist -> { currentPlaylist()?.let { confirmDeletePlaylist(it) }; true }
            R.id.action_sleep_timer -> { SleepTimerDialog.show(this); true }
            R.id.action_api -> { ApiSettingsDialog.show(this); true }
            R.id.action_crossfade -> { showCrossfadeDialog(); true }
            R.id.action_force_tempo -> { showTempoDialog(); true }
            R.id.action_mix_audio -> {
                val on = !item.isChecked
                item.isChecked = on
                Prefs.setMixAudio(this, on)
                true
            }
            R.id.action_skip_silence -> {
                val on = !item.isChecked
                item.isChecked = on
                Prefs.setSkipSilence(this, on)
                // The service applies this to the live player, but the change is only audible at
                // the next gap — say so, or toggling it looks like it did nothing.
                Toast.makeText(
                    this,
                    getString(if (on) R.string.skip_silence_on else R.string.skip_silence_off),
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---------------- Playlists ----------------

    private fun currentPlaylist(): Playlist? = currentPlaylistId()?.let { PlaylistStore.get(this, it) }

    /** The per-row overflow in the Playlists tab — the manager's actions on one playlist. */
    private fun showPlaylistRowMenu(group: Group, anchor: View) {
        val id = group.key.removePrefix("${LibraryTab.PLAYLISTS.prefix}:").toLongOrNull() ?: return
        val playlist = PlaylistStore.get(this, id) ?: return
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.play_playlist)
            menu.add(0, 2, 1, R.string.rename_playlist)
            menu.add(0, 3, 2, R.string.duplicate_playlist)
            menu.add(0, 4, 3, R.string.delete_playlist)
            setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    1 -> if (group.tracks.isEmpty()) toast(getString(R.string.playlist_empty_cannot_play))
                         else playTracks(group.tracks)
                    2 -> showRenameDialog(playlist)
                    3 -> {
                        val copy = PlaylistStore.duplicate(
                            this@MainActivity, id, getString(R.string.copy_suffix, playlist.name)
                        )
                        if (copy != null) toast(getString(R.string.playlist_duplicated, copy.name))
                        refresh()
                    }
                    4 -> confirmDeletePlaylist(playlist)
                }
                true
            }
        }.show()
    }

    /** Adds [tracks] to a playlist the user picks, creating one if they have none. */
    private fun showAddToPlaylist(tracks: List<AudioItem>, onDone: () -> Unit = {}) {
        if (tracks.isEmpty()) { toast(getString(R.string.nothing_selected)); return }
        val playlists = PlaylistStore.all(this)
        if (playlists.isEmpty()) { promptNewPlaylist(tracks, onDone); return }

        val labels = playlists.map { "${it.name}  (${it.trackIds.size})" }.toMutableList()
        labels.add(getString(R.string.new_playlist))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == playlists.size) { promptNewPlaylist(tracks, onDone); return@setItems }
                val playlist = playlists[which]
                val added = PlaylistStore.addTracks(this, playlist.id, tracks.map { it.id })
                toast(
                    if (added == 0) getString(R.string.added_none_to_playlist, playlist.name)
                    else getString(R.string.added_count_to_playlist, added, playlist.name)
                )
                onDone()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDone() }
            .show()
    }

    private fun promptNewPlaylist(tracks: List<AudioItem>, onDone: () -> Unit = {}) {
        promptName(R.string.new_playlist, "", R.string.create) { name ->
            val playlist = PlaylistStore.createWith(this, name, tracks.map { it.id })
            if (tracks.isEmpty()) toast(getString(R.string.playlist_created, playlist.name))
            else toast(getString(R.string.added_count_to_playlist, tracks.size, playlist.name))
            onDone()
            refresh()
        }
    }

    private fun showRenameDialog(playlist: Playlist) {
        promptName(R.string.rename_playlist, playlist.name, android.R.string.ok) { name ->
            PlaylistStore.rename(this, playlist.id, name)
            toast(getString(R.string.playlist_renamed, name))
            refresh()
        }
    }

    private fun confirmDeletePlaylist(playlist: Playlist) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_playlist)
            .setMessage(getString(R.string.delete_playlist_confirm, playlist.name))
            .setPositiveButton(R.string.delete_playlist) { _, _ ->
                PlaylistStore.delete(this, playlist.id)
                toast(getString(R.string.playlist_deleted, playlist.name))
                // Deleting the playlist we are standing inside has to walk back out of it.
                if (currentPlaylistId() == playlist.id) drill = null
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Single-field name prompt used for both creating and renaming playlists. */
    private fun promptName(titleRes: Int, initial: String, positiveRes: Int, onName: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setHint(R.string.playlist_name_hint)
            setText(initial)
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(positiveRes) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) toast(getString(R.string.name_required)) else onName(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Drag-to-reorder for playlist views; drag is started from the row's handle, not long-press. */
    private fun reorderCallback() = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun isLongPressDragEnabled() = false

        override fun getDragDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (reorderEnabled && !trackAdapter.selectionMode) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0

        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            trackAdapter.moveItem(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            val id = currentPlaylistId() ?: return
            val ordered = trackAdapter.currentList.toList()
            shownTracks = ordered
            PlaylistStore.setOrder(this@MainActivity, id, ordered.map { it.id })
        }
    }

    // ---------------- Odds and ends ----------------

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showPermissionPrompt() {
        // An external open needs no library, so it must not be blocked behind the grant screen.
        maybeHandleExternalOpen()
        binding.permissionView.visibility = View.VISIBLE
        binding.recycler.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        binding.newPlaylistFab.visibility = View.GONE
    }


    private fun showCrossfadeDialog() {
        val view = DialogCrossfadeBinding.inflate(layoutInflater)
        val label = { seconds: Int ->
            if (seconds == 0) getString(R.string.crossfade_off)
            else getString(R.string.crossfade_seconds, seconds)
        }

        view.crossfadeSlider.valueTo = Prefs.MAX_CROSSFADE_SEC.toFloat()
        view.crossfadeSlider.value = Prefs.crossfadeSec(this).toFloat()
        view.crossfadeValue.text = label(Prefs.crossfadeSec(this))
        view.crossfadeSlider.addOnChangeListener { _, value, _ ->
            view.crossfadeValue.text = label(value.toInt())
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crossfade)
            .setView(view.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val seconds = view.crossfadeSlider.value.toInt()
                Prefs.setCrossfadeSec(this, seconds)
                Toast.makeText(
                    this, getString(R.string.crossfade_set, label(seconds)), Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The Force Tempo panel: what tempo to force, how hard to push a track to reach it, and what
     * Lull currently believes the playing track's own tempo is.
     *
     * That last field is editable, and it is the important one. Every other setting in Lull is a
     * preference, but a BPM is a *measurement* — [BpmDetector] is right most of the time and wrong
     * some of the time, and the person listening can hear which. Typing over the number fixes that
     * track for good; clearing it throws the measurement away and has another go. Without this the
     * only remedy for one mis-detected track would be turning the whole feature off.
     */
    private fun showTempoDialog() {
        val view = DialogTempoBinding.inflate(layoutInflater)
        val playingId = controller?.currentMediaItem?.mediaId?.toLongOrNull()

        view.tempoEnable.isChecked = Prefs.forceTempo(this)
        view.tempoSlider.valueFrom = Prefs.MIN_TEMPO_BPM.toFloat()
        view.tempoSlider.valueTo = Prefs.MAX_TEMPO_BPM.toFloat()
        view.tempoSlider.stepSize = Prefs.TEMPO_BPM_STEP.toFloat()
        view.tempoSlider.value = Prefs.tempoBpm(this).toFloat()
        view.tempoChangeSlider.valueFrom = Prefs.MIN_TEMPO_CHANGE.toFloat()
        view.tempoChangeSlider.valueTo = Prefs.MAX_TEMPO_CHANGE.toFloat()
        view.tempoChangeSlider.value = Prefs.tempoMaxChange(this).toFloat()
        view.tempoOnlyFaster.isChecked = Prefs.tempoOnlyFaster(this)
        view.tempoPushQuiet.isChecked = Prefs.pushQuiet(this)
        view.quietBoostSlider.valueFrom = Prefs.MIN_QUIET_BOOST.toFloat()
        view.quietBoostSlider.valueTo = Prefs.MAX_QUIET_BOOST.toFloat()
        view.quietBoostSlider.value = Prefs.quietBoost(this).toFloat()
        view.quietThresholdSlider.valueFrom = Prefs.MIN_QUIET_THRESHOLD.toFloat()
        view.quietThresholdSlider.valueTo = Prefs.MAX_QUIET_THRESHOLD.toFloat()
        view.quietThresholdSlider.value = Prefs.quietThresholdDb(this).toFloat()
        view.quietThresholdHint.text =
            getString(R.string.quiet_threshold_hint, EnergyProfile.MIN_SECTION_SECONDS)

        val storedBpm = playingId?.let { TempoStore.of(this, it) } ?: TempoStore.UNKNOWN
        if (storedBpm > 0) view.tempoTrackBpm.setText(storedBpm.toString())

        // Everything on this panel changes the same one sentence at the bottom — "this track, at
        // these settings, will play like *this*" — so one function redraws it and every control
        // calls that function.
        val refresh = {
            view.tempoValue.text = getString(R.string.tempo_bpm, view.tempoSlider.value.toInt())
            view.tempoChangeValue.text =
                getString(R.string.tempo_max_change, view.tempoChangeSlider.value.toInt())
            view.quietBoostValue.text =
                getString(R.string.quiet_boost, view.quietBoostSlider.value.toInt())
            view.quietThresholdValue.text =
                getString(R.string.quiet_threshold, view.quietThresholdSlider.value.toInt())
            view.quietSections.text = quietLine(playingId, view.quietThresholdSlider.value.toInt())
            view.tempoTrackEffect.text = effectLine(
                playingId = playingId,
                typedBpm = view.tempoTrackBpm.text.toString().toIntOrNull(),
                storedBpm = storedBpm,
                targetBpm = view.tempoSlider.value.toInt(),
                maxChange = view.tempoChangeSlider.value.toInt(),
                onlyFaster = view.tempoOnlyFaster.isChecked
            )
        }
        view.tempoSlider.addOnChangeListener { _, _, _ -> refresh() }
        view.tempoChangeSlider.addOnChangeListener { _, _, _ -> refresh() }
        view.tempoOnlyFaster.setOnCheckedChangeListener { _, _ -> refresh() }
        view.quietBoostSlider.addOnChangeListener { _, _, _ -> refresh() }
        view.quietThresholdSlider.addOnChangeListener { _, _, _ -> refresh() }
        view.tempoPushQuiet.setOnCheckedChangeListener { _, _ -> refresh() }
        view.tempoTrackBpm.addTextChangedListener(afterTextChanged = { refresh() })
        if (playingId == null) {
            view.tempoTrackLabel.visibility = View.GONE
            view.tempoTrackBpm.visibility = View.GONE
            view.tempoTrackHint.visibility = View.GONE
        }
        refresh()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.force_tempo)
            .setView(view.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val on = view.tempoEnable.isChecked
                val bpm = view.tempoSlider.value.toInt()
                Prefs.setTempoBpm(this, bpm)
                Prefs.setTempoMaxChange(this, view.tempoChangeSlider.value.toInt())
                Prefs.setTempoOnlyFaster(this, view.tempoOnlyFaster.isChecked)
                Prefs.setQuietBoost(this, view.quietBoostSlider.value.toInt())
                Prefs.setQuietThresholdDb(this, view.quietThresholdSlider.value.toInt())
                Prefs.setPushQuiet(this, view.tempoPushQuiet.isChecked)
                // Written last, so the service re-applies once against settings that are all in
                // place rather than once per key as they land.
                Prefs.setForceTempo(this, on)

                if (playingId != null) {
                    val typed = view.tempoTrackBpm.text.toString().toIntOrNull()
                    // An emptied field is "forget what you measured", not "no change".
                    if (typed != storedBpm.takeIf { it > 0 }) {
                        TempoStore.set(this, playingId, typed ?: TempoStore.UNKNOWN)
                    }
                }
                toast(
                    if (on) getString(R.string.tempo_set, bpm)
                    else getString(R.string.tempo_unset)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * What the dead-spot settings would find in the track playing right now.
     *
     * Shown live against the threshold slider rather than only after saving, because "how quiet
     * counts" is the one setting here with no intuitive units — a number of decibels means nothing
     * until you can see that moving it from 9 to 6 turns two dead spots into five.
     */
    private fun quietLine(playingId: Long?, thresholdDb: Int): String {
        if (playingId == null) return ""
        if (ProfileStore.needsAnalysis(this, playingId)) return getString(R.string.quiet_not_measured)
        val sections = ProfileStore.sectionsFor(this, playingId, thresholdDb)
        if (sections.isEmpty()) return getString(R.string.quiet_sections_none)
        return resources.getQuantityString(
            R.plurals.quiet_sections,
            sections.size,
            sections.size,
            Format.duration(EnergyProfile.quietMs(sections))
        )
    }

    /** "128 BPM · playing at 1.09x (140 BPM)" — the whole feature, for the track you can hear. */
    private fun effectLine(
        playingId: Long?,
        typedBpm: Int?,
        storedBpm: Int,
        targetBpm: Int,
        maxChange: Int,
        onlyFaster: Boolean
    ): String {
        if (playingId == null) return getString(R.string.tempo_nothing_playing)
        val source = typedBpm ?: storedBpm.takeIf { it > 0 } ?: return when (storedBpm) {
            TempoStore.NONE -> getString(R.string.tempo_none)
            else -> getString(R.string.tempo_working)
        }
        val speed = Tempo.speedFor(source.toFloat(), targetBpm, maxChange, onlyFaster)
        if (speed == Tempo.UNCHANGED) return getString(R.string.tempo_effect_unchanged, source)
        return getString(
            R.string.tempo_effect,
            source,
            Tempo.formatSpeed(speed),
            Tempo.effectiveBpm(source.toFloat(), speed).toInt()
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
