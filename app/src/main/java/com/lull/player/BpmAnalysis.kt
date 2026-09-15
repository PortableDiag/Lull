package com.lull.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Estimating a track's tempo from its decoded audio — the half of [BpmDetector] that is arithmetic
 * rather than codecs, and therefore the half that gets tested.
 *
 * The method is the standard two-stage one, and it is deliberately the cheap version of it:
 *
 *  1. **An onset envelope.** Reduce the waveform to one number per 10ms — how much *louder* this
 *     frame is than the last, rectified so that only rises count. A beat is an energy rise; the
 *     sustained parts between beats are not. This throws away pitch, timbre and absolute level and
 *     keeps the rhythm, which is the only thing being asked about.
 *  2. **Autocorrelation of that envelope.** A periodic rhythm correlates with itself at the beat
 *     period, so the lag with the strongest correlation is the beat. Searching lags from
 *     [MIN_BPM] to [MAX_BPM] bounds it to tempos that exist in music.
 *
 * Two corrections matter:
 *
 *  - **A log-Gaussian preference around [PREFERRED_BPM].** Autocorrelation cannot tell a beat from
 *    its own subdivision — eighth notes peak at half the period, and a bar peaks at four times it.
 *    Weighting the search toward the tempo range people actually perceive as "the" pulse resolves
 *    the octave the way a listener would. Where it still guesses an octave wrong, [Tempo] folds it
 *    back, so the two halves of the feature cover each other.
 *  - **A confidence, and the honesty to return null.** An audiobook has no tempo, and a player that
 *    invents one for it and then speeds it up by 30% has done something indefensible. The
 *    normalised correlation at the winning lag is the confidence, and below [MIN_CONFIDENCE] the
 *    answer is "this has no tempo" rather than a number.
 */
object BpmAnalysis {

    /** Envelope frames per second. 100 gives 10ms resolution — finer than any beat needs. */
    const val FPS = 100

    /** Slowest and fastest we will claim. Outside this it is not a pulse anyone exercises to. */
    const val MIN_BPM = 50f
    const val MAX_BPM = 210f

    /** The centre of the octave-resolving weight — roughly where the perceived pulse sits. */
    const val PREFERRED_BPM = 120f

    /** Width of that weight, in natural-log units of tempo. Wide: it breaks ties, it doesn't rule. */
    const val PREFERENCE_WIDTH = 0.9f

    /** Below this normalised correlation, the audio is treated as having no tempo at all. */
    const val MIN_CONFIDENCE = 0.10f

    /** Shorter than this and the slowest lags have too few laps to correlate. */
    const val MIN_SECONDS = 8

    /** Resolution of the lag search, in envelope frames. See [correlate] for why it is not 1. */
    private const val LAG_STEP = 0.25f

    /** How well a half-lag must hold up before it is believed over the lag that won. */
    private const val OCTAVE_TIE = 0.85f

    /** A tempo, and how much the audio actually supported it. */
    data class Estimate(val bpm: Float, val confidence: Float)

    /**
     * Interleaved 16-bit PCM in, one onset value per [FPS]th of a second out.
     *
     * The rise is measured in the log domain so that it is a *proportional* change: a drum entering
     * over a quiet passage and the same drum over a loud one are the same musical event, and a
     * linear difference would rate the second one many times the first and let the loud sections of
     * a track drown out the beat in its quiet ones.
     *
     * @param sampleCount how many entries of [pcm] are real, which is not always its size.
     */
    fun envelope(pcm: ShortArray, sampleCount: Int, sampleRate: Int, channels: Int): FloatArray {
        if (sampleRate <= 0 || channels <= 0) return FloatArray(0)
        val hop = max(1, sampleRate / FPS) * channels
        val frames = sampleCount / hop
        if (frames < 2) return FloatArray(0)

        val out = FloatArray(frames - 1)
        var previous = Float.NaN
        for (f in 0 until frames) {
            var sum = 0.0
            val start = f * hop
            for (i in start until start + hop) {
                val sample = pcm[i] / 32768.0
                sum += sample * sample
            }
            val rms = sqrt(sum / hop).toFloat()
            val level = ln(rms + 1e-6f)
            if (!previous.isNaN()) out[f - 1] = max(0f, level - previous)
            previous = level
        }
        return out
    }

    /**
     * The tempo of an onset [envelope], or null where there isn't one.
     *
     * @param fps frames per second of the envelope — [FPS] unless a test says otherwise.
     */
    fun estimate(envelope: FloatArray, fps: Int = FPS): Estimate? {
        if (fps <= 0 || envelope.size < MIN_SECONDS * fps) return null

        val smoothed = smooth(envelope)

        // Autocorrelation of an un-centred signal is dominated by its mean, which says nothing
        // about periodicity — every lag would score nearly the same. Centre it first.
        val mean = smoothed.average().toFloat()
        val centred = FloatArray(smoothed.size) { smoothed[it] - mean }

        var energy = 0.0
        for (v in centred) energy += (v * v).toDouble()
        if (energy <= 0.0) return null

        val minLag = fps * 60f / MAX_BPM
        val maxLag = minOf(fps * 60f / MIN_BPM, centred.size / 2f)
        if (maxLag <= minLag) return null

        val steps = ((maxLag - minLag) / LAG_STEP).toInt() + 1
        val correlations = FloatArray(steps)
        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY

        for (k in 0 until steps) {
            val lag = minLag + k * LAG_STEP
            val r = correlate(centred, lag, energy)
            correlations[k] = r
            val score = r * preference(60f * fps / lag)
            if (score > bestScore) {
                bestScore = score
                best = k
            }
        }
        if (best < 0) return null
        best = halveIfTied(correlations, best, minLag)

        val confidence = correlations[best]
        if (confidence < MIN_CONFIDENCE) return null

        val lag = minLag + refine(correlations, best) * LAG_STEP
        val bpm = 60f * fps / lag
        if (bpm < MIN_BPM || bpm > MAX_BPM) return null
        return Estimate(bpm, confidence)
    }

    /**
     * Takes the *faster* of two readings when the audio supports both equally well.
     *
     * The weight around [PREFERRED_BPM] settles most octave questions, but it settles them by
     * preference, and preference is the wrong tool when the evidence is decisive. A rhythm that
     * repeats every 30 frames also repeats every 60, so both lags correlate — and at 200 BPM the
     * weight quietly prefers the 60 and reports 100. The asymmetry that resolves it: if the beat
     * really were the slower one, the faster lag would be lining beats up with the gaps between
     * them and would correlate *badly*. It doesn't. So a half-lag that holds up within
     * [OCTAVE_TIE] of the winner is not a coincidence, it is the beat.
     *
     * Only one halving, and only where the half is still a tempo we would report: this is a
     * tie-break, not a licence to keep doubling until it runs out of range.
     */
    private fun halveIfTied(correlations: FloatArray, best: Int, minLag: Float): Int {
        val lag = minLag + best * LAG_STEP
        val half = lag / 2f
        if (half < minLag) return best
        val index = ((half - minLag) / LAG_STEP).toInt()

        // The half-lag peak need not land exactly on a step, so look in a small window around it.
        var peak = index
        for (k in (index - 2).coerceAtLeast(0)..(index + 2).coerceAtMost(correlations.size - 1)) {
            if (correlations[k] > correlations[peak]) peak = k
        }
        return if (correlations[peak] >= correlations[best] * OCTAVE_TIE) peak else best
    }

    /**
     * Normalised autocorrelation at a **fractional** lag, the envelope linearly interpolated
     * between frames.
     *
     * Whole-frame lags are not good enough, and the way they fail is not a rounding error — it is
     * an octave. A beat at 160 BPM repeats every 37.5 frames, so *neither* lag 37 nor lag 38 lines
     * the rhythm up with itself, while lag 75 — two beats — lines it up perfectly. Searching whole
     * lags therefore reports 80 BPM for a 160 BPM track, confidently, and the tempos that suffer
     * are exactly the ones whose period lands on a half frame. Asking the question at quarter-frame
     * resolution removes the artefact rather than correcting for it afterwards.
     */
    private fun correlate(centred: FloatArray, lag: Float, energy: Double): Float {
        val whole = lag.toInt()
        val frac = lag - whole
        val count = centred.size - whole - 1
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val shifted = centred[i + whole] * (1f - frac) + centred[i + whole + 1] * frac
            sum += (centred[i] * shifted).toDouble()
        }
        return (sum / energy).toFloat()
    }

    /**
     * Spreads each onset over about 50ms before the envelope is correlated with itself.
     *
     * Without this the estimator reports **half** the tempo of anything whose beat period is not a
     * whole number of frames — which is nearly everything. A beat at 110 BPM lands every 54.5
     * frames, so a one-frame-wide onset spike at lag 54 or 55 is half a frame out of step with
     * itself and barely correlates, while at lag 109 — two beats — the error has come back round to
     * almost nothing and correlates beautifully. The rhythm is read an octave slow off an artefact
     * of where the frame boundaries happen to fall.
     *
     * Widening the spike so that it overlaps itself under a half-frame shift removes the artefact,
     * and it is also the more faithful envelope: a drum hit is not an instant, and 50ms is well
     * inside the window in which the ear hears one event.
     */
    private fun smooth(envelope: FloatArray): FloatArray {
        val kernel = floatArrayOf(1f, 2f, 3f, 2f, 1f)
        val weight = kernel.sum()
        val half = kernel.size / 2
        val out = FloatArray(envelope.size)
        for (i in envelope.indices) {
            var sum = 0f
            for (k in kernel.indices) {
                val j = i + k - half
                if (j in envelope.indices) sum += envelope[j] * kernel[k]
            }
            out[i] = sum / weight
        }
        return out
    }

    /** The octave-resolving weight: a Gaussian in log-tempo, centred on [PREFERRED_BPM]. */
    fun preference(bpm: Float): Float {
        val z = ln(bpm / PREFERRED_BPM) / PREFERENCE_WIDTH
        return exp(-0.5f * z * z)
    }

    /**
     * The winning index, moved to the sub-step peak of the parabola through it and its neighbours.
     *
     * The search grid is fine, but the answer is turned straight into a playback speed, so the
     * last quarter of a frame is still worth having: at a lag of 40 frames, one step either way is
     * about a beat per minute.
     */
    private fun refine(correlations: FloatArray, index: Int): Float {
        if (index <= 0 || index >= correlations.size - 1) return index.toFloat()
        val left = correlations[index - 1]
        val centre = correlations[index]
        val right = correlations[index + 1]
        val denominator = left - 2f * centre + right
        if (abs(denominator) < 1e-9f) return index.toFloat()
        val offset = 0.5f * (left - right) / denominator
        if (abs(offset) > 1f) return index.toFloat()
        return index + offset
    }
}
