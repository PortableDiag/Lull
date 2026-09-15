package com.lull.player.api

import com.lull.player.TrackFacts
import java.io.InputStream

/**
 * Everything the API can do, expressed without a single Android type.
 *
 * The split matters: [ApiRouter] decides what a request *means* — which handler, what the
 * parameters are, which status a failure deserves — and this decides what actually happens to the
 * library, the player and the stores. Only the second half needs a device, so the first half is
 * tested properly, against a fake, on the JVM.
 *
 * The rule for the whole interface: **an agent holding the key can do what the person holding the
 * phone can do, and nothing they can't.** Every operation here has a counterpart in the UI.
 */
interface ApiBackend {

    fun instance(): InstanceInfo

    // ---------------- Library ----------------

    fun tracks(query: String?, limit: Int, offset: Int): TrackPage

    fun track(id: Long): TrackFacts?

    /** The audio file itself — the API's answer to "share the whole track". */
    fun trackStream(id: Long): MediaStream?

    fun artworkStream(id: Long): MediaStream?

    /**
     * One track's tempo in beats per minute: 0 where nothing is known yet, -1 where it has been
     * analysed and hasn't got one. See `TempoStore` for why those are two different answers.
     */
    fun bpm(id: Long): Int

    /** Overrides a track's tempo, the way typing over the number in the panel does; 0 forgets it. */
    fun setBpm(id: Long, bpm: Int)

    /**
     * The quiet stretches inside a track at the current threshold, or null where it has never been
     * analysed — which is not the same as a track that was analysed and has none.
     */
    fun quietSections(id: Long): List<QuietSection>?

    /** Null for a browse axis that doesn't exist; an empty list for one that is simply empty. */
    fun groups(tab: String): List<GroupSummary>?

    fun groupTracks(key: String): List<TrackFacts>?

    // ---------------- Player ----------------

    fun player(): PlayerSnapshot

    /** Expands a request for a group, or a bare list of ids, into the tracks to queue. */
    fun resolveIds(ids: List<Long>, groupKey: String?): List<Long>

    /** @return how many tracks were queued. [startId] plays first where it is in the list. */
    fun play(ids: List<Long>, startId: Long?): Int

    fun resume()
    fun pause()
    fun next()
    fun previous()
    fun stop()
    fun seek(positionMs: Long)

    /** Appends to what is already playing. @return how many were added. */
    fun queue(ids: List<Long>): Int

    fun setRepeat(mode: String)
    fun setShuffle(mode: String)
    fun setVolume(level: Int)

    fun armSleep(minutes: Int)
    fun cancelSleep()

    /** @return false when the region is unusable — no track, or B not far enough past A. */
    fun setAb(aMs: Long, bMs: Long): Boolean
    fun clearAb()

    // ---------------- Ratings ----------------

    fun ratings(): Map<Long, Int>
    fun rate(ids: List<Long>, stars: Int)

    // ---------------- Playlists ----------------

    fun playlists(): List<PlaylistView>
    fun playlist(id: Long): PlaylistView?
    fun createPlaylist(name: String, ids: List<Long>): PlaylistView
    fun renamePlaylist(id: Long, name: String): Boolean
    fun deletePlaylist(id: Long): Boolean
    fun duplicatePlaylist(id: Long, name: String?): PlaylistView?
    fun addToPlaylist(id: Long, ids: List<Long>): Int
    fun removeFromPlaylist(id: Long, ids: List<Long>): Boolean
    fun reorderPlaylist(id: Long, ids: List<Long>): Boolean

    // ---------------- Settings ----------------

    fun settings(): SettingsSnapshot
    fun updateSettings(patch: SettingsPatch): SettingsSnapshot

    // ---------------- Share ----------------

    /** Pops the system share sheet on the device for [ids]; see [ShareResult] for the catch. */
    fun share(ids: List<Long>): ShareResult
}

data class InstanceInfo(
    val versionName: String,
    val versionCode: Int,
    val apiVersion: Int,
    val port: Int,
    val lan: Boolean,
    val hasAudioPermission: Boolean,
    val trackCount: Int
)

data class TrackPage(val total: Int, val offset: Int, val items: List<TrackFacts>)

data class GroupSummary(
    val key: String,
    val title: String,
    val subtitle: String,
    val trackIds: List<Long>
)

data class PlayerSnapshot(
    val connected: Boolean,
    val playing: Boolean,
    val mediaId: String?,
    val title: String,
    val artist: String,
    val positionMs: Long,
    val durationMs: Long,
    val queueIndex: Int,
    val queueSize: Int,
    val queue: List<TrackFacts>,
    val repeat: String,
    val shuffle: String,
    val volume: Int,
    val maxVolume: Int,
    val sleepRemainingMs: Long,
    val abAMs: Long,
    val abBMs: Long,
    /** What the playing track is actually running at — 1.0 unless Force Tempo is moving it. */
    val speed: Float = 1f,
    /** The playing track's own tempo, as [ApiBackend.bpm] reports it. */
    val sourceBpm: Int = 0,
    /** True while the position sits in one of the track's dead spots and is being pushed through. */
    val boosted: Boolean = false
)

/** A stretch of one track quiet enough to be worth speeding through. */
data class QuietSection(val startMs: Long, val endMs: Long)

data class PlaylistView(val id: Long, val name: String, val trackIds: List<Long>)

data class SettingsSnapshot(
    val theme: String,
    val crossfadeSec: Int,
    val skipSilence: Boolean,
    val mixAudio: Boolean,
    val volumeStyle: String,
    val repeat: String,
    val shuffle: String,
    val forceTempo: Boolean = false,
    val tempoBpm: Int = 0,
    val tempoMaxChange: Int = 0,
    val tempoOnlyFaster: Boolean = true,
    val pushQuiet: Boolean = false,
    val quietBoost: Int = 0,
    val quietThresholdDb: Int = 0
)

/** Null means "leave that one alone" — a PATCH of one field must not reset the other six. */
data class SettingsPatch(
    val theme: String? = null,
    val crossfadeSec: Int? = null,
    val skipSilence: Boolean? = null,
    val mixAudio: Boolean? = null,
    val volumeStyle: String? = null,
    val forceTempo: Boolean? = null,
    val tempoBpm: Int? = null,
    val tempoMaxChange: Int? = null,
    val tempoOnlyFaster: Boolean? = null,
    val pushQuiet: Boolean? = null,
    val quietBoost: Int? = null,
    val quietThresholdDb: Int? = null
)

/**
 * What came of a share request.
 *
 * [launched] says the chooser was started; [visible] says Lull was on screen at the time, which is
 * the part that decides whether anyone will actually *see* it — Android does not let a background
 * app throw a dialog over whatever you are doing. So the share text and a download url come back
 * regardless: a script that cannot pop a chooser can still take the file and the metadata itself.
 */
data class ShareResult(
    val launched: Boolean,
    val visible: Boolean,
    val subject: String,
    val text: String,
    val trackIds: List<Long>
)

/** A file on its way out: a stream the socket can drain, and what to call it. */
class MediaStream(
    val stream: InputStream,
    val contentType: String,
    val length: Long,
    val fileName: String
)
