package com.lull.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Everything Force Tempo needs to know about a track, worked out in **one decode**.
 *
 * Two questions, one pass over the audio, because the expensive part is the decode and doing it
 * twice for two answers about the same file would be indefensible:
 *
 *  - **Its tempo**, for the per-track speed — measured from a window in the middle of the track,
 *    or read straight off the `TBPM` tag when the file carries one (in which case the decode is
 *    still needed for the second answer, but the window is not).
 *  - **Its energy over time**, for pushing through the quiet stretches inside it — which needs the
 *    *whole* file, since a dead spot can be anywhere and the point is to find the one you didn't
 *    expect.
 *
 * That second requirement is why this decodes end to end rather than sampling. It costs a few
 * seconds per track on a phone, which is why it happens **once**, on a background worker, only
 * while the feature is switched on, and is written down in [TempoStore] and [ProfileStore]
 * afterwards. [PlaybackService] runs it a track ahead of where you are listening.
 *
 * Everything that *decides* anything lives in [BpmAnalysis] and [EnergyProfile], which have no
 * Android in them and are tested. This file is codec plumbing.
 *
 * Must not be called on the main thread.
 */
object TrackAnalysis {

    /** How far into the track the tempo window starts — the opening is the least typical part. */
    private const val TEMPO_SKIP_US = 30_000_000L

    /** How much audio the tempo estimate gets. Enough laps for the slowest lag it searches. */
    private const val TEMPO_WINDOW_SECONDS = 30

    /**
     * Give up rather than hold the worker forever on a pathological file.
     *
     * Generous, because unlike the tempo window this has a whole track to get through, and a decode
     * that is abandoned halfway leaves a profile that stops mid-track — which would read as "the
     * rest of this song is loud" and is worse than no profile at all. See [analyse].
     */
    private const val BUDGET_MS = 25_000L

    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * @param bpm a tempo, [TempoStore.NONE] if the audio hasn't got one, or [TempoStore.UNKNOWN]
     *   if it could not be worked out.
     * @param profile decibels below the track's own reference, one byte per second; empty when the
     *   decode did not reach the end of the file, because a partial profile is a lie about the part
     *   it never saw.
     */
    class Analysis(val bpm: Int, val profile: ByteArray)

    fun analyse(context: Context, uri: Uri): Analysis {
        val tagged = taggedBpm(context, uri)
        val decoded = runCatching { decode(context, uri, wantTempoWindow = tagged == null) }.getOrNull()
            ?: return Analysis(tagged ?: TempoStore.UNKNOWN, ByteArray(0))

        val bpm = tagged ?: estimateBpm(decoded)
        val profile = if (decoded.complete) EnergyProfile.build(decoded.secondsRms()) else ByteArray(0)
        return Analysis(bpm, profile)
    }

    private fun estimateBpm(decoded: Decoded): Int {
        val window = decoded.window ?: return TempoStore.UNKNOWN
        if (decoded.windowCount < BpmAnalysis.MIN_SECONDS * decoded.sampleRate * decoded.channels) {
            return TempoStore.UNKNOWN
        }
        val envelope = BpmAnalysis.envelope(
            window, decoded.windowCount, decoded.sampleRate, decoded.channels
        )
        return BpmAnalysis.estimate(envelope)?.bpm?.toInt() ?: TempoStore.NONE
    }

    /** The `TBPM` frame, where the file carries one. Cheap, exact, and usually absent. */
    private fun taggedBpm(context: Context, uri: Uri): Int? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { Id3Bpm.read(it) }
    }.getOrNull()

    // ---------------- The decode ----------------

    /**
     * What one pass produced: a running sum of squares per second for the whole track, and
     * optionally the raw PCM of one window in the middle for the tempo estimate.
     */
    private class Decoded(
        val sampleRate: Int,
        val channels: Int,
        wantWindow: Boolean,
        /** Absolute sample index the tempo window starts at; 0 for a track too short to skip into. */
        val windowStart: Long
    ) {
        /** Sum of squares and sample count per second of the track — enough for an RMS each. */
        val sums = ArrayList<Double>()
        val counts = ArrayList<Int>()

        val window: ShortArray? =
            if (wantWindow) ShortArray(TEMPO_WINDOW_SECONDS * sampleRate * channels) else null
        var windowCount = 0

        /** True only if the decoder reached end of stream — see [Analysis.profile]. */
        var complete = false

        private val perSecond = sampleRate * channels

        /** Interleaved samples, in order, from the start of the track. */
        fun add(buffer: ByteBuffer, totalSoFar: Long) {
            val shorts = buffer.asShortBuffer()
            var index = totalSoFar
            while (shorts.hasRemaining()) {
                val second = (index / perSecond).toInt()
                while (sums.size <= second) { sums += 0.0; counts += 0 }
                var sum = sums[second]
                var count = counts[second]
                // Stay inside this second, then loop round for the next one.
                val room = ((second + 1).toLong() * perSecond - index).toInt()
                val take = minOf(room, shorts.remaining())
                for (i in 0 until take) {
                    val sample = shorts.get() / 32768.0
                    sum += sample * sample
                    count++
                }
                sums[second] = sum
                counts[second] = count
                index += take
            }
        }

        fun secondsRms(): FloatArray = FloatArray(sums.size) {
            if (counts[it] <= 0) 0f else sqrt(sums[it] / counts[it]).toFloat()
        }
    }

    private fun decode(context: Context, uri: Uri, wantTempoWindow: Boolean): Decoded? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            val audio = format ?: return null
            extractor.selectTrack(trackIndex)

            val durationUs = if (audio.containsKey(MediaFormat.KEY_DURATION))
                audio.getLong(MediaFormat.KEY_DURATION) else 0L
            // A track too short to skip 30 seconds into is analysed from its beginning; on
            // something that brief the opening is most of it anyway.
            val windowStartUs =
                if (durationUs > TEMPO_SKIP_US + TEMPO_WINDOW_SECONDS * 1_000_000L) TEMPO_SKIP_US else 0L

            fun decodedFor(rate: Int, channels: Int) = Decoded(
                rate, channels, wantTempoWindow,
                windowStart = windowStartUs / 1_000_000L * rate * channels
            )

            var out = decodedFor(
                audio.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            )

            codec = MediaCodec.createDecoderByType(audio.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(audio, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + BUDGET_MS
            var inputDone = false
            var total = 0L

            while (true) {
                if (SystemClock.elapsedRealtime() > deadline) break

                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The real output format, which need not match what the container claimed.
                    val actual = codec.outputFormat
                    val rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (rate != out.sampleRate || channels != out.channels) {
                        out = decodedFor(rate, channels)
                        total = 0L
                    }
                    continue
                }
                if (index < 0) continue

                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    // 16-bit PCM, which is what a decoder configured without KEY_PCM_ENCODING
                    // emits. asShortBuffer() hands out an independent view each call, so the two
                    // consumers below do not have to take turns.
                    val ordered = buffer.order(ByteOrder.nativeOrder())
                    collectWindow(ordered, out, total)
                    out.add(ordered, total)
                    total += info.size / 2
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    out.complete = true
                    break
                }
            }
            return out
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Copies the part of this buffer that falls inside the tempo window.
     *
     * The window is a stretch of the same stream the profile is being built from, so it is taken as
     * it goes past rather than by seeking back for it — which is what makes one decode enough for
     * both answers.
     */
    private fun collectWindow(buffer: ByteBuffer, out: Decoded, totalSoFar: Long) {
        val window = out.window ?: return
        if (out.windowCount >= window.size) return

        val start = out.windowStart
        val shorts = buffer.asShortBuffer()
        val here = totalSoFar
        val end = here + shorts.remaining()
        if (end <= start) return

        val from = maxOf(0L, start - here).toInt()
        shorts.position(shorts.position() + from)
        val take = minOf(window.size - out.windowCount, shorts.remaining())
        if (take <= 0) return
        shorts.get(window, out.windowCount, take)
        out.windowCount += take
    }
}
