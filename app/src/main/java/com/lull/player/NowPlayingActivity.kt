package com.lull.player

import android.content.ComponentName
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lull.player.databinding.ActivityNowPlayingBinding
import kotlinx.coroutines.launch

class NowPlayingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowPlayingBinding
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var isSeeking = false
    private var maxVol = 15

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applySaved(this)
        super.onCreate(savedInstanceState)
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnTheme.setOnClickListener { showThemeDialog() }
        binding.btnPlayPause.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        binding.btnNext.setOnClickListener { controller?.seekToNext() }
        binding.btnPrev.setOnClickListener { controller?.seekToPrevious() }
        binding.btnRepeat.setOnClickListener { cycleRepeat() }
        binding.btnShuffle.setOnClickListener { toggleShuffle() }

        setupSeek()
        setupVolume()

        binding.volumeKnob.setColors(
            themeColor(com.google.android.material.R.attr.colorPrimary),
            themeColor(com.google.android.material.R.attr.colorSurfaceVariant),
            themeColor(com.google.android.material.R.attr.colorOnSurface)
        )
        applyVolumeStyle(Prefs.volumeStyle(this))
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            // Ignore a stale/cancelled connection (the activity may have been recreated by a
            // theme change before the controller finished connecting — get() would throw).
            if (controllerFuture !== future) return@addListener
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c.also { it.addListener(playerListener) }
            render()
        }, ContextCompat.getMainExecutor(this))
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    // ---------------- Rendering ----------------

    private fun render() {
        val c = controller
        val item: MediaItem? = c?.currentMediaItem
        if (c == null || item == null) { finishIfEmpty(); return }

        val md = item.mediaMetadata
        binding.title.text = md.title ?: ""
        binding.artist.text = md.artist ?: getString(R.string.unknown_artist)
        binding.btnPlayPause.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        binding.duration.text = TrackAdapter.formatDuration(c.duration.coerceAtLeast(0))

        // Repeat icon + tint
        val onVariant = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val primary = themeColor(com.google.android.material.R.attr.colorPrimary)
        when (c.repeatMode) {
            Player.REPEAT_MODE_ONE -> { binding.btnRepeat.setImageResource(R.drawable.ic_repeat_one); binding.btnRepeat.setColorFilter(primary) }
            Player.REPEAT_MODE_ALL -> { binding.btnRepeat.setImageResource(R.drawable.ic_repeat); binding.btnRepeat.setColorFilter(primary) }
            else -> { binding.btnRepeat.setImageResource(R.drawable.ic_repeat); binding.btnRepeat.setColorFilter(onVariant) }
        }
        binding.btnShuffle.setColorFilter(if (c.shuffleModeEnabled) primary else onVariant)

        loadArt(item.mediaId)
    }

    private var artLoadedFor: String? = null
    private fun loadArt(mediaId: String) {
        if (artLoadedFor == mediaId) return
        artLoadedFor = mediaId
        val id = mediaId.toLongOrNull() ?: return
        val uri = controller?.currentMediaItem?.localConfiguration?.uri ?: android.net.Uri.EMPTY
        val item = AudioItem(id, uri, "", "", "", albumIdFromController(), 0, 0)
        val cached = ArtLoader.cached(item)
        if (cached != null) { binding.art.setImageBitmap(cached); return }
        binding.art.setImageResource(R.drawable.bg_art_big)
        lifecycleScope.launch {
            ArtLoader.load(this@NowPlayingActivity, item, 600)?.let {
                if (artLoadedFor == mediaId) binding.art.setImageBitmap(it)
            }
        }
    }

    /** Album id isn't carried in MediaMetadata; recover it from the artwork uri if present. */
    private fun albumIdFromController(): Long {
        val art = controller?.currentMediaItem?.mediaMetadata?.artworkUri ?: return 0
        return art.lastPathSegment?.toLongOrNull() ?: 0
    }

    private val ticker = object : Runnable {
        override fun run() {
            val c = controller
            if (c != null && !isSeeking) {
                val dur = c.duration.coerceAtLeast(1)
                binding.seekBar.max = 1000
                binding.seekBar.progress = (c.currentPosition.toFloat() / dur * 1000).toInt()
                binding.position.text = TrackAdapter.formatDuration(c.currentPosition)
            }
            syncVolumeUi()
            handler.postDelayed(this, 500)
        }
    }

    private fun finishIfEmpty() {
        // Nothing loaded — drop back to the library.
        binding.title.text = ""
        binding.artist.text = ""
    }

    // ---------------- Seek ----------------

    private fun setupSeek() {
        binding.seekBar.max = 1000
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = controller?.duration?.coerceAtLeast(0) ?: 0
                    binding.position.text = TrackAdapter.formatDuration((progress / 1000f * dur).toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                val dur = controller?.duration?.coerceAtLeast(0) ?: 0
                controller?.seekTo((sb.progress / 1000f * dur).toLong())
                isSeeking = false
            }
        })
    }

    // ---------------- Repeat / shuffle ----------------

    private fun cycleRepeat() {
        val c = controller ?: return
        val next = (c.repeatMode + 1) % 3
        c.repeatMode = next
        Prefs.setRepeatMode(this, next)
        toast(getString(when (next) {
            Player.REPEAT_MODE_ONE -> R.string.repeat_one_msg
            Player.REPEAT_MODE_ALL -> R.string.repeat_all_msg
            else -> R.string.repeat_off_msg
        }))
        render()
    }

    private fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        Prefs.setShuffle(this, c.shuffleModeEnabled)
        toast(getString(if (c.shuffleModeEnabled) R.string.shuffle_on else R.string.shuffle_off))
        render()
    }

    // ---------------- Volume ----------------

    private fun setupVolume() {
        binding.volumeBar.max = maxVol
        binding.volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        binding.volumeKnob.onValueChange = { frac ->
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(frac * maxVol), 0)
        }
        binding.btnVolStyleBar.setOnClickListener { switchVolumeStyle() }
        binding.btnVolStyleKnob.setOnClickListener { switchVolumeStyle() }
        syncVolumeUi()
    }

    private fun syncVolumeUi() {
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.volumeBar.progress = vol
        binding.volumeKnob.setValue(vol.toFloat() / maxVol)
    }

    private fun switchVolumeStyle() {
        val next = if (Prefs.volumeStyle(this) == Prefs.VOL_BAR) Prefs.VOL_KNOB else Prefs.VOL_BAR
        Prefs.setVolumeStyle(this, next)
        applyVolumeStyle(next)
        toast(getString(if (next == Prefs.VOL_KNOB) R.string.volume_knob else R.string.volume_bar))
    }

    private fun applyVolumeStyle(style: Int) {
        val knob = style == Prefs.VOL_KNOB
        binding.volumeBarRow.visibility = if (knob) View.GONE else View.VISIBLE
        binding.volumeKnobWrap.visibility = if (knob) View.VISIBLE else View.GONE
        syncVolumeUi()
    }

    // ---------------- Misc ----------------

    private fun showThemeDialog() {
        val options = arrayOf(
            getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)
        )
        val current = ThemeManager.savedMode(this)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(options, current) { d, which ->
                d.dismiss(); if (which != current) ThemeManager.setMode(this, which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
