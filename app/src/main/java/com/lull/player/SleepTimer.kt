package com.lull.player

import android.os.SystemClock
import kotlin.math.cos

/**
 * Sleep timer: keep playing for a set stretch, fade out over the last [FADE_MS], then pause.
 *
 * Like [AbLoop], the state lives here rather than on the player because the timer has to outlive
 * the screen it was set from — [PlaybackService] runs the countdown, so it keeps ticking with the
 * UI closed and the screen off. Service and activities share a process, so a plain singleton is
 * enough, and all mutation happens on the main thread.
 *
 * The deadline is held in [SystemClock.elapsedRealtime], which counts *through* device sleep.
 * "Stop in 30 minutes" is a promise about wall-clock time, and uptime-based clocks stop when the
 * device dozes — which is precisely the state this feature is used in.
 *
 * Nothing here is persisted. A countdown that survived a restart would be a promise about a device
 * that was switched off; only the *duration* you last picked is remembered, by [Prefs].
 */
object SleepTimer {

    /** No timer set. */
    const val UNSET = 0L

    /** How much of the timer is spent fading out, where there is room for it. */
    const val FADE_MS = 30_000L

    var deadlineMs: Long = UNSET
        private set

    /** The full duration this timer was armed for; caps the fade on short timers. */
    var totalMs: Long = UNSET
        private set

    val isArmed: Boolean get() = deadlineMs != UNSET

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }

    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun notifyChanged() = listeners.toList().forEach { it() }

    fun remainingMs(): Long =
        if (!isArmed) 0L else (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    /** Never more than half the timer, so a 1-minute timer doesn't spend 30s fading. */
    private val fadeMs: Long get() = minOf(FADE_MS, totalMs / 2)

    /** True once the fade has started — the service steps volume finely while this holds. */
    val isFading: Boolean get() = isArmed && remainingMs() <= fadeMs

    /**
     * Volume multiplier for right now: 1 until the fade starts, then a raised cosine down to 0
     * at the deadline.
     *
     * Raised cosine rather than a straight line because it is *flat at both ends*: the fade eases
     * in without an audible step into it, and settles onto silence instead of arriving at it with
     * the volume still dropping. On a track you are falling asleep to, the moment a fade visibly
     * begins is as disruptive as the moment it ends, and a linear ramp announces both.
     */
    fun gain(): Float {
        if (!isArmed) return 1f
        val span = fadeMs
        if (span <= 0L) return 1f
        val left = remainingMs()
        if (left >= span) return 1f
        val t = 1f - left.toFloat() / span              // 0 when the fade starts, 1 at the deadline
        return (0.5 * (1.0 + cos(Math.PI * t))).toFloat().coerceIn(0f, 1f)
    }

    /** Arms (or re-arms) the timer. A non-positive duration cancels instead. */
    fun arm(durationMs: Long) {
        if (durationMs <= 0L) { cancel(); return }
        totalMs = durationMs
        deadlineMs = SystemClock.elapsedRealtime() + durationMs
        notifyChanged()
    }

    fun cancel() {
        if (!isArmed) return
        deadlineMs = UNSET
        totalMs = UNSET
        notifyChanged()
    }
}
