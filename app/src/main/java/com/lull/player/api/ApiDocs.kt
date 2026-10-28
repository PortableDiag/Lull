package com.lull.player.api

/**
 * The API's own reference, served at `GET /api/docs`.
 *
 * It lives in the app rather than only in the README because the thing most likely to read it is
 * an agent that has the key and the port and nothing else. A reference it can fetch is a reference
 * that cannot drift from the build it is talking to.
 */
object ApiDocs {

    /** The one-line index at `GET /api`, for a caller that just wants to see what exists. */
    val SUMMARY = listOf(
        "GET    /api/health",
        "GET    /api/instance",
        "GET    /api/docs",
        "GET    /api/tracks?q=&limit=&offset=",
        "GET    /api/tracks/{id}",
        "GET    /api/tracks/{id}/file",
        "GET    /api/tracks/{id}/art",
        "GET    /api/tracks/{id}/share",
        "PUT    /api/tracks/{id}/rating",
        "GET    /api/tracks/{id}/bpm",
        "PUT    /api/tracks/{id}/bpm",
        "GET    /api/tracks/{id}/quiet",
        "GET    /api/groups/{folders|artists|albums|genres|playlists|rated}",
        "GET    /api/group?key=",
        "GET    /api/player",
        "PATCH  /api/player",
        "POST   /api/player/play",
        "POST   /api/player/{pause|resume|next|previous|stop|seek|queue}",
        "POST   /api/player/sleep",
        "DELETE /api/player/sleep",
        "POST   /api/player/ab",
        "DELETE /api/player/ab",
        "GET    /api/ratings",
        "POST   /api/ratings",
        "GET    /api/playlists",
        "POST   /api/playlists",
        "GET    /api/playlists/{id}",
        "PATCH  /api/playlists/{id}",
        "DELETE /api/playlists/{id}",
        "POST   /api/playlists/{id}/tracks",
        "DELETE /api/playlists/{id}/tracks",
        "POST   /api/playlists/{id}/duplicate",
        "GET    /api/settings",
        "PATCH  /api/settings",
        "POST   /api/share"
    )

    val MARKDOWN = """
# Lull local API

Drives the player on this device over HTTP: the library, playback, playlists, ratings, settings
and sharing. Everything here is something the person holding the phone can already do from the
UI — the API is a second pair of hands, not a second set of features.

- **Base URL:** `http://127.0.0.1:<port>/api` — the port is shown in the app under **Local API**.
- **Off by default.** It runs only while you have it enabled, and it binds `127.0.0.1` unless you
  also turn on LAN access.
- **From a laptop:** `adb forward tcp:8787 tcp:8787`, then talk to `127.0.0.1:8787` as usual.

## Authentication

Every endpoint except `GET /api/health` needs the key the app generated:

```
X-API-Key: <key>
Authorization: Bearer <key>
```

Wrong or missing key → `401 {"error":"missing or invalid API key"}`.

### PhoneAPI

PhoneAPI links itself with the key handshake (`com.phoneapi.action.GRANT_KEY`) and gets its own
key, separate from yours. The owner chooses what it may do: **See** (every `GET`) and **Change**
(everything else); the other one answers `403`. Once linked, every answer to a request carrying
`X-PhoneAPI-Challenge` (health, 401s, 404s and errors included) carries
`X-PhoneAPI-Proof: base64url_nopad(HMAC-SHA256(proof_key, challenge))`. Unlink under **Local API**;
uninstalling PhoneAPI unlinks it too.

## Shapes

- Everything answers JSON except `…/file` and `…/art`, which answer bytes.
- A failure is always `{"error": "...", "hint": "..."}` with a status that says whose fault it was.
  `400` you sent something wrong, `404` it does not exist, `409` the app refused, `503` the player
  is not connected yet.
- Ids are MediaStore audio ids — the same ids playlists and ratings are keyed by.
- Both `camelCase` and `snake_case` are accepted on input; output is `snake_case`.

## Library

| Route | What |
|---|---|
| `GET /api/tracks?q=&limit=&offset=` | Every track, newest MediaStore order. `q` matches title, artist and album. `limit` defaults to 200, caps at 5000 |
| `GET /api/tracks/{id}` | One track, with `stars` and the urls for its file and art |
| `GET /api/tracks/{id}/file` | **The audio itself**, streamed, with `Content-Disposition` |
| `GET /api/tracks/{id}/art` | Album art bytes, `404` when the track has none |
| `GET /api/tracks/{id}/share` | The share subject/text for it, and the file url |
| `GET /api/groups/{tab}` | `folders`, `artists`, `albums`, `genres`, `playlists`, `rated` |
| `GET /api/group?key=album:42` | The tracks in one group. Keys come from `/api/groups/{tab}` |

## Player

`GET /api/player` reports everything at once: what is playing, position and duration, the queue,
repeat and shuffle, device volume, what is left on the sleep timer, and the A–B markers.

| Route | Body |
|---|---|
| `POST /api/player/play` | `{"ids":[1,2]}`, `{"id":1}`, `{"group":"album:42"}`, optional `{"startId":7}`. An empty body resumes |
| `POST /api/player/pause` · `resume` · `next` · `previous` · `stop` | — |
| `POST /api/player/seek` | `{"positionMs": 60000}` |
| `POST /api/player/queue` | `{"ids":[…]}` or `{"group":"…"}` — appends |
| `PATCH /api/player` | `{"repeat":"off\|one\|all"}`, `{"shuffle":"off\|all\|favourites"}`, `{"volume":7}` |
| `POST /api/player/sleep` | `{"minutes":30}` · `DELETE` the same path cancels |
| `POST /api/player/ab` | `{"aMs":1000,"bMs":9000}` · `DELETE` clears |

`shuffle: "favourites"` is the weighted draw that leans on your ratings — three stars and up come
around early and often. It reorders the queue rather than filtering it.

## Ratings

| Route | Body |
|---|---|
| `GET /api/ratings` | Every rating by id, plus counts per star |
| `PUT /api/tracks/{id}/rating` | `{"stars":4}` — `0` clears it |
| `POST /api/ratings` | `{"ids":[1,2],"stars":5}` — one write for the batch |

## Playlists

| Route | Body |
|---|---|
| `GET /api/playlists` · `GET /api/playlists/{id}` | — |
| `POST /api/playlists` | `{"name":"Sleep","ids":[1,2]}` |
| `PATCH /api/playlists/{id}` | `{"name":"…"}` and/or `{"order":[3,1,2]}` |
| `DELETE /api/playlists/{id}` | — |
| `POST /api/playlists/{id}/tracks` | `{"ids":[…]}` or `{"group":"…"}` |
| `DELETE /api/playlists/{id}/tracks` | `{"ids":[…]}` |
| `POST /api/playlists/{id}/duplicate` | optional `{"name":"…"}` |

## Settings

`GET /api/settings`, and `PATCH` any of `palette` (below), `theme` (`system\|light\|dark` — only
Material You, Ocean and Terminal follow it; every other palette is one look), `crossfadeSec` (0–12),
`skipSilence`, `mixAudio`, `volumeStyle` (`bar\|knob`), plus Force Tempo: `forceTempo`,
`tempoBpm` (60–200), `tempoMaxChange` (5–100, a percentage) and `tempoOnlyFaster`; and the dead-spot
settings `pushQuiet`, `quietBoost` (10–150) and `quietThresholdDb` (3–24).

`palette` is one of the house colour schemes, by key — ${com.lull.player.Palette.entries.joinToString(", ") { "`" + it.key + "`" }}
— or by its name in any case (`"White Glow"`). Unknown names are a `400` that lists them. The
screens that are open redraw in it at once.

## Force Tempo

Plays every track at one tempo, by speeding it up without changing its pitch — a workout playlist
with no slow patches in it.

```bash
curl -s -H "X-API-Key: ${'$'}KEY" -X PATCH 127.0.0.1:8787/api/settings \
  -d '{"forceTempo":true,"tempoBpm":140,"tempoMaxChange":30}'
```

`GET /api/player` then reports the `speed` the playing track is running at and its own
`source_bpm`.

A track's tempo is read from its `TBPM` tag where it has one and measured from the audio where it
does not, then remembered. `GET /api/tracks/{id}/bpm` reports it as one of three states:

| `state` | meaning |
|---|---|
| `known` | `bpm` is its tempo |
| `unknown` | nobody has worked it out yet — it plays unchanged until they do |
| `none` | it has been analysed and has no steady tempo (spoken word, drone). It plays unchanged. |

`PUT /api/tracks/{id}/bpm` with `{"bpm":128}` overrides a measurement that came out wrong, and it
sticks: nothing re-measures a tempo somebody supplied. `{"bpm":0}` forgets it and lets the next
play work it out afresh.

## Pushing through the quiet parts

Force Tempo sets a speed **per track**, which does nothing for a quiet bridge *inside* one — the
piano break in a 140 BPM song is still at 140 BPM. `pushQuiet` speeds up further while the position
sits in one of those stretches, and ramps back out at the end of it.

```bash
curl -s -H "X-API-Key: ${'$'}KEY" -X PATCH 127.0.0.1:8787/api/settings \
  -d '{"pushQuiet":true,"quietBoost":50,"quietThresholdDb":9}'
```

`GET /api/player` then reports `boosted` while it is happening, and `speed` shows the real figure —
read off the engine, not recomputed, because with this on the speed moves during a track.

`GET /api/tracks/{id}/quiet` lists what was found, at the current `quietThresholdDb`:

```json
{ "id": 42, "measured": true, "threshold_db": 9, "count": 1, "quiet_ms": 20000,
  "sections": [ { "start_ms": 78000, "end_ms": 98000, "start": "1:18", "duration_ms": 20000 } ] }
```

`"measured": false` means the track has never been analysed — which is *not* the same as a track
that was analysed and has no dead spots, and that one answers with `"count": 0`. A track is
measured while the track before it is playing, so a queue analyses itself a step ahead of you.

The threshold is applied when you ask, not when the track was measured: what is stored is the
track's loudness curve, so moving `quietThresholdDb` re-reads everything already measured instead
of invalidating a library.

## Share

`POST /api/share` with `{"ids":[…]}`, `{"id":1}` or `{"group":"album:42"}`.

It pops the system share sheet **on the device** — and says whether anyone can see it. Android does
not let a background app throw a chooser over whatever you are doing, so when Lull is not on screen
the response says `"visible": false`. The share `subject`, `text` and the `files` urls come back
either way, so a script that cannot show a chooser can take the track and its metadata itself:

```bash
curl -s -H "X-API-Key: ${'$'}KEY" 127.0.0.1:8787/api/tracks/42/share
curl -s -H "X-API-Key: ${'$'}KEY" 127.0.0.1:8787/api/tracks/42/file -OJ
```

## What the API deliberately cannot do

Since 1.12 the UI can **delete a track from the device**, and that is the one thing here with no
endpoint behind it. Two reasons, and neither is an oversight:

- From Android 10 up a file Lull did not create is not Lull's to delete. The system asks the user
  itself, over the app, and does the deleting. A request arriving on a socket while the phone is in
  a pocket has nobody to ask, so the honest answer would be `409` on almost every call.
- It is the only irreversible thing in the app. Everything else the key can reach is a list that can
  be rebuilt; a deleted file is gone. A key that leaks should cost you a scrambled queue, not a
  library.

`DELETE /api/playlists/{id}` removes the playlist and leaves every track on the device, which is
what it has always done.

## Worked example

```bash
KEY=<the key from the app>
H="X-API-Key: ${'$'}KEY"
BASE=http://127.0.0.1:8787/api

curl -s -H "${'$'}H" ${'$'}BASE/instance
curl -s -H "${'$'}H" "${'$'}BASE/tracks?q=rain&limit=5"
curl -s -H "${'$'}H" -X POST ${'$'}BASE/player/play -d '{"group":"folder:/storage/emulated/0/Music/Ambient"}'
curl -s -H "${'$'}H" -X PATCH ${'$'}BASE/player      -d '{"repeat":"one"}'
curl -s -H "${'$'}H" -X POST  ${'$'}BASE/player/sleep -d '{"minutes":45}'
```
""".trimIndent()
}
