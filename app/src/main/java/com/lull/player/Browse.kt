package com.lull.player

/**
 * The library's browse axes. A flat list of every track is fine for a phone holding a dozen files
 * and useless for one holding thousands, so the same library is offered grouped four ways as well.
 *
 * Each tab is a list of [Group]s you drill into; [LibraryTab.TRACKS] is the one that has no groups
 * and shows the tracks straight away.
 */
enum class LibraryTab(val prefix: String) {
    TRACKS("all"),
    FOLDERS("folder"),
    ARTISTS("artist"),
    ALBUMS("album"),
    GENRES("genre"),
    PLAYLISTS("pl");

    companion object {
        /** The tab a stored collection key belongs to, e.g. `artist:Boards of Canada`. */
        fun forKey(key: String): LibraryTab =
            values().firstOrNull { it != TRACKS && key.startsWith("${it.prefix}:") } ?: TRACKS
    }
}

/**
 * One row in a grouped view — a folder, artist, album, genre or playlist — together with the
 * tracks it holds, already in the order they should play.
 */
data class Group(
    /** Stable, storable address: `folder:/storage/emulated/0/Music`, `album:42`, `pl:3`. */
    val key: String,
    val title: String,
    val subtitle: String,
    val tracks: List<AudioItem>
) {
    val art: AudioItem? get() = MediaLibrary.groupArt(tracks)
}

object Browse {

    /** Case-insensitive, so `abba` and `ABBA` don't sit at opposite ends of the list. */
    private val byTitle = compareBy(String.CASE_INSENSITIVE_ORDER) { it: Group -> it.title }

    fun groups(
        tab: LibraryTab,
        library: List<AudioItem>,
        playlists: List<Playlist>,
        unknownArtist: String,
        unknownGenre: String,
        unknownAlbum: String,
        trackCount: (Int) -> String
    ): List<Group> = when (tab) {
        LibraryTab.TRACKS -> emptyList()

        LibraryTab.FOLDERS -> library
            .groupBy { it.folderPath }
            .map { (path, tracks) ->
                Group(
                    key = "${LibraryTab.FOLDERS.prefix}:$path",
                    title = tracks.first().folder.ifBlank { path.substringAfterLast('/') }
                        .ifBlank { "/" },
                    // The containing path, so two folders both called "Music" are tellable apart.
                    subtitle = shorten(path.substringBeforeLast('/', "")) +
                        "  ·  " + trackCount(tracks.size),
                    tracks = tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                )
            }
            .sortedWith(byTitle)

        LibraryTab.ARTISTS -> library
            .groupBy { it.artist.ifBlank { unknownArtist } }
            .map { (artist, tracks) ->
                Group(
                    key = "${LibraryTab.ARTISTS.prefix}:$artist",
                    title = artist,
                    subtitle = albumCount(tracks) + "  ·  " + trackCount(tracks.size),
                    // An artist reads best album by album, each album in its own track order.
                    tracks = tracks.sortedWith(
                        compareBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                    ).sortedWith(compareBy({ it.album.lowercase() }, { it.trackNo }, { it.title.lowercase() }))
                )
            }
            .sortedWith(byTitle)

        LibraryTab.ALBUMS -> library
            .groupBy { if (it.albumId > 0) it.albumId.toString() else "n:" + it.album }
            .map { (id, tracks) ->
                Group(
                    key = "${LibraryTab.ALBUMS.prefix}:$id",
                    title = tracks.first().album.ifBlank { unknownAlbum },
                    subtitle = tracks.first().albumArtist.ifBlank { unknownArtist } +
                        "  ·  " + trackCount(tracks.size),
                    tracks = tracks.sortedWith(compareBy({ it.trackNo }, { it.title.lowercase() }))
                )
            }
            .sortedWith(byTitle)

        LibraryTab.GENRES -> library
            .groupBy { it.genre.ifBlank { unknownGenre } }
            .map { (genre, tracks) ->
                Group(
                    key = "${LibraryTab.GENRES.prefix}:$genre",
                    title = genre,
                    subtitle = artistCount(tracks) + "  ·  " + trackCount(tracks.size),
                    tracks = tracks.sortedWith(
                        compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.trackNo })
                    )
                )
            }
            .sortedWith(byTitle)

        // Playlists keep the order the user dragged them into, so they are never re-sorted.
        LibraryTab.PLAYLISTS -> {
            val byId = library.associateBy { it.id }
            playlists.map { p ->
                val tracks = p.trackIds.mapNotNull { byId[it] }
                Group(
                    key = PlaylistStore.key(p.id),
                    title = p.name,
                    subtitle = trackCount(tracks.size),
                    tracks = tracks
                )
            }
        }
    }

    private fun albumCount(tracks: List<AudioItem>): String {
        val n = tracks.map { it.album.lowercase() }.distinct().size
        return if (n == 1) "1 album" else "$n albums"
    }

    private fun artistCount(tracks: List<AudioItem>): String {
        val n = tracks.map { it.artist.lowercase() }.distinct().size
        return if (n == 1) "1 artist" else "$n artists"
    }

    /** Trims the storage prefix off a path so the interesting end of it survives one line. */
    private fun shorten(path: String): String = path
        .removePrefix("/storage/emulated/0")
        .removePrefix("/storage")
        .ifBlank { "Internal storage" }
        .trimStart('/')
}
