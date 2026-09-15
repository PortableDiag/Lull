package com.lull.player.api

import com.lull.player.BpmAnalysis
import com.lull.player.Prefs
import com.lull.player.RatingStore
import com.lull.player.Sharing
import com.lull.player.TempoStore
import com.lull.player.TrackFacts
import org.json.JSONArray
import org.json.JSONObject

/**
 * The API's rules: which path means what, what each one accepts, and which failures are the
 * caller's fault rather than the app's.
 *
 * No Android types reach this file, which is the point — the whole endpoint surface is exercised
 * by unit tests against a fake [ApiBackend], so "does `PATCH /api/player` reject a shuffle mode it
 * has never heard of" is a question answered by the build rather than by a phone.
 *
 * Two conventions run through all of it:
 *
 *  - **Every endpoint is something the UI can already do.** The API is a second set of hands, not
 *    a second set of features.
 *  - **A wrong request gets a status and a sentence**, never a stack trace and never a silent 200.
 *    An agent's only way to tell what it got wrong is what comes back.
 */
class ApiRouter(
    private val backend: ApiBackend,
    private val expectedKey: () -> String
) {

    companion object {
        /** Bumped when a response shape changes in a way a caller could notice. */
        const val API_VERSION = 1

        /** Answered without a key: liveness, so a watchdog needs no credential to check it. */
        private val OPEN_PATHS = setOf("/api/health")

        private val REPEAT_MODES = setOf("off", "one", "all")
        private val SHUFFLE_MODES = setOf("off", "all", "favourites")
        private val THEMES = setOf("system", "light", "dark")
        private val VOLUME_STYLES = setOf("bar", "knob")
        private val TABS = setOf("folders", "artists", "albums", "genres", "playlists", "rated")

        private val BPM_RANGE_HELP =
            "${BpmAnalysis.MIN_BPM.toInt()} to ${BpmAnalysis.MAX_BPM.toInt()}, or 0 to forget it " +
                "and have it worked out again"
    }

    fun handle(request: ApiRequest): ApiResponse = try {
        when {
            request.method == "OPTIONS" -> ApiResponse.noContent()
            !request.path.startsWith("/api") -> ApiResponse.error(
                404, "not an API path",
                "everything this server answers lives under /api — GET /api/docs lists it"
            )
            request.path in OPEN_PATHS -> dispatch(request)
            !authorised(request) -> ApiResponse.error(
                401, "missing or invalid API key",
                "send it as the X-API-Key header, or Authorization: Bearer <key>"
            )
            else -> dispatch(request)
        }
    } catch (e: ApiException) {
        ApiResponse.error(e.status, e.message ?: "request failed", e.hint)
    } catch (e: Exception) {
        ApiResponse.error(500, "${e.javaClass.simpleName}: ${e.message}")
    }

    /**
     * Constant-time comparison. The timing of a string compare leaks how much of a key is right,
     * and a local socket is exactly where an attacker can afford the millions of tries that turns
     * into.
     */
    private fun authorised(request: ApiRequest): Boolean {
        val expected = expectedKey()
        if (expected.isEmpty()) return false
        val offered = request.header("x-api-key")
            ?: request.header("authorization")?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
            ?: return false
        val a = offered.toByteArray()
        val b = expected.toByteArray()
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    // ---------------- Dispatch ----------------

    private fun dispatch(r: ApiRequest): ApiResponse {
        val segments = r.path.removePrefix("/api").trim('/').split('/').filter { it.isNotEmpty() }

        return when (segments.firstOrNull()) {
            null -> index()
            "health" -> get(r) { ApiResponse.json(JSONObject().put("ok", true).put("app", "Lull").put("api", API_VERSION)) }
            "docs" -> get(r) { ApiResponse.text(ApiDocs.MARKDOWN, type = "text/markdown; charset=utf-8") }
            "instance" -> get(r) { instance() }
            "tracks" -> tracks(r, segments.drop(1))
            "groups" -> groups(r, segments.drop(1))
            "group" -> get(r) { group(r) }
            "player" -> player(r, segments.drop(1))
            "ratings" -> ratings(r)
            "playlists" -> playlists(r, segments.drop(1))
            "settings" -> settings(r)
            "share" -> post(r) { share(r) }
            else -> ApiResponse.error(404, "no route for ${r.method} ${r.path}", "GET /api/docs lists every endpoint")
        }
    }

    private fun index(): ApiResponse = ApiResponse.json(
        JSONObject()
            .put("app", "Lull")
            .put("api", API_VERSION)
            .put("docs", "/api/docs")
            .put("endpoints", JSONArray(ApiDocs.SUMMARY))
    )

    private inline fun get(r: ApiRequest, body: () -> ApiResponse): ApiResponse =
        if (r.method == "GET") body() else methodNotAllowed(r, "GET")

    private inline fun post(r: ApiRequest, body: () -> ApiResponse): ApiResponse =
        if (r.method == "POST") body() else methodNotAllowed(r, "POST")

    private fun methodNotAllowed(r: ApiRequest, vararg allowed: String) = ApiResponse(
        405, "application/json; charset=utf-8",
        JSONObject().put("error", "${r.method} not allowed on ${r.path}")
            .put("allow", allowed.joinToString(", ")).toString(2).toByteArray(),
        extraHeaders = mapOf("Allow" to allowed.joinToString(", "))
    )

    // ---------------- Library ----------------

    private fun instance(): ApiResponse {
        val info = backend.instance()
        return ApiResponse.json(
            JSONObject()
                .put("app", "Lull")
                .put("version", info.versionName)
                .put("version_code", info.versionCode)
                .put("api", info.apiVersion)
                .put("port", info.port)
                .put("lan", info.lan)
                .put("media_permission", info.hasAudioPermission)
                .put("tracks", info.trackCount)
        )
    }

    private fun tracks(r: ApiRequest, rest: List<String>): ApiResponse {
        return when {
            rest.isEmpty() -> get(r) { trackList(r) }

            rest.size == 1 -> get(r) {
                val facts = backend.track(id(rest[0])) ?: throw notFound("track", rest[0])
                ApiResponse.json(trackJson(facts))
            }

            rest.size == 2 && rest[1] == "file" -> get(r) {
                val media = backend.trackStream(id(rest[0])) ?: throw notFound("track", rest[0])
                ApiResponse.stream(media.stream, media.contentType, media.length, media.fileName)
            }

            rest.size == 2 && rest[1] == "art" -> get(r) {
                val media = backend.artworkStream(id(rest[0]))
                    ?: throw ApiException(404, "no artwork for track ${rest[0]}")
                ApiResponse.stream(media.stream, media.contentType, media.length, media.fileName)
            }

            rest.size == 2 && rest[1] == "share" -> get(r) {
                val facts = backend.track(id(rest[0])) ?: throw notFound("track", rest[0])
                ApiResponse.json(
                    JSONObject()
                        .put("subject", Sharing.subject(listOf(facts)))
                        .put("text", Sharing.describe(facts))
                        .put("file", "/api/tracks/${facts.id}/file")
                )
            }

            rest.size == 2 && rest[1] == "bpm" -> {
                val trackId = id(rest[0])
                if (backend.track(trackId) == null) throw notFound("track", rest[0])
                when (r.method) {
                    "GET" -> ApiResponse.json(bpmJson(trackId, backend.bpm(trackId)))
                    "PUT", "POST" -> {
                        backend.setBpm(trackId, requireBpm(r.json().opt("bpm")))
                        ApiResponse.json(bpmJson(trackId, backend.bpm(trackId)))
                    }
                    else -> methodNotAllowed(r, "GET", "PUT", "POST")
                }
            }

            rest.size == 2 && rest[1] == "quiet" -> get(r) {
                val trackId = id(rest[0])
                if (backend.track(trackId) == null) throw notFound("track", rest[0])
                val sections = backend.quietSections(trackId)
                ApiResponse.json(
                    JSONObject()
                        .put("id", trackId)
                        .put("measured", sections != null)
                        .put("threshold_db", backend.settings().quietThresholdDb)
                        .put("count", sections?.size ?: 0)
                        .put("quiet_ms", sections?.sumOf { it.endMs - it.startMs } ?: 0L)
                        .put(
                            "sections",
                            JSONArray(
                                (sections ?: emptyList()).map {
                                    JSONObject()
                                        .put("start_ms", it.startMs)
                                        .put("end_ms", it.endMs)
                                        .put("start", com.lull.player.Format.duration(it.startMs))
                                        .put("duration_ms", it.endMs - it.startMs)
                                }
                            )
                        )
                )
            }

            rest.size == 2 && rest[1] == "rating" -> {
                if (r.method != "PUT" && r.method != "POST") return methodNotAllowed(r, "PUT", "POST")
                val trackId = id(rest[0])
                if (backend.track(trackId) == null) throw notFound("track", rest[0])
                val stars = requireStars(r.json().opt("stars"))
                backend.rate(listOf(trackId), stars)
                ApiResponse.ok("id" to trackId, "stars" to stars)
            }

            else -> ApiResponse.error(404, "no route for ${r.method} ${r.path}")
        }
    }

    private fun trackList(r: ApiRequest): ApiResponse {
        val limit = r.queryInt("limit", 200).coerceIn(1, 5000)
        val offset = r.queryInt("offset", 0).coerceAtLeast(0)
        val page = backend.tracks(r.query["q"], limit, offset)
        return ApiResponse.json(
            JSONObject()
                .put("total", page.total)
                .put("offset", page.offset)
                .put("count", page.items.size)
                .put("tracks", JSONArray(page.items.map { trackJson(it) }))
        )
    }

    private fun groups(r: ApiRequest, rest: List<String>): ApiResponse {
        if (r.method != "GET") return methodNotAllowed(r, "GET")
        val tab = rest.firstOrNull()
            ?: return ApiResponse.json(JSONObject().put("tabs", JSONArray(TABS.toList())))
        val list = backend.groups(tab)
            ?: throw ApiException(404, "no browse axis called '$tab'", "one of: ${TABS.joinToString(", ")}")
        return ApiResponse.json(
            JSONObject()
                .put("tab", tab)
                .put("count", list.size)
                .put("groups", JSONArray(list.map { groupJson(it) }))
        )
    }

    /**
     * A group is addressed by query parameter rather than by path segment because its key holds a
     * folder path — `folder:/storage/emulated/0/Music/Ambient` — and slashes in a path segment are
     * a fight nobody needs to have.
     */
    private fun group(r: ApiRequest): ApiResponse {
        val key = r.query["key"] ?: throw ApiException(
            400, "group key required", "GET /api/group?key=album:42 — keys come from /api/groups/{tab}"
        )
        val tracks = backend.groupTracks(key) ?: throw notFound("group", key)
        return ApiResponse.json(
            JSONObject()
                .put("key", key)
                .put("count", tracks.size)
                .put("tracks", JSONArray(tracks.map { trackJson(it) }))
        )
    }

    // ---------------- Player ----------------

    private fun player(r: ApiRequest, rest: List<String>): ApiResponse = when (rest.firstOrNull()) {
        null -> when (r.method) {
            "GET" -> ApiResponse.json(playerJson(backend.player()))
            "PATCH" -> patchPlayer(r)
            else -> methodNotAllowed(r, "GET", "PATCH")
        }

        "play" -> post(r) { playRequest(r) }
        "pause" -> post(r) { backend.pause(); ApiResponse.ok() }
        "resume" -> post(r) { backend.resume(); ApiResponse.ok() }
        "next" -> post(r) { backend.next(); ApiResponse.ok() }
        "previous" -> post(r) { backend.previous(); ApiResponse.ok() }
        "stop" -> post(r) { backend.stop(); ApiResponse.ok() }

        "seek" -> post(r) {
            val obj = r.json()
            val position = obj.optLongOrNull("positionMs") ?: obj.optLongOrNull("position_ms")
                ?: throw ApiException(400, "positionMs required", "{\"positionMs\": 60000}")
            if (position < 0) throw ApiException(400, "positionMs cannot be negative")
            backend.seek(position)
            ApiResponse.ok("positionMs" to position)
        }

        "queue" -> post(r) {
            val ids = backend.resolveIds(r.idList(), r.json().optStringOrNull("group"))
            if (ids.isEmpty()) throw ApiException(400, "nothing to queue", "{\"ids\":[1,2]} or {\"group\":\"album:42\"}")
            ApiResponse.ok("queued" to backend.queue(ids))
        }

        "sleep" -> when (r.method) {
            "POST" -> {
                val minutes = r.json().optIntOrNull("minutes")
                    ?: throw ApiException(400, "minutes required", "{\"minutes\": 30}")
                if (minutes <= 0) throw ApiException(400, "minutes must be positive", "DELETE this path to cancel")
                backend.armSleep(minutes)
                ApiResponse.ok("minutes" to minutes)
            }
            "DELETE" -> { backend.cancelSleep(); ApiResponse.ok() }
            else -> methodNotAllowed(r, "POST", "DELETE")
        }

        "ab" -> when (r.method) {
            "POST" -> {
                val obj = r.json()
                val a = obj.optLongOrNull("aMs") ?: throw ApiException(400, "aMs required")
                val b = obj.optLongOrNull("bMs") ?: throw ApiException(400, "bMs required")
                if (!backend.setAb(a, b)) throw ApiException(
                    409, "that region cannot be looped",
                    "nothing is playing, or B is less than 500 ms past A"
                )
                ApiResponse.ok("aMs" to a, "bMs" to b)
            }
            "DELETE" -> { backend.clearAb(); ApiResponse.ok() }
            else -> methodNotAllowed(r, "POST", "DELETE")
        }

        else -> ApiResponse.error(404, "no route for ${r.method} ${r.path}")
    }

    private fun playRequest(r: ApiRequest): ApiResponse {
        val obj = r.json()
        val explicit = r.idList()
        val groupKey = obj.optStringOrNull("group")
        val startId = obj.optLongOrNull("startId") ?: obj.optLongOrNull("start_id")

        // A bare POST is "carry on" — the same thing the play button does when it is showing play.
        if (explicit.isEmpty() && groupKey == null) {
            backend.resume()
            return ApiResponse.ok("resumed" to true)
        }

        val ids = backend.resolveIds(explicit, groupKey)
        if (ids.isEmpty()) throw ApiException(
            404, "nothing to play",
            "no track matched those ids" + if (groupKey != null) " or the group '$groupKey'" else ""
        )
        val queued = backend.play(ids, startId)
        return ApiResponse.ok("queued" to queued, "startId" to startId)
    }

    private fun patchPlayer(r: ApiRequest): ApiResponse {
        val obj = r.json()
        var touched = false

        obj.optStringOrNull("repeat")?.let {
            val mode = it.lowercase()
            if (mode !in REPEAT_MODES) throw ApiException(
                400, "unknown repeat mode '$it'", "one of: ${REPEAT_MODES.joinToString(", ")}"
            )
            backend.setRepeat(mode); touched = true
        }
        obj.optStringOrNull("shuffle")?.let {
            val mode = it.lowercase()
            if (mode !in SHUFFLE_MODES) throw ApiException(
                400, "unknown shuffle mode '$it'", "one of: ${SHUFFLE_MODES.joinToString(", ")}"
            )
            backend.setShuffle(mode); touched = true
        }
        obj.optIntOrNull("volume")?.let {
            if (it < 0) throw ApiException(400, "volume cannot be negative")
            backend.setVolume(it); touched = true
        }

        if (!touched) throw ApiException(
            400, "nothing to change",
            "send repeat, shuffle and/or volume — GET /api/player shows the current values"
        )
        return ApiResponse.json(playerJson(backend.player()))
    }

    // ---------------- Ratings ----------------

    private fun ratings(r: ApiRequest): ApiResponse = when (r.method) {
        "GET" -> {
            val map = backend.ratings()
            val byId = JSONObject()
            for ((id, stars) in map) byId.put(id.toString(), stars)
            val counts = JSONObject()
            for (stars in 1..RatingStore.MAX) counts.put(stars.toString(), map.values.count { it == stars })
            ApiResponse.json(JSONObject().put("count", map.size).put("ratings", byId).put("counts", counts))
        }
        "POST", "PUT" -> {
            val ids = r.idList()
            if (ids.isEmpty()) throw ApiException(400, "ids required", "{\"ids\":[1,2],\"stars\":4}")
            val stars = requireStars(r.json().opt("stars"))
            backend.rate(ids, stars)
            ApiResponse.ok("rated" to ids.size, "stars" to stars)
        }
        else -> methodNotAllowed(r, "GET", "POST", "PUT")
    }

    /** 0 clears a rating, which is what tapping the star a track already sits on does in the UI. */
    private fun requireStars(raw: Any?): Int {
        val stars = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        } ?: throw ApiException(400, "stars required", "0 to ${RatingStore.MAX}, where 0 clears the rating")
        if (stars < RatingStore.UNRATED || stars > RatingStore.MAX) throw ApiException(
            400, "stars out of range: $stars", "0 to ${RatingStore.MAX}, where 0 clears the rating"
        )
        return stars
    }

    /**
     * 0 clears a tempo, which puts the track back in the queue to be worked out again — the same
     * thing emptying the field in the panel does. Anything else has to be a tempo that exists.
     */
    private fun requireBpm(raw: Any?): Int {
        val bpm = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        } ?: throw ApiException(400, "bpm required", BPM_RANGE_HELP)
        if (bpm == 0) return 0
        if (bpm < BpmAnalysis.MIN_BPM.toInt() || bpm > BpmAnalysis.MAX_BPM.toInt())
            throw ApiException(400, "bpm out of range: $bpm", BPM_RANGE_HELP)
        return bpm
    }

    /**
     * The three states spelled out, because `0` and `-1` are not obviously different to a caller
     * and the difference is the whole point: one means come back later, the other means don't.
     */
    private fun bpmJson(id: Long, bpm: Int): JSONObject = JSONObject()
        .put("id", id)
        .put("bpm", if (bpm > 0) bpm else JSONObject.NULL)
        .put(
            "state", when {
                bpm > 0 -> "known"
                bpm == TempoStore.NONE -> "none"
                else -> "unknown"
            }
        )

    // ---------------- Playlists ----------------

    private fun playlists(r: ApiRequest, rest: List<String>): ApiResponse = when {
        rest.isEmpty() -> when (r.method) {
            "GET" -> ApiResponse.json(
                JSONObject().put("count", backend.playlists().size)
                    .put("playlists", JSONArray(backend.playlists().map { playlistJson(it) }))
            )
            "POST" -> {
                val name = r.json().optStringOrNull("name")
                    ?: throw ApiException(400, "name required", "{\"name\":\"Sleep\",\"ids\":[1,2]}")
                val created = backend.createPlaylist(name, r.idList())
                ApiResponse.json(playlistJson(created), 201)
            }
            else -> methodNotAllowed(r, "GET", "POST")
        }

        rest.size == 1 -> when (r.method) {
            "GET" -> ApiResponse.json(playlistJson(backend.playlist(id(rest[0])) ?: throw notFound("playlist", rest[0])))
            "PATCH" -> patchPlaylist(r, id(rest[0]))
            "DELETE" -> {
                if (!backend.deletePlaylist(id(rest[0]))) throw notFound("playlist", rest[0])
                ApiResponse.ok("deleted" to id(rest[0]))
            }
            else -> methodNotAllowed(r, "GET", "PATCH", "DELETE")
        }

        rest.size == 2 && rest[1] == "tracks" -> {
            val playlistId = id(rest[0])
            val ids = backend.resolveIds(r.idList(), r.json().optStringOrNull("group"))
            when (r.method) {
                "POST" -> {
                    if (ids.isEmpty()) throw ApiException(400, "ids required", "{\"ids\":[1,2]} or {\"group\":\"album:42\"}")
                    if (backend.playlist(playlistId) == null) throw notFound("playlist", rest[0])
                    ApiResponse.ok("added" to backend.addToPlaylist(playlistId, ids))
                }
                "DELETE" -> {
                    if (ids.isEmpty()) throw ApiException(400, "ids required", "{\"ids\":[1,2]}")
                    if (!backend.removeFromPlaylist(playlistId, ids)) throw notFound("playlist", rest[0])
                    ApiResponse.ok("removed" to ids.size)
                }
                else -> methodNotAllowed(r, "POST", "DELETE")
            }
        }

        rest.size == 2 && rest[1] == "duplicate" -> post(r) {
            val copy = backend.duplicatePlaylist(id(rest[0]), r.json().optStringOrNull("name"))
                ?: throw notFound("playlist", rest[0])
            ApiResponse.json(playlistJson(copy), 201)
        }

        else -> ApiResponse.error(404, "no route for ${r.method} ${r.path}")
    }

    private fun patchPlaylist(r: ApiRequest, playlistId: Long): ApiResponse {
        val obj = r.json()
        val name = obj.optStringOrNull("name")
        val order = obj.optJSONArray("order")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                when (val v = arr.opt(i)) {
                    is Number -> v.toLong()
                    is String -> v.toLongOrNull()
                    else -> null
                }
            }
        }
        if (name == null && order == null) throw ApiException(
            400, "nothing to change", "send name and/or order (a full list of track ids)"
        )
        if (name != null && !backend.renamePlaylist(playlistId, name)) throw notFound("playlist", "$playlistId")
        if (order != null && !backend.reorderPlaylist(playlistId, order)) throw notFound("playlist", "$playlistId")
        return ApiResponse.json(playlistJson(backend.playlist(playlistId) ?: throw notFound("playlist", "$playlistId")))
    }

    // ---------------- Settings ----------------

    private fun settings(r: ApiRequest): ApiResponse = when (r.method) {
        "GET" -> ApiResponse.json(settingsJson(backend.settings()))
        "PATCH", "POST" -> {
            val obj = r.json()
            val theme = obj.optStringOrNull("theme")?.lowercase()
            if (theme != null && theme !in THEMES) throw ApiException(
                400, "unknown theme '$theme'", "one of: ${THEMES.joinToString(", ")}"
            )
            val style = obj.optStringOrNull("volumeStyle")?.lowercase()
                ?: obj.optStringOrNull("volume_style")?.lowercase()
            if (style != null && style !in VOLUME_STYLES) throw ApiException(
                400, "unknown volume style '$style'", "one of: ${VOLUME_STYLES.joinToString(", ")}"
            )
            val crossfade = obj.optIntOrNull("crossfadeSec") ?: obj.optIntOrNull("crossfade_sec")
            if (crossfade != null && crossfade !in 0..com.lull.player.Prefs.MAX_CROSSFADE_SEC)
                throw ApiException(400, "crossfadeSec out of range: $crossfade", "0 to ${com.lull.player.Prefs.MAX_CROSSFADE_SEC}")
            val tempoBpm = obj.optIntOrNull("tempoBpm") ?: obj.optIntOrNull("tempo_bpm")
            if (tempoBpm != null && tempoBpm !in Prefs.MIN_TEMPO_BPM..Prefs.MAX_TEMPO_BPM)
                throw ApiException(
                    400, "tempoBpm out of range: $tempoBpm",
                    "${Prefs.MIN_TEMPO_BPM} to ${Prefs.MAX_TEMPO_BPM}"
                )
            val tempoChange = obj.optIntOrNull("tempoMaxChange") ?: obj.optIntOrNull("tempo_max_change")
            if (tempoChange != null && tempoChange !in Prefs.MIN_TEMPO_CHANGE..Prefs.MAX_TEMPO_CHANGE)
                throw ApiException(
                    400, "tempoMaxChange out of range: $tempoChange",
                    "${Prefs.MIN_TEMPO_CHANGE} to ${Prefs.MAX_TEMPO_CHANGE}, a percentage"
                )
            val quietBoost = obj.optIntOrNull("quietBoost") ?: obj.optIntOrNull("quiet_boost")
            if (quietBoost != null && quietBoost !in Prefs.MIN_QUIET_BOOST..Prefs.MAX_QUIET_BOOST)
                throw ApiException(
                    400, "quietBoost out of range: $quietBoost",
                    "${Prefs.MIN_QUIET_BOOST} to ${Prefs.MAX_QUIET_BOOST}, a percentage added on top of the track's own speed"
                )
            val quietThreshold =
                obj.optIntOrNull("quietThresholdDb") ?: obj.optIntOrNull("quiet_threshold_db")
            if (quietThreshold != null &&
                quietThreshold !in Prefs.MIN_QUIET_THRESHOLD..Prefs.MAX_QUIET_THRESHOLD
            ) throw ApiException(
                400, "quietThresholdDb out of range: $quietThreshold",
                "${Prefs.MIN_QUIET_THRESHOLD} to ${Prefs.MAX_QUIET_THRESHOLD}, decibels below the track's own level"
            )
            val patch = SettingsPatch(
                theme = theme,
                crossfadeSec = crossfade,
                skipSilence = obj.optBooleanOrNull("skipSilence") ?: obj.optBooleanOrNull("skip_silence"),
                mixAudio = obj.optBooleanOrNull("mixAudio") ?: obj.optBooleanOrNull("mix_audio"),
                volumeStyle = style,
                forceTempo = obj.optBooleanOrNull("forceTempo") ?: obj.optBooleanOrNull("force_tempo"),
                tempoBpm = tempoBpm,
                tempoMaxChange = tempoChange,
                tempoOnlyFaster = obj.optBooleanOrNull("tempoOnlyFaster")
                    ?: obj.optBooleanOrNull("tempo_only_faster"),
                pushQuiet = obj.optBooleanOrNull("pushQuiet") ?: obj.optBooleanOrNull("push_quiet"),
                quietBoost = quietBoost,
                quietThresholdDb = quietThreshold
            )
            if (patch == SettingsPatch()) throw ApiException(
                400, "nothing to change", "GET /api/settings shows what there is to set"
            )
            ApiResponse.json(settingsJson(backend.updateSettings(patch)))
        }
        else -> methodNotAllowed(r, "GET", "PATCH")
    }

    // ---------------- Share ----------------

    private fun share(r: ApiRequest): ApiResponse {
        val ids = backend.resolveIds(r.idList(), r.json().optStringOrNull("group"))
        if (ids.isEmpty()) throw ApiException(
            400, "nothing to share",
            "{\"ids\":[1,2]}, {\"id\":1} or {\"group\":\"album:42\"}"
        )
        val result = backend.share(ids)
        return ApiResponse.json(
            JSONObject()
                .put("ok", true)
                .put("launched", result.launched)
                .put("visible", result.visible)
                .put("subject", result.subject)
                .put("text", result.text)
                .put("files", JSONArray(result.trackIds.map { "/api/tracks/$it/file" }))
                .apply {
                    if (!result.visible) put(
                        "note",
                        "Lull was not on screen, so the chooser cannot be shown to anyone — " +
                            "take the files and text from this response instead"
                    )
                }
        )
    }

    // ---------------- JSON shapes ----------------

    private fun trackJson(t: TrackFacts): JSONObject = JSONObject()
        .put("id", t.id)
        .put("title", t.title)
        .put("artist", t.artist)
        .put("album", t.album)
        .put("album_artist", t.albumArtist)
        .put("genre", t.genre)
        .put("track_no", t.trackNo)
        .put("duration_ms", t.durationMs)
        .put("duration", com.lull.player.Format.duration(t.durationMs))
        .put("size_bytes", t.sizeBytes)
        .put("stars", t.stars)
        .put("file_name", t.fileName)
        .put("file", "/api/tracks/${t.id}/file")
        .put("art", "/api/tracks/${t.id}/art")

    private fun groupJson(g: GroupSummary): JSONObject = JSONObject()
        .put("key", g.key)
        .put("title", g.title)
        .put("subtitle", g.subtitle)
        .put("tracks", g.trackIds.size)
        .put("track_ids", JSONArray(g.trackIds))

    private fun playlistJson(p: PlaylistView): JSONObject = JSONObject()
        .put("id", p.id)
        .put("name", p.name)
        .put("tracks", p.trackIds.size)
        .put("track_ids", JSONArray(p.trackIds))

    private fun settingsJson(s: SettingsSnapshot): JSONObject = JSONObject()
        .put("theme", s.theme)
        .put("crossfade_sec", s.crossfadeSec)
        .put("skip_silence", s.skipSilence)
        .put("mix_audio", s.mixAudio)
        .put("volume_style", s.volumeStyle)
        .put("repeat", s.repeat)
        .put("shuffle", s.shuffle)
        .put("force_tempo", s.forceTempo)
        .put("tempo_bpm", s.tempoBpm)
        .put("tempo_max_change", s.tempoMaxChange)
        .put("tempo_only_faster", s.tempoOnlyFaster)
        .put("push_quiet", s.pushQuiet)
        .put("quiet_boost", s.quietBoost)
        .put("quiet_threshold_db", s.quietThresholdDb)

    private fun playerJson(p: PlayerSnapshot): JSONObject = JSONObject()
        .put("connected", p.connected)
        .put("playing", p.playing)
        .put("media_id", p.mediaId ?: JSONObject.NULL)
        .put("title", p.title)
        .put("artist", p.artist)
        .put("position_ms", p.positionMs)
        .put("position", com.lull.player.Format.duration(p.positionMs))
        .put("duration_ms", p.durationMs)
        .put("duration", com.lull.player.Format.duration(p.durationMs))
        .put("queue_index", p.queueIndex)
        .put("queue_size", p.queueSize)
        .put("queue", JSONArray(p.queue.map { trackJson(it) }))
        .put("repeat", p.repeat)
        .put("shuffle", p.shuffle)
        .put("volume", p.volume)
        .put("max_volume", p.maxVolume)
        .put("sleep_remaining_ms", p.sleepRemainingMs)
        .put("ab_a_ms", p.abAMs)
        .put("ab_b_ms", p.abBMs)
        .put("speed", p.speed.toDouble())
        // Rendered the same way GET /api/tracks/{id}/bpm renders it: a tempo, or nothing. The
        // store's -1 means "analysed, hasn't got one", which is a distinction that belongs on the
        // endpoint that explains it rather than as a magic number in a field called source_bpm.
        .put("source_bpm", if (p.sourceBpm > 0) p.sourceBpm else JSONObject.NULL)
        .put("boosted", p.boosted)

    // ---------------- Small helpers ----------------

    private fun id(raw: String): Long =
        raw.toLongOrNull() ?: throw ApiException(400, "'$raw' is not an id")

    private fun notFound(what: String, which: String) =
        ApiException(404, "no $what '$which'")

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) when (val v = opt(name)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        } else null

    private fun JSONObject.optIntOrNull(name: String): Int? = optLongOrNull(name)?.toInt()

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
        if (has(name) && !isNull(name)) when (val v = opt(name)) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull()
            is Number -> v.toInt() != 0
            else -> null
        } else null
}
