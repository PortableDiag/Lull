# Changelog

## 1.0 — 2026-06-28
First release.

- Material 3 audio library (MediaStore) with album/embedded artwork, dark by default
- Background playback via a Media3 `MediaSessionService` (foreground service, media notification, audio focus, becoming-noisy handling)
- **Safe repeat-one**: with repeat-one active, next/previous restart the current track instead of skipping — from in-app, notification, lock screen, and Bluetooth/headset media buttons
- Repeat (off/one/all) and shuffle, both persisted
- Now Playing screen with scrub bar, transport, and a switchable **volume bar or circular knob**
- Tap-to-expand mini-player; light/dark/system theme
