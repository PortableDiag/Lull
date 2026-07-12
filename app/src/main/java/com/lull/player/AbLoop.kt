package com.lull.player

/**
 * A–B repeat: loop the region between two in-track markers.
 *
 * The markers live here rather than on the player because the loop has to outlive the Now Playing
 * screen — [PlaybackService] enforces the wrap, so it keeps looping with the UI closed and the
 * screen off. Service and activities share a process, so a plain singleton is enough.
 *
 * All mutation happens on the main thread; the service's watcher reads the markers from the same
 * thread it seeks on.
 */
object AbLoop {

    /** No marker set. */
    const val UNSET = -1L

    /** Shortest loop we accept, so a fumbled double-tap can't trap playback in a stutter. */
    const val MIN_SPAN_MS = 500L

    /**
     * B is kept this far from the end of the track. The watcher polls, so a B sitting exactly on
     * the final sample could be overshot into an automatic track change before we seek back.
     */
    const val END_GUARD_MS = 150L

    /** Media id the markers belong to; they are dropped when playback moves to another track. */
    var mediaId: String? = null
        private set
    var aMs: Long = UNSET
        private set
    var bMs: Long = UNSET
        private set

    /** Both markers placed — the service should be looping. */
    val isArmed: Boolean get() = aMs != UNSET && bMs != UNSET

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }

    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun notifyChanged() = listeners.toList().forEach { it() }

    /** Drops any existing B, since a new A almost always precedes picking a fresh end point. */
    fun setA(id: String, positionMs: Long) {
        mediaId = id
        aMs = positionMs.coerceAtLeast(0L)
        bMs = UNSET
        notifyChanged()
    }

    /**
     * @return false if A isn't set yet, we've moved to another track, or the region is too short —
     *   the caller reports why; the markers are left untouched.
     */
    fun setB(id: String, positionMs: Long, durationMs: Long): Boolean {
        if (aMs == UNSET || id != mediaId) return false
        val ceiling = if (durationMs > 0) durationMs - END_GUARD_MS else positionMs
        val b = positionMs.coerceAtMost(ceiling)
        if (b - aMs < MIN_SPAN_MS) return false
        bMs = b
        notifyChanged()
        return true
    }

    fun clear() {
        if (mediaId == null && aMs == UNSET && bMs == UNSET) return
        mediaId = null
        aMs = UNSET
        bMs = UNSET
        notifyChanged()
    }

    /** Called on track change: markers are meaningless against a different track. */
    fun clearIfNot(id: String?) {
        if (mediaId != null && mediaId != id) clear()
    }
}
