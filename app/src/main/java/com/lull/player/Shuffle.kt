package com.lull.player

import kotlin.math.ln
import kotlin.random.Random

/**
 * The ordering behind **Favourites shuffle**: a random queue that leans on what you rated highly.
 *
 * This has to be Lull's own concept rather than a player setting, because Media3's
 * `shuffleModeEnabled` is an *unweighted* permutation with no hook to bias it. So Favourites
 * shuffle builds the order here and hands the player a plain queue with shuffle switched off.
 *
 * It is a **reordering, not a filter**: every track you asked to play is still in the queue, so
 * Next eventually reaches all of it and repeat-all still wraps the whole thing. Highly rated
 * tracks simply come up early and often; a 1-star track is rarer than an unrated one, because an
 * explicit 1 star is a judgement and no rating at all is just silence.
 */
object Shuffle {

    /**
     * Relative chance of landing early, indexed by star rating (index 0 is unrated).
     *
     * The jump between 2 and 3 stars is the whole point — [RatingStore.FAVOURITE] is where a
     * rating starts meaning "yes", so that is where the weight steps up rather than creeping.
     */
    private val WEIGHTS = doubleArrayOf(3.0, 1.0, 2.0, 12.0, 24.0, 40.0)

    fun weightFor(stars: Int): Double = WEIGHTS[stars.coerceIn(0, RatingStore.MAX)]

    /**
     * A weighted random permutation — every item appears exactly once, with higher-weighted items
     * more likely to appear near the front.
     *
     * Uses the Efraimidis–Spirakis key: give each item `-ln(u) / w` for a uniform `u`, and sorting
     * ascending draws the whole list in weighted order without replacement in one pass. (Repeatedly
     * picking and re-normalising would be the obvious approach and is quadratic.)
     *
     * The keys are drawn **once, up front**, and only then sorted. Handing the draw to `sortedBy`
     * instead looks tidier and is broken: the selector runs on every comparison, so each item's key
     * changes underneath the sort. That is an inconsistent comparator, and on a list long enough
     * for TimSort to merge runs — a few dozen tracks — it throws
     * `IllegalArgumentException: Comparison method violates its general contract!`.
     */
    fun <T> weighted(
        items: List<T>,
        random: Random = Random.Default,
        starsOf: (T) -> Int
    ): List<T> {
        if (items.size < 2) return items
        return items
            .map { item ->
                // nextDouble() can return exactly 0, and ln(0) is -infinity — nudge it off the floor.
                val u = random.nextDouble().coerceAtLeast(1e-12)
                item to -ln(u) / weightFor(starsOf(item))
            }
            .sortedBy { it.second }
            .map { it.first }
    }
}
