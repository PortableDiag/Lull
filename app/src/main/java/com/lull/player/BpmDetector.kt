package com.lull.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.nio.ByteOrder

/**
 * Works out a track's tempo: read the tag if there is one, otherwise decode a slice of the audio
 * and measure it.
 *
 * **Why a slice and not the file.** Tempo is a property of the track, so a representative half
 * minute answers the same question a full decode would, for a twentieth of the work — and the work
 * is happening on a phone that is also playing music. The slice starts [SKIP_US] in rather than at
 * zero because the opening of a track is the least representative part of it: a fade-in, an
 * unaccompanied intro or a spoken sample has no beat to find, and analysing it would report "no
 * tempo" for a track that has one all the way through.
 *
 * **Everything here is a codec; everything that decides is in [BpmAnalysis].** That split is the
 * reason the estimator can be tested at all — this half needs a device and a decoder, and the half
 * that can be wrong in a way you would only notice at 1.3x does not.
 *
 * Must not be called on the main thread: it decodes audio and takes hundreds of milliseconds.
 * [PlaybackService] runs it on its own single worker and caches the result in [TempoStore], so a
 * track is analysed once and never again.
 */
object BpmDetector {

    /** How far into the track the analysis window starts. */
    private const val SKIP_US = 30_000_000L

    /** How much audio to analyse. Enough laps for the slowest lag [BpmAnalysis] searches. */
    private const val WINDOW_SECONDS = 30

    /** Give up rather than hold a worker thread while something pathological decodes. */
    private const val BUDGET_MS = 10_000L

    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * The track's tempo, [TempoStore.NONE] if it hasn't got one, or [TempoStore.UNKNOWN] if we
     * could not tell — a file that would not open, a codec that would not start.
     *
     * The three answers are distinct on purpose: only the first two are worth writing down. An
     * error is not evidence that a track is tempo-less, and recording it as such would mean a
     * track that failed to decode once is never analysed again.
     */
    fun detect(context: Context, uri: Uri): Int {
        tagged(context, uri)?.let { return it }
        val pcm = runCatching { decodeWindow(context, uri) }.getOrNull() ?: return TempoStore.UNKNOWN
        val envelope = BpmAnalysis.envelope(pcm.samples, pcm.count, pcm.sampleRate, pcm.channels)
        val estimate = BpmAnalysis.estimate(envelope) ?: return TempoStore.NONE
        return estimate.bpm.toInt()
    }

    /** The `TBPM` frame, where the file carries one. Cheap, exact, and usually absent. */
    private fun tagged(context: Context, uri: Uri): Int? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { Id3Bpm.read(it) }
    }.getOrNull()

    private class Pcm(val samples: ShortArray, val sampleRate: Int, val channels: Int) {
        var count = 0
    }

    /**
     * Decodes [WINDOW_SECONDS] of audio starting [SKIP_US] in, as interleaved 16-bit PCM.
     *
     * The seek is to the closest sync point *before* the target and only happens where the track
     * is long enough to have one — on anything shorter the window simply starts at the beginning,
     * which for a short track is most of it anyway.
     */
    private fun decodeWindow(context: Context, uri: Uri): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            val audio = format ?: return null
            extractor.selectTrack(trackIndex)

            val durationUs = if (audio.containsKey(MediaFormat.KEY_DURATION))
                audio.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > SKIP_US + WINDOW_SECONDS * 1_000_000L) {
                extractor.seekTo(SKIP_US, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            var sampleRate = audio.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var out = Pcm(ShortArray(WINDOW_SECONDS * sampleRate * channels), sampleRate, channels)

            codec = MediaCodec.createDecoderByType(audio.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(audio, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + BUDGET_MS
            var inputDone = false

            while (out.count < out.samples.size) {
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

                when (val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The real output format, which need not match what the container claimed.
                        val actual = codec.outputFormat
                        sampleRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (sampleRate != out.sampleRate || channels != out.channels) {
                            out = Pcm(ShortArray(WINDOW_SECONDS * sampleRate * channels), sampleRate, channels)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone && info.size == 0) Unit
                    else -> {
                        if (index < 0) continue
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            copyInto(buffer.order(ByteOrder.nativeOrder()), out)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return if (out.count >= BpmAnalysis.MIN_SECONDS * out.sampleRate * out.channels) out else null
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Appends one decoded buffer, stopping at the window's edge rather than growing past it. */
    private fun copyInto(buffer: java.nio.ByteBuffer, out: Pcm) {
        val shorts = buffer.asShortBuffer()
        val room = out.samples.size - out.count
        val take = minOf(room, shorts.remaining())
        if (take <= 0) return
        shorts.get(out.samples, out.count, take)
        out.count += take
    }
}
