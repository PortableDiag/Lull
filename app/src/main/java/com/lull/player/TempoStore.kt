package com.lull.player

import android.content.Context
import org.json.JSONObject

/**
 * What each track's tempo is, against the MediaStore id — the same id [RatingStore] and
 * [PlaylistStore] key on, so a BPM survives the file moving and nothing is ever written into the
 * audio itself.
 *
 * This is a cache with a memory, not a scratchpad. Working a tempo out costs a decode of half a
 * minute of audio ([BpmDetector]), which is cheap once and wasteful on every play, so the answer is
 * kept — and so is the *absence* of an answer. A track that has been analysed and has no tempo
 * (spoken word, drone, a field recording) is written down as [NONE] rather than left blank,
 * because an unmarked blank is indistinguishable from "not looked at yet" and an audiobook would
 * be re-analysed from scratch every time you played it.
 *
 * A number the user typed is stored the same way and simply wins: [set] overwrites whatever
 * detection concluded, and detection never overwrites anything already here ([setDetected]).
 * Detection is a guess about a waveform; a person correcting it is not.
 */
object TempoStore {

    /** Nothing known — nobody has looked yet. */
    const val UNKNOWN = 0

    /** Looked, and there is no tempo in there. Distinct from [UNKNOWN], and the reason it exists. */
    const val NONE = -1

    private const val KEY_TEMPOS = "tempos"

    private var cache: MutableMap<Long, Int>? = null

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }

    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun notifyChanged() = listeners.toList().forEach { it() }

    private fun sp(c: Context) =
        c.getSharedPreferences(ThemeManager.PREFS, Context.MODE_PRIVATE)

    private fun map(c: Context): MutableMap<Long, Int> = cache ?: load(c).also { cache = it }

    private fun load(c: Context): MutableMap<Long, Int> {
        val raw = sp(c).getString(KEY_TEMPOS, null) ?: return HashMap()
        return runCatching {
            val obj = JSONObject(raw)
            val out = HashMap<Long, Int>(obj.length())
            for (key in obj.keys()) {
                val id = key.toLongOrNull() ?: continue
                val bpm = obj.optInt(key, UNKNOWN)
                if (bpm != UNKNOWN) out[id] = bpm
            }
            out
        }.getOrDefault(HashMap())
    }

    private fun write(c: Context, map: Map<Long, Int>) {
        val obj = JSONObject()
        for ((id, bpm) in map) obj.put(id.toString(), bpm)
        sp(c).edit().putString(KEY_TEMPOS, obj.toString()).apply()
    }

    /** [UNKNOWN], [NONE], or a tempo. */
    fun of(c: Context, id: Long): Int = map(c)[id] ?: UNKNOWN

    /** The tempo to hand [Tempo.speedFor] — 0 for both "not yet known" and "hasn't got one". */
    fun bpmFor(c: Context, id: Long): Float = of(c, id).let { if (it > 0) it.toFloat() else 0f }

    /** True when there is nothing to do but analyse it. */
    fun needsDetection(c: Context, id: Long): Boolean = of(c, id) == UNKNOWN

    /**
     * Writes a tempo the user gave us. [UNKNOWN] clears the entry, which puts the track back in
     * the queue for detection — the way to say "your number was wrong, look again".
     */
    fun set(c: Context, id: Long, bpm: Int) {
        val value = normalise(bpm)
        val map = map(c)
        if ((map[id] ?: UNKNOWN) == value) return
        if (value == UNKNOWN) map.remove(id) else map[id] = value
        write(c, map)
        notifyChanged()
    }

    /**
     * Writes what the detector concluded, but only where nothing is known yet.
     *
     * The guard is what keeps a correction typed by hand from being quietly undone the next time
     * the track comes round and something decides to re-analyse it.
     */
    fun setDetected(c: Context, id: Long, bpm: Int) {
        if (!needsDetection(c, id)) return
        set(c, id, bpm)
    }

    private fun normalise(bpm: Int): Int = when {
        bpm == NONE -> NONE
        bpm <= 0 -> UNKNOWN
        else -> bpm.coerceIn(BpmAnalysis.MIN_BPM.toInt(), BpmAnalysis.MAX_BPM.toInt())
    }

    /**
     * Forgets the tempos of tracks that are gone, in one write.
     *
     * Per-id [set] calls would rewrite the JSON once each; deleting an album would rewrite it
     * twelve times for an answer nobody can use again.
     */
    fun forgetAll(c: Context, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val map = map(c)
        var changed = false
        for (id in ids) if (map.remove(id) != null) changed = true
        if (!changed) return
        write(c, map)
        notifyChanged()
    }

    /** Forget every tempo — for a library that has been re-tagged wholesale. */
    fun clear(c: Context) {
        cache = HashMap()
        sp(c).edit().remove(KEY_TEMPOS).apply()
        notifyChanged()
    }
}
