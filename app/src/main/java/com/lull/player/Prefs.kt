package com.lull.player

import android.content.Context
import androidx.media3.common.Player

/** Small wrapper over SharedPreferences for playback preferences (shared with [ThemeManager.PREFS]). */
object Prefs {
    private const val KEY_REPEAT = "repeat_mode"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_VOL_STYLE = "volume_style"
    private const val KEY_CROSSFADE = "crossfade_sec"

    /** Longest crossfade we offer. Beyond this it stops sounding like a transition and starts
     *  sounding like a mashup. */
    const val MAX_CROSSFADE_SEC = 12

    /** Public so [PlaybackService]'s preference listener can react only to this key. */
    const val KEY_MIX_AUDIO = "mix_audio"

    const val VOL_BAR = 0
    const val VOL_KNOB = 1

    private fun sp(context: Context) =
        context.getSharedPreferences(ThemeManager.PREFS, Context.MODE_PRIVATE)

    fun repeatMode(context: Context): Int =
        sp(context).getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF)

    fun setRepeatMode(context: Context, mode: Int) =
        sp(context).edit().putInt(KEY_REPEAT, mode).apply()

    fun shuffle(context: Context): Boolean =
        sp(context).getBoolean(KEY_SHUFFLE, false)

    fun setShuffle(context: Context, on: Boolean) =
        sp(context).edit().putBoolean(KEY_SHUFFLE, on).apply()

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
}
