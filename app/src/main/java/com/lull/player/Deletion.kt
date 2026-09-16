package com.lull.player

/**
 * The bookkeeping half of deleting a track: what actually went, what has to be forgotten about it,
 * and where it sat in the queue.
 *
 * No Android types, on purpose — the same reason [Tempo], [Shuffle] and [EnergyProfile] have none.
 * The parts of a delete worth getting right are decisions about lists, and a decision about a list
 * should not need a device to check.
 */
object Deletion {

    /**
     * What a delete did, measured against the library afterwards rather than against a result code.
     *
     * A `RESULT_OK` from the system's confirmation says the user pressed the button, not that every
     * file went — one of them can be on a card that has since been unmounted, or owned by a
     * different app. The only honest source is MediaStore, so the outcome is worked out from what
     * is still listed once the dust settles.
     */
    data class Outcome(val requested: Int, val goneIds: List<Long>) {
        val deleted: Int get() = goneIds.size
        val kept: Int get() = requested - deleted
        val complete: Boolean get() = kept == 0 && requested > 0
    }

    fun outcome(requested: List<Long>, stillPresent: Set<Long>): Outcome {
        val asked = requested.distinct()
        return Outcome(asked.size, asked.filterNot { it in stillPresent })
    }

    /**
     * The playlists with the deleted tracks taken out of them, or null when none of them held any.
     *
     * [PlaylistStore] deliberately *skips* a track it cannot find rather than pruning it, so that a
     * playlist survives an unmounted SD card. A track the user deliberately deleted is a different
     * thing: it is not coming back, and MediaStore hands a re-added file a new id, so leaving the
     * old one in would be a permanent hole that makes the playlist's count lie.
     */
    fun prune(playlists: List<Playlist>, gone: Set<Long>): List<Playlist>? {
        if (gone.isEmpty()) return null
        var changed = false
        val pruned = playlists.map { playlist ->
            val kept = playlist.trackIds.filterNot { it in gone }
            if (kept.size == playlist.trackIds.size) playlist
            else { changed = true; playlist.copy(trackIds = kept) }
        }
        return if (changed) pruned else null
    }

    /**
     * The positions to take out of the playing queue, **highest first**.
     *
     * Descending is the whole point: removing index 2 shifts everything above it down one, so a
     * low-to-high pass takes out the wrong tracks from the second removal onwards — and on a queue
     * where the deleted tracks are adjacent it takes out ones the user never asked to lose.
     */
    fun queueRemovals(queueMediaIds: List<String?>, gone: Set<Long>): List<Int> =
        queueMediaIds.indices
            .filter { i -> queueMediaIds[i]?.toLongOrNull()?.let { it in gone } == true }
            .sortedDescending()
}
