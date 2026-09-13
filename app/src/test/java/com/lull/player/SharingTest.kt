package com.lull.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a share says.
 *
 * The file that travels is the app's own file and cannot be got wrong; the *text* is the part that
 * is written by hand, and it is the only thing the person on the other end reads. So these check
 * the two things it must never do — invent facts it doesn't have, and bury the ones it does.
 */
class SharingTest {

    private val full = TrackFacts(
        id = 42,
        title = "Nightfall",
        artist = "Boards of Canada",
        album = "Geogaddi",
        albumArtist = "Boards of Canada",
        genre = "Ambient",
        trackNo = 4,
        durationMs = 321_000,
        sizeBytes = 1024L * 1536,
        stars = 4,
        fileName = "04 Nightfall.mp3"
    )

    @Test
    fun `a full track reads title, then who it is by, then the details`() {
        assertEquals(
            listOf(
                "Nightfall",
                "Boards of Canada — Geogaddi",
                "Track 4 · Ambient · 5:21 · 1.5 MB · ★★★★"
            ).joinToString("\n"),
            Sharing.describe(full)
        )
    }

    /** "Unknown artist · Unknown album" is a line that costs the reader time and tells them nothing. */
    @Test
    fun `fields it does not have are left out rather than filled with Unknown`() {
        val bare = TrackFacts(id = 7, title = "rain-loop", artist = "", album = "")
        assertEquals("rain-loop", Sharing.describe(bare))
    }

    @Test
    fun `an untitled track falls back to its filename`() {
        val nameless = TrackFacts(id = 7, title = "", artist = "", album = "", fileName = "track01.wav")
        assertEquals("track01.wav", Sharing.describe(nameless))
    }

    @Test
    fun `an unrated track shows no stars at all`() {
        val unrated = full.copy(stars = RatingStore.UNRATED)
        assertFalse(Sharing.describe(unrated).contains("★"))
        assertTrue(Sharing.describe(unrated).contains("5:21"))
    }

    /** On most libraries the two agree, and repeating the artist is noise on every single share. */
    @Test
    fun `the album artist appears only when it disagrees with the track artist`() {
        assertFalse(Sharing.describe(full).contains("Album artist"))
        val compilation = full.copy(albumArtist = "Various Artists")
        assertTrue(Sharing.describe(compilation).contains("Album artist: Various Artists"))
    }

    @Test
    fun `a selection off one album names it once in the header`() {
        val tracks = listOf(
            full.copy(id = 1, title = "One"),
            full.copy(id = 2, title = "Two"),
            full.copy(id = 3, title = "Three")
        )
        val text = Sharing.describeAll(tracks).lines()
        assertEquals("3 tracks — Boards of Canada — Geogaddi", text[0])
        assertEquals("1. One  (5:21)", text[1])
        assertEquals("3. Three  (5:21)", text[3])
        // The album is in the header, so it must not be repeated on every line.
        assertFalse(text.drop(1).any { it.contains("Geogaddi") })
    }

    @Test
    fun `a mixed selection names the artist per line instead`() {
        val tracks = listOf(
            full.copy(id = 1, title = "One", artist = "Autechre", album = "Amber"),
            full.copy(id = 2, title = "Two", artist = "Aphex Twin", album = "SAW II")
        )
        val text = Sharing.describeAll(tracks).lines()
        assertEquals("2 tracks", text[0])
        assertTrue(text[1].contains("Autechre"))
        assertTrue(text[2].contains("Aphex Twin"))
    }

    @Test
    fun `one track shares as a block, not as a list of one`() {
        assertEquals(Sharing.describe(full), Sharing.describeAll(listOf(full)))
        assertEquals("", Sharing.describeAll(emptyList()))
    }

    @Test
    fun `the subject names the track and who it is by`() {
        assertEquals("Nightfall — Boards of Canada", Sharing.subject(listOf(full)))
        assertEquals("Nightfall", Sharing.subject(listOf(full.copy(artist = ""))))
        assertEquals("2 tracks — Boards of Canada — Geogaddi", Sharing.subject(listOf(full, full)))
    }

    @Test
    fun `the mime type follows the extension, case and all`() {
        assertEquals("audio/mpeg", Sharing.mimeFor("a.mp3"))
        assertEquals("audio/mpeg", Sharing.mimeFor("A.MP3"))
        assertEquals("audio/flac", Sharing.mimeFor("x.flac"))
        assertEquals("audio/ogg", Sharing.mimeFor("x.oga"))
        assertEquals(Sharing.GENERIC_AUDIO, Sharing.mimeFor("x.unheardof"))
        assertEquals(Sharing.GENERIC_AUDIO, Sharing.mimeFor("noextension"))
    }

    /** Typing a mixed selection as one concrete format would be lying to the receiving app. */
    @Test
    fun `a selection is typed concretely only when the whole of it agrees`() {
        assertEquals("audio/mpeg", Sharing.mimeForAll(listOf("a.mp3", "b.mp3")))
        assertEquals(Sharing.GENERIC_AUDIO, Sharing.mimeForAll(listOf("a.mp3", "b.flac")))
        assertEquals(Sharing.GENERIC_AUDIO, Sharing.mimeForAll(emptyList()))
    }
}
