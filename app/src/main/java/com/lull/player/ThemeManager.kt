package com.lull.player

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

/**
 * Persists and applies the colour scheme: one of the house [Palette]s, plus the light/dark/system
 * mode that the [Palette.followsMode] ones obey. Mode defaults to dark, palette to Material You.
 *
 * Every other palette is one look, so it forces the night mode its own background calls for —
 * that is what turns the system-bar icons, dialogs and pickers the right way round for it rather
 * than for the phone's setting.
 */
object ThemeManager {
    const val PREFS = "lull_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_PALETTE = "palette"

    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    private val listeners = mutableListOf<() -> Unit>()

    /** Called on the main thread when the palette changes without the night mode changing too
     *  (AppCompat recreates every screen by itself when it does). The screens recreate. */
    fun addListener(listener: () -> Unit) { listeners += listener }

    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun sp(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedMode(context: Context): Int = sp(context).getInt(KEY_THEME, DARK)

    /** Saved by key, so adding a palette never shifts a saved choice; an unknown one is the default. */
    fun savedPalette(context: Context): Palette =
        sp(context).getString(KEY_PALETTE, null)?.let(Palette::find) ?: Palette.DEFAULT

    fun setMode(context: Context, mode: Int) {
        sp(context).edit().putInt(KEY_THEME, mode).apply()
        apply(mode, savedPalette(context))
    }

    fun setPalette(context: Context, palette: Palette) {
        val before = nightMode(savedMode(context), savedPalette(context))
        sp(context).edit().putString(KEY_PALETTE, palette.key).apply()
        val after = nightMode(savedMode(context), palette)
        apply(savedMode(context), palette)
        if (after == before) listeners.toList().forEach { it() }
    }

    private fun nightMode(mode: Int, palette: Palette): Int = when (palette.dark) {
        true -> AppCompatDelegate.MODE_NIGHT_YES
        false -> AppCompatDelegate.MODE_NIGHT_NO
        null -> when (mode) {
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    private fun apply(mode: Int, palette: Palette) =
        AppCompatDelegate.setDefaultNightMode(nightMode(mode, palette))

    /** Before `super.onCreate`, so the window is built with the palette's bars. */
    fun applySaved(activity: Activity) {
        val palette = savedPalette(activity)
        apply(savedMode(activity), palette)
        if (palette == Palette.MaterialYou) {
            // Below Android 12 there is no wallpaper colour; Lull's own scheme stands in.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) DynamicColors.applyToActivityIfAvailable(activity)
        } else {
            activity.theme.applyStyle(palette.overlay(), true)
        }
    }

    /**
     * After `super.onCreate`, before `setContentView`. When AppCompat moves the night mode inside
     * `super.onCreate` it rebuilds the theme from the manifest's, which drops the overlay applied
     * before it — the font survives (the base theme doesn't set one) and the colours do not.
     */
    fun reapply(activity: Activity) {
        val palette = savedPalette(activity)
        if (palette != Palette.MaterialYou) activity.theme.applyStyle(palette.overlay(), true)
    }
}
