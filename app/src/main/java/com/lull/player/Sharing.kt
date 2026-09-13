package com.lull.player

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem

/**
 * The facts that travel with a shared track.
 *
 * Deliberately free of Android types: a `content://` uri carries a *file*, and everything a person
 * reading the share would want to know about it — who it is by, what it came off, how long it is —
 * is stripped by the time the receiving app sees it. So the text is built from this, and it is
 * plain enough to test on the JVM and to serialise into the API's JSON without a second mapping.
 */
data class TrackFacts(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = "",
    val genre: String = "",
    val trackNo: Int = 0,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val stars: Int = RatingStore.UNRATED,
    val fileName: String = ""
)

/**
 * Sharing a track out of Lull — the counterpart to [OpenIntent], which brings one in.
 *
 * **The whole track goes, not a link to it.** `EXTRA_STREAM` carries the audio itself, with
 * [Intent.FLAG_GRANT_READ_URI_PERMISSION] so the receiving app can read it whether or not it holds
 * media permission of its own.
 *
 * **The metadata goes with it**, because a uri does not carry any. What arrives at the other end is
 * otherwise a filename — often a track number and nothing else — so title, artist, album, genre,
 * duration and your own rating are written into `EXTRA_TEXT`, and the title/artist into
 * `EXTRA_SUBJECT` for the apps that show one (mail, and most note-takers).
 *
 * Nothing is re-encoded and no tag is rewritten: the file that leaves is byte-for-byte the file on
 * disk, which is the same promise [RatingStore] makes about ratings.
 */
object Sharing {

    /** What a share of unknown type is typed as. Every receiver that takes audio at all takes this. */
    const val GENERIC_AUDIO = "audio/*"

    // ---------------- The text ----------------

    /**
     * The metadata block for one track: title, then who it is by and what it came off, then the
     * details that fit on one line.
     *
     * Empty fields are skipped rather than written as "Unknown" — a share that says
     * `Unknown artist · Unknown album` tells the reader nothing and costs them a line to find out.
     */
    fun describe(facts: TrackFacts): String {
        val lines = ArrayList<String>(4)
        lines.add(facts.title.ifBlank { facts.fileName.ifBlank { "Audio" } })

        val by = listOf(facts.artist, facts.album).filter { it.isNotBlank() }
        if (by.isNotEmpty()) lines.add(by.joinToString(" — "))

        // The album artist only earns its line when it disagrees with the track artist; on most
        // libraries they are the same string and repeating it is noise.
        if (facts.albumArtist.isNotBlank() && !facts.albumArtist.equals(facts.artist, true)) {
            lines.add("Album artist: ${facts.albumArtist}")
        }

        val details = ArrayList<String>(5)
        if (facts.trackNo > 0) details.add("Track ${facts.trackNo}")
        if (facts.genre.isNotBlank()) details.add(facts.genre)
        if (facts.durationMs > 0) details.add(Format.duration(facts.durationMs))
        if (facts.sizeBytes > 0) details.add(Format.size(facts.sizeBytes))
        if (facts.stars > RatingStore.UNRATED) details.add(RatingStore.glyphs(facts.stars))
        if (details.isNotEmpty()) lines.add(details.joinToString(" · "))

        return lines.joinToString("\n")
    }

    /**
     * The text for a share of several tracks: a one-line header, then one numbered line each.
     *
     * A full block per track would run to pages on a selection of forty, and the per-track blocks
     * are the part a reader skims rather than reads — so the list stays one line per track, with
     * the album named once up top when they all came off the same one.
     */
    fun describeAll(tracks: List<TrackFacts>): String {
        if (tracks.isEmpty()) return ""
        if (tracks.size == 1) return describe(tracks.first())

        val albums = tracks.map { it.album }.filter { it.isNotBlank() }.distinct()
        val artists = tracks.map { it.artist }.filter { it.isNotBlank() }.distinct()
        val header = when {
            albums.size == 1 && artists.size == 1 -> "${tracks.size} tracks — ${artists[0]} — ${albums[0]}"
            albums.size == 1 -> "${tracks.size} tracks — ${albums[0]}"
            artists.size == 1 -> "${tracks.size} tracks — ${artists[0]}"
            else -> "${tracks.size} tracks"
        }

        val lines = tracks.mapIndexed { i, t ->
            val name = t.title.ifBlank { t.fileName.ifBlank { "Audio" } }
            val who = t.artist.takeIf { it.isNotBlank() && artists.size > 1 }?.let { " — $it" }.orEmpty()
            val len = t.durationMs.takeIf { it > 0 }
                ?.let { "  (${Format.duration(it)})" }.orEmpty()
            "${i + 1}. $name$who$len"
        }
        return (listOf(header) + lines).joinToString("\n")
    }

    /** The one-line subject for the apps that show one. */
    fun subject(tracks: List<TrackFacts>): String = when {
        tracks.isEmpty() -> ""
        tracks.size == 1 -> {
            val t = tracks.first()
            val name = t.title.ifBlank { t.fileName.ifBlank { "Audio" } }
            if (t.artist.isBlank()) name else "$name — ${t.artist}"
        }
        else -> describeAll(tracks).substringBefore('\n')
    }

    /**
     * The mime type to send [fileName] as.
     *
     * Typed as precisely as the extension allows, because some receivers filter on the concrete
     * type and would not offer themselves for the wildcard one; anything unrecognised falls back
     * to [GENERIC_AUDIO] rather than guessing wrong.
     */
    fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mp3", "mpga" -> "audio/mpeg"
        "m4a", "aac", "mp4" -> "audio/mp4"
        "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "mka" -> "audio/x-matroska"
        "wma" -> "audio/x-ms-wma"
        "aiff", "aif" -> "audio/aiff"
        "amr" -> "audio/amr"
        "mid", "midi" -> "audio/midi"
        "ape" -> "audio/x-ape"
        "wv" -> "audio/x-wavpack"
        "3gp" -> "audio/3gpp"
        else -> GENERIC_AUDIO
    }

    /**
     * One mime type for a whole selection: the shared one if they agree, else the generic one.
     * A chooser given `audio/mpeg` for a list containing a FLAC would be lying to the receiver.
     */
    fun mimeForAll(fileNames: List<String>): String =
        fileNames.map { mimeFor(it) }.distinct().singleOrNull() ?: GENERIC_AUDIO

    // ---------------- The intent ----------------

    fun facts(item: AudioItem, stars: Int): TrackFacts = TrackFacts(
        id = item.id,
        title = item.title,
        artist = item.artist,
        album = item.album,
        albumArtist = item.albumArtist,
        genre = item.genre,
        trackNo = item.trackNo,
        durationMs = item.durationMs,
        sizeBytes = item.size,
        stars = stars,
        fileName = item.path.substringAfterLast('/')
    )

    /** A chooser that sends [tracks] — the files themselves — with their metadata alongside. */
    fun intentFor(context: Context, tracks: List<AudioItem>): Intent? {
        if (tracks.isEmpty()) return null
        val facts = tracks.map { facts(it, RatingStore.of(context, it.id)) }
        return chooser(
            context = context,
            uris = tracks.map { it.uri },
            mime = mimeForAll(facts.map { it.fileName }),
            subject = subject(facts),
            text = describeAll(facts),
            title = context.getString(R.string.share_track)
        )
    }

    /**
     * The same, for whatever is playing right now.
     *
     * Now Playing talks to a `MediaController` and has no library of its own, and the playing track
     * may not be *in* the library at all — a file opened from a file manager is played straight off
     * the intent's uri. So the facts come off the `MediaItem` the session is holding, which is the
     * one thing guaranteed to describe what you are actually hearing.
     */
    fun intentForCurrent(context: Context, item: MediaItem, durationMs: Long): Intent? {
        val uri = item.localConfiguration?.uri ?: return null
        val md = item.mediaMetadata
        val id = item.mediaId.toLongOrNull() ?: -1L
        val fileName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val facts = TrackFacts(
            id = id,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            genre = md.genre?.toString().orEmpty(),
            durationMs = durationMs.coerceAtLeast(0L),
            stars = if (id > 0) RatingStore.of(context, id) else RatingStore.UNRATED,
            fileName = fileName
        )
        return chooser(
            context = context,
            uris = listOf(uri),
            mime = mimeFor(fileName),
            subject = subject(listOf(facts)),
            text = describe(facts),
            title = context.getString(R.string.share_track)
        )
    }

    /**
     * Builds the chooser. `SEND` for one file and `SEND_MULTIPLE` for several, because a receiver
     * that accepts only single sends is the common case and would not appear for the plural action.
     *
     * `ClipData` is set as well as `EXTRA_STREAM`: the grant flag applies to the clip data, so
     * without it a receiver holding no media permission gets a uri it cannot open — the same
     * handoff Lull relies on in the other direction when a file manager opens a track *into* it.
     *
     * It is built with [ClipData.newUri] and not `newRawUri`, which is the difference between the
     * share sheet naming the file and the share sheet showing a bare MediaStore id. `newUri` asks
     * the provider for the type and the display name; `newRawUri` says there is nothing to ask.
     */
    private fun chooser(
        context: Context, uris: List<Uri>, mime: String, subject: String, text: String, title: String
    ): Intent? {
        if (uris.isEmpty()) return null
        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
            // The share sheet's preview title, set the way the platform asks for it (the title on
            // the chooser itself is ignored from API 33 with a log line saying so). It does not
            // rescue the sheet's file chip, which on API 35 labels a MediaStore audio uri with a
            // bare id because the chooser's own metadata read fails ("Invalid column flags" — it
            // asks MediaStore for a DocumentsContract column). That is the sheet drawing itself
            // wrong; the receiving app queries DISPLAY_NAME and gets the real filename.
            putExtra(Intent.EXTRA_TITLE, subject)
            clipData = ClipData.newUri(context.contentResolver, subject, uris.first()).also { clip ->
                for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // The title on the chooser itself is only read below API 33; from there the sheet takes the
        // wrapped intent's EXTRA_TITLE instead. Both are set, because minSdk is 26.
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
