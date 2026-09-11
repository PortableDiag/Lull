package com.lull.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Favourites shuffle makes a claim about *probability*, which one run can neither confirm nor
 * refute — so these check the distribution over many runs, from a fixed seed so a failure is
 * reproducible rather than a flake.
 */
class ShuffleTest {

    /** One track per star rating, 0..5, repeated so each rating gets the same number of chances. */
    private fun corpus(copies: Int = 4): List<Int> =
        (0..RatingStore.MAX).flatMap { stars -> List(copies) { stars } }

    @Test
    fun `every item survives the shuffle exactly once`() {
        val random = Random(20260911)
        val items = (1..500).toList()
        repeat(50) {
            val out = Shuffle.weighted(items, random) { it % 6 }
            assertEquals(items.size, out.size)
            assertEquals(items.toSet(), out.toSet())
        }
    }

    @Test
    fun `a one-track queue and an empty queue come back untouched`() {
        assertEquals(emptyList<Int>(), Shuffle.weighted(emptyList<Int>(), Random(1)) { 5 })
        assertEquals(listOf(42), Shuffle.weighted(listOf(42), Random(1)) { 5 })
    }

    /** The ordering that matters: better ratings should sit nearer the front, on average. */
    @Test
    fun `mean position falls as the rating rises`() {
        val random = Random(20260911)
        val items = corpus()
        val totalRank = IntArray(RatingStore.MAX + 1)
        val runs = 4000

        repeat(runs) {
            Shuffle.weighted(items, random) { it }.forEachIndexed { index, stars ->
                totalRank[stars] += index
            }
        }
        val mean = totalRank.map { it.toDouble() / (runs * 4) }

        // 5 ahead of 4 ahead of 3, and every favourite ahead of everything that isn't one.
        assertTrue("5* should lead 4*: $mean", mean[5] < mean[4])
        assertTrue("4* should lead 3*: $mean", mean[4] < mean[3])
        assertTrue("3* should lead unrated: $mean", mean[3] < mean[0])
        // An explicit low rating is a judgement; no rating at all is just silence.
        assertTrue("unrated should lead 2*: $mean", mean[0] < mean[2])
        assertTrue("2* should lead 1*: $mean", mean[2] < mean[1])
    }

    /**
     * The operator's actual ask: with a library that is mostly unrated, what comes up first should
     * still nearly always be something they liked.
     */
    @Test
    fun `a favourite leads the queue the overwhelming majority of the time`() {
        val random = Random(20260911)
        // Three 5-star, one 4-star, one 3-star, one 2-star, one 1-star, five unrated.
        val items = listOf(5, 5, 5, 4, 3, 2, 1, 0, 0, 0, 0, 0)
        val runs = 10_000
        val favouriteFirst = (1..runs).count {
            Shuffle.weighted(items, random) { it }.first() >= RatingStore.FAVOURITE
        }
        val share = favouriteFirst.toDouble() / runs
        assertTrue("favourite led only ${"%.3f".format(share)} of queues", share > 0.85)
    }

    /** Weighting must never become filtering: a 1-star track still gets its turn. */
    @Test
    fun `even the worst rated track reaches the front sometimes`() {
        val random = Random(20260911)
        val items = listOf(5, 5, 5, 1)
        val leads = (1..20_000).count { Shuffle.weighted(items, random) { it }.first() == 1 }
        assertTrue("a 1-star track never led in 20000 queues", leads > 0)
    }
}
