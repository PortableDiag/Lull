package com.lull.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A named, ordered set of tracks, referenced by their MediaStore id (the same id used as mediaId). */
data class Playlist(
    val id: Long,
    val name: String,
    val trackIds: List<Long>
)

/**
 * Persists playlists as JSON in the shared [ThemeManager.PREFS] file. Playlists are just lists of
 * track ids, so they cost almost nothing to store and survive the underlying files moving around;
 * a track that has since been deleted is simply skipped when a playlist is shown, not pruned, so it
 * comes back if the file (or SD card) reappears.
 *
 * The last-viewed collection is stored here too, which is what lets the library reopen on the
 * playlist you left it on.
 */
object PlaylistStore {
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_NEXT_ID = "playlist_next_id"
    private const val KEY_LAST = "last_collection"

    /** Collection key for the whole device library (the default view). */
    const val ALL = "all"

    private fun sp(c: Context) =
        c.getSharedPreferences(ThemeManager.PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<Playlist> {
        val raw = sp(c).getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return runCatching { parse(raw) }.getOrDefault(emptyList())
    }

    fun get(c: Context, id: Long): Playlist? = all(c).firstOrNull { it.id == id }

    fun create(c: Context, name: String): Playlist {
        val nextId = sp(c).getLong(KEY_NEXT_ID, 1L)
        val playlist = Playlist(nextId, name.trim(), emptyList())
        sp(c).edit().putLong(KEY_NEXT_ID, nextId + 1).apply()
        write(c, all(c) + playlist)
        return playlist
    }

    fun rename(c: Context, id: Long, name: String) =
        write(c, all(c).map { if (it.id == id) it.copy(name = name.trim()) else it })

    fun delete(c: Context, id: Long) {
        write(c, all(c).filter { it.id != id })
        if (lastCollection(c) == key(id)) setLastCollection(c, ALL)
    }

    /** Appends the track unless it is already present. Returns false if it was already there. */
    fun addTrack(c: Context, id: Long, trackId: Long): Boolean {
        val list = all(c).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val playlist = list[idx]
        if (playlist.trackIds.contains(trackId)) return false
        list[idx] = playlist.copy(trackIds = playlist.trackIds + trackId)
        write(c, list)
        return true
    }

    fun removeTrack(c: Context, id: Long, trackId: Long) = updateTracks(c, id) { it - trackId }

    fun setOrder(c: Context, id: Long, trackIds: List<Long>) = updateTracks(c, id) { trackIds }

    private inline fun updateTracks(c: Context, id: Long, transform: (List<Long>) -> List<Long>) {
        val list = all(c).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(trackIds = transform(list[idx].trackIds))
        write(c, list)
    }

    // ---------------- Last-viewed collection ----------------

    fun key(id: Long): String = "pl:$id"

    fun lastCollection(c: Context): String = sp(c).getString(KEY_LAST, ALL) ?: ALL

    fun setLastCollection(c: Context, collectionKey: String) =
        sp(c).edit().putString(KEY_LAST, collectionKey).apply()

    // ---------------- JSON ----------------

    private fun parse(raw: String): List<Playlist> {
        val arr = JSONArray(raw)
        val out = ArrayList<Playlist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val idsArr = o.getJSONArray("tracks")
            val ids = ArrayList<Long>(idsArr.length())
            for (j in 0 until idsArr.length()) ids.add(idsArr.getLong(j))
            out.add(Playlist(o.getLong("id"), o.getString("name"), ids))
        }
        return out
    }

    private fun write(c: Context, list: List<Playlist>) {
        val arr = JSONArray()
        for (p in list) {
            val ids = JSONArray()
            for (t in p.trackIds) ids.put(t)
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("tracks", ids))
        }
        sp(c).edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
    }
}
