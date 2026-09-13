package com.lull.player

import java.util.concurrent.TimeUnit

/**
 * The handful of value-to-string conversions that more than one screen needs — and that the API
 * and [Sharing] need without a View, a Context or a device anywhere in sight.
 *
 * Kept free of Android types on purpose: these are the parts of the app with exact right answers
 * (`3661000 ms` is `1:01:01`, not approximately that), so they are the parts worth testing on the
 * JVM, and a dependency on `android.*` is what would stop that.
 */
object Format {

    /** `m:ss`, or `h:mm:ss` once there is an hour to show. Never negative, never blank. */
    fun duration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val h = TimeUnit.SECONDS.toHours(total)
        val m = TimeUnit.SECONDS.toMinutes(total) % 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    /** Binary units, because that is what a file manager shows for the same file. */
    fun size(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return if (unit == 0) "$bytes B" else String.format("%.1f %s", value, units[unit])
    }
}
