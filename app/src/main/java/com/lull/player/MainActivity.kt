package com.lull.player

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Menu
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.lull.player.databinding.ActivityMainBinding
import com.lull.player.databinding.DialogCrossfadeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TrackAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    /** The full device library. */
    private var library: List<AudioItem> = emptyList()
    /** The base list for the current view — the library, or a playlist's tracks in order. */
    private var collection: List<AudioItem> = emptyList()
    /** [collection] after the search filter. */
    private var shown: List<AudioItem> = emptyList()
    private var query: String = ""

    /** [PlaylistStore.ALL] or `pl:<id>`. */
    private var collectionKey: String = PlaylistStore.ALL
    /** Set once the saved collection has been restored, so returning to the app doesn't reset it. */
    private var restoredCollection = false
    /** Drag reorder only makes sense in a playlist view with no active search. */
    private var reorderEnabled = false

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

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

        adapter = TrackAdapter(
            lifecycleScope,
            onClick = { _, pos -> playAt(pos) },
            onLongClick = { item, _ -> onTrackLongPress(item) },
            onStartDrag = { vh -> itemTouchHelper.startDrag(vh) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        itemTouchHelper = ItemTouchHelper(reorderCallback())
        itemTouchHelper.attachToRecyclerView(binding.recycler)

        binding.grantButton.setOnClickListener { requestPerm.launch(permission) }
        binding.miniPlayer.setOnClickListener { openNowPlaying() }
        binding.miniPlayPause.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        binding.miniNext.setOnClickListener { controller?.seekToNext() }
    }

    override fun onStart() {
        super.onStart()
        connectController()
        if (hasPermission()) loadTracks() else showPermissionPrompt()
    }

    override fun onStop() {
        super.onStop()
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
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
        if (query.isNotEmpty()) {
            menu.findItem(R.id.action_search).expandActionView()
            searchView.setQuery(query, false)
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean = true
            override fun onQueryTextChange(text: String?): Boolean { applyFilter(text ?: ""); return true }
        })
        menu.findItem(R.id.action_mix_audio).isChecked = Prefs.mixAudio(this)
        menu.findItem(R.id.action_skip_silence).isChecked = Prefs.skipSilence(this)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Rename/Delete only apply while a playlist is open.
        val inPlaylist = collectionKey != PlaylistStore.ALL
        menu.findItem(R.id.action_rename_playlist)?.isVisible = inPlaylist
        menu.findItem(R.id.action_delete_playlist)?.isVisible = inPlaylist

        // Re-read each time the overflow opens, so a running timer shows what's left on it.
        menu.findItem(R.id.action_sleep_timer)?.title =
            if (SleepTimer.isArmed)
                getString(
                    R.string.sleep_timer_menu,
                    TrackAdapter.formatDuration(SleepTimer.remainingMs())
                )
            else getString(R.string.sleep_timer)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_theme -> { showThemeDialog(); true }
            R.id.action_playlists -> { showPlaylistsDialog(); true }
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

    private fun applyFilter(text: String) {
        query = text.trim()
        val q = query.lowercase()
        shown = if (q.isEmpty()) collection else collection.filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
        adapter.submitList(shown)

        binding.emptyText.setText(
            when {
                q.isNotEmpty() && collection.isNotEmpty() -> R.string.no_results
                collectionKey != PlaylistStore.ALL -> R.string.playlist_empty
                else -> R.string.no_audio
            }
        )
        binding.recycler.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE

        // Reorder is only coherent on an unfiltered playlist, where list position maps 1:1 to stored order.
        reorderEnabled = collectionKey != PlaylistStore.ALL && q.isEmpty() && shown.isNotEmpty()
        adapter.dragHandles = reorderEnabled
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showPermissionPrompt() {
        binding.permissionView.visibility = View.VISIBLE
        binding.recycler.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
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

    private fun playAt(position: Int) {
        val c = controller ?: return
        if (position !in shown.indices) return
        if (hasPermission()) maybeRequestNotifications()
        // Queue the currently visible (possibly filtered) list so the tapped index lines up.
        val items: List<MediaItem> = shown.map { it.toMediaItem() }
        // Start a new session honouring the user's saved repeat/shuffle preference, so if you
        // left it on "repeat one" for white-noise it comes back that way.
        c.shuffleModeEnabled = Prefs.shuffle(this)
        c.repeatMode = Prefs.repeatMode(this)
        c.setMediaItems(items, position, 0L)
        c.prepare()
        c.play()
        bindMini()
    }

    private fun bindMini() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            binding.miniPlayer.visibility = View.GONE
            adapter.nowPlayingId = -1
            return
        }
        binding.miniPlayer.visibility = View.VISIBLE
        val md = item.mediaMetadata
        binding.miniTitle.text = md.title ?: ""
        binding.miniArtist.text = md.artist ?: getString(R.string.unknown_artist)
        binding.miniPlayPause.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        adapter.nowPlayingId = item.mediaId.toLongOrNull() ?: -1

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
        }
    }

    private fun openNowPlaying() {
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    private fun loadTracks() {
        binding.permissionView.visibility = View.GONE
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { queryAudio() }
            library = items
            // On first load, reopen the collection the user last viewed; fall back to the whole
            // library if that playlist has since been deleted.
            if (!restoredCollection) {
                collectionKey = PlaylistStore.lastCollection(this@MainActivity)
                if (collectionKey != PlaylistStore.ALL && currentPlaylist() == null) {
                    collectionKey = PlaylistStore.ALL
                }
                restoredCollection = true
            }
            rebuildCollection()
            updateTitle()
            invalidateOptionsMenu()
            bindMini()
        }
    }

    // ---------------- Collections (library / playlists) ----------------

    private fun currentPlaylist(): Playlist? {
        if (collectionKey == PlaylistStore.ALL) return null
        val id = collectionKey.removePrefix("pl:").toLongOrNull() ?: return null
        return PlaylistStore.get(this, id)
    }

    /** Switch the visible collection, persisting it so the app reopens here next time. */
    private fun showCollection(key: String) {
        collectionKey = key
        PlaylistStore.setLastCollection(this, key)
        rebuildCollection()
        updateTitle()
        invalidateOptionsMenu()
        binding.recycler.scrollToPosition(0)
    }

    /** Rebuild [collection] from the current key, mapping playlist ids onto the loaded library. */
    private fun rebuildCollection() {
        val playlist = currentPlaylist()
        collection = if (playlist == null) library
        else {
            val byId = library.associateBy { it.id }
            playlist.trackIds.mapNotNull { byId[it] }
        }
        applyFilter(query)
    }

    private fun updateTitle() {
        supportActionBar?.title = currentPlaylist()?.name ?: getString(R.string.app_name)
    }

    private fun showPlaylistsDialog() {
        val playlists = PlaylistStore.all(this)
        val keys = ArrayList<String>().apply {
            add(PlaylistStore.ALL); addAll(playlists.map { PlaylistStore.key(it.id) })
        }
        val labels = ArrayList<String>().apply {
            add(getString(R.string.all_tracks))
            addAll(playlists.map { "${it.name}  (${it.trackIds.size})" })
        }
        val current = keys.indexOf(collectionKey).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playlists)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, which ->
                d.dismiss(); showCollection(keys[which])
            }
            .setNeutralButton(R.string.new_playlist) { _, _ -> promptNewPlaylist(null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onTrackLongPress(item: AudioItem) {
        val playlist = currentPlaylist()
        if (playlist == null) { showAddToPlaylist(item); return }
        val actions = arrayOf(
            getString(R.string.add_to_playlist), getString(R.string.remove_from_playlist)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showAddToPlaylist(item)
                    1 -> {
                        PlaylistStore.removeTrack(this, playlist.id, item.id)
                        toast(getString(R.string.removed_from_playlist, playlist.name))
                        rebuildCollection()
                    }
                }
            }
            .show()
    }

    private fun showAddToPlaylist(item: AudioItem) {
        val playlists = PlaylistStore.all(this)
        if (playlists.isEmpty()) { promptNewPlaylist(item); return }
        val labels = playlists.map { it.name }.toMutableList()
        labels.add(getString(R.string.new_playlist))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == playlists.size) { promptNewPlaylist(item); return@setItems }
                val playlist = playlists[which]
                val added = PlaylistStore.addTrack(this, playlist.id, item.id)
                toast(getString(
                    if (added) R.string.added_to_playlist else R.string.already_in_playlist,
                    playlist.name
                ))
                if (added && playlist.id == currentPlaylist()?.id) rebuildCollection()
            }
            .show()
    }

    private fun promptNewPlaylist(trackToAdd: AudioItem?) {
        promptName(R.string.new_playlist, "", R.string.create) { name ->
            val playlist = PlaylistStore.create(this, name)
            if (trackToAdd != null) {
                PlaylistStore.addTrack(this, playlist.id, trackToAdd.id)
                toast(getString(R.string.added_to_playlist, playlist.name))
                if (playlist.id == currentPlaylist()?.id) rebuildCollection()
            } else {
                toast(getString(R.string.playlist_created, playlist.name))
                showCollection(PlaylistStore.key(playlist.id))
            }
        }
    }

    private fun showRenameDialog(playlist: Playlist) {
        promptName(R.string.rename_playlist, playlist.name, android.R.string.ok) { name ->
            PlaylistStore.rename(this, playlist.id, name)
            toast(getString(R.string.playlist_renamed, name))
            updateTitle()
        }
    }

    private fun confirmDeletePlaylist(playlist: Playlist) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_playlist)
            .setMessage(getString(R.string.delete_playlist_confirm, playlist.name))
            .setPositiveButton(R.string.delete_playlist) { _, _ ->
                PlaylistStore.delete(this, playlist.id)
                toast(getString(R.string.playlist_deleted, playlist.name))
                showCollection(PlaylistStore.ALL)
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
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
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
            if (reorderEnabled) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0

        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            adapter.moveItem(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            val playlist = currentPlaylist() ?: return
            val ordered = adapter.currentList.toList()
            collection = ordered
            shown = ordered
            PlaylistStore.setOrder(this@MainActivity, playlist.id, ordered.map { it.id })
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun queryAudio(): List<AudioItem> {
        val list = ArrayList<AudioItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        )
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        contentResolver.query(collection, projection, null, null, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val artist = c.getString(artistCol) ?: ""
                list.add(
                    AudioItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = c.getString(titleCol) ?: "Unknown",
                        artist = if (artist == "<unknown>") "" else artist,
                        album = c.getString(albumCol) ?: "",
                        albumId = c.getLong(albumIdCol),
                        durationMs = c.getLong(durCol),
                        size = c.getLong(sizeCol)
                    )
                )
            }
        }
        return list
    }
}
