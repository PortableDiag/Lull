package com.lull.player.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Puts the API back after a reboot — but only if it was switched on.
 *
 * An agent given a key and a port has every reason to expect both to still work tomorrow, and a
 * phone reboots overnight. The receiver does nothing at all when the API is off, which is the state
 * it ships in.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // An update stops the process and nothing restarts it, so PhoneAPI would lose the API at every update.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ApiService.startIfEnabled(context)
    }
}
