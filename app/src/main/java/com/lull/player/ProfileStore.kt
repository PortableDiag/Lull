package com.lull.player

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * Each track's loudness over time — one byte per second, as [EnergyProfile] builds it.
 *
 * Kept apart from [TempoStore] for two reasons. A tempo is one number and a profile is a few
 * hundred bytes, so they belong in **different preference files**: `SharedPreferences` loads a file
 * whole and keeps it in memory, and a rating lookup on a RecyclerView bind should not be paging
 * past the waveform of every track you have ever played. And they have different lifetimes — a
 * tempo the user typed is theirs forever, while a profile is pure measurement and can be thrown
 * away and rebuilt whenever the analysis changes.
 *
 * **What is stored is deliberately not the answer.** A profile is decibels below the track's own
 * reference; which of those seconds count as a *dead spot* is decided at playback time from the
 * current threshold setting. So moving that slider re-reads every track already measured, instead
 * of invalidating the lot and re-decoding a library.
 *
 * Three states, the same shape as [TempoStore]'s: **absent** means nobody has looked, an **empty**
 * profile means it was analysed and produced nothing usable (a decode that ran out of budget
 * before the end of the file), and anything else is a profile. The empty case has to be recorded
 * or a file that cannot be decoded in time is re-decoded on every single play.
 */
object ProfileStore {

    private const val PREFS = "lull_profiles"
    private const val KEY_PROFILES = "profiles"

    private var cache: MutableMap<Long, ByteArray>? = null

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }

    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun notifyChanged() = listeners.toList().forEach { it() }

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun map(c: Context): MutableMap<Long, ByteArray> = cache ?: load(c).also { cache = it }

    private fun load(c: Context): MutableMap<Long, ByteArray> {
        val raw = sp(c).getString(KEY_PROFILES, null) ?: return HashMap()
        return runCatching {
            val obj = JSONObject(raw)
            val out = HashMap<Long, ByteArray>(obj.length())
            for (key in obj.keys()) {
                val id = key.toLongOrNull() ?: continue
                out[id] = decode(obj.optString(key))
            }
            out
        }.getOrDefault(HashMap())
    }

    private fun write(c: Context, map: Map<Long, ByteArray>) {
        val obj = JSONObject()
        for ((id, profile) in map) obj.put(id.toString(), encode(profile))
        sp(c).edit().putString(KEY_PROFILES, obj.toString()).apply()
    }

    /** The profile for a track, or null if it has never been analysed. */
    fun of(c: Context, id: Long): ByteArray? = map(c)[id]

    /** True when there is nothing to do but analyse it. */
    fun needsAnalysis(c: Context, id: Long): Boolean = !map(c).containsKey(id)

    /**
     * The dead spots in a track at the current setting.
     *
     * Computed on demand rather than stored, which is the whole point of storing the curve instead
     * of the conclusion — see the note on this class.
     */
    fun sectionsFor(c: Context, id: Long, thresholdDb: Int): List<EnergyProfile.Section> {
        val profile = of(c, id) ?: return emptyList()
        return EnergyProfile.sections(profile, thresholdDb)
    }

    fun set(c: Context, id: Long, profile: ByteArray) {
        val map = map(c)
        val existing = map[id]
        if (existing != null && existing.contentEquals(profile)) return
        map[id] = profile
        write(c, map)
        notifyChanged()
    }

    /** Forgets one track's profile, so the next play measures it again. */
    fun forget(c: Context, id: Long) {
        val map = map(c)
        if (map.remove(id) == null) return
        write(c, map)
        notifyChanged()
    }

    /** [forget], for a whole selection, in one write. */
    fun forgetAll(c: Context, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val map = map(c)
        var changed = false
        for (id in ids) if (map.remove(id) != null) changed = true
        if (!changed) return
        write(c, map)
        notifyChanged()
    }

    /** Forget every profile — for a library that has been re-encoded or re-mastered wholesale. */
    fun clear(c: Context) {
        cache = HashMap()
        sp(c).edit().remove(KEY_PROFILES).apply()
        notifyChanged()
    }

    private fun encode(profile: ByteArray): String =
        if (profile.isEmpty()) "" else Base64.encodeToString(profile, Base64.NO_WRAP)

    private fun decode(encoded: String?): ByteArray = runCatching {
        if (encoded.isNullOrEmpty()) ByteArray(0) else Base64.decode(encoded, Base64.NO_WRAP)
    }.getOrDefault(ByteArray(0))
}
