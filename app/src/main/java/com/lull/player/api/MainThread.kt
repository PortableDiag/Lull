package com.lull.player.api

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs a block on the main thread and waits for its answer.
 *
 * The API is served on socket threads; a `MediaController`, `AbLoop`, `SleepTimer` and the theme
 * all insist on the main one. So the handlers do their thinking on the socket thread and hop here
 * for the single call that has to happen over there.
 *
 * The wait is bounded. A request that blocks forever because the main thread is wedged is worse
 * than one that comes back saying so — an agent can retry a 503, but it cannot retry a socket that
 * never answers.
 */
object MainThread {

    /** Long enough for anything the UI thread legitimately does; short enough to give up on. */
    const val TIMEOUT_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())

    private fun onMainThread() = Looper.myLooper() == Looper.getMainLooper()

    fun <T> get(block: () -> T): T {
        if (onMainThread()) return block()

        val latch = CountDownLatch(1)
        var result: T? = null
        var failure: Throwable? = null
        handler.post {
            try { result = block() } catch (t: Throwable) { failure = t } finally { latch.countDown() }
        }
        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw ApiException(503, "the app did not answer within ${TIMEOUT_MS}ms")
        }
        failure?.let {
            // A handler that already decided what this failure is — "that reorder drops a track",
            // a 400 — must keep its status and its hint. Wrapping it here would turn every refusal
            // that happens to be decided on the main thread into an indistinguishable 500.
            if (it is ApiException) throw it
            throw ApiException(500, "${it.javaClass.simpleName}: ${it.message}")
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun run(block: () -> Unit) { get(block) }
}
