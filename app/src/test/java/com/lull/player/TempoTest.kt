package com.lull.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Force Tempo's arithmetic.
 *
 * Every case here is one somebody hits on a real playlist, and most of them are cases where the
 * obvious formula (`target / source`) gives an answer that is wrong rather than merely imprecise —
 * which is exactly the kind of wrong that is invisible until you are three tracks into a run.
 */
class TempoTest {

    private fun speed(source: Number, target: Int, maxChange: Int = 30, onlyFaster: Boolean = true) =
        Tempo.speedFor(source.toFloat(), target, maxChange, onlyFaster)

    // ---------------- The ordinary case ----------------

    @Test
    fun `a slightly slow track is nudged up to the target`() {
        assertEquals(140f / 128f, speed(128, 140), 0.001f)
    }

    @Test
    fun `a track already at the target is left exactly alone`() {
        assertEquals(1f, speed(140, 140), 0f)
    }

    // ---------------- Octave folding ----------------

    @Test
    fun `a half-time track is recognised, not doubled`() {
        // 70 BPM counted in double time is 140 — the pace already matches, so nothing should move.
        // target/source would say 2.0x, which would be a catastrophe rather than a workout.
        assertEquals(1f, speed(70, 140), 0.001f)
    }

    @Test
    fun `a nearly-half-time track folds and then gets its small nudge`() {
        // 64 doubles to 128, which is the reading that lands near 140; the stretch is 140/128.
        assertEquals(140f / 128f, speed(64, 140), 0.001f)
    }

    @Test
    fun `a track that is merely slow is not excused as a half-time one`() {
        // The trap: 95 doubles to 190, which is "closer" to 140 in ratio than 95 is. Accepting that
        // would leave a genuinely slow track untouched in the middle of a workout. 190 is nowhere
        // near 140, so the fold is refused and the track is stretched as far as it is allowed.
        assertEquals(1.30f, speed(95, 140), 0.001f)
    }

    @Test
    fun `folding is symmetric — a double-time reading halves`() {
        // 280 is out of any detector's range, but the arithmetic should still halve it to 140.
        assertEquals(1f, speed(280, 140), 0.001f)
    }

    // ---------------- Only faster ----------------

    @Test
    fun `a fast track is left alone rather than slowed down`() {
        assertEquals(1f, speed(175, 140), 0f)
    }

    @Test
    fun `switching only-faster off does slow a fast track`() {
        assertEquals(0.8f, speed(175, 140, onlyFaster = false), 0.001f)
    }

    @Test
    fun `slowing down is clamped by the same percentage as speeding up`() {
        // 1/1.30, not 1 - 0.30: the limit is a ratio in both directions.
        assertEquals(1f / 1.30f, speed(400, 140, onlyFaster = false), 0.001f)
    }

    // ---------------- The clamp ----------------

    @Test
    fun `nothing is stretched past the ceiling`() {
        for (source in 60..200) {
            val s = speed(source, 200, maxChange = 25)
            assertTrue("$source BPM produced ${s}x", s <= 1.25f + 1e-4f)
            assertTrue("$source BPM produced ${s}x", s >= 1f)
        }
    }

    @Test
    fun `a bigger allowance actually reaches further`() {
        assertTrue(speed(95, 140, maxChange = 60) > speed(95, 140, maxChange = 30))
    }

    // ---------------- Unknown tempos ----------------

    @Test
    fun `a track with no tempo is never touched`() {
        assertEquals(1f, speed(0, 140), 0f)
        assertEquals(1f, speed(-1, 140), 0f)
    }

    @Test
    fun `a target of zero is not a request to stop`() {
        assertEquals(1f, speed(128, 0), 0f)
    }

    // ---------------- Reporting ----------------

    @Test
    fun `the effective tempo is what the user was promised`() {
        val s = speed(128, 140)
        assertEquals(140f, Tempo.effectiveBpm(128f, s), 0.1f)
    }

    @Test
    fun `a clamped track reports the tempo it actually reaches, not the target`() {
        val s = speed(95, 140)
        assertEquals(95f * 1.30f, Tempo.effectiveBpm(95f, s), 0.1f)
    }

    // ---------------- Track time against clock time ----------------

    @Test
    fun `a forced track's remaining time shrinks on the clock`() {
        // Six seconds of music at 1.3x is 4.6 seconds of listening. A crossfade that believed the
        // first number would start late and be cut off by the transition it was meant to cover.
        assertEquals(4615L, Tempo.wallClockMs(6_000L, 1.3f))
        assertEquals(6_000L, Tempo.wallClockMs(6_000L, 1f))
        assertEquals(7_500L, Tempo.wallClockMs(6_000L, 0.8f))
    }

    @Test
    fun `a nonsensical speed cannot divide by zero`() {
        assertTrue(Tempo.wallClockMs(6_000L, 0f) > 0L)
        assertTrue(Tempo.wallClockMs(6_000L, -1f) > 0L)
    }

    @Test
    fun `speed is formatted the way the panel shows it`() {
        assertEquals("1.09x", Tempo.formatSpeed(1.09375f))
        assertEquals("1.00x", Tempo.formatSpeed(1f))
    }
}
