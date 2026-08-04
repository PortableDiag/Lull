# Changelog

## 1.6 — 2026-08-03
- **Sleep timer**: play for 5–90 minutes, fade out over the last 30 seconds, then pause. Set it from the moon button on Now Playing or from the overflow menu; while it runs, the Now Playing title bar counts it down and the overflow entry shows the time left. The countdown lives in `PlaybackService`, so it keeps running with the app closed and the screen off, and it is measured against `elapsedRealtime` so it counts through device sleep rather than stopping with the CPU.
- The fade follows a **raised cosine**, which is flat at both ends — it eases in without an audible step and settles onto silence instead of arriving at it mid-drop. On a short timer the fade is capped at half the total, so a 1-minute timer doesn't spend 30 seconds fading.
- It ends on **pause**, not stop: the queue and your place in it survive, so it's one tap to carry on.
- A running timer is deliberately **not persisted** — restoring a countdown after a restart would be a promise about a device that was switched off. Only the duration you last chose is remembered.
- Internal: crossfade and the sleep timer both want the engine volume, so they now contribute independent gains that are multiplied in one place (`applyVolumes`) rather than overwriting each other. A crossfade is also skipped if the timer would fire before it finished, so the last thing you hear isn't a track you never chose to end on.

## 1.5 — 2026-07-18
- **Playlists**: create, rename and delete playlists, and add or remove tracks. Long-press a track to add it to a playlist (or remove it, from inside a playlist). Playlists are stored as lists of track ids in `SharedPreferences`, so a track that has since been deleted is skipped when the playlist is shown rather than pruned.
- **Reorder**: drag the handle on the right of a row to reorder a playlist; the new order is saved when you drop it. The handle only appears in a playlist view with no active search, where row position maps 1:1 to stored order.
- **Opens where you left off**: the library reopens on the last collection you were viewing — All tracks or a specific playlist. Falls back to All tracks if that playlist was deleted.
- The toolbar title now shows the current collection (the app name for All tracks, otherwise the playlist name). Switch or create playlists from the new Playlists toolbar action.

## 1.4 — 2026-07-12
- **Trim silence** (off by default, in the overflow menu): shortens long stretches of near-silence as they play — dead air inside a track, and the padding at its head and tail. Runs in ExoPlayer's audio sink, below the track transition, so unlike crossfade it does not displace gapless playback; the two compose.
- Note: with Trim silence on, the scrub bar jumps forward over a trimmed gap and a track ends before the bar reaches the end. The position is reported in media time, so this is expected rather than a glitch.

## 1.3 — 2026-07-11
- **A-B loop**: mark two points in a track and loop between them; survives closing the UI.
- **Crossfade** (0–12s, off by default): overlaps the end of one track with the start of the next, on an equal-power curve. Mutually exclusive with gapless; skipped while repeat-one or an A-B loop is active.

## 1.1 — 2026-07-10
- **Mix with other audio**: keep playing without taking audio focus, so a video or a call notification from another app doesn't stop the music.

## 1.0 — 2026-06-28
First release.

- Material 3 audio library (MediaStore) with album/embedded artwork, dark by default
- Background playback via a Media3 `MediaSessionService` (foreground service, media notification, audio focus, becoming-noisy handling)
- **Safe repeat-one**: with repeat-one active, next/previous restart the current track instead of skipping — from in-app, notification, lock screen, and Bluetooth/headset media buttons
- Repeat (off/one/all) and shuffle, both persisted
- Now Playing screen with scrub bar, transport, and a switchable **volume bar or circular knob**
- Tap-to-expand mini-player; light/dark/system theme
