package com.lull.player

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlin.math.cos
import kotlin.math.sin

/**
 * Background playback host. Media3's MediaSessionService runs as a foreground service with
 * a media notification while playing, and routes lock-screen / Bluetooth / headset media
 * buttons to the session player (our [RepeatAwarePlayer]).
 *
 * It also owns the two features a single ExoPlayer can't express on its own:
 *
 *  - the [AbLoop] region, wrapped from B back to A by a poller, so it survives the UI closing;
 *  - crossfade, which is *by definition* two tracks sounding at once. One ExoPlayer decodes one
 *    stream, so we keep two engines: one audible, one warming up the next track. At the end of a
 *    fade the standby engine becomes the session's player and the roles swap.
 *
 * With crossfade set to 0 (the default) the second engine is never started and playback follows
 * exactly the same path it did before the feature existed — which is what keeps gapless intact,
 * since gapless and crossfade are mutually exclusive.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    private val engines = arrayOfNulls<ExoPlayer>(2)
    private val wrappers = arrayOfNulls<RepeatAwarePlayer>(2)
    private var activeIndex = 0

    /** The engine you can hear, and the one the MediaSession is currently pointed at. */
    private val active: ExoPlayer? get() = engines[activeIndex]

    /** Idle, or fading in underneath [active] during a crossfade. */
    private val standby: ExoPlayer? get() = engines[1 - activeIndex]

    private var crossfading = false
    private var fadeStartedAt = 0L
    private var fadeSpanMs = 0L

    // When "Mix with other audio" is toggled in settings, re-apply audio focus handling on the
    // live player so the change takes effect without restarting playback.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_MIX_AUDIO) applyAudioFocus()
    }

    private val abListener: () -> Unit = { syncAbWatcher() }

    override fun onCreate() {
        super.onCreate()

        for (i in 0..1) {
            val exo = ExoPlayer.Builder(this)
                .setHandleAudioBecomingNoisy(true)
                .build()
            exo.addListener(engineListener(i))
            engines[i] = exo
            wrappers[i] = RepeatAwarePlayer(exo)
        }
        applyAudioFocus()

        mediaSession = MediaSession.Builder(this, wrappers[activeIndex]!!)
            .setSessionActivity(openUiIntent())
            .build()

        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
        AbLoop.addListener(abListener)
        handler.post(ticker)
    }

    /**
     * Events from the standby engine are none of our business — only the audible one drives
     * A-B markers and can abort a fade — so each engine gets a listener that knows its own index.
     */
    private fun engineListener(index: Int) = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            if (index != activeIndex) return
            AbLoop.clearIfNot(item?.mediaId)
            // The outgoing track ran off its own end mid-fade (we aim to finish just before this,
            // but timing is not exact). Hand over now rather than let it play the next track
            // underneath the one already fading in.
            if (crossfading) finishCrossfade()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (index == activeIndex && !isPlaying && crossfading) abortCrossfade()
        }

        override fun onPositionDiscontinuity(
            old: Player.PositionInfo,
            new: Player.PositionInfo,
            reason: Int
        ) {
            // A seek or a manual skip means the user overrode the transition we were mid-way through.
            if (index == activeIndex && crossfading && reason == Player.DISCONTINUITY_REASON_SEEK) {
                abortCrossfade()
            }
        }
    }

    // ---------------- Crossfade ----------------

    private val ticker = object : Runnable {
        override fun run() {
            if (crossfading) stepCrossfade() else maybeStartCrossfade()
            val next = when {
                crossfading -> FADE_STEP_MS
                active?.isPlaying == true -> POLL_PLAYING_MS
                else -> POLL_IDLE_MS
            }
            handler.postDelayed(this, next)
        }
    }

    private fun maybeStartCrossfade() {
        val a = active ?: return
        val configured = Prefs.crossfadeSec(this) * 1000L
        if (configured <= 0L) return
        if (!a.isPlaying) return

        // Explicitly excluded: repeat-one is a request to hear *this* track again, not to blend it
        // into anything. Same reasoning for an armed A-B loop, which repeats inside one track.
        if (a.repeatMode == Player.REPEAT_MODE_ONE) return
        if (AbLoop.isArmed) return

        val duration = a.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return

        val next = a.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return                 // last track with repeat off — just end.
        if (next == a.currentMediaItemIndex) return       // repeat-all on a one-track queue.

        // Don't let a long crossfade swallow a short track: a fade is capped at a third of the
        // outgoing track, so a 12s setting over a 20s interlude becomes a ~6s fade, not a wash.
        val span = minOf(configured, duration / 3)
        if (span < MIN_FADE_MS) return
        if (duration - a.currentPosition > span) return

        startCrossfade(next, span)
    }

    private fun startCrossfade(nextIndex: Int, span: Long) {
        val a = active ?: return
        val b = standby ?: return

        val items = ArrayList<MediaItem>(a.mediaItemCount)
        for (i in 0 until a.mediaItemCount) items += a.getMediaItemAt(i)
        if (nextIndex !in items.indices) return

        // The standby engine never requests audio focus — it and the active engine are both ours,
        // and a focus request from one would be reported to the other as a loss, pausing it.
        b.setAudioAttributes(MUSIC_ATTRIBUTES, /* handleAudioFocus = */ false)
        b.repeatMode = a.repeatMode
        b.shuffleModeEnabled = a.shuffleModeEnabled
        b.setMediaItems(items, nextIndex, 0L)
        b.volume = 0f
        b.prepare()
        b.play()

        fadeSpanMs = span
        fadeStartedAt = SystemClock.uptimeMillis()
        crossfading = true
    }

    private fun stepCrossfade() {
        val a = active ?: return
        val b = standby ?: return
        val t = ((SystemClock.uptimeMillis() - fadeStartedAt).toFloat() / fadeSpanMs).coerceIn(0f, 1f)

        // Equal-power (sin/cos), not linear. Two different tracks are uncorrelated signals, so
        // linear ramps that cross at 0.5 sum to ~3dB below either track — an audible sag in the
        // middle of every transition. sin/cos crosses at 0.707 and keeps summed power flat.
        val angle = t * (Math.PI / 2).toFloat()
        a.volume = cos(angle)
        b.volume = sin(angle)

        if (t >= 1f) finishCrossfade()
    }

    /** The fade is done: the engine that was fading in is now simply the player. */
    private fun finishCrossfade() {
        val session = mediaSession ?: return
        val old = active ?: return
        val new = standby ?: return
        crossfading = false

        new.volume = 1f
        old.stop()
        old.clearMediaItems()
        old.volume = 1f

        activeIndex = 1 - activeIndex
        session.setPlayer(wrappers[activeIndex]!!)
        applyAudioFocus()   // focus is only requested once the outgoing engine has let go of it.
    }

    /** User paused, seeked or skipped mid-fade — drop the incoming track and restore full volume. */
    private fun abortCrossfade() {
        if (!crossfading) return
        crossfading = false
        standby?.apply {
            stop()
            clearMediaItems()
            volume = 0f
        }
        active?.volume = 1f
    }

    // ---------------- A-B loop ----------------

    /**
     * Wraps playback from B back to A. Polling (rather than a one-shot alarm) keeps the loop honest
     * when the user seeks inside the region or pauses partway through: each pass re-reads the real
     * position and re-times itself against it.
     */
    private val abWatcher = object : Runnable {
        override fun run() {
            val player = active ?: return
            if (!AbLoop.isArmed) return  // disarmed between posts — stop rescheduling.
            val a = AbLoop.aMs
            val b = AbLoop.bMs
            val position = player.currentPosition

            if (position >= b) {
                player.seekTo(a)
                handler.postDelayed(this, AB_POLL_MIN_MS)
            } else {
                // Sleep until we expect to reach B, but wake at least ~4x/sec so a seek or a pause
                // during the region doesn't leave us sitting on a stale deadline.
                handler.postDelayed(this, (b - position).coerceIn(AB_POLL_MIN_MS, AB_POLL_MAX_MS))
            }
        }
    }

    private fun syncAbWatcher() {
        handler.removeCallbacks(abWatcher)
        if (AbLoop.isArmed) handler.post(abWatcher)
    }

    // ---------------- Audio focus ----------------

    /**
     * Mixing means *not* requesting audio focus, so other players keep going; disabling it makes
     * us grab focus (pausing others) and respond to interruptions like a normal media app.
     *
     * Only the audible engine ever asks for focus — see [startCrossfade].
     */
    private fun applyAudioFocus() {
        active?.setAudioAttributes(MUSIC_ATTRIBUTES, /* handleAudioFocus = */ !Prefs.mixAudio(this))
        standby?.setAudioAttributes(MUSIC_ATTRIBUTES, /* handleAudioFocus = */ false)
    }

    private fun prefs(): SharedPreferences =
        getSharedPreferences(ThemeManager.PREFS, MODE_PRIVATE)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // If the user swipes the app away while nothing is playing, tear down the service.
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(abWatcher)
        AbLoop.removeListener(abListener)
        AbLoop.clear()
        prefs().unregisterOnSharedPreferenceChangeListener(prefsListener)

        mediaSession?.release()
        mediaSession = null
        for (i in 0..1) {
            engines[i]?.release()
            engines[i] = null
            wrappers[i] = null
        }
        super.onDestroy()
    }

    private fun openUiIntent(): PendingIntent {
        val intent = Intent(this, NowPlayingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private companion object {
        const val FADE_STEP_MS = 40L      // 25 volume updates a second — smooth to the ear.
        const val POLL_PLAYING_MS = 250L
        const val POLL_IDLE_MS = 1000L
        const val MIN_FADE_MS = 500L

        const val AB_POLL_MIN_MS = 20L
        const val AB_POLL_MAX_MS = 250L

        val MUSIC_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
    }
}
