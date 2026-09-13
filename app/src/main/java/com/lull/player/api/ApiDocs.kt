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

`GET /api/settings`, and `PATCH` any of `theme` (`system\|light\|dark`), `crossfadeSec` (0–12),
`skipSilence`, `mixAudio`, `volumeStyle` (`bar\|knob`).

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
