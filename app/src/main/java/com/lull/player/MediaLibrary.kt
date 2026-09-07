package com.lull.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * Reads the device's audio out of MediaStore, with the extra columns the Folder, Artist, Album and
 * Genre views are grouped by.
 *
 * Genre is the awkward one: the `GENRE` column on a track only exists from API 30, so it is read
 * instead from the genre membership tables, which have been there all along. That is one query per
 * genre — but a device has tens of genres, not thousands of them, and it happens once per load on
 * the IO dispatcher rather than per row.
 */
object MediaLibrary {

    private const val TAG = "LullLibrary"

    fun query(context: Context): List<AudioItem> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = ArrayList<String>().apply {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
            }
        }.toTypedArray()

        // Only real music: MediaStore also indexes ringtones, alarms and notification blips.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.IS_PODCAST} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val genres = genresByTrackId(context)
        val list = ArrayList<AudioItem>()

        runCatching {
            context.contentResolver.query(collection, projection, selection, null, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val trackCol = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val bucketCol = c.getColumnIndex("bucket_display_name")
                val relCol = c.getColumnIndex("relative_path")
                val albumArtistCol = c.getColumnIndex("album_artist")

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val artist = c.getString(artistCol)?.takeIf { it != "<unknown>" }.orEmpty()
                    val data = if (dataCol >= 0) c.getString(dataCol).orEmpty() else ""

                    // DATA is the reliable one where we can still read it; RELATIVE_PATH is the
                    // scoped-storage replacement and is all we get on some volumes.
                    val dir = when {
                        data.contains('/') -> data.substringBeforeLast('/')
                        relCol >= 0 -> c.getString(relCol).orEmpty().trimEnd('/')
                        else -> ""
                    }
                    val folderName = when {
                        bucketCol >= 0 && !c.getString(bucketCol).isNullOrBlank() -> c.getString(bucketCol)
                        dir.isNotEmpty() -> dir.substringAfterLast('/')
                        else -> ""
                    }

                    list.add(
                        AudioItem(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            title = c.getString(titleCol) ?: "Unknown",
                            artist = artist,
                            album = c.getString(albumCol).orEmpty(),
                            albumId = c.getLong(albumIdCol),
                            durationMs = c.getLong(durCol),
                            size = c.getLong(sizeCol),
                            path = data,
                            folderPath = dir,
                            folder = folderName,
                            genre = genres[id].orEmpty(),
                            albumArtist = if (albumArtistCol >= 0)
                                c.getString(albumArtistCol).orEmpty().ifBlank { artist } else artist,
                            // TRACK is disc*1000 + track on multi-disc rips; the low three digits
                            // are the track, which is what an album listing wants.
                            trackNo = if (trackCol >= 0) c.getInt(trackCol) % 1000 else 0
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "audio query failed", it) }

        return list
    }

    /**
     * Maps track id -> genre name via the genre membership tables. Returns an empty map rather
     * than failing the whole load if genres are unreadable — a missing genre costs one view,
     * an exception costs the library.
     */
    private fun genresByTrackId(context: Context): Map<Long, String> {
        val out = HashMap<Long, String>()
        runCatching {
            val genresUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Audio.Genres.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI

            context.contentResolver.query(
                genresUri,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
                null, null, null
            )?.use { g ->
                val gIdCol = g.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
                val gNameCol = g.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
                while (g.moveToNext()) {
                    val genreId = g.getLong(gIdCol)
                    val name = g.getString(gNameCol)?.trim().orEmpty()
                    if (name.isEmpty()) continue

                    val membersUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        MediaStore.Audio.Genres.Members.getContentUri(MediaStore.VOLUME_EXTERNAL, genreId)
                    else MediaStore.Audio.Genres.Members.getContentUri("external", genreId)

                    context.contentResolver.query(
                        membersUri, arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null
                    )?.use { m ->
                        val col = m.getColumnIndexOrThrow(MediaStore.Audio.Genres.Members.AUDIO_ID)
                        while (m.moveToNext()) out[m.getLong(col)] = name
                    }
                }
            }
        }.onFailure { Log.w(TAG, "genre query failed", it) }
        return out
    }

    /** Album art for a group row: the first track in it that has any. */
    fun groupArt(tracks: List<AudioItem>): AudioItem? = tracks.firstOrNull { it.albumId > 0 }

    fun contentUriFor(id: Long): Uri =
        ContentUris.withAppendedId(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            id
        )
}
