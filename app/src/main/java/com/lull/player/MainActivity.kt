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
import android.view.Menu
import android.view.View
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.lull.player.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TrackAdapter
    private var tracks: List<AudioItem> = emptyList()
    private var shown: List<AudioItem> = emptyList()
    private var query: String = ""

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
        supportActionBar?.setDisplayShowTitleEnabled(false)

        ViewCompat.setOnApplyWindowInsetsListener(binding.miniPlayer) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = sb.bottom + (10 * resources.displayMetrics.density).toInt()
            }
            insets
        }

        adapter = TrackAdapter(lifecycleScope) { _, pos -> playAt(pos) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

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
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return if (item.itemId == R.id.action_theme) { showThemeDialog(); true }
        else super.onOptionsItemSelected(item)
    }

    private fun applyFilter(text: String) {
        query = text.trim()
        val q = query.lowercase()
        shown = if (q.isEmpty()) tracks else tracks.filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
        adapter.submitList(shown)
        binding.emptyText.setText(if (q.isNotEmpty() && tracks.isNotEmpty()) R.string.no_results else R.string.no_audio)
        binding.recycler.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
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

        val track = tracks.firstOrNull { it.id.toString() == item.mediaId }
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
            tracks = items
            binding.emptyText.setText(R.string.no_audio)
            applyFilter(query)
            bindMini()
        }
    }

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
