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

### Background playback
- Built on a Media3 **`MediaSessionService`** foreground service: plays with the screen off or the app closed, shows a media notification with controls, handles audio focus, and pauses on headphone unplug.

### Volume knob *or* bar
- The Now Playing screen offers a volume **slider bar** or a **circular knob** (drag around the dial) — switch with the tune button; your choice is saved. Both drive the device media volume, staying in sync with the hardware buttons.

### Library & playback
- Material 3 list of all device audio with album/embedded artwork, via **MediaStore**.
- Tap‑to‑expand **mini‑player** plus a full **Now Playing** screen: artwork, scrub bar, shuffle, repeat cycle, previous / play / next.
- **Light / dark / follow‑system** themes — **defaults to dark**.

No analytics, ads, or network access — Lull only reads the audio already on your device.

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
├── MainActivity.kt        # library: MediaStore query, list, mini-player, permissions
├── NowPlayingActivity.kt  # full controls: scrub, shuffle, repeat, volume bar/knob
├── PlaybackService.kt     # MediaSessionService — background playback + notification
├── RepeatAwarePlayer.kt   # makes next/prev restart the track when repeat-one is on
├── VolumeKnobView.kt      # custom circular volume knob
├── ArtLoader.kt           # async artwork loading + LRU cache
├── TrackAdapter.kt        # RecyclerView list adapter
├── AudioItem.kt           # audio model + MediaItem mapping
├── Prefs.kt               # repeat / shuffle / volume-style persistence
└── ThemeManager.kt        # light/dark/system theme
```

---

## Roadmap ideas
- Sleep timer (fade out after N minutes)
- Folder / playlist browsing and queues
- Per‑track resume position
- Gapless / crossfade options

---

## License

MIT — see [LICENSE](LICENSE).
