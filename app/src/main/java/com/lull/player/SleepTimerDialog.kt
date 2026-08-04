package com.lull.player

import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The one place the sleep timer is set. Reached from the library's overflow menu and from the moon
 * button on Now Playing — both drive the same [SleepTimer], so a timer set on one screen is the
 * timer the other shows.
 */
object SleepTimerDialog {

    private val MINUTES = intArrayOf(5, 10, 15, 20, 30, 45, 60, 90)

    fun show(context: Context) {
        val labels = Array(MINUTES.size + 1) { i ->
            if (i == 0) context.getString(R.string.sleep_timer_off)
            else context.getString(R.string.sleep_timer_minutes, MINUTES[i - 1])
        }

        // Pre-select the running timer's duration; with nothing running, the one you last chose.
        val current = if (SleepTimer.isArmed) {
            MINUTES.indexOf(Prefs.sleepMinutes(context)).let { if (it < 0) 0 else it + 1 }
        } else 0

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.sleep_timer)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    SleepTimer.cancel()
                    Toast.makeText(context, R.string.sleep_timer_cancelled, Toast.LENGTH_SHORT).show()
                } else {
                    val minutes = MINUTES[which - 1]
                    Prefs.setSleepMinutes(context, minutes)
                    SleepTimer.arm(minutes * 60_000L)
                    Toast.makeText(
                        context,
                        context.getString(R.string.sleep_timer_set, minutes),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
