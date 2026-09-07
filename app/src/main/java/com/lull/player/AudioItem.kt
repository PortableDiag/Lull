package com.lull.player

import android.content.ContentUris
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class AudioItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val size: Long,
    /** Absolute path to the file where MediaStore still exposes one. Empty otherwise. */
    val path: String = "",
    /** Directory holding the file, e.g. `/storage/emulated/0/Music/Ambient`. Empty if unknown. */
    val folderPath: String = "",
    /** The folder's own name, e.g. `Ambient` — what the Folders view lists. */
    val folder: String = "",
    /** From MediaStore's genre tables; empty when the file carries no genre tag. */
    val genre: String = "",
    /** Album artist where the tag has one, else the track artist. */
    val albumArtist: String = "",
    val trackNo: Int = 0
) {
    val artworkUri: Uri?
        get() = if (albumId > 0)
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
        else null

    fun toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUri)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
}
