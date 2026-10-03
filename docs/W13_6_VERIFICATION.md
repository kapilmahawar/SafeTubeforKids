# W13.6 — advanced adaptive video quality

What Automatic quality actually does, measured rather than assumed.

- Baseline: `8e243f4`
- Device: Mi Box 4 (MIBOX4), Android 12 / API 31, `adb connect 172.16.1.2:5555`
- New tests: `AdaptiveQualityTest` 8/8 (45 s - two of them wait on the app's own timers)

## The two paths, and which one the app actually plays

`PlayerMedia.mediaSource` builds either a `DashMediaSource` (multi-track) or one
`ProgressiveMediaSource`. `VideoResolver` asks NewPipe for a DASH manifest (`extractor.dashMpdUrl`) and falls
back to progressive renditions when there is none.

**Every source available on this device resolves progressive**, verified from the app's own log:

```text
Playing n0X6zXho8Tw (progressive, 1 renditions, 1 caption tracks) - quality Auto (360p)   <- a kids video
Playing aqz-KE-bpKQ (progressive, 6 renditions, 0 caption tracks) - quality Auto (360p)   <- the 4K probe
```

So on this hardware Automatic is the app's own chooser over progressive renditions, and *DASH adaptive
track switching cannot be exercised*: when a DASH source is built, ExoPlayer adapts internally and
`AutoQuality.choose`'s result is not used for the source at all (it only feeds the label and the stall
bookkeeping, both of which are inert on that path because `stepDownAfterStall` returns early for it). That
is the code as written, and it is why the DASH path is reported BLOCKED rather than "verified".

## Audit result: no defect found in the policy or its wiring

Read end to end, and every phase question has an answer in the code rather than a gap:

- **Automatic releases only the video constraint** (`clearOverridesOfType(C.TRACK_TYPE_VIDEO)`, W13.4) and
  the chooser is invoked on every load for progressive media.
- **A manual pin is authoritative**: `applyQuality` sets `autoQuality = false`, and both
  `stepDownAfterStall` and `maybeStepUpQuality` return immediately while it is false. A stall cannot
  override a pin - there is no product rule that permits it, and none is needed.
- **Nothing changes while paused**: the step-up loop requires `player.isPlaying` and the stall path
  requires `player.playWhenReady` (W13.5).
- **The chooser is bounded**: at most `MAX_AUTO_STEP_DOWNS` (2) downgrades and `MAX_STEP_UPS` (2) upgrades
  per video, upgrades need 30 s of clean playback *and* a stricter 50 % budget while the opening choice
  spends 70 %, so adjacent-rendition flapping is bounded by design rather than by hysteresis timing.
- **Not resetting the recovery clock on an ordinary buffering top-up is deliberate**, and says so in the
  code: "an ordinary mid-stream top-up must not keep resetting it, or Auto would never climb back". This
  was the one place that looked like a defect until the comment was read; it is expected behaviour, so it
  was left alone.
- **Bandwidth** comes from ExoPlayer's own estimate, with `BandwidthOverride` as the debug seam.
  **Buffered duration and dropped frames are not used at all**, and a bandwidth drop only acts through a
  stall: the app does not downgrade on a measurement alone. Both are documented limitations, not defects -
  the design intent is that continuity is preserved until the stream actually struggles.

## What changed

One fix, from the audit's "stale data from an old video" question: `prepare()` now clears
`renderedQualityHeight` for a new item. Left behind, the quality chip reported the previous video's picture
for the new one - `720p (showing 1080p)` - before it had rendered anything. The non-vacuity check confirms
exactly that failure without the fix.

## Tests (`AdaptiveQualityTest`, 8)

Asserts what the app asks the engine to play (the media source's URI), how many times it reopens the
stream, and the state it reports - not that a chooser ran:

- Automatic plays a rendition the item has, chosen from the measurement (900 kbps → 360p, and the engine is
  handed the 360p stream);
- the same item gets a better rendition from a better measurement (50 Mbps → 1080p stream);
- a manual pin is not replaced by the chooser (50 Mbps for 6.5 s changes nothing);
- an 8-second stall does not override a manual pin;
- a paused player is never stepped up (and is not resumed);
- nothing measured means no upgrade;
- one item's rendered size does not leak into the next item's chip;
- a quality change keeps the chosen audio track, the caption pin and the playhead.

Reintroducing the stale-size defect failed exactly that one test, with the defect as its message
(`expected:<720p> but was:<720p (showing 540p)>`).

Playback package totals after the change: **94 tests, 0 failures** - including W13.4's 11 and W13.5's 13.

Note for anyone extending these: `prepare()` sets the title *before* it hands the source to the player, so
waiting on the title alone reads quality state before the load has happened. The first version of these
tests did exactly that and produced three failures that were the tests' fault, not the app's; the diagnostic
run that separated them is described in the commit.

## On the Mi Box

```text
Auto quality: 360p chosen from 1080p=6933kbps, 720p=4206kbps, 480p=1098kbps, 360p=359kbps,
              240p=245kbps, 144p=111kbps with 900 kbps measured
Playing aqz-KE-bpKQ (progressive, 6 renditions, 0 caption tracks) - quality Auto (360p)
Rendered video: 640x360 (360p)

~18s after the measurement is raised to 50 Mbps:
Auto quality: stalled at 360p (50000 kbps measured) - stepping down to 240p
Playing aqz-KE-bpKQ (...) - quality Auto (240p)          Rendered video: 426x240 (240p)

paused, at 300 Mbps measured, for 14 s: 0 quality-driven player lines, playing=False
```

- The injected measurement determines the rendition, with the arithmetic in the log, and the **decoder**
  confirms what is on screen.
- The downgrade path is genuinely adaptive and was reproduced twice: a real stall (the player spent 8 s
  buffering) reopened the stream one rendition lower and the decoder dropped to 426x240. The measured
  bandwidth in that line is the *override*, which does not throttle the network - the stall is the real
  connection, which is exactly what the phase wanted to see.
- **The upgrade path could not be demonstrated on the device**: the stream stalls inside the 30 s clean
  window (twice, at ~18 s and ~24 s), and by design that resets the clock before a climb is earned. It is
  covered deterministically by the unit tests instead.
- A manual pin on the probe video was **not** re-verified physically this phase: the key sequence for the
  quality chip ended the one-video queue and left the player. W13.4 verified the manual selection on this
  same probe (`Player menu QUALITY -> h1080` → `rendered 1920x1080`), and the "adaptive must not replace a
  pin" behaviour is asserted by two of the new tests.

## Cleanup and limitations

- The temporary probe source had been left approved in the library since W13.4 (that phase's cleanup
  checked the video id, not the source id, and so reported success wrongly). It has now been deleted, and
  the remaining sources are the kid's own five.
- `BandwidthOverride` is cleared (`{"bandwidthKbps":null}`) and `screensaver_enabled` is back to 1.
- Not measurable on this hardware/source: DASH adaptive track switching, the upgrade path (blocked by real
  stalls), and any input from buffered duration or dropped frames (not implemented).
- Not run, per scope: W6, FULL and EXAMPLE tiers. Nothing here crosses into authorization, catalog or queue
  logic.
