package com.lull.player

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Persists and applies the light/dark/system theme. Defaults to dark. */
object ThemeManager {
    const val PREFS = "lull_prefs"
    private const val KEY_THEME = "theme_mode"

    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    fun savedMode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_THEME, DARK)

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_THEME, mode).apply()
        apply(mode)
    }

    fun apply(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun applySaved(context: Context) = apply(savedMode(context))
}
