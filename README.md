# Lull

A **music, podcast and white‑noise player for Android**. Dark by default. No ads, no analytics, no
account, no library to import — it plays the audio already on your device, and nothing in it talks
to a server.

What it is *for* is control over how things actually sound and how you actually reach them: an audio
engine where gapless, crossfade and trim‑silence each compose instead of fighting; a Force Tempo
that puts a whole playlist on one BPM without touching its pitch; star ratings with a shuffle that
leans on them; seven ways through the same library; sharing that carries a track's metadata with
it; and a local API that lets a script drive the whole app.

> Package: `com.lull.player` · minSdk 26 (Android 8.0) · targetSdk 35 · [latest release](https://github.com/PortableDiag/Lull/releases/latest)

---

## What it does that most players don't

| | |
|---|---|
| **Gapless, crossfade and trim silence that compose** | Crossfade keeps **two ExoPlayer engines** and overlaps them on an equal‑power curve; trim‑silence runs *below* the transition in the audio sink, so it works with either. Crossfade stands aside for repeat‑one and A‑B, which are requests to hear *this* track. |
| **Force Tempo** | Set a BPM and every track plays at it — a **pitch‑preserving** stretch, so the track still sounds like itself. A tempo comes from the file's `TBPM` tag or is measured from the audio, and is remembered. A track with **no** steady tempo is found to have none and left alone; a track already fast enough is left alone too. |
| **Pushing through the quiet parts** | The piano bridge inside a 140 BPM song is *also* at 140 BPM, so a per‑track speed does nothing for it. Lull finds the quiet stretches inside a track and speeds up further through them, ramping in and out so it reads as the track leaning forward. |
| **Favourites shuffle** | Rate tracks 1–5 and shuffle draws a **weighted** queue — three stars and up come around early and often. It is a **reordering, not a filter**: everything you asked for is still in the queue. |
| **A local HTTP API** | Off until you switch it on, then key‑gated: a script or an agent can browse, play, queue, rate, build playlists, change settings and share — everything the UI can do. |
| **Share that carries the metadata** | The file itself goes, plus title, artist, album, genre, duration, size and your rating — a `content://` uri carries none of that, and what arrives otherwise is a filename. |
| **Safe repeat‑one** | With repeat‑one on, next and previous **restart** the track instead of skipping — from every control surface, including Bluetooth and headset buttons. |
| **Seven ways to browse** | Tracks, Folders, Artists, Albums, Genres, Playlists and Rated, with the tab *and* the group you were in both remembered. |
| **Open one file, get its folder** | Opening a track from a file manager queues the whole folder behind it, with no media permission needed. |
| **A sleep timer that fades properly** | 5–90 minutes, then a **raised‑cosine** fade — flat at both ends, so it neither steps in nor lands still dropping — and it ends on pause, so your place survives. |

Plus the ordinary things done properly: background playback with a media notification, A‑B looping,
multi‑select, a playlist manager with drag‑reorder, a circular volume knob, and light/dark/system
themes.

---

## Features

### How tracks join: gapless, crossfade, trim silence
Three independent settings shape what you hear between and inside tracks.

| Setting | Default | What it does |
|---|---|---|
| **Gapless** | on | The Media3 default: consecutive tracks butt up against each other with no pause. |
| **Crossfade** | off | Overlaps the end of one track with the start of the next (0–12s), on an **equal-power** sin/cos curve so the transition doesn't sag in the middle. Needs two tracks sounding at once, so `PlaybackService` keeps **two ExoPlayer engines** and swaps which one the media session points at when a fade completes. |
| **Trim silence** | off | Shortens long runs of near-silence — dead air **inside** a track, plus the padding at its head and tail. |

Crossfade and gapless are mutually exclusive by definition, and crossfade is skipped while **repeat-one** or an **A-B loop** is armed (both are requests to hear *this* track, not to blend it into something else).

**Trim silence is not part of that trade-off.** It's ExoPlayer's `SilenceSkippingAudioProcessor`, which lives in the audio sink and shortens near-silent PCM as it plays out — *below* the track transition. So it works on any format with no scan of the file up front, and it composes with whichever of gapless or crossfade is in effect. It's off by default because it changes what you hear, and a rest the artist wrote is not a gap the player should close.

### Force Tempo
**Set a BPM and everything plays at it.** Switch it on under **Force tempo** in the overflow menu,
pick a target anywhere from 60 to 200, and each track is sped up as far as it needs to reach it.

It exists for one complaint: a workout playlist where **one slow track is a slow patch in the
workout**. Everything about the defaults follows from that.

| Control | Default | What it does |
|---|---|---|
| **Target tempo** | 140 BPM | The tempo to bring every track to. |
| **Most a track may be stretched** | 30% | The ceiling. Under about 30% a stretched track just sounds like a track at that tempo; well over it, it sounds stretched. A track that cannot reach the target inside the ceiling goes as far as the ceiling allows rather than further. |
| **Never slow a track down** | on | A track already at or above the target is left **completely alone**. Slowing a fast track to hit a number would be the feature working against its own purpose. Off, a fast track is slowed to meet the target too. |

The stretch is ExoPlayer's, which time‑stretches with **Sonic** — the pitch does not move, so a
128 BPM track at 1.09x is still in the key it was recorded in.

**Where a tempo comes from.** The file's **`TBPM` tag** if it has one; otherwise Lull **measures**
it — half a minute decoded from 30 seconds in (the opening of a track is the least representative
part of it), reduced to an onset envelope at 10ms resolution and autocorrelated. Either way the
answer is **remembered per track**, so it is worked out once and never again, and the **next** track
in the queue is analysed while the current one is playing — so the only track ever heard at the
wrong speed is the one that was already playing when you switched the feature on.

**A track with no tempo is never touched.** An audiobook, a drone or a field recording is analysed,
found to have no steady beat, and **written down as having none** — it plays unchanged and is not
analysed again. "Nothing known yet" and "nothing there" are deliberately two different states, all
the way out to the API: a player that invented a tempo for a podcast and then played it 30% fast
would be indefensible.

**The measured number is editable**, because it is a measurement rather than a preference and you
can hear when it is wrong. The panel shows the playing track's BPM in a field you can type over; a
number you supply **wins and is never quietly re‑measured**, and clearing it throws the measurement
away and has another go.

#### Octaves, which is where this gets interesting
A tempo is only defined up to a factor of two — a track counted at 70 BPM and a track counted at
140 can be the same felt pace. That ambiguity is handled in **two** places, and it has to be.

- **Choosing the speed.** 70 BPM in double time *is* 140, so such a track is left alone rather than
  played at 2.0x. But the fold is only accepted when the halved or doubled reading lands **near**
  the target (within 15%), so a merely slow **95 BPM** track is not excused as a secretly‑fast one
  on the grounds that 190 is "closer" to 140 in ratio — it gets stretched, which is the point.
- **Measuring it.** The estimator resolves its own octave with a preference weight around the
  perceived pulse, plus a tie‑break that takes the **faster** reading when the audio supports both
  equally: a rhythm that repeats every beat also repeats every two, and if the beat really were the
  slower one the faster lag would be lining beats up with the gaps and correlating badly. It
  doesn't. So the half‑lag holding up is not a coincidence.

The lag search runs at **quarter‑frame** resolution rather than whole frames, and that is not a
precision nicety — it is the difference between a right answer and an octave. A 160 BPM beat lands
every 37.5 envelope frames, so *neither* lag 37 nor lag 38 lines the rhythm up with itself, while
lag 75 — two beats — lines it up perfectly. A whole‑frame search reports 80 BPM for that track,
confidently.

**What it composes with.** Force Tempo is the first thing in Lull that makes **media time and
wall‑clock time different quantities**. A crossfade is counted by the clock while a track's
remaining duration is counted in the track, so at 1.3x a fade timed against the wrong one starts
late and gets cut off; an A‑B loop would overshoot B by the same ratio. Both convert through one
place. A crossfade also sets the **incoming** track's own speed before it is heard, so two tracks
at different tempos fade into each other at the two speeds that bring them both to the one you
asked for — the transition itself has no tempo change in it.

### Pushing through the quiet parts
Force Tempo above sets a speed **per track**, and that is genuinely all it can do about a dead
spot: the piano bridge in the middle of a 140 BPM rock song is *also* at 140 BPM, so there is no
tempo there to correct. What is wrong with it is that the **energy drops out** — and that is a
different measurement and a different fix.

**Push through quiet parts** finds those stretches and speeds up further while you are in one.

| Control | Default | What it does |
|---|---|---|
| **Push through quiet parts** | off | The switch. |
| **How much faster in a quiet part** | +50% | Stacked on top of whatever the track was already doing. Goes to +150%, much further than the whole‑track ceiling, because this applies to the passage you specifically *want* to be over — stretch artefacts matter far less there than the seconds do. |
| **How quiet counts as a dead spot** | 9 dB | Measured against the track's own level. |

**Relative to the track, never absolute.** A quietly mastered song is not one long dead spot and a
loud one is not free of them, so "full level" is a **high percentile of that track's own seconds**.
Not its peak — one cymbal crash would raise the bar for the whole song and turn ordinary verses
into dead spots.

**Three guards against speeding up where it would be vandalism:**

- **A stretch must last 6 seconds.** The bar of held breath before a drop is the *opposite* of a
  dead spot; speeding through it would wreck the one moment the track was building to.
- **Two quiet stretches split by a short fill are one dead spot**, not two — otherwise the speed
  ramps down and straight back up in the middle of it.
- **Every second of a reported section meets the threshold on its own.** An earlier cut widened the
  threshold on the way *out* of a section, as decibel hysteresis, which let a run continue through
  seconds that did not qualify at all: on a real device a **4‑second dip swallowed the remaining
  160 seconds** of the track. Merging already provides the anti‑flap property that was meant to
  buy, so the hysteresis is gone.

**It ramps, and it ramps early.** The speed moves over ~800ms rather than stepping — a step is a
lurch, and a lurch is what you notice instead of the music. The ramp *starts* 800ms before the
quiet part does, so it has finished arriving by the time you get there; beginning it on the
boundary would play the first seconds of the dead spot, the ones most worth skipping, at the old
speed.

**One decode, two answers.** Where a track goes quiet needs the **whole** file, unlike its tempo,
which needs half a minute from the middle. So [`TrackAnalysis`](app/src/main/java/com/lull/player/TrackAnalysis.kt)
decodes end to end once and produces both — once per track, on a background worker, only while the
feature is on, and a track *ahead* of the one you are listening to.

**What is stored is the curve, not the conclusion.** One byte per second, in its own preferences
file. So moving the threshold slider **re‑reads every track already measured** rather than
invalidating a library, and the panel tells you live what the current setting finds in the track
playing right now.

### Ratings, and a shuffle that leans on them
Rate a track **1–5 stars** and the shuffle will take the hint.

- **Set a rating** from the star row on Now Playing — tapping the star it already sits on clears the rating, which is the only thing that would otherwise undo it. Or long-press in the library and **rate a whole selection at once** from the contextual bar; that dialog starts on the rating the selection already shares.
- **See them** as a compact star strip on each library row, and browse them on the **Rated** tab — five stars down to one, best group first. There is deliberately **no "unrated" bucket**: on any real library it would hold nearly everything and simply be a second copy of the Tracks tab.
- Ratings are stored as a map of MediaStore ids in `SharedPreferences`, exactly like playlists. Nothing is written to the audio file — no tag rewriting, so a rating can't corrupt a track or change its checksum — and a rating survives the file moving. MediaStore has no writable rating column for audio, so this is Lull's own store either way.

**Favourites shuffle** is the third state of the shuffle button (off → shuffle → favourites), and the point of the ratings: **3 stars and up come around early and often.**

| Rating | Relative chance of landing early |
|---|---|
| ★★★★★ | 40 |
| ★★★★ | 24 |
| ★★★ | 12 |
| *unrated* | 3 |
| ★★ | 2 |
| ★ | 1 |

The step between 2 and 3 stars is the whole design: three stars is the lowest rating that still means "yes", so that is where the weight jumps rather than creeps. An explicit **1 star is rarer than no rating at all** — a low rating is a judgement, while no rating is just silence.

It is a **reordering, not a filter**. Every track you asked for is still in the queue, so Next eventually reaches all of it and repeat-all still wraps the whole thing; the favourites simply come up first. Tapping a specific track still plays **that** track first and weights the rest behind it — tapping a row is a request to hear that row, the same call Lull already makes for a file opened from a file manager.

Media3's own `shuffleModeEnabled` is an unweighted permutation with nothing to bias, so this had to be Lull's own ordering: it builds the queue itself (a one-pass [Efraimidis–Spirakis](https://doi.org/10.1016/j.ipl.2005.11.003) weighted draw) and hands the player a plain queue with shuffle switched off. Switching it on **mid-queue** re-weights only what has not played yet, leaving the current track alone — which also means switching it back off cannot unscramble it, because the order it replaced is gone.

### Browsing: seven ways through one library
The flat list of every track is fine for a phone holding a dozen files and useless for one holding thousands, so the same library is offered **seven ways**: Tracks, Folders, Artists, Albums, Genres, Playlists and Rated. Each grouped tab is a list you drill into and Back out of; re-tapping the current tab also comes back out. **The tab and the group you were on are both remembered.**

Genre is read from MediaStore's **genre membership tables** rather than the `GENRE` column on a track, which only exists from API 30. That is one query per genre — tens, not thousands — run once per load on the IO dispatcher.

### Playlists
- Playlists are **their own tab**, with a per-row menu — **play, rename, duplicate, delete** — and a button to make a new one. Long-press tracks and add the whole selection at once; adding a selection is **one write** for the batch rather than one per track.
- **Drag to reorder** with the handle on the right of a row (shown only in a playlist view with no active search, where row position maps 1:1 to stored order); the order is saved when you drop it.
- **Opens where you left off** — the library reopens on the last collection you viewed (All tracks or a specific playlist), falling back to All tracks if that playlist was deleted.
- Playlists are stored as lists of MediaStore ids in `SharedPreferences`, so they cost almost nothing and survive files moving; a track that has since been deleted is **skipped** when the playlist is shown, not pruned, so it returns if the file (or SD card) reappears.

### Multi-selection
**Long-press any row to start selecting**, then tap to add more. The contextual bar offers **play**, **add to queue**, **add to playlist**, **rate**, **share**, **remove from this playlist**, and **select all**.

It works on **groups** as well as tracks: long-press an album, folder or genre and everything inside it comes with the selection, without opening it first. Selection is held by **track id rather than row position**, so it survives a search keystroke or a playlist edit — positions would not. The reorder handles hide while a selection is running, because dragging and selecting are otherwise two gestures fighting over the same row.

### Sharing a track out
The counterpart to opening one in. **Share** from the Now Playing top bar sends the playing track;
long-press in the library and the contextual bar shares a whole selection — including a selection
made by long-pressing an album, folder or genre.

**The file itself travels, and its metadata travels with it.** `EXTRA_STREAM` carries the audio,
with a read grant so the receiving app can open it whether or not it holds media permission of its
own; title, artist, album, genre, duration, size and your own rating are written into the share
text, because a `content://` uri carries none of that and what arrives otherwise is a filename —
often a track number and nothing else. Nothing is re-encoded and no tag is rewritten: the file that
leaves is byte-for-byte the file on disk.

Empty fields are left out rather than written as "Unknown", and the share is typed as precisely as
the extension allows (`audio/mpeg`, not the wildcard) because some receivers filter on the concrete
type. A selection of mixed formats falls back to the generic type rather than misdescribe itself.

### The local API
**Lull can be driven by a script or an agent** — everything you can do with a finger, over HTTP on
this device. Turn it on under **Local API** in the overflow menu; Lull generates a key, shows the
address it is listening on, and that is the whole setup.

```bash
KEY=<copied from the app>          # adb forward tcp:8787 tcp:8787, from a laptop
curl -s -H "X-API-Key: $KEY" 127.0.0.1:8787/api/docs        # the full reference
curl -s -H "X-API-Key: $KEY" "127.0.0.1:8787/api/tracks?q=rain"
curl -s -H "X-API-Key: $KEY" -X POST 127.0.0.1:8787/api/player/play -d '{"group":"album:42"}'
curl -s -H "X-API-Key: $KEY" -X PATCH 127.0.0.1:8787/api/player -d '{"repeat":"one"}'
curl -s -H "X-API-Key: $KEY" -X POST 127.0.0.1:8787/api/player/sleep -d '{"minutes":45}'
```

| | |
|---|---|
| **Library** | every track with search and paging, the six browse axes and the tracks in any group, one track's metadata, **its file**, and its artwork |
| **Playback** | play (ids, a group, or resume), pause, next, previous, stop, seek, append to the queue, repeat, shuffle *including Favourites*, device volume |
| **Ratings** | read them all, set one, or rate a batch in one write — `0` clears, exactly as tapping the star a track already sits on does |
| **Playlists** | create, read, rename, reorder, duplicate, delete, add and remove tracks |
| **Settings** | theme, crossfade, trim silence, mix-with-other-audio, volume style, Force Tempo |
| **Tempo** | read a track's BPM and the state of it (known / unknown / none), or override one that came out wrong; list the dead spots inside a track |
| **Sleep timer · A-B** | arm and cancel; set and clear a loop region |
| **Share** | pop the real chooser on the device, or take the file and the text yourself |

**The rules it is built to.**

- **Off until you turn it on**, and stopping it is one switch (or the Stop action on its own
  notification). It runs in its **own foreground service**, separate from playback, because the
  request an agent most obviously wants to make is "start playing something" — which is exactly
  when `PlaybackService` does not exist. The notification names the address, because a socket that
  is open should never be a thing you have to go looking for.
- **Bound to `127.0.0.1`** unless you switch on LAN access, which says plainly what it changes.
  From a laptop, `adb forward` is the intended route and needs no LAN access at all.
- **The key is generated, never typed**, and compared in constant time. Anything but
  `GET /api/health` without it is a `401`. You can issue a new one at any moment, which instantly
  invalidates the old.
- **Everything it can do, the UI can do.** It is a second pair of hands, not a second set of
  features — so playing a list honours your saved shuffle mode, a group key means what it means in
  the Browse tabs, and a playlist an agent creates appears on the library screen while you watch.
- **`GET /api/docs` serves the whole reference** to whoever holds the key, so an agent needs the
  port and nothing else — and the reference cannot drift from the build answering it.
- **Sharing, for something that has no hands.** `POST /api/share` starts the real chooser and then
  tells you whether anyone can *see* it: Android discards a dialog thrown by an app that is not on
  screen, so a response saying `"visible": false` is the honest answer, and the share text and the
  file urls come back either way. The direct route is `GET /api/tracks/{id}/file` — the audio,
  streamed and named.

### Sleep timer
**Play for 5–90 minutes, fade out, then pause** — the other half of falling asleep to something, alongside the safe repeat‑one below.

- Set it from the **moon button** on Now Playing, or from **Sleep timer** in the library's overflow menu. While it's running the Now Playing title bar counts it down (`Sleep in 24:31`) and the menu entry shows the time left.
- The last **30 seconds** are a **raised-cosine fade** to silence. That curve is flat at both ends, so the fade eases in without an audible step and settles onto silence instead of arriving at it still dropping — on a track you're falling asleep to, the moment a fade visibly *begins* is as disruptive as the moment it ends. On a short timer the fade is capped at half the total.
- It ends on **pause**, not stop, so the queue and your place in it survive — one tap to carry on.
- The countdown runs in `PlaybackService`, so it survives closing the app, and it's measured against `elapsedRealtime` so it counts **through device sleep** rather than stopping with the CPU. A running timer isn't persisted across a restart: restoring a countdown would be a promise about a device that was switched off.
- It composes with crossfade rather than fighting it — the two contribute independent gains that are multiplied in one place, so a fade-out landing mid-transition dims the pair together. A crossfade that *wouldn't finish* before the deadline is skipped outright, so the track you hear last isn't one you never chose to end on.

### Safe repeat‑one (what Lull was originally written for)
- With **Repeat One** active, **Next → restart** and **Previous → restart** the current track.
- Works for every control surface: in‑app buttons, the media notification, the lock screen, and **hardware / Bluetooth media buttons** (headsets, sleep headbands).
- The skip buttons stay enabled even on a single‑track queue, so they're tappable‑to‑restart.
- Off / All repeat modes skip tracks normally. Your repeat & shuffle choices are **remembered**, so a white‑noise loop comes back the way you left it.

### A-B loop
- Mark **A** and **B** in a track and loop the region between them, driven from the service so it survives closing the UI. Re-reads the real playback position each pass, so seeking or pausing inside the region doesn't desync it.

### Background playback
- Built on a Media3 **`MediaSessionService`** foreground service: plays with the screen off or the app closed, shows a media notification with controls, handles audio focus, and pauses on headphone unplug.
- **Mix with other audio** (on by default): keeps playing without grabbing audio focus, so another app's video or notification doesn't stop your music.

### Opening audio from a file manager
Lull registers for **`ACTION_VIEW` on audio** — `audio/*`, plus the `application/ogg` and `application/flac` spellings some providers still use, over `content://` and `file://` — so it appears in **Open with** for `.mp3`, `.wav`, `.m4a`, `.flac`, `.ogg`, `.opus` and the rest. There is a second, extension-matched filter for senders that hand over a bare uri with no type at all, and Lull accepts a **share** (`SEND` / `SEND_MULTIPLE`) as well.

Opening one track gives you **its whole folder**, so Next pages through it. Three routes, tried in order:

1. **The `ClipData` the launching app attached.** A file manager already knows the folder; when it attaches the siblings, the read grant on the intent covers every one of them. No permission, no lookup — and it is the only route that works for a `.nomedia` folder. (Sift does this for audio; other file managers would have to adopt the same convention.)
2. **The folder, out of Lull's own library** — resolve the opened file to a real path, then take every track the library holds from that directory.
3. **The single file**, which always works.

Route 2's path resolution is the awkward part: a file manager typically hands over its own `content://<their.app>.fileprovider/…` uri, which answers neither a MediaStore id nor a `DATA` column. The descriptor it opens still points at the real file, though, and `/proc/self/fd/N` is a symlink to it — so the folder is recoverable even when the uri says nothing. (Same problem, same fix as in Loopr.)

**A launch from another app never waits on media permission.** Without it there is nothing to load and the intent's own grant carries the playback; with it, the library load is awaited first so the folder can be built.

### Volume knob *or* bar
- The Now Playing screen offers a volume **slider bar** or a **circular knob** (drag around the dial) — switch with the tune button; your choice is saved. Both drive the device media volume, staying in sync with the hardware buttons.

### Library & playback
- Material 3 list of all device audio with album/embedded artwork, via **MediaStore**.
- **What counts as "your audio"**: everything except the system's own noises — ringtones, alarms and notification sounds are excluded, and music, podcasts, **audiobooks** and voice recordings are all listed. Filtering on MediaStore's `IS_MUSIC` instead would be the obvious thing and is wrong: an audiobook has `IS_MUSIC = 0`, so on a device that is mostly spoken word it hides most of the library.
- Tap‑to‑expand **mini‑player** plus a full **Now Playing** screen: artwork, scrub bar, shuffle, repeat cycle, previous / play / next.
- **Light / dark / follow‑system** themes — **defaults to dark**.

**No analytics, ads, or outbound network access** — Lull talks to no server, and there is nothing
in it that could. Since 1.9 the manifest does hold `INTERNET`, because the **local API** above
cannot open a listening socket without it; that socket is off until you switch it on and binds
`127.0.0.1` unless you say otherwise. Nothing ever leaves the device unless you send it.

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
| **Force tempo** | Off / on, with a target BPM (60–200), a stretch ceiling, a never‑slow‑down switch, and pushing through quiet parts | Off |
| **Local API** | Off / on, with a generated key, a port and a LAN switch | Off |

Repeat, shuffle (off / shuffle / **favourites**) and the volume bar/knob choice are set on the **Now Playing** screen, along with the **star rating** for the playing track; the **A-B loop** buttons, the **sleep timer** (moon) and **share** are there too. The sleep timer is the one entry that isn't a persistent setting — a running countdown is intentionally dropped on restart, and only the duration you last picked is remembered.

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

## Under the hood: how safe repeat‑one works

The feature Lull started life as, and still the neatest piece of it. The key part is [`RepeatAwarePlayer`](app/src/main/java/com/lull/player/RepeatAwarePlayer.kt), a Media3 `ForwardingPlayer` that wraps ExoPlayer **inside the media session**. Because it sits at the session/player layer, it intercepts skips from *every* origin — UI, notification, lock screen, and AVRCP/hardware media buttons:

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

### Tests
```bash
./gradlew testDebugUnitTest      # 171 tests
```

Four things are tested, and they are the four where being nearly right is invisible from the UI:

- **`Shuffle`** makes a claim about *probability*, which one run can neither confirm nor refute — so
  the distribution is checked over thousands of draws from a fixed seed.
- **The API** — request parsing, routing, auth and every error shape — runs against a fake backend
  with no Android in it. One test walks the route list `/api/docs` advertises and fails if any of it
  is unrouted, so the reference cannot drift from the build.
- **The share text and the formatters**, because that text is the only thing the person on the
  other end of a share actually reads.
- **Force Tempo** — both halves. The arithmetic has exactly right answers and gets them checked;
  the **estimator** is run against generated click tracks at known tempos, which is the only way to
  catch the failure that matters, because it is not a small error but an **octave**. Two of those
  tests exist because they failed when they were written: a whole-frame lag search reads a 160 BPM
  track as 80, and a uniform 200 BPM rhythm reads as 100 unless the tie-break prefers the beat over
  the bar.
- **Dead-spot detection**, where most of the tests are *false positives* — the build-up before a
  drop, a quietly mastered track, a passage hovering on the threshold. Speeding up in the wrong
  place is not a crash; it is a track that lurches on a phone in the middle of a run, and only a
  test says so before you do.

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
├── MainActivity.kt        # library: seven browse tabs, drill-down, multi-select, mini-player, playlists
├── MediaLibrary.kt        # the MediaStore load, with folder + genre columns
├── Browse.kt              # the browse axes and how the library is grouped into them
├── GroupAdapter.kt        # rows of folders / artists / albums / genres / playlists
├── OpenIntent.kt          # launches from another app: ClipData, folder resolution, single file
├── NowPlayingActivity.kt  # full controls: scrub, A-B loop, shuffle, repeat, volume bar/knob
├── PlaybackService.kt     # MediaSessionService — background playback, crossfade, A-B, trim silence, Force Tempo
├── RepeatAwarePlayer.kt   # makes next/prev restart the track when repeat-one is on
├── AbLoop.kt              # the A-B marker pair, shared between the service and the UI
├── SleepTimer.kt          # sleep-timer deadline + fade gain, shared the same way
├── SleepTimerDialog.kt    # the duration picker, shared by both screens
├── PlaylistStore.kt       # playlists (create/rename/delete/add/remove/reorder) + last-viewed collection
├── RatingStore.kt         # 1-5 star ratings by track id, with an in-memory cache
├── Shuffle.kt             # the weighted draw behind Favourites shuffle
├── Tempo.kt               # Force Tempo's arithmetic: source BPM + target -> playback speed
├── BpmAnalysis.kt         # onset envelope + autocorrelation, and the octave decisions
├── EnergyProfile.kt       # where a track goes quiet, and which stretches are worth pushing through
├── TrackAnalysis.kt       # one decode, two answers: the tempo and the energy over time
├── ProfileStore.kt        # each track's loudness curve — the curve, not the conclusion
├── Id3Bpm.kt              # the TBPM frame, where the file already knows its tempo
├── TempoStore.kt          # a tempo per track, including "analysed, hasn't got one"
├── Sharing.kt             # sharing a track out: the file, and the metadata that travels with it
├── Format.kt              # durations and sizes, the conversions with exact right answers
├── Changes.kt             # "something (usually the API) changed what the library should show"
├── VolumeKnobView.kt      # custom circular volume knob
├── ArtLoader.kt           # async artwork loading + LRU cache
├── TrackAdapter.kt        # RecyclerView list adapter
├── AudioItem.kt           # audio model + MediaItem mapping
├── Prefs.kt               # repeat, shuffle, volume style, crossfade, mix-audio, trim-silence, Force Tempo
└── ThemeManager.kt        # light/dark/system theme

app/src/main/java/com/lull/player/api/      # the local API — off unless you switch it on
├── ApiRouter.kt           # which path means what, and which failures are the caller's fault
├── ApiBackend.kt          # everything the API can do, with no Android type in sight
├── LullBackend.kt         # ...wired to the real library, player and stores
├── Http.kt                # just enough HTTP/1.1 to serve it, and no more
├── ApiServer.kt           # the socket: accept, read one request, answer, close
├── ApiService.kt          # the foreground service that keeps it listening
├── ApiDocs.kt             # the reference served at GET /api/docs
├── ApiPrefs.kt            # enabled, key, port, LAN
├── ApiSettingsDialog.kt   # the panel it is switched on from
├── MainThread.kt          # hop to the main thread for the calls that insist on it
└── BootReceiver.kt        # puts it back after a reboot, if it was on
```

---

## Roadmap ideas
- Per‑track resume position
- Sensitivity control for trim silence (how quiet, and for how long, counts as a gap)
- Exporting ratings, playlists and tempos

---

## License

MIT — see [LICENSE](LICENSE).
