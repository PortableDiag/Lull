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
import com.lull.player.databinding.ActivityMainBinding
import com.lull.player.databinding.DialogCrossfadeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The library. Six browse axes — tracks, folders, artists, albums, genres and playlists — over one
 * MediaStore load, plus multi-selection and the playlist manager.
 *
 * Two list shapes share one RecyclerView: a [GroupAdapter] of folders/artists/albums/genres/
 * playlists, and a [TrackAdapter] of the tracks inside one of them. Tapping a group drills in;
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

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { bindMini() }
    }

    /** Now Playing can rate the track we are listing, so mirror the store rather than our copy. */
    private val ratingListener: () -> Unit = {
        refresh()
        trackAdapter.notifyRatingsChanged()
    }

    // ---------------- Lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applySaved(this)
        super.onCreate(savedInstanceState)
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
        if (hasPermission()) loadTracks() else showPermissionPrompt()
    }

    override fun onStop() {
        super.onStop()
        RatingStore.removeListener(ratingListener)
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
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_theme -> { showThemeDialog(); true }
            R.id.action_new_playlist -> { promptNewPlaylist(emptyList()); true }
            R.id.action_rename_playlist -> { currentPlaylist()?.let { showRenameDialog(it) }; true }
            R.id.action_delete_playlist -> { currentPlaylist()?.let { confirmDeletePlaylist(it) }; true }
            R.id.action_sleep_timer -> { SleepTimerDialog.show(this); true }
            R.id.action_crossfade -> { showCrossfadeDialog(); true }
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

    private fun showThemeDialog() {
        val options = arrayOf(
            getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)
        )
        val current = ThemeManager.savedMode(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(options, current) { d, which ->
                d.dismiss(); if (which != current) ThemeManager.setMode(this, which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
