package com.lull.player.api

import android.Manifest
import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.lull.player.AbLoop
import com.lull.player.AudioItem
import com.lull.player.Browse
import com.lull.player.Changes
import com.lull.player.LibraryTab
import com.lull.player.MediaLibrary
import com.lull.player.PlaylistStore
import com.lull.player.Prefs
import com.lull.player.R
import com.lull.player.RatingStore
import com.lull.player.Shuffle
import com.lull.player.Sharing
import com.lull.player.SleepTimer
import com.lull.player.Tempo
import com.lull.player.TempoStore
import com.lull.player.ThemeManager
import com.lull.player.TrackFacts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The API, wired to the actual app.
 *
 * Every method here has a counterpart the user can reach with a finger, and where there is a choice
 * it makes the *same* choice the UI makes — playing a list honours the saved shuffle mode, rating a
 * batch is one write, a group key means what it means in the Browse tabs. An API that quietly
 * behaved differently from the buttons would be a second app sharing one library.
 *
 * Three things about how it talks to the rest of the app:
 *
 *  - **The library is cached.** A MediaStore load with the genre tables behind it is not something
 *    to run per request; it is re-read when it is [STALE_AFTER_MS] old.
 *  - **Anything the player, the markers, the timer or the theme own happens on the main thread**,
 *    through [MainThread] — those are all main-thread-only, and the API arrives on socket threads.
 *  - **Mutations announce themselves** through [Changes], so a playlist an agent creates appears on
 *    a library that is open and looking at it.
 */
class LullBackend(
    private val context: Context,
    private val controller: () -> MediaController?
) : ApiBackend {

    companion object {
        /** Short enough that a new file shows up while you are still looking for it. */
        const val STALE_AFTER_MS = 15_000L

        /** Artwork is re-encoded to serve it, so pick a size worth the bytes. */
        private const val ART_PX = 512
    }

    private var cached: List<AudioItem> = emptyList()
    private var cachedAt = 0L

    @Synchronized
    private fun library(): List<AudioItem> {
        val now = System.currentTimeMillis()
        if (cached.isEmpty() || now - cachedAt > STALE_AFTER_MS) {
            cached = if (hasAudioPermission()) MediaLibrary.query(context) else emptyList()
            cachedAt = now
        }
        return cached
    }

    private fun byId(id: Long): AudioItem? = library().firstOrNull { it.id == id }

    private fun facts(item: AudioItem): TrackFacts = Sharing.facts(item, RatingStore.of(context, item.id))

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** The player is connected lazily; a request that needs it and hasn't got it is a 503, not a 500. */
    private fun requireController(): MediaController =
        controller() ?: throw ApiException(
            503, "the player is not connected yet",
            "it connects within a second of the API starting — retry"
        )

    // ---------------- Instance ----------------

    override fun instance(): InstanceInfo {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode.toInt()
        else pkg.versionCode
        return InstanceInfo(
            versionName = pkg.versionName.orEmpty(),
            versionCode = code,
            apiVersion = ApiRouter.API_VERSION,
            port = ApiPrefs.port(context),
            lan = ApiPrefs.lan(context),
            hasAudioPermission = hasAudioPermission(),
            trackCount = library().size
        )
    }

    // ---------------- Library ----------------

    override fun tracks(query: String?, limit: Int, offset: Int): TrackPage {
        val q = query?.trim()?.lowercase().orEmpty()
        val all = library().filter {
            q.isEmpty() || it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
        val page = all.drop(offset).take(limit)
        return TrackPage(all.size, offset, page.map { facts(it) })
    }

    override fun track(id: Long): TrackFacts? = byId(id)?.let { facts(it) }

    override fun bpm(id: Long): Int = TempoStore.of(context, id)

    /**
     * The API's half of the correction the panel offers, and held to the same rule: a number given
     * to us wins over a measured one and is never quietly re-measured. 0 forgets it, which is how
     * you ask for it to be worked out again.
     */
    override fun setBpm(id: Long, bpm: Int) = TempoStore.set(context, id, bpm)

    override fun trackStream(id: Long): MediaStream? {
        val item = byId(id) ?: return null
        val stream = runCatching { context.contentResolver.openInputStream(item.uri) }.getOrNull()
            ?: throw ApiException(
                404, "the file behind track $id could not be opened",
                "MediaStore still lists it, so it has probably been deleted or unmounted"
            )
        val name = item.path.substringAfterLast('/').ifBlank { "${item.title}.audio" }
        return MediaStream(stream, Sharing.mimeFor(name), item.size, name)
    }

    /**
     * Artwork as JPEG bytes. Re-encoded rather than served off disk because the source is a
     * thumbnail the platform decodes for us — there is no original file to hand over.
     */
    override fun artworkStream(id: Long): MediaStream? {
        val item = byId(id) ?: return null
        val bitmap: Bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                context.contentResolver.loadThumbnail(item.uri, Size(ART_PX, ART_PX), null)
            else null
        }.getOrNull() ?: runCatching {
            item.artworkUri?.let { art ->
                context.contentResolver.openInputStream(art)
                    ?.use { android.graphics.BitmapFactory.decodeStream(it) }
            }
        }.getOrNull() ?: return null

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        val bytes = out.toByteArray()
        return MediaStream(
            ByteArrayInputStream(bytes), "image/jpeg", bytes.size.toLong(), "art-$id.jpg"
        )
    }

    override fun groups(tab: String): List<GroupSummary>? {
        val libraryTab = LibraryTab.values().firstOrNull { it.name.equals(tab, true) }
            ?: return null
        if (libraryTab == LibraryTab.TRACKS) return null
        return browse(libraryTab).map {
            GroupSummary(it.key, it.title, it.subtitle, it.tracks.map { t -> t.id })
        }
    }

    override fun groupTracks(key: String): List<TrackFacts>? {
        val tab = LibraryTab.forKey(key)
        if (tab == LibraryTab.TRACKS) return null
        return browse(tab).firstOrNull { it.key == key }?.tracks?.map { facts(it) }
    }

    private fun browse(tab: LibraryTab) = Browse.groups(
        tab = tab,
        library = library(),
        playlists = PlaylistStore.all(context),
        unknownArtist = context.getString(R.string.unknown_artist),
        unknownGenre = context.getString(R.string.unknown_genre),
        unknownAlbum = context.getString(R.string.unknown_album),
        trackCount = { n ->
            if (n == 1) context.getString(R.string.track_count_one)
            else context.getString(R.string.track_count, n)
        },
        ratingOf = { id -> RatingStore.of(context, id) }
    )

    // ---------------- Player ----------------

    override fun player(): PlayerSnapshot = MainThread.get {
        val c = controller()
        if (c == null) {
            PlayerSnapshot(
                connected = false, playing = false, mediaId = null, title = "", artist = "",
                positionMs = 0, durationMs = 0, queueIndex = -1, queueSize = 0, queue = emptyList(),
                repeat = repeatName(Prefs.repeatMode(context)),
                shuffle = shuffleName(Prefs.shuffleMode(context)),
                volume = volume(), maxVolume = maxVolume(),
                sleepRemainingMs = SleepTimer.remainingMs(),
                abAMs = AbLoop.aMs, abBMs = AbLoop.bMs
            )
        } else {
            val item = c.currentMediaItem
            val queue = (0 until c.mediaItemCount).map { i ->
                val queued = c.getMediaItemAt(i)
                val id = queued.mediaId.toLongOrNull() ?: -1L
                byId(id)?.let { facts(it) } ?: TrackFacts(
                    id = id,
                    title = queued.mediaMetadata.title?.toString().orEmpty(),
                    artist = queued.mediaMetadata.artist?.toString().orEmpty(),
                    album = queued.mediaMetadata.albumTitle?.toString().orEmpty()
                )
            }
            PlayerSnapshot(
                connected = true,
                playing = c.isPlaying,
                mediaId = item?.mediaId,
                title = item?.mediaMetadata?.title?.toString().orEmpty(),
                artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = c.duration.coerceAtLeast(0),
                queueIndex = c.currentMediaItemIndex,
                queueSize = c.mediaItemCount,
                queue = queue,
                repeat = repeatName(c.repeatMode),
                shuffle = shuffleName(Prefs.shuffleMode(context)),
                volume = volume(), maxVolume = maxVolume(),
                sleepRemainingMs = SleepTimer.remainingMs(),
                abAMs = AbLoop.aMs, abBMs = AbLoop.bMs,
                // Reported rather than read off the player: a MediaController's playback parameters
                // are the session's, and Force Tempo sets the speed on the ExoPlayer underneath it.
                // Recomputing from the same inputs the service used is the honest answer, and it is
                // also the only one available while nothing is playing yet.
                speed = Prefs.speedFor(context, tempoOf(item?.mediaId)),
                sourceBpm = item?.mediaId?.toLongOrNull()?.let { TempoStore.of(context, it) } ?: 0
            )
        }
    }

    /** The playing track's own tempo as [Tempo] wants it — 0 for unknown and for tempo-less. */
    private fun tempoOf(mediaId: String?): Float =
        mediaId?.toLongOrNull()?.let { TempoStore.bpmFor(context, it) } ?: 0f

    /**
     * Ids, a group key, or both. A group resolves to the tracks in it, in the order the Browse tab
     * would list them — the API's "play this album" has to mean the same thing as tapping it.
     */
    override fun resolveIds(ids: List<Long>, groupKey: String?): List<Long> {
        val fromGroup = groupKey?.let { key ->
            val tab = LibraryTab.forKey(key)
            if (tab == LibraryTab.TRACKS) emptyList()
            else browse(tab).firstOrNull { it.key == key }?.tracks?.map { it.id }.orEmpty()
        }.orEmpty()
        val known = library().map { it.id }.toSet()
        return (ids.filter { it in known } + fromGroup).distinct()
    }

    override fun play(ids: List<Long>, startId: Long?): Int = MainThread.get {
        val c = requireController()
        val byIdMap = library().associateBy { it.id }
        val tracks = ids.mapNotNull { byIdMap[it] }
        if (tracks.isEmpty()) throw ApiException(404, "none of those ids are in the library")

        // Exactly what MainActivity.startQueue does, and for the same reasons: a tapped track
        // still plays first under Favourites shuffle, and plain shuffle stays the player's flag.
        val mode = Prefs.shuffleMode(context)
        var items = tracks
        var index = startId?.let { id -> tracks.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0

        if (mode == Prefs.SHUFFLE_FAVOURITES) {
            val first = startId?.let { id -> tracks.firstOrNull { it.id == id } }
            val rest = Shuffle.weighted(
                if (first == null) tracks else tracks.filterNot { it.id == first.id }
            ) { RatingStore.of(context, it.id) }
            items = if (first == null) rest else listOf(first) + rest
            index = 0
        }

        c.shuffleModeEnabled = mode == Prefs.SHUFFLE_ALL
        c.repeatMode = Prefs.repeatMode(context)
        c.setMediaItems(items.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
        items.size
    }

    override fun resume() = MainThread.run { requireController().play() }

    override fun pause() = MainThread.run { requireController().pause() }

    override fun next() = MainThread.run { requireController().seekToNext() }

    override fun previous() = MainThread.run { requireController().seekToPrevious() }

    override fun stop() = MainThread.run {
        val c = requireController()
        c.stop()
        c.clearMediaItems()
    }

    override fun seek(positionMs: Long) = MainThread.run { requireController().seekTo(positionMs) }

    override fun queue(ids: List<Long>): Int = MainThread.get {
        val c = requireController()
        val byIdMap = library().associateBy { it.id }
        val tracks = ids.mapNotNull { byIdMap[it] }
        if (tracks.isEmpty()) throw ApiException(404, "none of those ids are in the library")
        if (c.mediaItemCount == 0) {
            c.setMediaItems(tracks.map { it.toMediaItem() }, 0, 0L)
            c.prepare()
            c.play()
        } else {
            c.addMediaItems(tracks.map { it.toMediaItem() })
        }
        tracks.size
    }

    override fun setRepeat(mode: String) = MainThread.run {
        val value = when (mode) {
            "one" -> Player.REPEAT_MODE_ONE
            "all" -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        Prefs.setRepeatMode(context, value)
        controller()?.repeatMode = value
    }

    /**
     * Switching *to* Favourites re-weights what has not played yet and leaves the playing track
     * alone — the same thing the shuffle button does, including the part where switching it back
     * off cannot unscramble what it reordered.
     */
    override fun setShuffle(mode: String) = MainThread.run {
        val value = when (mode) {
            "all" -> Prefs.SHUFFLE_ALL
            "favourites" -> Prefs.SHUFFLE_FAVOURITES
            else -> Prefs.SHUFFLE_OFF
        }
        Prefs.setShuffleMode(context, value)
        val c = controller()
        if (c != null) {
            c.shuffleModeEnabled = value == Prefs.SHUFFLE_ALL
            if (value == Prefs.SHUFFLE_FAVOURITES) reorderUpcoming(c)
        }
    }

    private fun reorderUpcoming(c: MediaController) {
        val count = c.mediaItemCount
        val from = c.currentMediaItemIndex + 1
        if (count - from < 2) return
        val upcoming = (from until count).map { c.getMediaItemAt(it) }
        val ordered = Shuffle.weighted(upcoming) {
            RatingStore.of(context, it.mediaId.toLongOrNull() ?: -1L)
        }
        c.removeMediaItems(from, count)
        c.addMediaItems(ordered)
    }

    override fun setVolume(level: Int) = MainThread.run {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            level.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),
            0
        )
    }

    private fun volume(): Int =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getStreamVolume(AudioManager.STREAM_MUSIC)

    private fun maxVolume(): Int =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    override fun armSleep(minutes: Int) = MainThread.run {
        Prefs.setSleepMinutes(context, minutes)
        SleepTimer.arm(minutes * 60_000L)
    }

    override fun cancelSleep() = MainThread.run { SleepTimer.cancel() }

    override fun setAb(aMs: Long, bMs: Long): Boolean = MainThread.get {
        val c = controller() ?: return@get false
        val id = c.currentMediaItem?.mediaId ?: return@get false
        AbLoop.setA(id, aMs)
        val ok = AbLoop.setB(id, bMs, c.duration)
        if (!ok) AbLoop.clear()
        ok
    }

    override fun clearAb() = MainThread.run { AbLoop.clear() }

    private fun repeatName(mode: Int) = when (mode) {
        Player.REPEAT_MODE_ONE -> "one"
        Player.REPEAT_MODE_ALL -> "all"
        else -> "off"
    }

    private fun shuffleName(mode: Int) = when (mode) {
        Prefs.SHUFFLE_ALL -> "all"
        Prefs.SHUFFLE_FAVOURITES -> "favourites"
        else -> "off"
    }

    // ---------------- Ratings ----------------

    override fun ratings(): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        for (item in library()) {
            val stars = RatingStore.of(context, item.id)
            if (stars != RatingStore.UNRATED) out[item.id] = stars
        }
        return out
    }

    /** On the main thread because rating notifies the screens, and they are made of views. */
    override fun rate(ids: List<Long>, stars: Int) = MainThread.run {
        RatingStore.setAll(context, ids, stars)
    }

    // ---------------- Playlists ----------------

    override fun playlists(): List<PlaylistView> =
        PlaylistStore.all(context).map { PlaylistView(it.id, it.name, it.trackIds) }

    override fun playlist(id: Long): PlaylistView? =
        PlaylistStore.get(context, id)?.let { PlaylistView(it.id, it.name, it.trackIds) }

    override fun createPlaylist(name: String, ids: List<Long>): PlaylistView = MainThread.get {
        val created = PlaylistStore.createWith(context, name, ids)
        Changes.notifyChanged()
        PlaylistView(created.id, created.name, created.trackIds)
    }

    override fun renamePlaylist(id: Long, name: String): Boolean = MainThread.get {
        if (PlaylistStore.get(context, id) == null) return@get false
        PlaylistStore.rename(context, id, name)
        Changes.notifyChanged()
        true
    }

    override fun deletePlaylist(id: Long): Boolean = MainThread.get {
        if (PlaylistStore.get(context, id) == null) return@get false
        PlaylistStore.delete(context, id)
        Changes.notifyChanged()
        true
    }

    override fun duplicatePlaylist(id: Long, name: String?): PlaylistView? = MainThread.get {
        val source = PlaylistStore.get(context, id) ?: return@get null
        val copy = PlaylistStore.duplicate(
            context, id, name ?: context.getString(R.string.copy_suffix, source.name)
        ) ?: return@get null
        Changes.notifyChanged()
        PlaylistView(copy.id, copy.name, copy.trackIds)
    }

    override fun addToPlaylist(id: Long, ids: List<Long>): Int = MainThread.get {
        val added = PlaylistStore.addTracks(context, id, ids)
        Changes.notifyChanged()
        added
    }

    override fun removeFromPlaylist(id: Long, ids: List<Long>): Boolean = MainThread.get {
        if (PlaylistStore.get(context, id) == null) return@get false
        PlaylistStore.removeTracks(context, id, ids)
        Changes.notifyChanged()
        true
    }

    override fun reorderPlaylist(id: Long, ids: List<Long>): Boolean = MainThread.get {
        val existing = PlaylistStore.get(context, id) ?: return@get false
        // A reorder that dropped or invented members would be an edit wearing a reorder's clothes.
        if (ids.toSet() != existing.trackIds.toSet()) throw ApiException(
            400, "order must list exactly the tracks already in the playlist",
            "it holds ${existing.trackIds.size}: ${existing.trackIds.joinToString(",")}"
        )
        PlaylistStore.setOrder(context, id, ids)
        Changes.notifyChanged()
        true
    }

    // ---------------- Settings ----------------

    override fun settings(): SettingsSnapshot = SettingsSnapshot(
        theme = when (ThemeManager.savedMode(context)) {
            ThemeManager.LIGHT -> "light"
            ThemeManager.DARK -> "dark"
            else -> "system"
        },
        crossfadeSec = Prefs.crossfadeSec(context),
        skipSilence = Prefs.skipSilence(context),
        mixAudio = Prefs.mixAudio(context),
        volumeStyle = if (Prefs.volumeStyle(context) == Prefs.VOL_KNOB) "knob" else "bar",
        repeat = repeatName(Prefs.repeatMode(context)),
        shuffle = shuffleName(Prefs.shuffleMode(context)),
        forceTempo = Prefs.forceTempo(context),
        tempoBpm = Prefs.tempoBpm(context),
        tempoMaxChange = Prefs.tempoMaxChange(context),
        tempoOnlyFaster = Prefs.tempoOnlyFaster(context)
    )

    override fun updateSettings(patch: SettingsPatch): SettingsSnapshot = MainThread.get {
        patch.theme?.let {
            ThemeManager.setMode(
                context, when (it) {
                    "light" -> ThemeManager.LIGHT
                    "dark" -> ThemeManager.DARK
                    else -> ThemeManager.SYSTEM
                }
            )
        }
        patch.crossfadeSec?.let { Prefs.setCrossfadeSec(context, it) }
        patch.skipSilence?.let { Prefs.setSkipSilence(context, it) }
        patch.mixAudio?.let { Prefs.setMixAudio(context, it) }
        patch.volumeStyle?.let {
            Prefs.setVolumeStyle(context, if (it == "knob") Prefs.VOL_KNOB else Prefs.VOL_BAR)
        }
        patch.tempoBpm?.let { Prefs.setTempoBpm(context, it) }
        patch.tempoMaxChange?.let { Prefs.setTempoMaxChange(context, it) }
        patch.tempoOnlyFaster?.let { Prefs.setTempoOnlyFaster(context, it) }
        // Last, for the same reason the panel writes it last: the service reacts to each of these
        // keys, and the switch is the one that makes it act on all of them.
        patch.forceTempo?.let { Prefs.setForceTempo(context, it) }
        settings()
    }

    // ---------------- Share ----------------

    /**
     * Pops the system chooser for [ids].
     *
     * It is started with `NEW_TASK` because the caller is a service, and whether anyone ever sees
     * it depends on something the API cannot control: Android does not let an app that is not on
     * screen put a dialog in front of you. So the result says whether Lull was visible, and the
     * caller gets the text and the file urls either way.
     */
    override fun share(ids: List<Long>): ShareResult = MainThread.get {
        val tracks = ids.mapNotNull { byId(it) }
        if (tracks.isEmpty()) throw ApiException(404, "none of those ids are in the library")
        val factsList = tracks.map { facts(it) }
        val visible = Foreground.isVisible

        val intent = Sharing.intentFor(context, tracks)
        val launched = intent != null && runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess

        ShareResult(
            launched = launched,
            visible = visible,
            subject = Sharing.subject(factsList),
            text = Sharing.describeAll(factsList),
            trackIds = tracks.map { it.id }
        )
    }
}

/**
 * Whether any of Lull's screens is in front of the user.
 *
 * Only the share endpoint needs this, and it needs it to be *honest* rather than optimistic: a
 * chooser started from the background is discarded by the system with no error, so an API that
 * reported success there would be lying in the one case the caller most needs to know about.
 */
object Foreground : Application.ActivityLifecycleCallbacks {

    private var started = 0

    val isVisible: Boolean get() = started > 0

    fun install(app: Application) {
        app.unregisterActivityLifecycleCallbacks(this)
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
