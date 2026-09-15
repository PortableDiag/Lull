package com.lull.player

import android.content.Context
import androidx.media3.common.Player

/** Small wrapper over SharedPreferences for playback preferences (shared with [ThemeManager.PREFS]). */
object Prefs {
    private const val KEY_REPEAT = "repeat_mode"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_SHUFFLE_MODE = "shuffle_mode"
    private const val KEY_VOL_STYLE = "volume_style"
    private const val KEY_CROSSFADE = "crossfade_sec"
    private const val KEY_SLEEP_MINUTES = "sleep_minutes"

    /** Longest crossfade we offer. Beyond this it stops sounding like a transition and starts
     *  sounding like a mashup. */
    const val MAX_CROSSFADE_SEC = 12

    /** Pre-selected the first time the sleep-timer dialog is opened. */
    const val DEFAULT_SLEEP_MINUTES = 30

    /** Public so [PlaybackService]'s preference listener can react only to these keys. */
    const val KEY_MIX_AUDIO = "mix_audio"
    const val KEY_SKIP_SILENCE = "skip_silence"

    // ---------------- Force Tempo ----------------

    const val KEY_FORCE_TEMPO = "force_tempo"
    const val KEY_TEMPO_BPM = "tempo_bpm"
    const val KEY_TEMPO_MAX_CHANGE = "tempo_max_change"
    const val KEY_TEMPO_ONLY_FASTER = "tempo_only_faster"

    /** The keys Force Tempo is spread across — the set [PlaybackService] re-applies on. */
    val TEMPO_KEYS = setOf(
        KEY_FORCE_TEMPO, KEY_TEMPO_BPM, KEY_TEMPO_MAX_CHANGE, KEY_TEMPO_ONLY_FASTER
    )

    /** The slider's ends. Below 60 nothing is a workout and above 200 nothing is a track. */
    const val MIN_TEMPO_BPM = 60
    const val MAX_TEMPO_BPM = 200
    const val TEMPO_BPM_STEP = 5

    /** A running cadence, and the most common answer to "what should this be set to". */
    const val DEFAULT_TEMPO_BPM = 140

    /**
     * How far a track may be stretched, as a percentage.
     *
     * 30% is the default because it is roughly where time-stretching stops being unnoticeable:
     * under it the ear hears a track that happens to be at that tempo, over it a track being
     * driven. The ceiling is deliberately reachable — a 100% setting is silly and somebody will
     * want to hear silly once.
     */
    const val MIN_TEMPO_CHANGE = 5
    const val MAX_TEMPO_CHANGE = 100
    const val DEFAULT_TEMPO_CHANGE = 30

    const val VOL_BAR = 0
    const val VOL_KNOB = 1

    /** The three states of the shuffle button, in the order it cycles through them. */
    const val SHUFFLE_OFF = 0
    const val SHUFFLE_ALL = 1
    const val SHUFFLE_FAVOURITES = 2

    private fun sp(context: Context) =
        context.getSharedPreferences(ThemeManager.PREFS, Context.MODE_PRIVATE)

    fun repeatMode(context: Context): Int =
        sp(context).getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF)

    fun setRepeatMode(context: Context, mode: Int) =
        sp(context).edit().putInt(KEY_REPEAT, mode).apply()

    /**
     * Off, plain shuffle, or Favourites shuffle — see [Shuffle] for what the third one means.
     *
     * Falls back to the boolean this was before 1.8 when the new key has never been written, so an
     * upgrade keeps the shuffle setting it was left on.
     */
    fun shuffleMode(context: Context): Int {
        val sp = sp(context)
        if (!sp.contains(KEY_SHUFFLE_MODE)) {
            return if (sp.getBoolean(KEY_SHUFFLE, false)) SHUFFLE_ALL else SHUFFLE_OFF
        }
        return sp.getInt(KEY_SHUFFLE_MODE, SHUFFLE_OFF).coerceIn(SHUFFLE_OFF, SHUFFLE_FAVOURITES)
    }

    fun setShuffleMode(context: Context, mode: Int) {
        val value = mode.coerceIn(SHUFFLE_OFF, SHUFFLE_FAVOURITES)
        sp(context).edit()
            .putInt(KEY_SHUFFLE_MODE, value)
            // Kept in step so a downgrade to 1.7 still finds a sane setting.
            .putBoolean(KEY_SHUFFLE, value != SHUFFLE_OFF)
            .apply()
    }

    fun volumeStyle(context: Context): Int =
        sp(context).getInt(KEY_VOL_STYLE, VOL_BAR)

    fun setVolumeStyle(context: Context, style: Int) =
        sp(context).edit().putInt(KEY_VOL_STYLE, style).apply()

    /**
     * When true, playback mixes with other apps' audio instead of requesting audio focus
     * (so a browser/Telegram video keeps playing over our music). Defaults to true.
     */
    fun mixAudio(context: Context): Boolean =
        sp(context).getBoolean(KEY_MIX_AUDIO, true)

    fun setMixAudio(context: Context, on: Boolean) =
        sp(context).edit().putBoolean(KEY_MIX_AUDIO, on).apply()

    /**
     * Seconds of overlap between tracks; 0 means off.
     *
     * Off is the default on purpose: crossfading and gapless playback are mutually exclusive, and
     * at 0 the second engine never spins up, so the gapless path stays exactly as it is.
     */
    fun crossfadeSec(context: Context): Int =
        sp(context).getInt(KEY_CROSSFADE, 0).coerceIn(0, MAX_CROSSFADE_SEC)

    fun setCrossfadeSec(context: Context, seconds: Int) =
        sp(context).edit().putInt(KEY_CROSSFADE, seconds.coerceIn(0, MAX_CROSSFADE_SEC)).apply()

    /**
     * When true, stretches of near-silence are shortened as they play — dead air inside a track,
     * and the padding at its head and tail. Off by default: it changes what you hear, and a rest
     * the artist wrote is not a gap the player should close.
     *
     * Unlike crossfade this is not exclusive with gapless: it runs in the audio sink, below the
     * transition, so it composes with whichever of the two is in effect.
     */
    fun skipSilence(context: Context): Boolean =
        sp(context).getBoolean(KEY_SKIP_SILENCE, false)

    fun setSkipSilence(context: Context, on: Boolean) =
        sp(context).edit().putBoolean(KEY_SKIP_SILENCE, on).apply()

    /**
     * The duration the sleep-timer dialog offers first.
     *
     * Only the *choice* is remembered — a running countdown is deliberately not persisted, since
     * restoring one after a restart would be a promise about a device that was switched off.
     */
    fun sleepMinutes(context: Context): Int =
        sp(context).getInt(KEY_SLEEP_MINUTES, DEFAULT_SLEEP_MINUTES)

    fun setSleepMinutes(context: Context, minutes: Int) =
        sp(context).edit().putInt(KEY_SLEEP_MINUTES, minutes).apply()

    // ---------------- Force Tempo ----------------

    /**
     * When true, every track is played at whatever speed brings it to [tempoBpm] — see [Tempo] for
     * what "brings it to" has to mean once octaves and a stretch ceiling are accounted for.
     *
     * Off by default, and it is the one setting in Lull that changes the *pitch-preserved speed* of
     * the music rather than the gaps around it. A track whose tempo is unknown or absent is left
     * alone, so switching this on never silently alters a podcast.
     */
    fun forceTempo(context: Context): Boolean =
        sp(context).getBoolean(KEY_FORCE_TEMPO, false)

    fun setForceTempo(context: Context, on: Boolean) =
        sp(context).edit().putBoolean(KEY_FORCE_TEMPO, on).apply()

    fun tempoBpm(context: Context): Int =
        sp(context).getInt(KEY_TEMPO_BPM, DEFAULT_TEMPO_BPM).coerceIn(MIN_TEMPO_BPM, MAX_TEMPO_BPM)

    fun setTempoBpm(context: Context, bpm: Int) =
        sp(context).edit().putInt(KEY_TEMPO_BPM, bpm.coerceIn(MIN_TEMPO_BPM, MAX_TEMPO_BPM)).apply()

    fun tempoMaxChange(context: Context): Int =
        sp(context).getInt(KEY_TEMPO_MAX_CHANGE, DEFAULT_TEMPO_CHANGE)
            .coerceIn(MIN_TEMPO_CHANGE, MAX_TEMPO_CHANGE)

    fun setTempoMaxChange(context: Context, percent: Int) =
        sp(context).edit()
            .putInt(KEY_TEMPO_MAX_CHANGE, percent.coerceIn(MIN_TEMPO_CHANGE, MAX_TEMPO_CHANGE))
            .apply()

    /**
     * Never play a track slower than it was recorded. On by default, because the feature exists to
     * remove the slow patches from a workout and slowing a fast track down to hit a number would
     * be it working against its own purpose.
     */
    fun tempoOnlyFaster(context: Context): Boolean =
        sp(context).getBoolean(KEY_TEMPO_ONLY_FASTER, true)

    fun setTempoOnlyFaster(context: Context, on: Boolean) =
        sp(context).edit().putBoolean(KEY_TEMPO_ONLY_FASTER, on).apply()

    /** The speed one track should play at under the current settings; 1.0 when it shouldn't move. */
    fun speedFor(context: Context, bpm: Float): Float {
        if (!forceTempo(context)) return Tempo.UNCHANGED
        return Tempo.speedFor(bpm, tempoBpm(context), tempoMaxChange(context), tempoOnlyFaster(context))
    }
}
