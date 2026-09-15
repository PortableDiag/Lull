package com.lull.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding the dead spots in a track.
 *
 * The failure that matters here is not a crash — it is a track that speeds up somewhere it
 * shouldn't, on a phone, halfway through a run. The three cases worth most of the attention are
 * therefore the three *false positives*: the bar of space before a drop, a quietly mastered track
 * that is not quiet relative to itself, and a passage hovering on the threshold that would
 * otherwise be chopped into a dozen sections and ramp the speed up and down through all of them.
 */
class EnergyProfileTest {

    /** A profile is decibels below the track's reference, one byte per second. */
    private fun profile(vararg runs: Pair<Int, Int>): ByteArray {
        val out = ArrayList<Byte>()
        for ((seconds, db) in runs) repeat(seconds) { out += db.toByte() }
        return out.toByteArray()
    }

    private fun sections(p: ByteArray, threshold: Int = 9) = EnergyProfile.sections(p, threshold)

    // ---------------- Finding one ----------------

    @Test
    fun `a long quiet stretch in the middle is found`() {
        // 30s loud, 20s quiet, 30s loud.
        val s = sections(profile(30 to 0, 20 to 14, 30 to 0))
        assertEquals(1, s.size)
        assertEquals(30_000L, s[0].startMs)
        assertEquals(50_000L, s[0].endMs)
        assertEquals(20_000L, s[0].durationMs)
    }

    @Test
    fun `two separate dead spots stay separate`() {
        val s = sections(profile(20 to 0, 10 to 14, 40 to 0, 12 to 14, 10 to 0))
        assertEquals(2, s.size)
        assertEquals(20_000L, s[0].startMs)
        assertEquals(70_000L, s[1].startMs)
    }

    @Test
    fun `a quiet ending is still a dead spot`() {
        val s = sections(profile(30 to 0, 15 to 14))
        assertEquals(1, s.size)
        assertEquals(45_000L, s[0].endMs)
    }

    // ---------------- Refusing to find one ----------------

    @Test
    fun `the bar of space before a drop is not a dead spot`() {
        // Four seconds of near-silence, which is a build-up, not a dead spot — speeding through it
        // would wreck the one moment the track was building to.
        assertTrue(sections(profile(40 to 0, 4 to 20, 40 to 0)).isEmpty())
    }

    @Test
    fun `a uniformly quiet track has no dead spots`() {
        // Everything sits at the track's own reference, because the reference *is* the track. A
        // quietly mastered song must not read as one long dead spot.
        assertTrue(sections(profile(180 to 0)).isEmpty())
    }

    @Test
    fun `ordinary dynamics do not qualify`() {
        // A verse 5dB under the choruses is a song, not a dead spot, at the default threshold.
        assertTrue(sections(profile(30 to 0, 30 to 5, 30 to 0)).isEmpty())
    }

    @Test
    fun `an empty profile and a nonsense threshold are not crashes`() {
        assertTrue(EnergyProfile.sections(ByteArray(0), 9).isEmpty())
        assertTrue(EnergyProfile.sections(profile(30 to 14), 0).isEmpty())
        assertTrue(EnergyProfile.sections(profile(30 to 14), -5).isEmpty())
    }

    // ---------------- Hysteresis and merging ----------------

    @Test
    fun `a passage hovering on the threshold is one section, not many`() {
        // Alternating 10dB / 7dB around a threshold of 9. Each dip qualifies on its own and the
        // gaps between them are short, so merging makes it one section and one speed ramp rather
        // than five of each.
        val runs = ArrayList<Pair<Int, Int>>()
        runs += 20 to 0
        repeat(5) { runs += 3 to 10; runs += 3 to 7 }
        runs += 20 to 0
        val s = sections(profile(*runs.toTypedArray()))
        assertEquals(1, s.size)
    }

    @Test
    fun `a short dip does not swallow the rest of the track`() {
        // The regression this file exists for. A 4-second dip at 0:40, then material sitting
        // 2dB under the track's reference for the remaining 156 seconds. At a threshold of 3 an
        // earlier version entered at the dip and never left — because widening the threshold on
        // the way out let the run continue through seconds that did not qualify at all — and
        // reported one 160-second dead spot covering 80% of the song.
        val p = profile(40 to 0, 4 to 26, 156 to 2)
        val s = sections(p, 3)
        assertTrue("a 4-second dip became a section covering the rest of the track", s.isEmpty())
    }

    @Test
    fun `every second of a reported section qualifies on its own`() {
        // The invariant behind the fix: a section may never contain a second quieter than the
        // threshold asked for, whatever the merging did.
        val p = profile(10 to 0, 8 to 14, 2 to 12, 8 to 14, 30 to 1)
        for (threshold in listOf(5, 9, 13)) {
            for (section in EnergyProfile.sections(p, threshold)) {
                val from = (section.startMs / 1000).toInt()
                val to = (section.endMs / 1000).toInt()
                // Merged sections may bridge a short louder fill; the ends must still qualify.
                assertTrue(
                    "section start at ${from}s does not meet threshold $threshold",
                    (p[from].toInt() and 0xFF) >= threshold
                )
                assertTrue(
                    "section end at ${to - 1}s does not meet threshold $threshold",
                    (p[to - 1].toInt() and 0xFF) >= threshold
                )
            }
        }
    }

    @Test
    fun `two quiet stretches split by a short fill are one dead spot`() {
        val s = sections(profile(20 to 0, 8 to 14, 2 to 0, 8 to 14, 20 to 0))
        assertEquals(1, s.size)
        assertEquals(18_000L, s[0].durationMs)
    }

    @Test
    fun `a long loud passage really does split them`() {
        val s = sections(profile(20 to 0, 8 to 14, 30 to 0, 8 to 14, 20 to 0))
        assertEquals(2, s.size)
    }

    // ---------------- The threshold is a real control ----------------

    @Test
    fun `lowering the threshold finds more`() {
        val p = profile(20 to 0, 10 to 6, 20 to 0, 10 to 14, 20 to 0)
        assertEquals("only the deep one at 9dB", 1, sections(p, 9).size)
        assertEquals("both at 5dB", 2, sections(p, 5).size)
        assertEquals("neither at 20dB", 0, sections(p, 20).size)
    }

    // ---------------- Building a profile from audio ----------------

    @Test
    fun `a profile is relative to the track's own level`() {
        // The same shape at two masterings, 20dB apart, must produce the same profile.
        val loud = FloatArray(60) { if (it in 20..39) 0.05f else 0.5f }
        val quiet = FloatArray(60) { if (it in 20..39) 0.005f else 0.05f }
        val a = EnergyProfile.build(loud)
        val b = EnergyProfile.build(quiet)
        assertTrue("mastering level must not change the profile", a.contentEquals(b))
        assertEquals(1, EnergyProfile.sections(a, 9).size)
    }

    @Test
    fun `the reference is a high percentile, not the peak`() {
        // One second of a cymbal 20dB above everything else must not redefine the whole track.
        val rms = FloatArray(120) { if (it == 60) 1.0f else 0.1f }
        val p = EnergyProfile.build(rms)
        assertTrue("a single transient turned the track into a dead spot", EnergyProfile.sections(p, 9).isEmpty())
    }

    @Test
    fun `digital silence does not produce an infinite level`() {
        val rms = FloatArray(60) { if (it in 20..39) 0f else 0.5f }
        val p = EnergyProfile.build(rms)
        assertTrue(p.all { (it.toInt() and 0xFF) <= EnergyProfile.MAX_STORED_DB })
        assertEquals(1, EnergyProfile.sections(p, 9).size)
    }

    @Test
    fun `an empty track builds an empty profile`() {
        assertEquals(0, EnergyProfile.build(FloatArray(0)).size)
    }

    @Test
    fun `building a profile does not reorder the caller's data`() {
        val rms = floatArrayOf(0.5f, 0.1f, 0.9f, 0.2f)
        val copy = rms.copyOf()
        EnergyProfile.build(rms)
        assertTrue("build() scrambled its input", rms.contentEquals(copy))
    }

    // ---------------- Asking where we are ----------------

    @Test
    fun `sectionAt finds the section a position is inside`() {
        val s = sections(profile(30 to 0, 20 to 14, 30 to 0))
        assertTrue(35_000L in s[0])
        assertFalse(29_000L in s[0])
        assertFalse(50_000L in s[0])
        assertEquals(s[0], EnergyProfile.sectionAt(s, 35_000L))
        assertEquals(null, EnergyProfile.sectionAt(s, 10_000L))
    }

    @Test
    fun `the lookahead catches a section that is about to start`() {
        val s = sections(profile(30 to 0, 20 to 14, 30 to 0))
        // Without a lookahead the ramp starts on the boundary, so the first second of the dead
        // spot — the part most worth skipping — plays at the old speed.
        assertEquals(null, EnergyProfile.sectionAt(s, 29_400L))
        assertEquals(s[0], EnergyProfile.sectionAt(s, 29_400L, lookaheadMs = 800L))
    }

    @Test
    fun `the lookahead does not reach back into a section already over`() {
        val s = sections(profile(30 to 0, 20 to 14, 30 to 0))
        assertEquals(null, EnergyProfile.sectionAt(s, 50_000L, lookaheadMs = 800L))
    }

    @Test
    fun `quietMs totals what would be pushed through`() {
        val s = sections(profile(20 to 0, 10 to 14, 40 to 0, 12 to 14, 10 to 0))
        assertEquals(22_000L, EnergyProfile.quietMs(s))
    }

    // ---------------- What the speed actually becomes ----------------

    @Test
    fun `a boost multiplies the track's own forced speed`() {
        // 128 BPM toward a 140 target is 1.094x; a 50% boost inside a dead spot is ~1.64x.
        val base = Tempo.speedFor(128f, 140, 30, true)
        assertEquals(1.094f, base, 0.001f)
        assertEquals(1.641f, Tempo.boosted(base, 50, Prefs.MAX_TOTAL_SPEED), 0.001f)
    }

    @Test
    fun `a boost on an untouched track still pushes through its dead spots`() {
        // Force Tempo left this one at 1.0 (it was already fast enough). The dead spot inside it
        // is still a dead spot, so the boost has to apply to the base of 1.0.
        assertEquals(1.5f, Tempo.boosted(Tempo.UNCHANGED, 50, Prefs.MAX_TOTAL_SPEED), 0.001f)
    }

    @Test
    fun `the two multipliers cannot stack into a sound effect`() {
        assertEquals(Prefs.MAX_TOTAL_SPEED, Tempo.boosted(1.3f, 150, Prefs.MAX_TOTAL_SPEED), 0.001f)
    }

    @Test
    fun `a zero or negative boost changes nothing`() {
        assertEquals(1.2f, Tempo.boosted(1.2f, 0, Prefs.MAX_TOTAL_SPEED), 0.001f)
        assertEquals(1.2f, Tempo.boosted(1.2f, -40, Prefs.MAX_TOTAL_SPEED), 0.001f)
    }
}
