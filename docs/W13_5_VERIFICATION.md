# W13.5 — captions, seeking and resume reliability

Audit, fixes and verification for the three player paths a child feels most: subtitles, seeking, and being
put back where they left off.

- Baseline: `90b7fe1`
- Device: Mi Box 4 (MIBOX4), Android 12 / API 31, `adb connect 172.16.1.2:5555`
- New tests: `SeekResumeCaptionTest` 13/13

## What the audit found

Traced end to end rather than from labels: `PlaybackKeys`/`seekBy`/`savePosition`, the periodic save loop,
`prepare()`'s resume load, `applyCaptionSelection`, and the caption menu's options.

**Confirmed defects**

1. **A seek was never persisted.** `seekBy` moved the playhead and returned. Rows were written only on pause,
   on `release()` and by a 10-second timer, so a seek followed by the app dying - or the TV losing power -
   restored the position from before the seek, which can be minutes away. This is the previously recorded
   concern, confirmed.
2. **Saves could land out of order.** `savePosition()` captured the position synchronously but wrote from
   `scope.launch(Dispatchers.IO)`, so an older capture (the timer's) could overwrite a newer one (a pause or
   a seek) and offer the child a playhead they had already left.
3. **A queue move could write one item's playhead into another item's row.** `prepare()` assigns
   `currentVideoId = videoId` *before* `resolveMedia()` and the media-source swap. A save in that window
   stored the **new** id with the **previous** item's position - and resolution is a network call, so the
   window is seconds wide, not microseconds.
4. **The chosen caption language pinned the wrong track.** `applyCaptionSelection` used
   `TrackSelectionOverride(group, 0)` - track 0 of the first group that merely *contained* a match - so with
   two languages in one group, choosing the second played the first. Nothing logged which track was applied,
   so "chose a language" and "applied a track" were indistinguishable.
5. **A caption pin could be missed entirely.** It was applied only by a fixed `delay(600)` after prepare; if
   the text tracks arrived later, no override was set and the child was left with a chosen language and no
   subtitles.
6. **A paused seek could change quality behind the child's back.** `stepDownAfterStall` had no
   `playWhenReady` guard, so a seek while paused that sat in BUFFERING for 8s stepped Auto quality down and
   reopened the stream - a restart in answer to a key press that asked for none.

**Verified as not defective** (so nothing was changed): seek clamping (`0..duration`, `TIME_UNSET` handled)
and accumulation (each press reads the live position); seeking while paused does not resume playback;
reaching the very end advancing the queue (that is the end-of-video design); `PlaybackCommandBus` being the
remote-command path rather than the D-pad seek path, so it cannot duplicate seeks; the caption *disable*
path; a video with no text tracks showing "No subtitles for this video"; and resume load refusing a position
at or past 95% of the duration.

## What changed

- `savePosition(positionMs: Long? = null)` writes through a single-threaded dispatcher
  (`Dispatchers.IO.limitedParallelism(1)`), so saves land in the order they were captured.
- `seekBy` persists the position it just asked for.
- A new `playerVideoId` records the item the **player** holds - set immediately after `setMediaSource` - and
  resume rows are written against that, never against the item being resolved.
- A position of zero is persisted once an item has actually started, so rewinding to the beginning replaces
  the older row instead of leaving a stale "resume from 5:00" behind.
- `applyCaptionSelection` pins the *matching* track, logs the track it applied
  (`Captions selection: en (track 0 of 1: English)`), and is idempotent so it can also run from
  `onTracksChanged` - which is what applies a caption when the text tracks arrive after prepare.
- `stepDownAfterStall` returns early when the player is not going to play.

## Tests (`SeekResumeCaptionTest`, 13 tests)

Assertions are on persisted rows, `TrackSelectionParameters` and the calls the controller makes on the
player - never on labels or highlights: a seek persisted when it happens; repeated seeks leaving the last
one stored; a paused seek neither resuming nor losing the place; clamping at both bounds; rewinding to zero
replacing the older row; a save during a queue move not contaminating the next item's row; restoration of a
stored position (proved by the player being moved to it, not by a label); a finished video not being offered
as resumable; the chosen language pinning *its* track index; captions off/on; an absent language neither
pinned nor claimed; a caption surviving a quality reopen; and a new item without that language falling back
to off with no stale language on offer.

They are not vacuous. Reintroducing the three original defects failed 8 of 13. Reverting **only** the
cross-item fix failed exactly one, with the defect as the message:

```text
a save during a queue move cannot write one item's playhead into another item's row
  and vid-b must not inherit it expected null, but was:
  PlaybackPositionEntity(videoId=vid-b, positionMs=100000, durationMs=600000, ...)
```

## On the Mi Box (remote only, approved content)

```text
Playing n0X6zXho8Tw (progressive, 1 renditions, 1 caption tracks) - quality Auto (360p)
Menu opened: CAPTIONS (2 options)          dump: Subtitles | Off | ✓ English (auto)
Player menu CAPTIONS -> cap:en             Player menu CAPTIONS -> off
Player menu QUALITY -> h360                position 44s -> 57s, playing=True, chip Subtitles: English
rewind 1..4  seeks=[-10s -> 56s | -10s -> 47s | -10s -> 37s | -10s -> 28s]
forward 1..2 seeks=[10s -> 41s | 10s -> 52s]                        playing=True throughout
paused seek: playing=False -> Seek 10s -> 164s -> playing=False
kill inside the periodic window, then reopen: Resumable position for n0X6zXho8Tw: 52s   (52s = the seek's own target)
after NEXT the app played 3hnObEUXIXI; kill; reopen; resume evidence: []  -> nothing inherited
```

The caption line `Captions selection: <tag> (track i of n: <label>)` is the engine-side evidence; it appears
only when the selection actually changes, which is why it is not repeated on every reopen.

## Limitations

- **This library exposes one caption track per video** ("1 caption tracks"), so multi-language selection
  could not be verified physically: the device run covers enable/disable/selection and preservation with a
  single track, and the wrong-track defect (choosing the second language) is covered by the unit test that
  pins track index 1. No second language was invented to make the device test look broader.
- Rendering of caption *cues* was not pixel-verified; the evidence is the engine's applied selection plus the
  harness's existing `captions-enable` check (the fixture's video has one auto-generated English track).
- The seek-persistence test on the device cannot fully separate "my immediate save" from a periodic save that
  happened to fire after the seek in the same second; the *discriminating* evidence is the unit test, which
  asserts the row with no timer in the window.
- The exact end of a video still advances the approved queue (by design); the device pass therefore stayed
  clear of the final seconds rather than treating that as a defect.
- Device left as found: `screensaver_enabled=1`, and resume rows cleared for the fixture video afterwards.
