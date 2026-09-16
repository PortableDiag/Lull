package com.lull.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deleting a track is the one thing in Lull that cannot be undone, so the parts of it that are
 * decisions about lists are checked here rather than on a device with a real library to lose.
 */
class DeletionTest {

    // ---------------- Outcome ----------------

    @Test
    fun `a track still listed afterwards was not deleted`() {
        val outcome = Deletion.outcome(listOf(1L, 2L, 3L), setOf(2L))
        assertEquals(3, outcome.requested)
        assertEquals(listOf(1L, 3L), outcome.goneIds)
        assertEquals(1, outcome.kept)
        assertFalse(outcome.complete)
    }

    @Test
    fun `everything gone is a complete delete`() {
        val outcome = Deletion.outcome(listOf(7L, 8L), emptySet())
        assertEquals(2, outcome.deleted)
        assertEquals(0, outcome.kept)
        assertTrue(outcome.complete)
    }

    @Test
    fun `a cancelled delete leaves nothing gone`() {
        val outcome = Deletion.outcome(listOf(7L, 8L), setOf(7L, 8L))
        assertEquals(0, outcome.deleted)
        assertEquals(emptyList<Long>(), outcome.goneIds)
        assertFalse(outcome.complete)
    }

    /** A selection built from overlapping groups can name the same track twice; it is still one file. */
    @Test
    fun `an id asked for twice is counted once`() {
        val outcome = Deletion.outcome(listOf(4L, 4L, 5L), emptySet())
        assertEquals(2, outcome.requested)
        assertEquals(listOf(4L, 5L), outcome.goneIds)
    }

    // ---------------- Pruning playlists ----------------

    @Test
    fun `a deleted track is taken out of every playlist holding it`() {
        val playlists = listOf(
            Playlist(1, "Sleep", listOf(1L, 2L, 3L)),
            Playlist(2, "Gym", listOf(3L, 4L))
        )
        val pruned = Deletion.prune(playlists, setOf(3L))!!
        assertEquals(listOf(1L, 2L), pruned[0].trackIds)
        assertEquals(listOf(4L), pruned[1].trackIds)
    }

    /** Null rather than an identical copy, so a delete of tracks nobody filed rewrites nothing. */
    @Test
    fun `a playlist that held none of them is not rewritten`() {
        val playlists = listOf(Playlist(1, "Sleep", listOf(1L, 2L)))
        assertNull(Deletion.prune(playlists, setOf(9L)))
        assertNull(Deletion.prune(playlists, emptySet()))
    }

    @Test
    fun `names and ids survive the prune`() {
        val pruned = Deletion.prune(listOf(Playlist(6, "Rain", listOf(1L, 2L))), setOf(1L))!!
        assertEquals(6L, pruned[0].id)
        assertEquals("Rain", pruned[0].name)
    }

    @Test
    fun `a playlist emptied by the delete survives as an empty playlist`() {
        val pruned = Deletion.prune(listOf(Playlist(1, "Sleep", listOf(1L))), setOf(1L))!!
        assertEquals(1, pruned.size)
        assertEquals(emptyList<Long>(), pruned[0].trackIds)
    }

    // ---------------- Queue removals ----------------

    /**
     * The regression this exists for: ascending removal takes out the wrong rows. With 1 and 2
     * deleted, removing index 1 first shifts the old index 2 down into it, so the second removal
     * at index 2 hits what used to be index 3 — a track the user is still expecting to hear.
     */
    @Test
    fun `queue positions come back highest first`() {
        val queue = listOf("10", "11", "12", "13")
        assertEquals(listOf(2, 1), Deletion.queueRemovals(queue, setOf(11L, 12L)))
    }

    @Test
    fun `a queue holding none of them yields nothing to remove`() {
        assertEquals(emptyList<Int>(), Deletion.queueRemovals(listOf("10", "11"), setOf(99L)))
        assertEquals(emptyList<Int>(), Deletion.queueRemovals(emptyList(), setOf(10L)))
    }

    /** An externally opened file has no MediaStore id in its mediaId, and must never match. */
    @Test
    fun `a queue entry with no usable id is left alone`() {
        val queue = listOf("10", null, "not-an-id", "11")
        assertEquals(listOf(3, 0), Deletion.queueRemovals(queue, setOf(10L, 11L, -1L)))
    }

    @Test
    fun `the same track queued twice is removed at both positions`() {
        assertEquals(listOf(3, 0), Deletion.queueRemovals(listOf("5", "6", "7", "5"), setOf(5L)))
    }
}
