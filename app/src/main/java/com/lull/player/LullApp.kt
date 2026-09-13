package com.lull.player

import android.app.Application
import com.lull.player.api.Foreground

/**
 * Exists for one job: start watching whether any of Lull's screens is in front of the user, from a
 * point that is guaranteed to be before the first one opens.
 *
 * The API's share endpoint reports whether a chooser it started can actually be *seen*, and that
 * answer has to be right — Android silently discards a dialog thrown by an app that is not on
 * screen, so an optimistic answer there is a lie in exactly the case the caller needs the truth.
 * Registering the lifecycle callbacks from [com.lull.player.api.ApiService] was not good enough:
 * the service is started from `MainActivity.onCreate` and comes up asynchronously, so it routinely
 * missed that very activity's `onStart` and then believed nothing was on screen for the rest of
 * the process's life.
 */
class LullApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Foreground.install(this)
    }
}
