package com.lull.player.api

import android.content.Context
import com.lull.player.ThemeManager
import java.security.SecureRandom

/**
 * Whether the local API is on, what key opens it, and where it listens.
 *
 * Stored in the same [ThemeManager.PREFS] file as everything else, because it is a setting like
 * any other — but with two rules the rest of the settings don't need:
 *
 *  - **The API is off until someone turns it on**, and turning it off is enough to stop it. An
 *    audio player that quietly opens a socket is not something anybody asked for.
 *  - **The key is generated, never typed.** A key a person chooses is a key a person can pick
 *    badly, and this one guards every operation the app can perform.
 */
object ApiPrefs {

    private const val KEY_ENABLED = "api_enabled"
    private const val KEY_KEY = "api_key"
    private const val KEY_PORT = "api_port"
    private const val KEY_LAN = "api_lan"

    /** Lull's default port. Picked to sit clear of the other local services on this machine. */
    const val DEFAULT_PORT = 8787

    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    /** 24 bytes of `SecureRandom`, hex — long enough that guessing it is not a strategy. */
    const val KEY_BYTES = 24

    private fun sp(c: Context) = c.getSharedPreferences(ThemeManager.PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = sp(c).getBoolean(KEY_ENABLED, false)

    fun setEnabled(c: Context, on: Boolean) {
        // Turning it on with no key would leave an open door: generate one in the same breath.
        if (on && key(c).isEmpty()) regenerateKey(c)
        sp(c).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun key(c: Context): String = sp(c).getString(KEY_KEY, "").orEmpty()

    fun regenerateKey(c: Context): String {
        val key = newKey()
        sp(c).edit().putString(KEY_KEY, key).apply()
        return key
    }

    fun newKey(): String {
        val bytes = ByteArray(KEY_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun port(c: Context): Int =
        sp(c).getInt(KEY_PORT, DEFAULT_PORT).coerceIn(MIN_PORT, MAX_PORT)

    fun setPort(c: Context, port: Int) =
        sp(c).edit().putInt(KEY_PORT, port.coerceIn(MIN_PORT, MAX_PORT)).apply()

    /**
     * False (the default) binds loopback only, so the API is reachable from this device — and
     * from a laptop through `adb forward` — and from nowhere else. True binds every interface.
     */
    fun lan(c: Context): Boolean = sp(c).getBoolean(KEY_LAN, false)

    fun setLan(c: Context, on: Boolean) = sp(c).edit().putBoolean(KEY_LAN, on).apply()

    fun bindAddress(c: Context): String = if (lan(c)) "0.0.0.0" else "127.0.0.1"
}
