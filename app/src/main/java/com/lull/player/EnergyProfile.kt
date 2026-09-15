package com.lull.player

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where a track goes quiet, and for how long.
 *
 * Force Tempo fixes the *slow track* in a playlist. This fixes the other half of the same
 * complaint: the **quiet stretch inside a track** — the piano bridge in the middle of a rock song,
 * the ambient break before the last chorus. That stretch is not slower in tempo. A 140 BPM song
 * has a 140 BPM bridge, so a per-track speed leaves it exactly where it was, and the workout
 * stalls anyway. What makes it a dead spot is that the *energy* drops out.
 *
 * So the measurement is loudness over time, at one value per second, and the decision is
 * **relative to the track itself**. An absolute threshold would be useless: a quiet mastering and a
 * loud one are not a quiet section and a loud one, and a whole ambient album is not one long dead
 * spot. [REFERENCE_PERCENTILE] of the track's own seconds defines what "full" means for that track,
 * and a section is quiet when it sits the configured number of decibels under that.
 *
 * Three rules stop it from firing on things that are not dead spots:
 *
 *  - **A minimum length.** A bar of held breath before a drop is the *opposite* of a dead spot, and
 *    speeding through it would wreck the one moment the track was building to. Under
 *    [MIN_SECTION_SECONDS] nothing counts.
 *  - **Gap merging.** Two quiet stretches split by a couple of loud seconds are one dead spot with
 *    a fill in it, and treating them separately would ramp the speed down and straight back up.
 *    This is also what stops a passage hovering at the threshold from being chopped into nine
 *    sections — and it is the *only* thing that does, deliberately. An earlier version also
 *    widened the threshold on the way out, in decibels, and that turned out to be a trap: a run
 *    then continues through material that does not meet the threshold at all, so at a low setting
 *    a four-second dip swallowed the remaining 160 seconds of the track. Merging achieves the same
 *    anti-flap property without ever including a second that does not qualify on its own.
 *
 * All of it is arithmetic over a list of numbers, so it is tested on the JVM — which matters more
 * here than usual, because getting it wrong is not a crash. It is a track that speeds up somewhere
 * it shouldn't, on a phone, in the middle of a run.
 */
object EnergyProfile {

    /** One loudness value per second. Finer than any dead spot needs; coarse enough to store. */
    const val SECONDS_PER_SAMPLE = 1

    /**
     * Which percentile of a track's own seconds counts as its "full" level.
     *
     * Not the maximum: one cymbal crash or one clipped transient would raise the bar for the whole
     * track and turn ordinary verses into dead spots. Not the mean either, since a track with long
     * quiet passages would drag its own reference down and then find nothing unusual about them.
     */
    const val REFERENCE_PERCENTILE = 0.75f

    /** Shorter than this is a breath, a bar of space, or the run-up to a drop — not a dead spot. */
    const val MIN_SECTION_SECONDS = 6

    /** Two quiet stretches closer together than this are one dead spot with a fill in the middle. */
    const val MERGE_GAP_SECONDS = 3

    /** Stored as one byte per second: decibels below the reference, clamped to this. */
    const val MAX_STORED_DB = 60

    /** A stretch of track worth pushing through, in milliseconds from the start of the track. */
    data class Section(val startMs: Long, val endMs: Long) {
        operator fun contains(positionMs: Long) = positionMs in startMs until endMs
        val durationMs: Long get() = endMs - startMs
    }

    /**
     * Per-second RMS (0..1) in, decibels-below-reference out — one byte per second, which is what
     * gets stored.
     *
     * Working in **dB below this track's own reference** rather than in absolute level is what
     * makes a stored profile independent of both the mastering and the user's current threshold
     * setting: the setting is applied when the profile is read, so moving the slider re-reads every
     * track correctly instead of invalidating everything measured so far.
     */
    fun build(secondsRms: FloatArray): ByteArray {
        if (secondsRms.isEmpty()) return ByteArray(0)
        val levels = FloatArray(secondsRms.size) { 20f * log10(max(secondsRms[it], 1e-6f)) }
        val reference = percentile(levels, REFERENCE_PERCENTILE)
        return ByteArray(levels.size) {
            (reference - levels[it]).coerceIn(0f, MAX_STORED_DB.toFloat()).roundToInt().toByte()
        }
    }

    /**
     * The sections of [profile] worth pushing through, at the given threshold.
     *
     * @param thresholdDb how far under the track's own level counts as quiet.
     */
    fun sections(
        profile: ByteArray,
        thresholdDb: Int,
        minSeconds: Int = MIN_SECTION_SECONDS
    ): List<Section> {
        if (profile.isEmpty() || thresholdDb <= 0) return emptyList()

        val runs = ArrayList<IntRange>()
        var start = -1
        for (i in profile.indices) {
            val below = profile[i].toInt() and 0xFF
            if (start < 0) {
                if (below >= thresholdDb) start = i
            } else if (below < thresholdDb) {
                runs += start until i
                start = -1
            }
        }
        if (start >= 0) runs += start until profile.size

        return merge(runs)
            .filter { it.last - it.first + 1 >= minSeconds }
            .map {
                Section(
                    it.first.toLong() * SECONDS_PER_SAMPLE * 1000L,
                    (it.last + 1).toLong() * SECONDS_PER_SAMPLE * 1000L
                )
            }
    }

    /** The section containing [positionMs], or the one starting within [lookaheadMs] of it. */
    fun sectionAt(sections: List<Section>, positionMs: Long, lookaheadMs: Long = 0L): Section? =
        sections.firstOrNull { positionMs + lookaheadMs >= it.startMs && positionMs < it.endMs }

    /** How much of a track the dead spots account for — what the panel reports about one track. */
    fun quietMs(sections: List<Section>): Long = sections.sumOf { it.durationMs }

    private fun merge(runs: List<IntRange>): List<IntRange> {
        if (runs.isEmpty()) return runs
        val out = ArrayList<IntRange>()
        var current = runs.first()
        for (next in runs.drop(1)) {
            if (next.first - current.last - 1 <= MERGE_GAP_SECONDS) {
                current = current.first..next.last
            } else {
                out += current
                current = next
            }
        }
        out += current
        return out
    }

    /**
     * The p'th value of [values] in order.
     *
     * Sorts a copy rather than the caller's array: the caller is holding levels it still needs in
     * their original order, and a percentile that silently reorders its input is a trap.
     */
    fun percentile(values: FloatArray, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf()
        sorted.sort()
        val index = ((sorted.size - 1) * p.coerceIn(0f, 1f)).roundToInt()
        return sorted[index]
    }

}
