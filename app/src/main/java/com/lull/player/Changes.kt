package com.lull.player

/**
 * "Something changed what the library should be showing."
 *
 * [RatingStore] already tells the screens when a rating moves, because two screens can both be
 * looking at one. The API adds a third party that can change playlists and ratings while nobody
 * touched the phone at all — and a library that only notices when you leave the tab and come back
 * would make the API look broken from the one seat where its effects are actually visible.
 *
 * Listeners are called on the main thread, because that is the only thread the API mutates from.
 */
object Changes {

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }

    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun notifyChanged() = listeners.toList().forEach { it() }
}
