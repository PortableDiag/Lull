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

    /** The service can drop the markers on us (track change), so mirror its state rather than ours. */
    private val abListener: () -> Unit = { renderAb() }

    /** The service clears the timer when it fires, so mirror its state rather than ours. */
    private val sleepListener: () -> Unit = { renderSleep() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applySaved(this)
        super.onCreate(savedInstanceState)
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnTheme.setOnClickListener { showThemeDialog() }
        binding.btnSleep.setOnClickListener { SleepTimerDialog.show(this) }
        binding.btnPlayPause.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        binding.btnNext.setOnClickListener { controller?.seekToNext() }
        binding.btnPrev.setOnClickListener { controller?.seekToPrevious() }
        binding.btnRepeat.setOnClickListener { cycleRepeat() }
        binding.btnShuffle.setOnClickListener { toggleShuffle() }

        setupSeek()
        setupAbLoop()
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
        AbLoop.addListener(abListener)
        SleepTimer.addListener(sleepListener)
        renderAb()
        renderSleep()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        AbLoop.removeListener(abListener)
        SleepTimer.removeListener(sleepListener)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    // ---------------- Rendering ----------------

    private fun render() {
        val c = controller
        val item: MediaItem? = c?.currentMediaItem
        if (c == null || item == null) { finishIfEmpty(); renderAb(); return }

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

        renderAb()
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
            if (SleepTimer.isArmed) renderSleep()   // the countdown has to move on its own.
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

    // ---------------- A-B loop ----------------

    private fun setupAbLoop() {
        binding.btnSetA.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            val id = c.currentMediaItem?.mediaId ?: return@setOnClickListener
            val at = c.currentPosition.coerceAtLeast(0L)
            AbLoop.setA(id, at)
            toast(getString(R.string.ab_a_set, TrackAdapter.formatDuration(at)))
        }

        binding.btnSetB.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            val id = c.currentMediaItem?.mediaId ?: return@setOnClickListener
            if (AbLoop.aMs == AbLoop.UNSET || AbLoop.mediaId != id) {
                toast(getString(R.string.ab_need_a_first))
                return@setOnClickListener
            }
            // duration is TIME_UNSET (negative) until the track is prepared; setB copes with that.
            if (!AbLoop.setB(id, c.currentPosition, c.duration)) {
                toast(getString(R.string.ab_too_short))
            }
        }

        binding.btnAbClear.setOnClickListener {
            AbLoop.clear()
            toast(getString(R.string.ab_cleared))
        }
    }

    private fun renderAb() {
        val currentId = controller?.currentMediaItem?.mediaId
        val mine = AbLoop.mediaId != null && AbLoop.mediaId == currentId
        val aSet = mine && AbLoop.aMs != AbLoop.UNSET
        val armed = mine && AbLoop.isArmed

        val primary = themeColor(com.google.android.material.R.attr.colorPrimary)
        val onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface)
        val onVariant = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

        binding.abStatus.text = when {
            armed -> getString(
                R.string.ab_active,
                TrackAdapter.formatDuration(AbLoop.aMs),
                TrackAdapter.formatDuration(AbLoop.bMs)
            )
            aSet -> getString(R.string.ab_waiting_for_b, TrackAdapter.formatDuration(AbLoop.aMs))
            else -> getString(R.string.ab_off)
        }
        binding.abStatus.setTextColor(if (armed) primary else onVariant)
        binding.btnSetA.setTextColor(if (aSet) primary else onSurface)
        binding.btnSetB.setTextColor(if (armed) primary else onSurface)

        // Kept on screen at all times so the loop is never something you can't get out of;
        // it just greys out when there is nothing to clear.
        binding.btnAbClear.isEnabled = aSet
        binding.btnAbClear.alpha = if (aSet) 1f else 0.35f
    }

    // ---------------- Sleep timer ----------------

    /**
     * While a timer is running the top label counts it down instead of saying "Now playing" —
     * the one thing you'd want to know at a glance, on the screen you'd glance at.
     */
    private fun renderSleep() {
        val armed = SleepTimer.isArmed
        val primary = themeColor(com.google.android.material.R.attr.colorPrimary)

        binding.topLabel.text =
            if (armed) getString(
                R.string.sleep_timer_remaining,
                TrackAdapter.formatDuration(SleepTimer.remainingMs())
            )
            else getString(R.string.now_playing)
        binding.topLabel.setTextColor(
            if (armed) primary else themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        binding.btnSleep.setColorFilter(
            if (armed) primary else themeColor(com.google.android.material.R.attr.colorOnSurface)
        )
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
