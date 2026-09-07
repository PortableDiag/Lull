# Lull

A modern, dark‑first **music & white‑noise player for Android**, built for one habit other players get wrong: **looping a single track safely**. When repeat‑one is on, "next" and "previous" — from anywhere, including Bluetooth/headset buttons — **restart the current track instead of skipping**. Fall asleep to looping white noise and an accidental skip on a headphone/headband button just restarts it, rather than jumping into your music and waking you.

> Package: `com.lull.player` · minSdk 26 (Android 8.0) · targetSdk 35

---

## Features

### Safe repeat‑one (the point of the app)
- With **Repeat One** active, **Next → restart** and **Previous → restart** the current track.
- Works for every control surface: in‑app buttons, the media notification, the lock screen, and **hardware / Bluetooth media buttons** (headsets, sleep headbands).
- The skip buttons stay enabled even on a single‑track queue, so they're tappable‑to‑restart.
- Off / All repeat modes skip tracks normally. Your repeat & shuffle choices are **remembered**, so a white‑noise loop comes back the way you left it.

### Sleep timer
The other half of falling asleep to a looping track: **play for 5–90 minutes, fade out, then pause.**

- Set it from the **moon button** on Now Playing, or from **Sleep timer** in the library's overflow menu. While it's running the Now Playing title bar counts it down (`Sleep in 24:31`) and the menu entry shows the time left.
- The last **30 seconds** are a **raised-cosine fade** to silence. That curve is flat at both ends, so the fade eases in without an audible step and settles onto silence instead of arriving at it still dropping — on a track you're falling asleep to, the moment a fade visibly *begins* is as disruptive as the moment it ends. On a short timer the fade is capped at half the total.
- It ends on **pause**, not stop, so the queue and your place in it survive — one tap to carry on.
- The countdown runs in `PlaybackService`, so it survives closing the app, and it's measured against `elapsedRealtime` so it counts **through device sleep** rather than stopping with the CPU. A running timer isn't persisted across a restart: restoring a countdown would be a promise about a device that was switched off.
- It composes with crossfade rather than fighting it — the two contribute independent gains that are multiplied in one place, so a fade-out landing mid-transition dims the pair together. A crossfade that *wouldn't finish* before the deadline is skipped outright, so the track you hear last isn't one you never chose to end on.

### Background playback
- Built on a Media3 **`MediaSessionService`** foreground service: plays with the screen off or the app closed, shows a media notification with controls, handles audio focus, and pauses on headphone unplug.
- **Mix with other audio** (on by default): keeps playing without grabbing audio focus, so another app's video or notification doesn't stop your music.

### How tracks join: gapless, crossfade, trim silence
Three independent settings shape what you hear between and inside tracks.

| Setting | Default | What it does |
|---|---|---|
| **Gapless** | on | The Media3 default: consecutive tracks butt up against each other with no pause. |
| **Crossfade** | off | Overlaps the end of one track with the start of the next (0–12s), on an **equal-power** sin/cos curve so the transition doesn't sag in the middle. Needs two tracks sounding at once, so `PlaybackService` keeps **two ExoPlayer engines** and swaps which one the media session points at when a fade completes. |
| **Trim silence** | off | Shortens long runs of near-silence — dead air **inside** a track, plus the padding at its head and tail. |

Crossfade and gapless are mutually exclusive by definition, and crossfade is skipped while **repeat-one** or an **A-B loop** is armed (both are requests to hear *this* track, not to blend it into something else).

**Trim silence is not part of that trade-off.** It's ExoPlayer's `SilenceSkippingAudioProcessor`, which lives in the audio sink and shortens near-silent PCM as it plays out — *below* the track transition. So it works on any format with no scan of the file up front, and it composes with whichever of gapless or crossfade is in effect. It's off by default because it changes what you hear, and a rest the artist wrote is not a gap the player should close.

### Opening audio from a file manager
Lull registers for **`ACTION_VIEW` on audio** — `audio/*`, plus the `application/ogg` and `application/flac` spellings some providers still use, over `content://` and `file://` — so it appears in **Open with** for `.mp3`, `.wav`, `.m4a`, `.flac`, `.ogg`, `.opus` and the rest. There is a second, extension-matched filter for senders that hand over a bare uri with no type at all, and Lull accepts a **share** (`SEND` / `SEND_MULTIPLE`) as well.

Opening one track gives you **its whole folder**, so Next pages through it. Three routes, tried in order:

1. **The `ClipData` the launching app attached.** A file manager already knows the folder; when it attaches the siblings, the read grant on the intent covers every one of them. No permission, no lookup — and it is the only route that works for a `.nomedia` folder. (Sift does this for audio; other file managers would have to adopt the same convention.)
2. **The folder, out of Lull's own library** — resolve the opened file to a real path, then take every track the library holds from that directory.
3. **The single file**, which always works.

Route 2's path resolution is the awkward part: a file manager typically hands over its own `content://<their.app>.fileprovider/…` uri, which answers neither a MediaStore id nor a `DATA` column. The descriptor it opens still points at the real file, though, and `/proc/self/fd/N` is a symlink to it — so the folder is recoverable even when the uri says nothing. (Same problem, same fix as in Loopr.)

**A launch from another app never waits on media permission.** Without it there is nothing to load and the intent's own grant carries the playback; with it, the library load is awaited first so the folder can be built.

### Browsing: folders, artists, albums, genres
The flat list of every track is fine for a phone holding a dozen files and useless for one holding thousands, so the same library is offered **six ways**: Tracks, Folders, Artists, Albums, Genres and Playlists. Each grouped tab is a list you drill into and Back out of; re-tapping the current tab also comes back out. **The tab and the group you were on are both remembered.**

Genre is read from MediaStore's **genre membership tables** rather than the `GENRE` column on a track, which only exists from API 30. That is one query per genre — tens, not thousands — run once per load on the IO dispatcher.

### Multi-selection
**Long-press any row to start selecting**, then tap to add more. The contextual bar offers **play**, **add to queue**, **add to playlist**, **remove from this playlist**, and **select all**.

It works on **groups** as well as tracks: long-press an album, folder or genre and everything inside it comes with the selection, without opening it first. Selection is held by **track id rather than row position**, so it survives a search keystroke or a playlist edit — positions would not. The reorder handles hide while a selection is running, because dragging and selecting are otherwise two gestures fighting over the same row.

### Playlists
- Playlists are **their own tab**, with a per-row menu — **play, rename, duplicate, delete** — and a button to make a new one. Long-press tracks and add the whole selection at once; adding a selection is **one write** for the batch rather than one per track.
- **Drag to reorder** with the handle on the right of a row (shown only in a playlist view with no active search, where row position maps 1:1 to stored order); the order is saved when you drop it.
- **Opens where you left off** — the library reopens on the last collection you viewed (All tracks or a specific playlist), falling back to All tracks if that playlist was deleted.
- Playlists are stored as lists of MediaStore ids in `SharedPreferences`, so they cost almost nothing and survive files moving; a track that has since been deleted is **skipped** when the playlist is shown, not pruned, so it returns if the file (or SD card) reappears.

### A-B loop
- Mark **A** and **B** in a track and loop the region between them, driven from the service so it survives closing the UI. Re-reads the real playback position each pass, so seeking or pausing inside the region doesn't desync it.

### Volume knob *or* bar
- The Now Playing screen offers a volume **slider bar** or a **circular knob** (drag around the dial) — switch with the tune button; your choice is saved. Both drive the device media volume, staying in sync with the hardware buttons.

### Library & playback
- Material 3 list of all device audio with album/embedded artwork, via **MediaStore**.
- Tap‑to‑expand **mini‑player** plus a full **Now Playing** screen: artwork, scrub bar, shuffle, repeat cycle, previous / play / next.
- **Light / dark / follow‑system** themes — **defaults to dark**.

No analytics, ads, or network access — Lull only reads the audio already on your device.

---

## Where the settings live

Everything persistent is in the **overflow menu** on the library screen, and is remembered across restarts:

| Menu item | Type | Default |
|---|---|---|
| **Theme** | Follow system / Light / Dark | Dark |
| **Sleep timer** | Off / 5–90 minutes | Off |
| **Crossfade** | Slider, 0–12s (0 = off) | Off |
| **Trim silence** | Checkbox | Off |
| **Mix with other audio** | Checkbox | On |

Repeat, shuffle and the volume bar/knob choice are set on the **Now Playing** screen; the **A-B loop** buttons and the **sleep timer** (moon) are there too. The sleep timer is the one entry that isn't a persistent setting — a running countdown is intentionally dropped on restart, and only the duration you last picked is remembered.

None of the three playback settings can be pushed through a `MediaController`: crossfade is Lull's own concept rather than a Media3 one, and skip-silence and audio-focus handling live on `ExoPlayer` and not on the `Player` interface a controller talks to. So all three are written to `SharedPreferences` and picked up by `PlaybackService` through an `OnSharedPreferenceChangeListener`, which is what lets them take effect on the *live* player without restarting playback.

---

## Screenshots

_Add screenshots here (`docs/`) — library list, Now Playing with the knob, and the media notification._

---

## Tech stack

| Area | Choice |
|------|--------|
| Language | Kotlin |
| Media | [AndroidX Media3 / ExoPlayer + MediaSession](https://developer.android.com/media/media3) `1.4.1` |
| UI | Material 3 (Views + ViewBinding), ConstraintLayout, RecyclerView |
| Async | Kotlin Coroutines |
| Build | Gradle 8.11.1, Android Gradle Plugin 8.7.3, JDK 17 |

---

## How the safe repeat‑one works

The key piece is [`RepeatAwarePlayer`](app/src/main/java/com/lull/player/RepeatAwarePlayer.kt), a Media3 `ForwardingPlayer` that wraps ExoPlayer **inside the media session**. Because it sits at the session/player layer, it intercepts skips from *every* origin — UI, notification, lock screen, and AVRCP/hardware media buttons:

```kotlin
override fun seekToNext() {
    if (repeatMode == Player.REPEAT_MODE_ONE) seekTo(currentMediaItemIndex, 0L) else super.seekToNext()
}
// ...and the same for seekToNextMediaItem / seekToPrevious / seekToPreviousMediaItem
```

It also reports the four seek commands as always‑available so the buttons stay enabled (and therefore "restart‑able") on a single‑track queue.

---

## Building

### Requirements
- JDK 17, Android SDK **platform 35** + **build‑tools 34.0.0**
- `local.properties` with `sdk.dir=/path/to/Android/Sdk`

### Debug (no signing needed)
```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

### Signed release
1. `keytool -genkeypair -v -keystore lull-release.jks -alias lull -keyalg RSA -keysize 2048 -validity 10000`
2. Copy `keystore.properties.example` → `keystore.properties` and fill in your passwords.
3. `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

> `keystore.properties` and `*.jks` are git‑ignored — keep signing secrets out of version control.

### Install
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## Project structure

```
app/src/main/java/com/lull/player/
├── MainActivity.kt        # library: six browse tabs, drill-down, multi-select, mini-player, playlists
├── MediaLibrary.kt        # the MediaStore load, with folder + genre columns
├── Browse.kt              # the browse axes and how the library is grouped into them
├── GroupAdapter.kt        # rows of folders / artists / albums / genres / playlists
├── OpenIntent.kt          # launches from another app: ClipData, folder resolution, single file
├── NowPlayingActivity.kt  # full controls: scrub, A-B loop, shuffle, repeat, volume bar/knob
├── PlaybackService.kt     # MediaSessionService — background playback, crossfade, A-B, trim silence
├── RepeatAwarePlayer.kt   # makes next/prev restart the track when repeat-one is on
├── AbLoop.kt              # the A-B marker pair, shared between the service and the UI
├── SleepTimer.kt          # sleep-timer deadline + fade gain, shared the same way
├── SleepTimerDialog.kt    # the duration picker, shared by both screens
├── PlaylistStore.kt       # playlists (create/rename/delete/add/remove/reorder) + last-viewed collection
├── VolumeKnobView.kt      # custom circular volume knob
├── ArtLoader.kt           # async artwork loading + LRU cache
├── TrackAdapter.kt        # RecyclerView list adapter
├── AudioItem.kt           # audio model + MediaItem mapping
├── Prefs.kt               # repeat, shuffle, volume style, crossfade, mix-audio, trim-silence
└── ThemeManager.kt        # light/dark/system theme
```

---

## Roadmap ideas
- Per‑track resume position
- Sensitivity control for trim silence (how quiet, and for how long, counts as a gap)

---

## License

MIT — see [LICENSE](LICENSE).
