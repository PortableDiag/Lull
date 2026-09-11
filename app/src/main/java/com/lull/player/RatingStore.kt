package com.lull.player

import android.content.Context
import org.json.JSONObject

/**
 * Star ratings, 1–5, held against the MediaStore track id — the same id playlists use and the same
 * one that travels as a `MediaItem`'s `mediaId`.
 *
 * MediaStore has no writable rating column for audio, so this is Lull's own store: a JSON map in
 * the shared [ThemeManager.PREFS] file, exactly like [PlaylistStore]. Ratings therefore cost almost
 * nothing, survive files moving around, and never touch the file itself — no tag rewriting, so a
 * rating can't corrupt a track or change its checksum.
 *
 * The parsed map is cached in memory because [TrackAdapter] asks for a rating on every row bind;
 * a JSON parse per row would be absurd. Service and activities share a process, so a singleton
 * cache is consistent for everyone — the same reasoning as [AbLoop].
 */
object RatingStore {

    /** No rating. Distinct from 1 star, which is a judgement. */
    const val UNRATED = 0

    const val MAX = 5

    /**
     * At or above this a track counts as one you like — the set Favourites shuffle leans on.
     * Three is the pivot the operator asked for: it is the lowest rating that still means "yes".
     */
    const val FAVOURITE = 3

    private const val KEY_RATINGS = "ratings"

    private var cache: MutableMap<Long, Int>? = null

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }

    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun notifyChanged() = listeners.toList().forEach { it() }

    private fun sp(c: Context) =
        c.getSharedPreferences(ThemeManager.PREFS, Context.MODE_PRIVATE)

    private fun map(c: Context): MutableMap<Long, Int> = cache ?: load(c).also { cache = it }

    private fun load(c: Context): MutableMap<Long, Int> {
        val raw = sp(c).getString(KEY_RATINGS, null) ?: return HashMap()
        return runCatching {
            val obj = JSONObject(raw)
            val out = HashMap<Long, Int>(obj.length())
            for (key in obj.keys()) {
                val id = key.toLongOrNull() ?: continue
                val stars = obj.optInt(key).coerceIn(UNRATED, MAX)
                if (stars != UNRATED) out[id] = stars
            }
            out
        }.getOrDefault(HashMap())
    }

    private fun write(c: Context, map: Map<Long, Int>) {
        val obj = JSONObject()
        for ((id, stars) in map) obj.put(id.toString(), stars)
        sp(c).edit().putString(KEY_RATINGS, obj.toString()).apply()
    }

    /** Stars for one track, or [UNRATED]. Cheap enough to call from a RecyclerView bind. */
    fun of(c: Context, id: Long): Int = map(c)[id] ?: UNRATED

    fun isFavourite(c: Context, id: Long): Boolean = of(c, id) >= FAVOURITE

    /** Sets (or with [UNRATED], clears) one track's rating. */
    fun set(c: Context, id: Long, stars: Int) {
        val value = stars.coerceIn(UNRATED, MAX)
        val map = map(c)
        val existing = map[id] ?: UNRATED
        if (existing == value) return
        if (value == UNRATED) map.remove(id) else map[id] = value
        write(c, map)
        notifyChanged()
    }

    /**
     * Rates a whole selection in one write, rather than rewriting the JSON once per track —
     * the same reason [PlaylistStore.addTracks] exists.
     */
    fun setAll(c: Context, ids: Collection<Long>, stars: Int) {
        if (ids.isEmpty()) return
        val value = stars.coerceIn(UNRATED, MAX)
        val map = map(c)
        var changed = false
        for (id in ids) {
            val existing = map[id] ?: UNRATED
            if (existing == value) continue
            if (value == UNRATED) map.remove(id) else map[id] = value
            changed = true
        }
        if (!changed) return
        write(c, map)
        notifyChanged()
    }

    /** How many tracks carry each rating, 1..[MAX] — what the Rated tab counts its groups by. */
    fun countByStars(c: Context): Map<Int, Int> =
        map(c).values.groupingBy { it }.eachCount()

    /** A star strip for display: `★★★☆☆` trimmed to the filled ones, empty when unrated. */
    fun glyphs(stars: Int): String = "★".repeat(stars.coerceIn(UNRATED, MAX))
}
