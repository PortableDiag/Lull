package com.lull.player.api

import com.lull.player.TrackFacts
import java.io.ByteArrayInputStream

/**
 * A library, a player and a set of stores, in memory.
 *
 * The point is not to simulate Android — it is to let [ApiRouter] be asked every question a caller
 * can ask and have the answers checked. Anything the router is supposed to refuse never reaches
 * here, and anything it is supposed to pass through arrives recorded.
 */
class FakeBackend : ApiBackend {

    val tracks = linkedMapOf(
        1L to TrackFacts(1, "Nightfall", "Boards of Canada", "Geogaddi", genre = "Ambient",
            trackNo = 4, durationMs = 321_000, sizeBytes = 5_000_000, stars = 4, fileName = "04.mp3"),
        2L to TrackFacts(2, "Sunshine Recorder", "Boards of Canada", "Geogaddi",
            durationMs = 360_000, stars = 5, fileName = "05.mp3"),
        3L to TrackFacts(3, "rain-loop", "", "", durationMs = 3_600_000, fileName = "rain.wav")
    )

    val playlistsById = linkedMapOf(1L to PlaylistView(1, "Sleep", listOf(3L)))
    val ratingsById = hashMapOf(1L to 4, 2L to 5)

    // What the router asked for, in the order it asked.
    val calls = mutableListOf<String>()

    var playing = false
    // Named apart from the interface's setters: a `var repeat` would generate a
    // setRepeat(String) that clashes with the one being implemented.
    var repeatMode = "off"
    var shuffleMode = "off"
    var volumeLevel = 7
    var sleepMinutes = 0
    var abA = -1L
    var abB = -1L
    var lastPlayed: List<Long> = emptyList()
    var lastStartId: Long? = null
    var shareVisible = true
    var abAcceptable = true
    var nextPlaylistId = 2L

    override fun instance() = InstanceInfo("1.9", 11, ApiRouter.API_VERSION, 8787, false, true, tracks.size)

    override fun tracks(query: String?, limit: Int, offset: Int): TrackPage {
        calls += "tracks(q=$query,limit=$limit,offset=$offset)"
        val q = query?.lowercase().orEmpty()
        val all = tracks.values.filter {
            q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }
        return TrackPage(all.size, offset, all.drop(offset).take(limit))
    }

    override fun track(id: Long): TrackFacts? = tracks[id]

    override fun trackStream(id: Long): MediaStream? {
        val t = tracks[id] ?: return null
        val bytes = "audio-bytes-for-$id".toByteArray()
        return MediaStream(ByteArrayInputStream(bytes), "audio/mpeg", bytes.size.toLong(), t.fileName)
    }

    override fun artworkStream(id: Long): MediaStream? {
        if (id != 1L) return null
        val bytes = byteArrayOf(1, 2, 3)
        return MediaStream(ByteArrayInputStream(bytes), "image/jpeg", 3, "art-$id.jpg")
    }

    override fun groups(tab: String): List<GroupSummary>? = when (tab.lowercase()) {
        "albums" -> listOf(GroupSummary("album:10", "Geogaddi", "2 tracks", listOf(1L, 2L)))
        "folders" -> listOf(GroupSummary("folder:/music", "music", "3 tracks", listOf(1L, 2L, 3L)))
        "artists", "genres", "playlists", "rated" -> emptyList()
        else -> null
    }

    override fun groupTracks(key: String): List<TrackFacts>? = when (key) {
        "album:10" -> listOf(tracks[1]!!, tracks[2]!!)
        else -> null
    }

    override fun player() = PlayerSnapshot(
        connected = true, playing = playing, mediaId = "1", title = "Nightfall",
        artist = "Boards of Canada", positionMs = 1_000, durationMs = 321_000,
        queueIndex = 0, queueSize = tracks.size, queue = tracks.values.toList(),
        repeat = repeatMode, shuffle = shuffleMode, volume = volumeLevel, maxVolume = 15,
        sleepRemainingMs = sleepMinutes * 60_000L, abAMs = abA, abBMs = abB
    )

    override fun resolveIds(ids: List<Long>, groupKey: String?): List<Long> {
        val fromGroup = groupKey?.let { key ->
            groups("albums").orEmpty().firstOrNull { it.key == key }?.trackIds
                ?: groups("folders").orEmpty().firstOrNull { it.key == key }?.trackIds
        }.orEmpty()
        return (ids.filter { it in tracks.keys } + fromGroup).distinct()
    }

    override fun play(ids: List<Long>, startId: Long?): Int {
        calls += "play(${ids.joinToString(",")},start=$startId)"
        lastPlayed = ids
        lastStartId = startId
        playing = true
        return ids.size
    }

    override fun resume() { calls += "resume"; playing = true }
    override fun pause() { calls += "pause"; playing = false }
    override fun next() { calls += "next" }
    override fun previous() { calls += "previous" }
    override fun stop() { calls += "stop"; playing = false }
    override fun seek(positionMs: Long) { calls += "seek($positionMs)" }

    override fun queue(ids: List<Long>): Int {
        calls += "queue(${ids.joinToString(",")})"
        return ids.size
    }

    override fun setRepeat(mode: String) { repeatMode = mode; calls += "repeat($mode)" }
    override fun setShuffle(mode: String) { shuffleMode = mode; calls += "shuffle($mode)" }
    override fun setVolume(level: Int) { volumeLevel = level; calls += "volume($level)" }
    override fun armSleep(minutes: Int) { sleepMinutes = minutes; calls += "sleep($minutes)" }
    override fun cancelSleep() { sleepMinutes = 0; calls += "sleep(cancel)" }

    override fun setAb(aMs: Long, bMs: Long): Boolean {
        calls += "ab($aMs,$bMs)"
        if (!abAcceptable) return false
        abA = aMs; abB = bMs
        return true
    }

    override fun clearAb() { abA = -1; abB = -1; calls += "ab(clear)" }

    override fun ratings(): Map<Long, Int> = ratingsById

    override fun rate(ids: List<Long>, stars: Int) {
        calls += "rate(${ids.joinToString(",")},$stars)"
        for (id in ids) if (stars == 0) ratingsById.remove(id) else ratingsById[id] = stars
    }

    override fun playlists(): List<PlaylistView> = playlistsById.values.toList()

    override fun playlist(id: Long): PlaylistView? = playlistsById[id]

    override fun createPlaylist(name: String, ids: List<Long>): PlaylistView {
        val created = PlaylistView(nextPlaylistId++, name, ids)
        playlistsById[created.id] = created
        return created
    }

    override fun renamePlaylist(id: Long, name: String): Boolean {
        val existing = playlistsById[id] ?: return false
        playlistsById[id] = existing.copy(name = name)
        return true
    }

    override fun deletePlaylist(id: Long): Boolean = playlistsById.remove(id) != null

    override fun duplicatePlaylist(id: Long, name: String?): PlaylistView? {
        val source = playlistsById[id] ?: return null
        return createPlaylist(name ?: "${source.name} copy", source.trackIds)
    }

    override fun addToPlaylist(id: Long, ids: List<Long>): Int {
        val existing = playlistsById[id] ?: return 0
        val fresh = ids.filterNot { it in existing.trackIds }
        playlistsById[id] = existing.copy(trackIds = existing.trackIds + fresh)
        return fresh.size
    }

    override fun removeFromPlaylist(id: Long, ids: List<Long>): Boolean {
        val existing = playlistsById[id] ?: return false
        playlistsById[id] = existing.copy(trackIds = existing.trackIds.filterNot { it in ids })
        return true
    }

    /** Set to make the next reorder fail the way the real backend refuses a lossy one. */
    var reorderRefusal: ApiException? = null

    override fun reorderPlaylist(id: Long, ids: List<Long>): Boolean {
        reorderRefusal?.let { throw it }
        val existing = playlistsById[id] ?: return false
        playlistsById[id] = existing.copy(trackIds = ids)
        return true
    }

    private var settings = SettingsSnapshot("dark", 0, false, true, "bar", "off", "off")

    override fun settings(): SettingsSnapshot = settings

    override fun updateSettings(patch: SettingsPatch): SettingsSnapshot {
        calls += "settings($patch)"
        settings = settings.copy(
            theme = patch.theme ?: settings.theme,
            crossfadeSec = patch.crossfadeSec ?: settings.crossfadeSec,
            skipSilence = patch.skipSilence ?: settings.skipSilence,
            mixAudio = patch.mixAudio ?: settings.mixAudio,
            volumeStyle = patch.volumeStyle ?: settings.volumeStyle
        )
        return settings
    }

    override fun share(ids: List<Long>): ShareResult {
        calls += "share(${ids.joinToString(",")})"
        val facts = ids.mapNotNull { tracks[it] }
        return ShareResult(
            launched = shareVisible,
            visible = shareVisible,
            subject = com.lull.player.Sharing.subject(facts),
            text = com.lull.player.Sharing.describeAll(facts),
            trackIds = ids
        )
    }
}
