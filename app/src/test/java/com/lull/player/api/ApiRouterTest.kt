package com.lull.player.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The API's contract, end to end, minus the socket and the device.
 *
 * The reason this exists rather than a page of `curl` commands in a release checklist: an endpoint
 * that quietly starts answering 500 where it used to answer 404, or accepts a shuffle mode it
 * should refuse, is invisible from the UI and invisible from a smoke test that only checks the
 * happy path. An agent holding the key is the only thing that would ever notice, and it will be
 * holding it at three in the morning.
 */
class ApiRouterTest {

    private val key = "0123456789abcdef"
    private lateinit var backend: FakeBackend
    private lateinit var router: ApiRouter

    @Before
    fun setUp() {
        backend = FakeBackend()
        router = ApiRouter(backend) { key }
    }

    // ---------------- Helpers ----------------

    private fun call(
        method: String, path: String, body: String? = null, withKey: Boolean = true,
        headers: Map<String, String> = emptyMap()
    ): ApiResponse {
        val query = path.substringAfter('?', "")
        val all = HashMap(headers)
        if (withKey) all["x-api-key"] = key
        return router.handle(
            ApiRequest(
                method = method,
                path = path.substringBefore('?'),
                query = Http.parseQuery(query),
                headers = all,
                body = body?.toByteArray() ?: ByteArray(0)
            )
        )
    }

    private fun ApiResponse.obj(): JSONObject = JSONObject(String(body))

    // ---------------- Auth ----------------

    @Test
    fun `without a key nothing but health answers`() {
        assertEquals(401, call("GET", "/api/tracks", withKey = false).status)
        assertEquals(401, call("GET", "/api/player", withKey = false).status)
        assertEquals(401, call("POST", "/api/player/pause", withKey = false).status)
        assertEquals(200, call("GET", "/api/health", withKey = false).status)
    }

    @Test
    fun `a wrong key is refused, including one that is merely a prefix of the right one`() {
        assertEquals(401, call("GET", "/api/player", headers = mapOf("x-api-key" to "nope"), withKey = false).status)
        assertEquals(
            401,
            call("GET", "/api/player", headers = mapOf("x-api-key" to key.dropLast(1)), withKey = false).status
        )
    }

    @Test
    fun `the key may arrive as a bearer token`() {
        val r = call("GET", "/api/player", withKey = false, headers = mapOf("authorization" to "Bearer $key"))
        assertEquals(200, r.status)
    }

    /** An API with no key set is not an open API; it is a closed one. */
    @Test
    fun `an empty configured key locks everything rather than opening it`() {
        val open = ApiRouter(backend) { "" }
        assertEquals(401, open.handle(ApiRequest("GET", "/api/player")).status)
        assertEquals(
            401,
            open.handle(ApiRequest("GET", "/api/player", headers = mapOf("x-api-key" to ""))).status
        )
    }

    @Test
    fun `a CORS preflight is answered without a key`() {
        assertEquals(204, call("OPTIONS", "/api/player", withKey = false).status)
    }

    // ---------------- Shape of failure ----------------

    @Test
    fun `an unknown route is a 404 that says where the list is`() {
        val r = call("GET", "/api/nonsense")
        assertEquals(404, r.status)
        assertTrue(r.obj().getString("hint").contains("/api/docs"))
    }

    @Test
    fun `a wrong method is a 405 carrying the Allow header`() {
        val r = call("DELETE", "/api/tracks")
        assertEquals(405, r.status)
        assertEquals("GET", r.extraHeaders["Allow"])
    }

    @Test
    fun `an id that is not a number is a 400, not a crash`() {
        val r = call("GET", "/api/tracks/banana")
        assertEquals(400, r.status)
    }

    @Test
    fun `a path outside the API is refused`() {
        assertEquals(404, call("GET", "/etc/passwd").status)
    }

    // ---------------- Library ----------------

    @Test
    fun `the track list pages and searches`() {
        val all = call("GET", "/api/tracks").obj()
        assertEquals(3, all.getInt("total"))
        assertEquals(3, all.getJSONArray("tracks").length())

        val searched = call("GET", "/api/tracks?q=nightfall").obj()
        assertEquals(1, searched.getInt("total"))

        val paged = call("GET", "/api/tracks?limit=1&offset=1").obj()
        assertEquals(3, paged.getInt("total"))
        assertEquals(1, paged.getInt("count"))
        assertEquals(2L, paged.getJSONArray("tracks").getJSONObject(0).getLong("id"))
    }

    /** A caller asking for the whole library at once must not be able to ask for all of memory. */
    @Test
    fun `a silly limit is clamped rather than honoured`() {
        call("GET", "/api/tracks?limit=999999")
        assertTrue(backend.calls.any { it.contains("limit=5000") })
        call("GET", "/api/tracks?limit=0&offset=-5")
        assertTrue(backend.calls.any { it.contains("limit=1,offset=0") })
    }

    @Test
    fun `one track carries its metadata and the urls for its file and art`() {
        val t = call("GET", "/api/tracks/1").obj()
        assertEquals("Nightfall", t.getString("title"))
        assertEquals("Ambient", t.getString("genre"))
        assertEquals(4, t.getInt("stars"))
        assertEquals("5:21", t.getString("duration"))
        assertEquals("/api/tracks/1/file", t.getString("file"))
        assertEquals("/api/tracks/1/art", t.getString("art"))
    }

    @Test
    fun `a missing track is a 404`() {
        assertEquals(404, call("GET", "/api/tracks/99").status)
        assertEquals(404, call("GET", "/api/tracks/99/file").status)
    }

    @Test
    fun `the file endpoint streams rather than buffering, and names the download`() {
        val r = call("GET", "/api/tracks/1/file")
        assertEquals(200, r.status)
        assertEquals("audio/mpeg", r.contentType)
        assertNotEquals(null, r.stream)
        assertEquals(0, r.body.size)
        assertTrue(r.extraHeaders["Content-Disposition"]!!.contains("04.mp3"))
    }

    /** A filename reaches a header, so a quote in one must not be able to forge another header. */
    @Test
    fun `a filename with a quote or a newline cannot break the headers`() {
        val nasty = ApiResponse.stream(
            java.io.ByteArrayInputStream(ByteArray(0)), "audio/mpeg", 0, "ev\"il\r\nX-Bad: 1.mp3"
        )
        val header = nasty.extraHeaders["Content-Disposition"]!!
        assertFalse(header.contains("\r"))
        assertFalse(header.contains("\n"))
        assertEquals(1, header.count { it == '"' } - 1)
    }

    @Test
    fun `a track with no artwork is a 404 rather than an empty image`() {
        assertEquals(200, call("GET", "/api/tracks/1/art").status)
        assertEquals(404, call("GET", "/api/tracks/2/art").status)
    }

    @Test
    fun `the share endpoint hands back the text a script would paste`() {
        val r = call("GET", "/api/tracks/1/share").obj()
        assertEquals("Nightfall — Boards of Canada", r.getString("subject"))
        assertTrue(r.getString("text").contains("Geogaddi"))
        assertEquals("/api/tracks/1/file", r.getString("file"))
    }

    @Test
    fun `groups list per axis, and an axis that does not exist is a 404`() {
        val albums = call("GET", "/api/groups/albums").obj()
        assertEquals(1, albums.getInt("count"))
        assertEquals("album:10", albums.getJSONArray("groups").getJSONObject(0).getString("key"))

        val bad = call("GET", "/api/groups/moods")
        assertEquals(404, bad.status)
        assertTrue(bad.obj().getString("hint").contains("albums"))
    }

    @Test
    fun `a group is addressed by key, and the key is required`() {
        val tracks = call("GET", "/api/group?key=album:10").obj()
        assertEquals(2, tracks.getInt("count"))
        assertEquals(400, call("GET", "/api/group").status)
        assertEquals(404, call("GET", "/api/group?key=album:404").status)
    }

    // ---------------- Player ----------------

    @Test
    fun `the player reports everything at once`() {
        val p = call("GET", "/api/player").obj()
        assertEquals("Nightfall", p.getString("title"))
        assertEquals("0:01", p.getString("position"))
        assertEquals("5:21", p.getString("duration"))
        assertEquals(3, p.getJSONArray("queue").length())
        assertEquals("off", p.getString("repeat"))
        assertEquals(7, p.getInt("volume"))
    }

    @Test
    fun `play takes ids, a single id, or a group`() {
        assertEquals(200, call("POST", "/api/player/play", """{"ids":[1,2]}""").status)
        assertEquals(listOf(1L, 2L), backend.lastPlayed)

        call("POST", "/api/player/play", """{"id":3}""")
        assertEquals(listOf(3L), backend.lastPlayed)

        call("POST", "/api/player/play", """{"group":"album:10"}""")
        assertEquals(listOf(1L, 2L), backend.lastPlayed)
    }

    @Test
    fun `play with a startId asks for that track first`() {
        call("POST", "/api/player/play", """{"group":"album:10","startId":2}""")
        assertEquals(2L, backend.lastStartId)
    }

    /** The play button, when it is showing play. An empty body is not an error. */
    @Test
    fun `play with no body resumes instead of failing`() {
        val r = call("POST", "/api/player/play")
        assertEquals(200, r.status)
        assertTrue(r.obj().getBoolean("resumed"))
        assertTrue(backend.calls.contains("resume"))
    }

    @Test
    fun `play with ids that are not in the library is a 404`() {
        val r = call("POST", "/api/player/play", """{"ids":[404]}""")
        assertEquals(404, r.status)
    }

    @Test
    fun `the transport controls each reach the player once`() {
        call("POST", "/api/player/pause")
        call("POST", "/api/player/next")
        call("POST", "/api/player/previous")
        call("POST", "/api/player/stop")
        assertEquals(listOf("pause", "next", "previous", "stop"), backend.calls)
    }

    @Test
    fun `seek needs a position and refuses a negative one`() {
        assertEquals(200, call("POST", "/api/player/seek", """{"positionMs":60000}""").status)
        assertTrue(backend.calls.contains("seek(60000)"))
        assertEquals(200, call("POST", "/api/player/seek", """{"position_ms":1000}""").status)
        assertEquals(400, call("POST", "/api/player/seek", "{}").status)
        assertEquals(400, call("POST", "/api/player/seek", """{"positionMs":-1}""").status)
    }

    @Test
    fun `queue appends and reports how many`() {
        val r = call("POST", "/api/player/queue", """{"ids":[1,2]}""")
        assertEquals(2, r.obj().getInt("queued"))
        assertEquals(400, call("POST", "/api/player/queue", "{}").status)
    }

    @Test
    fun `repeat and shuffle accept only the modes that exist`() {
        assertEquals(200, call("PATCH", "/api/player", """{"repeat":"one"}""").status)
        assertEquals("one", backend.repeatMode)
        assertEquals(200, call("PATCH", "/api/player", """{"shuffle":"favourites"}""").status)
        assertEquals("favourites", backend.shuffleMode)

        val bad = call("PATCH", "/api/player", """{"shuffle":"weighted"}""")
        assertEquals(400, bad.status)
        assertTrue(bad.obj().getString("hint").contains("favourites"))
        // The refused value must not have been applied on the way to being refused.
        assertEquals("favourites", backend.shuffleMode)

        assertEquals(400, call("PATCH", "/api/player", """{"repeat":"sometimes"}""").status)
        assertEquals(400, call("PATCH", "/api/player", "{}").status)
    }

    @Test
    fun `volume is set, and a negative one is refused`() {
        assertEquals(200, call("PATCH", "/api/player", """{"volume":3}""").status)
        assertEquals(3, backend.volumeLevel)
        assertEquals(400, call("PATCH", "/api/player", """{"volume":-2}""").status)
    }

    @Test
    fun `the sleep timer is armed by POST and cancelled by DELETE`() {
        assertEquals(200, call("POST", "/api/player/sleep", """{"minutes":45}""").status)
        assertEquals(45, backend.sleepMinutes)
        assertEquals(200, call("DELETE", "/api/player/sleep").status)
        assertEquals(0, backend.sleepMinutes)

        assertEquals(400, call("POST", "/api/player/sleep", "{}").status)
        val zero = call("POST", "/api/player/sleep", """{"minutes":0}""")
        assertEquals(400, zero.status)
        assertTrue(zero.obj().getString("hint").contains("DELETE"))
    }

    @Test
    fun `an A-B region the app refuses is a 409 that says why`() {
        assertEquals(200, call("POST", "/api/player/ab", """{"aMs":1000,"bMs":9000}""").status)
        assertEquals(1000L, backend.abA)

        backend.abAcceptable = false
        val refused = call("POST", "/api/player/ab", """{"aMs":0,"bMs":10}""")
        assertEquals(409, refused.status)
        assertTrue(refused.obj().getString("hint").contains("500 ms"))

        assertEquals(400, call("POST", "/api/player/ab", """{"aMs":0}""").status)
        assertEquals(200, call("DELETE", "/api/player/ab").status)
    }

    // ---------------- Ratings ----------------

    // ---------------- Force Tempo ----------------

    @Test
    fun `a tempo nobody has worked out yet says so instead of guessing`() {
        val r = call("GET", "/api/tracks/1/bpm").obj()
        assertEquals("unknown", r.getString("state"))
        assertTrue(r.isNull("bpm"))
    }

    @Test
    fun `a tempo can be set and read back`() {
        assertEquals(200, call("PUT", "/api/tracks/1/bpm", """{"bpm":128}""").status)
        val r = call("GET", "/api/tracks/1/bpm").obj()
        assertEquals("known", r.getString("state"))
        assertEquals(128, r.getInt("bpm"))
    }

    @Test
    fun `zero forgets a tempo rather than setting one`() {
        call("PUT", "/api/tracks/1/bpm", """{"bpm":128}""")
        assertEquals(200, call("PUT", "/api/tracks/1/bpm", """{"bpm":0}""").status)
        assertEquals("unknown", call("GET", "/api/tracks/1/bpm").obj().getString("state"))
    }

    @Test
    fun `a tempo no music has is refused`() {
        assertEquals(400, call("PUT", "/api/tracks/1/bpm", """{"bpm":900}""").status)
        assertEquals(400, call("PUT", "/api/tracks/1/bpm", """{"bpm":2}""").status)
        assertEquals(400, call("PUT", "/api/tracks/1/bpm", "{}").status)
    }

    @Test
    fun `a tempo for a track that is not there is a 404`() {
        assertEquals(404, call("GET", "/api/tracks/99/bpm").status)
        assertEquals(404, call("PUT", "/api/tracks/99/bpm", """{"bpm":128}""").status)
    }

    @Test
    fun `force tempo is patched through settings and comes back in them`() {
        val r = call(
            "PATCH", "/api/settings",
            """{"forceTempo":true,"tempoBpm":150,"tempoMaxChange":40,"tempoOnlyFaster":false}"""
        ).obj()
        assertTrue(r.getBoolean("force_tempo"))
        assertEquals(150, r.getInt("tempo_bpm"))
        assertEquals(40, r.getInt("tempo_max_change"))
        assertFalse(r.getBoolean("tempo_only_faster"))
    }

    @Test
    fun `snake case works for the tempo settings too`() {
        val r = call("PATCH", "/api/settings", """{"force_tempo":true,"tempo_bpm":110}""").obj()
        assertTrue(r.getBoolean("force_tempo"))
        assertEquals(110, r.getInt("tempo_bpm"))
    }

    @Test
    fun `a tempo setting outside the slider's range is refused`() {
        assertEquals(400, call("PATCH", "/api/settings", """{"tempoBpm":20}""").status)
        assertEquals(400, call("PATCH", "/api/settings", """{"tempoBpm":400}""").status)
        assertEquals(400, call("PATCH", "/api/settings", """{"tempoMaxChange":300}""").status)
    }

    @Test
    fun `the player reports the speed it is running at`() {
        val r = call("GET", "/api/player").obj()
        assertTrue(r.has("speed"))
        assertTrue(r.has("source_bpm"))
    }

    @Test
    fun `a track nobody has analysed says so rather than claiming no dead spots`() {
        val r = call("GET", "/api/tracks/1/quiet").obj()
        assertFalse(r.getBoolean("measured"))
        assertEquals(0, r.getInt("count"))
    }

    @Test
    fun `a track analysed and found clean is measured with no sections`() {
        backend.quiet[1L] = emptyList()
        val r = call("GET", "/api/tracks/1/quiet").obj()
        assertTrue(r.getBoolean("measured"))
        assertEquals(0, r.getInt("count"))
    }

    @Test
    fun `dead spots come back with their positions and total`() {
        backend.quiet[1L] = listOf(QuietSection(78_000, 98_000), QuietSection(150_000, 162_000))
        val r = call("GET", "/api/tracks/1/quiet").obj()
        assertTrue(r.getBoolean("measured"))
        assertEquals(2, r.getInt("count"))
        assertEquals(32_000L, r.getLong("quiet_ms"))
        val first = r.getJSONArray("sections").getJSONObject(0)
        assertEquals(78_000L, first.getLong("start_ms"))
        assertEquals("1:18", first.getString("start"))
        assertEquals(20_000L, first.getLong("duration_ms"))
    }

    @Test
    fun `dead spots for a track that is not there is a 404`() {
        assertEquals(404, call("GET", "/api/tracks/99/quiet").status)
    }

    @Test
    fun `the dead-spot settings patch through and come back`() {
        val r = call(
            "PATCH", "/api/settings",
            """{"pushQuiet":true,"quietBoost":80,"quietThresholdDb":6}"""
        ).obj()
        assertTrue(r.getBoolean("push_quiet"))
        assertEquals(80, r.getInt("quiet_boost"))
        assertEquals(6, r.getInt("quiet_threshold_db"))
    }

    @Test
    fun `a dead-spot setting outside its range is refused`() {
        assertEquals(400, call("PATCH", "/api/settings", """{"quietBoost":400}""").status)
        assertEquals(400, call("PATCH", "/api/settings", """{"quietBoost":1}""").status)
        assertEquals(400, call("PATCH", "/api/settings", """{"quietThresholdDb":90}""").status)
    }

    @Test
    fun `the player says whether it is pushing through a dead spot`() {
        assertTrue(call("GET", "/api/player").obj().has("boosted"))
    }

    @Test
    fun `ratings come back by id and counted by star`() {
        val r = call("GET", "/api/ratings").obj()
        assertEquals(2, r.getInt("count"))
        assertEquals(4, r.getJSONObject("ratings").getInt("1"))
        assertEquals(1, r.getJSONObject("counts").getInt("5"))
        assertEquals(0, r.getJSONObject("counts").getInt("3"))
    }

    @Test
    fun `one track is rated through its own path`() {
        assertEquals(200, call("PUT", "/api/tracks/2/rating", """{"stars":3}""").status)
        assertEquals(3, backend.ratingsById[2L])
        assertEquals(404, call("PUT", "/api/tracks/99/rating", """{"stars":3}""").status)
    }

    /** Zero clears, which is what tapping the star a track already sits on does in the UI. */
    @Test
    fun `zero stars clears a rating and six is refused`() {
        assertEquals(200, call("PUT", "/api/tracks/1/rating", """{"stars":0}""").status)
        assertFalse(backend.ratingsById.containsKey(1L))

        val tooMany = call("PUT", "/api/tracks/1/rating", """{"stars":6}""")
        assertEquals(400, tooMany.status)
        assertEquals(400, call("PUT", "/api/tracks/1/rating", """{"stars":-1}""").status)
        assertEquals(400, call("PUT", "/api/tracks/1/rating", "{}").status)
    }

    @Test
    fun `a batch rating is one call for the whole selection`() {
        val r = call("POST", "/api/ratings", """{"ids":[1,2,3],"stars":5}""")
        assertEquals(3, r.obj().getInt("rated"))
        assertEquals(listOf("rate(1,2,3,5)"), backend.calls)
        assertEquals(400, call("POST", "/api/ratings", """{"stars":5}""").status)
    }

    // ---------------- Playlists ----------------

    @Test
    fun `playlists are created, read, renamed, reordered and deleted`() {
        val created = call("POST", "/api/playlists", """{"name":"Night","ids":[1,2]}""")
        assertEquals(201, created.status)
        val id = created.obj().getLong("id")
        assertEquals("Night", created.obj().getString("name"))
        assertEquals(2, created.obj().getInt("tracks"))

        assertEquals(200, call("PATCH", "/api/playlists/$id", """{"name":"Late"}""").status)
        assertEquals("Late", backend.playlistsById[id]!!.name)

        assertEquals(200, call("PATCH", "/api/playlists/$id", """{"order":[2,1]}""").status)
        assertEquals(listOf(2L, 1L), backend.playlistsById[id]!!.trackIds)

        assertEquals(200, call("DELETE", "/api/playlists/$id").status)
        assertFalse(backend.playlistsById.containsKey(id))
        assertEquals(404, call("DELETE", "/api/playlists/$id").status)
    }

    @Test
    fun `a playlist needs a name`() {
        assertEquals(400, call("POST", "/api/playlists", """{"ids":[1]}""").status)
    }

    @Test
    fun `tracks are added to and removed from a playlist`() {
        assertEquals(2, call("POST", "/api/playlists/1/tracks", """{"ids":[1,2]}""").obj().getInt("added"))
        assertEquals(listOf(3L, 1L, 2L), backend.playlistsById[1L]!!.trackIds)

        assertEquals(200, call("DELETE", "/api/playlists/1/tracks", """{"ids":[3]}""").status)
        assertEquals(listOf(1L, 2L), backend.playlistsById[1L]!!.trackIds)

        assertEquals(404, call("POST", "/api/playlists/99/tracks", """{"ids":[1]}""").status)
        assertEquals(400, call("POST", "/api/playlists/1/tracks", "{}").status)
    }

    @Test
    fun `a whole album can be added to a playlist by key`() {
        call("POST", "/api/playlists/1/tracks", """{"group":"album:10"}""")
        assertEquals(listOf(3L, 1L, 2L), backend.playlistsById[1L]!!.trackIds)
    }

    @Test
    fun `duplicating copies the contents`() {
        val copy = call("POST", "/api/playlists/1/duplicate")
        assertEquals(201, copy.status)
        assertEquals(listOf(3L), copy.obj().getJSONArray("track_ids").let { listOf(it.getLong(0)) })
        assertEquals(404, call("POST", "/api/playlists/99/duplicate").status)
    }

    /**
     * A backend that has already decided what a failure *is* keeps its status and its hint. This
     * caught a real one: the main-thread hop the real backend mutates through was re-wrapping every
     * refusal decided over there as a 500, so "that order drops a track" reached the caller as an
     * internal error with the class name glued to the front of it.
     */
    @Test
    fun `a refusal from the backend keeps its own status and hint`() {
        backend.reorderRefusal = ApiException(400, "order must list exactly the tracks", "it holds 1")
        val r = call("PATCH", "/api/playlists/1", """{"order":[9]}""")
        assertEquals(400, r.status)
        assertEquals("order must list exactly the tracks", r.obj().getString("error"))
        assertEquals("it holds 1", r.obj().getString("hint"))
    }

    @Test
    fun `a playlist patch with nothing in it is refused`() {
        assertEquals(400, call("PATCH", "/api/playlists/1", "{}").status)
    }

    // ---------------- Settings ----------------

    @Test
    fun `settings read back, and patch one field without resetting the rest`() {
        val before = call("GET", "/api/settings").obj()
        assertEquals("dark", before.getString("theme"))
        assertTrue(before.getBoolean("mix_audio"))

        val after = call("PATCH", "/api/settings", """{"crossfadeSec":6}""").obj()
        assertEquals(6, after.getInt("crossfade_sec"))
        assertEquals("dark", after.getString("theme"))
        assertTrue(after.getBoolean("mix_audio"))
    }

    @Test
    fun `snake_case is accepted on input as well as camelCase`() {
        assertEquals(200, call("PATCH", "/api/settings", """{"skip_silence":true}""").status)
        assertTrue(backend.settings().skipSilence)
    }

    @Test
    fun `settings values outside what the UI can set are refused`() {
        assertEquals(400, call("PATCH", "/api/settings", """{"theme":"neon"}""").status)
        assertEquals(400, call("PATCH", "/api/settings", """{"crossfadeSec":60}""").status)
        assertEquals(400, call("PATCH", "/api/settings", """{"volumeStyle":"dial"}""").status)
        assertEquals(400, call("PATCH", "/api/settings", "{}").status)
        assertEquals("dark", backend.settings().theme)
    }

    // ---------------- Share ----------------

    @Test
    fun `share returns the text and the files whether or not a chooser can be shown`() {
        val visible = call("POST", "/api/share", """{"ids":[1,2]}""").obj()
        assertTrue(visible.getBoolean("launched"))
        assertEquals("2 tracks — Boards of Canada — Geogaddi", visible.getString("subject"))
        assertEquals("/api/tracks/1/file", visible.getJSONArray("files").getString(0))
        assertFalse(visible.has("note"))

        backend.shareVisible = false
        val hidden = call("POST", "/api/share", """{"id":1}""").obj()
        assertFalse(hidden.getBoolean("visible"))
        // The one case a caller most needs told about, so it must be said rather than implied.
        assertTrue(hidden.getString("note").contains("not on screen"))
        assertTrue(hidden.getString("text").contains("Nightfall"))
    }

    @Test
    fun `share with nothing to share is a 400`() {
        assertEquals(400, call("POST", "/api/share", "{}").status)
        assertEquals(405, call("GET", "/api/share").status)
    }

    // ---------------- Meta ----------------

    @Test
    fun `health answers without a key and says what this is`() {
        val r = call("GET", "/api/health", withKey = false).obj()
        assertTrue(r.getBoolean("ok"))
        assertEquals("Lull", r.getString("app"))
    }

    @Test
    fun `instance reports the build and the library`() {
        val r = call("GET", "/api/instance").obj()
        assertEquals("1.9", r.getString("version"))
        assertEquals(3, r.getInt("tracks"))
        assertTrue(r.getBoolean("media_permission"))
    }

    @Test
    fun `the index and the docs list the same routes the router actually answers`() {
        val index = call("GET", "/api").obj()
        assertTrue(index.getJSONArray("endpoints").length() > 20)

        val docs = call("GET", "/api/docs")
        assertTrue(docs.contentType.startsWith("text/markdown"))
        val text = String(docs.body)

        // Every route the summary advertises must be one dispatch actually recognises: a docs page
        // that lists an endpoint nobody implemented is worse than no docs page.
        for (line in ApiDocs.SUMMARY) {
            val method = line.substringBefore(' ')
            val path = line.substringAfterLast(' ')
                .replace("{id}", "1")
                .replace("{folders|artists|albums|genres|playlists|rated}", "albums")
                .substringBefore('?')
            if (path.contains('{')) continue   // the brace-alternation forms are covered above

            // A fresh backend per route: the list contains a DELETE for the same playlist a later
            // line duplicates, and this is asking "is it routed", not "does it survive the others".
            backend = FakeBackend()
            router = ApiRouter(backend) { key }
            val status = call(method, path, "{}").status
            assertNotEquals("$method $path is advertised but unrouted", 404, status)
            assertNotEquals("$method $path is advertised but unroutable", 405, status)
        }
        assertTrue(text.contains("X-API-Key"))
    }
}
