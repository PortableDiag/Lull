package com.lull.player

import kotlin.math.abs
import kotlin.math.ln

/**
 * Force Tempo's arithmetic: given what a track's tempo *is* and what you want it to be, how fast
 * should it play?
 *
 * The naive answer — `speed = target / source` — is wrong twice over, and both failures are
 * audible:
 *
 *  1. **Octave ambiguity.** A track counted at 70 BPM and a track counted at 140 BPM can be the
 *     same felt pace; 70 is simply the half-time reading of it. Stretching such a track to 2.0x
 *     would double a pace that already matched. So the reading is folded by factors of two, but
 *     only when a folded reading lands *near* the target ([NEAR_TOLERANCE]) — near enough that
 *     calling it the same tempo is a description rather than an excuse. A 95 BPM track is not a
 *     190 BPM track that someone wrote down slowly, and is not treated as one.
 *  2. **Unbounded stretch.** Sonic time-stretching is transparent at 1.1x and a novelty at 1.8x.
 *     [maxChangePercent] is the ceiling the user sets, and the answer is clamped to it rather
 *     than allowed to be absurd.
 *
 * And one preference on top, because of what the feature is *for*: with [onlyFaster] set (the
 * default), a track that is already at or above the target is left completely alone. The point of
 * Force Tempo is a workout with no slow patches in it — slowing a fast track down to hit a number
 * would be the feature working against its own reason for existing.
 *
 * No Android in here: it is arithmetic with exactly right answers, so it is tested on the JVM.
 */
object Tempo {

    /** Playback speed meaning "leave it alone". */
    const val UNCHANGED = 1f

    /**
     * How far a halved or doubled reading may land from the target and still be accepted as the
     * same tempo. 15% is about the width of the window in which two tempos are felt as one pace
     * rather than as a change.
     */
    const val NEAR_TOLERANCE = 0.15f

    /**
     * The speed to play a track at.
     *
     * @param sourceBpm the track's own tempo; 0 or less means unknown, and unknown means untouched.
     * @param targetBpm the tempo the user asked for.
     * @param maxChangePercent the largest stretch allowed, as a percentage (30 → up to 1.30x, and
     *   down to 1/1.30 when [onlyFaster] is off).
     * @param onlyFaster never play a track slower than it was recorded.
     */
    fun speedFor(
        sourceBpm: Float,
        targetBpm: Int,
        maxChangePercent: Int,
        onlyFaster: Boolean
    ): Float {
        if (sourceBpm <= 0f || targetBpm <= 0) return UNCHANGED

        val reading = resolveReading(sourceBpm, targetBpm.toFloat())
        val raw = targetBpm / reading

        if (onlyFaster && raw < UNCHANGED) return UNCHANGED

        val ceiling = 1f + maxChangePercent / 100f
        val floor = if (onlyFaster) UNCHANGED else 1f / ceiling
        return raw.coerceIn(floor, ceiling)
    }

    /**
     * Which reading of [sourceBpm] to believe: itself, its double, or its half.
     *
     * A folded reading wins only if it lands inside [NEAR_TOLERANCE] of the target — that is the
     * whole guard against turning "this track is slower than you asked for" into "this track is
     * secretly already the right speed". Where nothing qualifies, the detector's own number stands
     * and the stretch (or the clamp) is honest about what it is doing.
     */
    fun resolveReading(sourceBpm: Float, targetBpm: Float): Float {
        if (sourceBpm <= 0f) return sourceBpm
        val limit = ln(1f + NEAR_TOLERANCE)
        var best = sourceBpm
        var bestDistance = Float.MAX_VALUE
        for (candidate in floatArrayOf(sourceBpm, sourceBpm * 2f, sourceBpm / 2f)) {
            val distance = abs(ln(targetBpm / candidate))
            if (distance <= limit && distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * How long a stretch of *track* takes on the *clock*, at [speed].
     *
     * Force Tempo splits two things that were the same number everywhere else in the player. A
     * crossfade is counted by the clock; a track's remaining duration and an A-B marker are counted
     * in the track. At 1.3x the last six seconds of a track are 4.6 seconds of listening, so a
     * fade timed against the wrong one either starts late and gets cut off by the transition, or
     * starts early and runs long — and an A-B loop overshoots B by the ratio of the two.
     */
    fun wallClockMs(mediaMs: Long, speed: Float): Long =
        (mediaMs / speed.coerceAtLeast(MIN_SPEED)).toLong()

    /** A floor under any speed used as a divisor, so nothing downstream can divide by zero. */
    const val MIN_SPEED = 0.05f

    /** The tempo you will actually hear: the track's own, played at [speed]. */
    fun effectiveBpm(sourceBpm: Float, speed: Float): Float = sourceBpm * speed

    /** `1.09x` — what the settings panel and the API report. Two decimals is finer than the ear. */
    fun formatSpeed(speed: Float): String = String.format("%.2fx", speed)
}
