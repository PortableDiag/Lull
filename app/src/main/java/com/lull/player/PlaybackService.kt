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
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Background playback host. Media3's MediaSessionService runs as a foreground service with
 * a media notification while playing, and routes lock-screen / Bluetooth / headset media
 * buttons to the session player (our [RepeatAwarePlayer]).
 *
 * It also owns the three features a single ExoPlayer can't express on its own:
 *
 *  - the [AbLoop] region, wrapped from B back to A by a poller, so it survives the UI closing;
 *  - the [SleepTimer] countdown and its fade-to-pause, for the same reason — it has to keep
 *    running with the screen off, which is the only state it is ever used in;
 *  - crossfade, which is *by definition* two tracks sounding at once. One ExoPlayer decodes one
 *    stream, so we keep two engines: one audible, one warming up the next track. At the end of a
 *    fade the standby engine becomes the session's player and the roles swap.
 *  - Force Tempo, which needs a *per track* speed and therefore something that knows which track
 *    is about to play — see [applyTempo]. It is here rather than in the UI for the usual reason:
 *    the speed has to be right on a transition that happens with the app closed.
 *
 * With crossfade set to 0 (the default) the second engine is never started and playback follows
 * exactly the same path it did before the feature existed — which is what keeps gapless intact,
 * since gapless and crossfade are mutually exclusive.
 *
 * Trim silence — the third transition-shaping feature — is not part of that trade-off; see
 * [applySkipSilence].
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

    // Crossfade's own gain for each engine. The sleep timer scales both on top of these; see
    // [applyVolumes], which is the only thing that writes ExoPlayer.volume.
    private var crossActive = 1f
    private var crossStandby = 0f

    // Settings that the MediaController can't carry (neither is part of the Player interface) are
    // toggled through preferences and picked up here, so they take effect without restarting
    // playback: audio focus handling for "Mix with other audio", skip-silence for "Trim silence".
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == Prefs.KEY_MIX_AUDIO -> applyAudioFocus()
            key == Prefs.KEY_SKIP_SILENCE -> applySkipSilence()
            key in Prefs.TEMPO_KEYS -> applyTempo()
        }
    }

    /**
     * Working a tempo out means decoding audio, which is not something to do on the thread that is
     * also playing it. One worker, so a queue of unanalysed tracks is worked through in order
     * rather than all at once on a phone that has other things to do.
     */
    private val tempoWorker = Executors.newSingleThreadExecutor()

    /** Ids currently with the worker, so a slow analysis is not queued a second time behind itself. */
    private val analysing = HashSet<Long>()

    /** A tempo corrected by hand, or one the worker has just written down, changes the speed now. */
    private val tempoListener: () -> Unit = { handler.post { applyTempo() } }

    /** The dead spots in the track now playing, at the current threshold. Re-read on a change. */
    private var quietSections: List<EnergyProfile.Section> = emptyList()

    /** The speed the audible engine is heading for, and the one it is actually at. */
    private var targetSpeed = Tempo.UNCHANGED
    private var rampingSpeed = false

    private val abListener: () -> Unit = { syncAbWatcher() }

    /**
     * Arming while paused (or with crossfade off) must not have to wait out a one-second idle
     * poll before the countdown starts moving, so re-time the ticker the moment it changes.
     */
    private val sleepListener: () -> Unit = {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onCreate() {
        super.onCreate()

        for (i in 0..1) {
            val exo = ExoPlayer.Builder(this)
                .setHandleAudioBecomingNoisy(true)
                .setSkipSilenceEnabled(Prefs.skipSilence(this))
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
        SleepTimer.addListener(sleepListener)
        TempoStore.addListener(tempoListener)
        ProfileStore.addListener(tempoListener)
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
            applyTempo()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (index == activeIndex && !isPlaying && crossfading) abortCrossfade()
        }

        override fun onPositionDiscontinuity(
            old: Player.PositionInfo,
            new: Player.PositionInfo,
            reason: Int
        ) {
            // A seek or a manual skip means the user overrode the transition we were mid-way
            // through. Only a real seek: trimming a silence also jumps the position, but it
            // reports DISCONTINUITY_REASON_SILENCE_SKIP and is nobody's decision to abort a fade.
            if (index == activeIndex && crossfading && reason == Player.DISCONTINUITY_REASON_SEEK) {
                abortCrossfade()
            }
        }
    }

    // ---------------- Force Tempo ----------------

    /**
     * Points the audible engine at the speed this track should play at, and gets the *next* track's
     * tempo worked out while there is still a track's worth of time to do it in.
     *
     * The prefetch is the difference between the feature working and the feature being noticeable.
     * Analysis takes a fraction of a second, but a fraction of a second at the wrong speed is at
     * the very start of a track, which is exactly where a change of speed is audible as a lurch.
     * Doing the next track now means the only track ever played at the wrong speed is the first one
     * after you switch the feature on — and that one is already playing when you switch it.
     *
     * Speed is set on the ExoPlayer rather than through the session, so it composes with everything
     * else here: it is pitch-preserving (ExoPlayer time-stretches with Sonic), independent of the
     * volume gains crossfade and the sleep timer multiply together, and below the audio sink where
     * trim-silence works.
     */
    private fun applyTempo() {
        val a = active ?: return
        quietSections = sectionsFor(a.currentMediaItem)

        // Straight to the base speed, without a ramp: this is a track change, a settings change or
        // a newly measured tempo, and in all three the right speed is the one to start from. The
        // ramp exists for the boundary of a dead spot *within* a track and nothing else.
        val base = speedFor(a.currentMediaItem)
        targetSpeed = base
        rampingSpeed = false
        a.setPlaybackSpeed(base)
        stepQuiet()

        requestDetection(a.currentMediaItem)
        val next = a.nextMediaItemIndex
        if (next != C.INDEX_UNSET && next < a.mediaItemCount) requestDetection(a.getMediaItemAt(next))
    }

    /** What [item] should play at: 1.0 with the feature off, and 1.0 for a track with no tempo. */
    private fun speedFor(item: MediaItem?): Float {
        val id = item?.mediaId?.toLongOrNull() ?: return Tempo.UNCHANGED
        return Prefs.speedFor(this, TempoStore.bpmFor(this, id))
    }

    private fun sectionsFor(item: MediaItem?): List<EnergyProfile.Section> {
        if (!Prefs.forceTempo(this) || !Prefs.pushQuiet(this)) return emptyList()
        val id = item?.mediaId?.toLongOrNull() ?: return emptyList()
        return ProfileStore.sectionsFor(this, id, Prefs.quietThresholdDb(this))
    }

    // ---------------- Pushing through the quiet parts ----------------

    /**
     * Decides, every tick, whether the track is in one of its dead spots — and moves the engine's
     * speed **toward** the answer rather than to it.
     *
     * Two details carry the whole feel of this:
     *
     *  - **A ramp, not a step.** Slamming from 1.15x to 1.7x between two audio buffers is a lurch,
     *    and a lurch is exactly the thing the listener notices instead of the music. Over
     *    [RAMP_MS] the change reads as the track leaning forward.
     *  - **A lookahead.** The ramp is started [RAMP_LOOKAHEAD_MS] *before* the section does, so it
     *    has finished arriving by the time the quiet part actually starts. Beginning the ramp on
     *    the boundary means the first seconds of the dead spot — the ones most worth skipping — are
     *    the ones played at the old speed.
     *
     * Position is read from the player each tick rather than integrated, so a seek into or out of a
     * section is handled by the same code path as playing into one.
     */
    private fun stepQuiet() {
        val a = active ?: return
        val base = speedFor(a.currentMediaItem)

        val inSection = quietSections.isNotEmpty() &&
            EnergyProfile.sectionAt(quietSections, a.currentPosition, RAMP_LOOKAHEAD_MS) != null
        targetSpeed = Prefs.speedFor(this, bpmOf(a.currentMediaItem), inSection)

        val current = a.playbackParameters.speed
        if (kotlin.math.abs(current - targetSpeed) < SPEED_EPSILON) {
            rampingSpeed = false
            return
        }
        // The step is sized off the *base* speed so that the ramp takes the same time whatever the
        // boost is set to — a 150% boost should not arrive three times faster than a 50% one.
        val step = (base * RAMP_PER_TICK).coerceAtLeast(MIN_RAMP_STEP)
        val next = if (targetSpeed > current) minOf(targetSpeed, current + step)
                   else maxOf(targetSpeed, current - step)
        a.setPlaybackSpeed(next)
        rampingSpeed = kotlin.math.abs(next - targetSpeed) >= SPEED_EPSILON
    }

    private fun bpmOf(item: MediaItem?): Float {
        val id = item?.mediaId?.toLongOrNull() ?: return 0f
        return TempoStore.bpmFor(this, id)
    }

    /**
     * Queues a track for analysis, at most once.
     *
     * Nothing is analysed while the feature is off: switching it on is the moment the work becomes
     * worth doing, and a player that quietly decoded every track you played on the chance you might
     * one day want this would be spending your battery on a guess.
     */
    private fun requestDetection(item: MediaItem?) {
        if (!Prefs.forceTempo(this)) return
        val id = item?.mediaId?.toLongOrNull() ?: return
        val uri = item.localConfiguration?.uri ?: return
        val wantsProfile = Prefs.pushQuiet(this) && ProfileStore.needsAnalysis(this, id)
        if (!TempoStore.needsDetection(this, id) && !wantsProfile) return
        if (!analysing.add(id)) return
        tempoWorker.execute {
            val analysis = TrackAnalysis.analyse(this, uri)
            handler.post {
                analysing.remove(id)
                // UNKNOWN means the analysis itself failed — a file that would not open, a codec
                // that would not start. Writing that down would retire the track permanently on
                // the strength of one bad moment, so it is simply left for next time.
                if (analysis.bpm != TempoStore.UNKNOWN) TempoStore.setDetected(this, id, analysis.bpm)
                // The profile is written even when it came back empty, which records "measured,
                // nothing usable" and stops the file being re-decoded on every play. Only a run
                // that failed outright leaves the track unmeasured.
                if (analysis.bpm != TempoStore.UNKNOWN || analysis.profile.isNotEmpty()) {
                    ProfileStore.set(this, id, analysis.profile)
                }
            }
        }
    }

    /** The engine's speed, floored so that it can never be divided by zero. */
    private fun speedOf(player: ExoPlayer): Float =
        player.playbackParameters.speed.coerceAtLeast(Tempo.MIN_SPEED)

    // ---------------- Crossfade ----------------

    private val ticker = object : Runnable {
        override fun run() {
            if (crossfading) stepCrossfade() else maybeStartCrossfade()
            stepSleep()
            if (active?.isPlaying == true) stepQuiet()
            val next = when {
                crossfading || SleepTimer.isFading || rampingSpeed -> FADE_STEP_MS
                active?.isPlaying == true -> POLL_PLAYING_MS
                else -> POLL_IDLE_MS
            }
            handler.postDelayed(this, next)
        }
    }

    /**
     * The only writer of engine volume.
     *
     * Crossfade and the sleep timer both want to control it, and either can be running while the
     * other is. So each contributes an independent gain and they multiply here: a sleep fade that
     * lands mid-crossfade dims the pair together, rather than the two of them overwriting each
     * other's value 25 times a second.
     */
    private fun applyVolumes() {
        val sleep = SleepTimer.gain()
        active?.volume = crossActive * sleep
        standby?.volume = crossStandby * sleep
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

        // Force Tempo makes media time and wall-clock time two different things, and the numbers
        // in this method are a mix of both: the fade is counted by the clock (see [stepCrossfade]),
        // while duration and position are counted in the track. At 1.3x a track's last 6 seconds
        // of *music* is 4.6 seconds of *fade*, and a crossfade timed against the wrong one either
        // starts late and gets cut off by the transition, or starts early and runs long.
        val speed = speedOf(a)
        val remaining = Tempo.wallClockMs(duration - a.currentPosition, speed)

        // Don't let a long crossfade swallow a short track: a fade is capped at a third of the
        // outgoing track, so a 12s setting over a 20s interlude becomes a ~6s fade, not a wash.
        val span = minOf(configured, Tempo.wallClockMs(duration, speed) / 3)
        if (span < MIN_FADE_MS) return
        if (remaining > span) return

        // Don't open a transition the sleep timer is about to close. Fading a new track in during
        // the last seconds before everything stops is wasted work, and it would make the track you
        // hear last one you never chose to end on.
        if (SleepTimer.isArmed && SleepTimer.remainingMs() <= span) return

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
        // The incoming track's own speed, set before it is heard. Two tracks with different tempos
        // fade into each other at the two speeds that bring them both to the one you asked for,
        // which is the only way the transition itself doesn't have a tempo change in it.
        b.setPlaybackSpeed(speedFor(items[nextIndex]))
        crossActive = 1f
        crossStandby = 0f
        applyVolumes()
        b.prepare()
        b.play()

        fadeSpanMs = span
        fadeStartedAt = SystemClock.uptimeMillis()
        crossfading = true
    }

    private fun stepCrossfade() {
        if (active == null || standby == null) return
        val t = ((SystemClock.uptimeMillis() - fadeStartedAt).toFloat() / fadeSpanMs).coerceIn(0f, 1f)

        // Equal-power (sin/cos), not linear. Two different tracks are uncorrelated signals, so
        // linear ramps that cross at 0.5 sum to ~3dB below either track — an audible sag in the
        // middle of every transition. sin/cos crosses at 0.707 and keeps summed power flat.
        val angle = t * (Math.PI / 2).toFloat()
        crossActive = cos(angle)
        crossStandby = sin(angle)
        applyVolumes()

        if (t >= 1f) finishCrossfade()
    }

    /** The fade is done: the engine that was fading in is now simply the player. */
    private fun finishCrossfade() {
        val session = mediaSession ?: return
        val old = active ?: return
        if (standby == null) return
        crossfading = false

        old.stop()
        old.clearMediaItems()

        activeIndex = 1 - activeIndex
        // Reset *after* the swap: crossActive/crossStandby name roles, not engines.
        crossActive = 1f
        crossStandby = 0f
        applyVolumes()

        session.setPlayer(wrappers[activeIndex]!!)
        applyAudioFocus()   // focus is only requested once the outgoing engine has let go of it.
        // The engine that just became audible already carries the right speed (set before it was
        // faded in), but nothing has yet looked ahead from its position in the queue — and the
        // transition listener stays quiet here, because for that engine no item changed.
        applyTempo()
    }

    /** User paused, seeked or skipped mid-fade — drop the incoming track and restore full volume. */
    private fun abortCrossfade() {
        if (!crossfading) return
        crossfading = false
        standby?.apply {
            stop()
            clearMediaItems()
        }
        crossActive = 1f
        crossStandby = 0f
        applyVolumes()
    }

    // ---------------- Sleep timer ----------------

    /**
     * Runs the countdown. Volume only needs stepping once the fade has started — until then the
     * gain is a flat 1 and writing it every tick would be pointless traffic to the audio sink.
     */
    private fun stepSleep() {
        if (!SleepTimer.isArmed) return
        if (SleepTimer.remainingMs() <= 0L) { finishSleep(); return }
        if (SleepTimer.isFading) applyVolumes()
    }

    /**
     * Timer expired: stop the music, *then* hand the volume back.
     *
     * That order is deliberate. Clearing the timer first would restore the gain to 1 while the
     * track is still sounding, so the last thing you'd hear is a fraction of a second at full
     * volume — exactly the thing a sleep timer exists to prevent. Restoring it afterwards leaves
     * the player ready to be pressed play again.
     *
     * Pause, not stop: the queue and your place in it survive, so this is recoverable in one tap.
     */
    private fun finishSleep() {
        if (crossfading) abortCrossfade()
        active?.pause()
        SleepTimer.cancel()
        applyVolumes()
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
                // during the region doesn't leave us sitting on a stale deadline. B is a position
                // in the track and the delay is a wall-clock one, so Force Tempo's speed converts
                // between them — otherwise a forced track overshoots B by the ratio of the two.
                val delay = Tempo.wallClockMs(b - position, speedOf(player))
                handler.postDelayed(this, delay.coerceIn(AB_POLL_MIN_MS, AB_POLL_MAX_MS))
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

    // ---------------- Trim silence ----------------

    /**
     * Silence trimming lives in ExoPlayer's audio sink, which shortens runs of near-silent PCM as
     * they are played out. That placement is what makes it a third, independent feature rather
     * than a variant of the other two: it sits *below* the track transition, so it trims dead air
     * in the middle of a track and the padding at its edges whether the tracks are joined gaplessly
     * or crossfaded, and it needs no scan of the file up front.
     *
     * Applied to both engines: the standby one is inaudible right now, but it is the engine you
     * will be listening to after the next fade.
     */
    private fun applySkipSilence() {
        val on = Prefs.skipSilence(this)
        for (engine in engines) engine?.skipSilenceEnabled = on
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
        SleepTimer.removeListener(sleepListener)
        SleepTimer.cancel()
        TempoStore.removeListener(tempoListener)
        ProfileStore.removeListener(tempoListener)
        tempoWorker.shutdownNow()
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

        /** How long the speed takes to move between the two sides of a dead spot's edge. */
        const val RAMP_MS = 800L

        /** Fraction of the base speed to move per [FADE_STEP_MS] tick — [RAMP_MS] worth of steps. */
        const val RAMP_PER_TICK = FADE_STEP_MS.toFloat() / RAMP_MS

        /** Start the ramp this far ahead of a section, so it has arrived by the time one starts. */
        const val RAMP_LOOKAHEAD_MS = RAMP_MS

        /** Close enough to the target to stop writing new speeds to the audio sink. */
        const val SPEED_EPSILON = 0.005f

        /** Keeps a ramp moving even if the base speed were tiny. */
        const val MIN_RAMP_STEP = 0.01f

        const val AB_POLL_MIN_MS = 20L
        const val AB_POLL_MAX_MS = 250L

        val MUSIC_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
    }
}
