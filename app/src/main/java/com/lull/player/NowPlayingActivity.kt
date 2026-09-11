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

    /** The library can rate the playing track from its selection bar, so mirror the store. */
    private val ratingListener: () -> Unit = { renderRating() }

    /** The five stars, low to high, so the index is one less than the rating it sets. */
    private val stars by lazy {
        listOf(binding.star1, binding.star2, binding.star3, binding.star4, binding.star5)
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
        binding.btnSleep.setOnClickListener { SleepTimerDialog.show(this) }
        binding.btnPlayPause.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        binding.btnNext.setOnClickListener { controller?.seekToNext() }
        binding.btnPrev.setOnClickListener { controller?.seekToPrevious() }
        binding.btnRepeat.setOnClickListener { cycleRepeat() }
        binding.btnShuffle.setOnClickListener { cycleShuffle() }

        setupSeek()
        setupAbLoop()
        setupVolume()
        setupRating()

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
        RatingStore.addListener(ratingListener)
        renderAb()
        renderSleep()
        renderRating()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        AbLoop.removeListener(abListener)
        SleepTimer.removeListener(sleepListener)
        RatingStore.removeListener(ratingListener)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    // ---------------- Rendering ----------------

    private fun render() {
        val c = controller
        val item: MediaItem? = c?.currentMediaItem
        if (c == null || item == null) { finishIfEmpty(); renderAb(); renderRating(); return }

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
        when (Prefs.shuffleMode(this)) {
            Prefs.SHUFFLE_ALL -> {
                binding.btnShuffle.setImageResource(R.drawable.ic_shuffle)
                binding.btnShuffle.setColorFilter(primary)
            }
            Prefs.SHUFFLE_FAVOURITES -> {
                binding.btnShuffle.setImageResource(R.drawable.ic_shuffle_star)
                binding.btnShuffle.setColorFilter(primary)
            }
            else -> {
                binding.btnShuffle.setImageResource(R.drawable.ic_shuffle)
                binding.btnShuffle.setColorFilter(onVariant)
            }
        }

        renderAb()
        renderRating()
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

    /** Off -> shuffle -> Favourites shuffle, the way the repeat button cycles its three states. */
    private fun cycleShuffle() {
        val c = controller ?: return
        val next = (Prefs.shuffleMode(this) + 1) % 3
        Prefs.setShuffleMode(this, next)
        when (next) {
            Prefs.SHUFFLE_ALL -> c.shuffleModeEnabled = true
            Prefs.SHUFFLE_FAVOURITES -> {
                c.shuffleModeEnabled = false
                reorderUpcomingByRating(c)
            }
            else -> c.shuffleModeEnabled = false
        }
        toast(getString(when (next) {
            Prefs.SHUFFLE_ALL -> R.string.shuffle_on
            Prefs.SHUFFLE_FAVOURITES -> R.string.shuffle_favourites_msg
            else -> R.string.shuffle_off
        }))
        render()
    }

    /**
     * Re-weights what is still to come, leaving the playing track where it is.
     *
     * Favourites shuffle is an *order*, not a player flag — Media3's shuffle is an unweighted
     * permutation with nothing to bias — so switching it on has to rewrite the queue in place.
     * The flip side is that switching it back off cannot unscramble it: the order it replaced is
     * gone. That is why it reorders only what has not played yet.
     */
    private fun reorderUpcomingByRating(c: MediaController) {
        val count = c.mediaItemCount
        val from = c.currentMediaItemIndex + 1
        if (count - from < 2) return
        val upcoming = (from until count).map { c.getMediaItemAt(it) }
        val ordered = Shuffle.weighted(upcoming) {
            RatingStore.of(this, it.mediaId.toLongOrNull() ?: -1L)
        }
        c.removeMediaItems(from, count)
        c.addMediaItems(ordered)
    }

    // ---------------- Rating ----------------

    private fun setupRating() {
        stars.forEachIndexed { i, button -> button.setOnClickListener { rateCurrent(i + 1) } }
    }

    /** Tapping the star a track already sits on clears the rating — nothing else would undo it. */
    private fun rateCurrent(value: Int) {
        val id = controller?.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val next = if (RatingStore.of(this, id) == value) RatingStore.UNRATED else value
        RatingStore.set(this, id, next)
        toast(
            if (next == RatingStore.UNRATED) getString(R.string.rating_cleared)
            else getString(R.string.rated_msg, RatingStore.glyphs(next))
        )
    }

    private fun renderRating() {
        val id = controller?.currentMediaItem?.mediaId?.toLongOrNull()
        val rating = if (id == null) RatingStore.UNRATED else RatingStore.of(this, id)
        val primary = themeColor(com.google.android.material.R.attr.colorPrimary)
        val onVariant = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        stars.forEachIndexed { i, button ->
            val filled = i < rating
            button.setImageResource(if (filled) R.drawable.ic_star else R.drawable.ic_star_border)
            button.setColorFilter(if (filled) primary else onVariant)
            button.contentDescription = getString(R.string.rating_star_desc, i + 1)
        }
        binding.ratingRow.visibility = if (id == null) View.GONE else View.VISIBLE
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
