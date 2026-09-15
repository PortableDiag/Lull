package com.lull.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

/**
 * The tempo estimator, against audio with a tempo we already know because we built it.
 *
 * This is the same reasoning [ShuffleTest] runs on: the claim is about a signal rather than about a
 * value, and "it looked right on one track" is not a check. A generated click track has an exactly
 * known answer, so an estimator that is a few percent out — enough to make Force Tempo play a
 * 128 BPM track at 1.14x instead of 1.09x, which is audible — fails here rather than on a run.
 *
 * The two ways to be wrong that matter most are both covered: the wrong *octave* (reporting half or
 * double the real tempo) and the false positive (reporting a tempo for audio that has none, which
 * is how an audiobook would end up played at 1.3x).
 */
class BpmAnalysisTest {

    private val sampleRate = 44_100

    /**
     * A click track: a decaying burst of noise on every beat over a quiet noise floor.
     *
     * Noise rather than a tone because the estimator works on energy rises, and a tone would test
     * a signal no drum ever produces. The floor is there because a digitally silent gap makes the
     * log-domain rise infinite, which would flatter the estimator.
     */
    private fun clicks(bpm: Float, seconds: Int, seed: Int = 7): ShortArray {
        val random = Random(seed)
        val out = ShortArray(sampleRate * seconds)
        for (i in out.indices) out[i] = (random.nextFloat() * 2f - 1f).times(600).toInt().toShort()

        val period = (60f / bpm * sampleRate).toInt()
        val burst = sampleRate / 20                       // 50ms
        var start = 0
        while (start + burst < out.size) {
            for (i in 0 until burst) {
                val decay = exp(-6f * i / burst)
                val value = (random.nextFloat() * 2f - 1f) * 26_000f * decay
                out[start + i] = value.toInt().coerceIn(-32_768, 32_767).toShort()
            }
            start += period
        }
        return out
    }

    private fun estimate(pcm: ShortArray, channels: Int = 1) =
        BpmAnalysis.estimate(BpmAnalysis.envelope(pcm, pcm.size, sampleRate, channels))

    // ---------------- It finds the tempo ----------------

    @Test
    fun `finds the tempo of a click track`() {
        // 160 is in here on purpose: its beat lands every 37.5 envelope frames, which is the case
        // a whole-frame lag search reads as 80 BPM. See BpmAnalysis.correlate.
        for (bpm in listOf(90f, 110f, 128f, 140f, 160f, 175f, 200f)) {
            val estimate = estimate(clicks(bpm, 30))
            assertNotNull("no tempo found at $bpm BPM", estimate)
            assertEquals("at $bpm BPM", bpm, estimate!!.bpm, 2f)
        }
    }

    @Test
    fun `finds the same tempo in stereo as in mono`() {
        val mono = clicks(128f, 30)
        val stereo = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            stereo[i * 2] = mono[i]
            stereo[i * 2 + 1] = mono[i]
        }
        val a = estimate(mono)!!
        val b = BpmAnalysis.estimate(BpmAnalysis.envelope(stereo, stereo.size, sampleRate, 2))!!
        assertEquals(a.bpm, b.bpm, 1f)
    }

    @Test
    fun `an ambiguous octave resolves to the beat, not to the bar`() {
        // A uniform click train correlates just as well at one beat as at two, so nothing in the
        // signal rules out half the tempo. The tie-break has to pick the faster reading, because
        // the slower one would mean every other click landed on nothing.
        for (bpm in listOf(180f, 200f)) {
            assertEquals("at $bpm BPM", bpm, estimate(clicks(bpm, 30))!!.bpm, 3f)
        }
    }

    @Test
    fun `a genuinely slow track is not talked into being twice as fast`() {
        // The other side of the same tie-break: at 70 BPM there is no peak at 35 frames' worth of
        // lag, so nothing should tempt it upward.
        assertEquals(70f, estimate(clicks(70f, 30))!!.bpm, 3f)
    }

    @Test
    fun `a confident answer is a confident answer`() {
        assertTrue(estimate(clicks(128f, 30))!!.confidence > 0.3f)
    }

    // ---------------- It declines to invent one ----------------

    @Test
    fun `silence has no tempo`() {
        assertNull(estimate(ShortArray(sampleRate * 30)))
    }

    @Test
    fun `noise has no tempo`() {
        val random = Random(11)
        val pcm = ShortArray(sampleRate * 30) {
            (random.nextFloat() * 2f - 1f).times(12_000).toInt().toShort()
        }
        assertNull("a tempo was invented for white noise", estimate(pcm))
    }

    @Test
    fun `too short to judge is not judged`() {
        // Four seconds cannot contain enough laps of a slow beat to correlate against itself.
        assertNull(estimate(clicks(128f, 4)))
    }

    // ---------------- The octave ----------------

    @Test
    fun `the preference weight favours the perceived pulse over its harmonics`() {
        assertTrue(BpmAnalysis.preference(120f) > BpmAnalysis.preference(60f))
        assertTrue(BpmAnalysis.preference(120f) > BpmAnalysis.preference(240f))
        assertEquals(1f, BpmAnalysis.preference(BpmAnalysis.PREFERRED_BPM), 0.001f)
    }

    @Test
    fun `an answer is always inside the range it advertises`() {
        for (bpm in listOf(55f, 70f, 100f, 160f, 200f)) {
            val estimate = estimate(clicks(bpm, 30)) ?: continue
            assertTrue(
                "${estimate.bpm} is outside the advertised range",
                estimate.bpm >= BpmAnalysis.MIN_BPM && estimate.bpm <= BpmAnalysis.MAX_BPM
            )
        }
    }

    // ---------------- The envelope ----------------

    @Test
    fun `the envelope is one frame per hundredth of a second`() {
        val pcm = clicks(120f, 10)
        val envelope = BpmAnalysis.envelope(pcm, pcm.size, sampleRate, 1)
        assertTrue(
            "${envelope.size} frames for 10 seconds",
            kotlin.math.abs(envelope.size - 10 * BpmAnalysis.FPS) <= 2
        )
    }

    @Test
    fun `the envelope keeps rises and discards falls`() {
        val pcm = clicks(120f, 10)
        val envelope = BpmAnalysis.envelope(pcm, pcm.size, sampleRate, 1)
        assertTrue("an onset envelope cannot go negative", envelope.all { it >= 0f })
        assertTrue("a click track should produce some onsets", envelope.any { it > 0.5f })
    }

    @Test
    fun `an empty or nonsensical buffer is not a crash`() {
        assertEquals(0, BpmAnalysis.envelope(ShortArray(0), 0, sampleRate, 1).size)
        assertEquals(0, BpmAnalysis.envelope(ShortArray(100), 100, 0, 1).size)
        assertNull(BpmAnalysis.estimate(FloatArray(0)))
    }

    // ---------------- End to end, the way the feature uses it ----------------

    @Test
    fun `a measured tempo drives the speed the user actually hears`() {
        val estimate = estimate(clicks(96f, 30))!!
        val speed = Tempo.speedFor(estimate.bpm, 140, 30, true)
        // 96 is genuinely slow — no folding excuse — so it should be pushed to the 30% ceiling.
        assertEquals(1.30f, speed, 0.01f)
    }
}
