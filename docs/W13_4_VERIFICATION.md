# W13.4 — manual video-quality selection

Quality selection on Android TV, end to end: what the menu offers, what choosing an option does to the
player's track selection, and what the chip claims afterwards.

- Baseline: `6b9d0ee`
- Device: Mi Box 4 (MIBOX4), Android 12 / API 31, `adb connect 172.16.1.2:5555`
- New unit tests: `QualitySelectionTest` 11/11
- Physical evidence: Big Buck Bunny (`aqz-KE-bpKQ`, 6 renditions) approved through the parent API for the
  probe, then removed again, exactly as the harness's opt-in probe does

## What the audit found

Traced from `buildOptions(PlayerMenu.QUALITY)` through `selectMenuOption` to `applyQuality`. The quality
path has **two** implementations, and each is self-consistent by construction:

- **Multi-track (DASH)**: `PlayerMedia.mediaSource` returns a `DashMediaSource`, so the item exposes several
  real video tracks. The menu is built from `player.currentTracks` (the tracks that exist) and a choice is a
  `TrackSelectionOverride` on the chosen rendition.
- **Progressive**: the same function returns one `ProgressiveMediaSource`, so there is exactly one video
  track and `adaptiveVideoGroup()` is null. The menu is built from the resolver's renditions and a choice
  reopens the stream at that rendition, keeping the playhead.

So the previously recorded concern - "the menu uses `currentTracks` while the persisted-preference guard
uses `media.qualities`" - is **not** a defect: each branch reads the list that describes its own path, and
the guard only drops a preference the new item cannot offer. Verified in the current source, not assumed.

The defects that *are* real, all with a requirement attached:

1. **Automatic silently took the subtitles with it.** `builder.clearOverrides()` clears *every* override, and
   a pinned caption language lives in the same map (set by `applyCaptionSelection`). Choosing Automatic
   therefore dropped a subtitle or audio choice: a quality control that changes more than quality.
2. **Automatic looked unselected whenever anything was pinned.** The Auto option's state was
   `overrides.isEmpty()`, so a pinned subtitle made Auto appear off while Auto was exactly what was playing.
3. **Nothing reported what was being rendered.** The chip repeated the *request*, so a pinned rendition the
   live stream could not honour was presented as applied. There was no distinction between requested,
   selected and rendered quality anywhere - not in the UI, not in the log.
4. **A quality the stream could not honour failed silently** (an early `return` with no log), so the child
   got no answer at all.
5. **A menu could outlive its video.** `prepare()` only replaced the menu when a resume point existed, so a
   quality menu left open across a queue move kept offering the previous item's choices (reachable by
   pressing NEXT while the menu is up).

Confirmed as **not** defective: audio preservation on the progressive path (`forcedAudioTrackId` is reused
in `prepare`), caption preservation on the progressive path (`applyCaptionSelection` is re-applied 600 ms
after every prepare), and position preservation (`applyQuality` passes `player.currentPosition`).

## What changed

- `applyQuality`: Automatic now calls `clearOverridesOfType(C.TRACK_TYPE_VIDEO)` - the video pin is released
  and nothing else is. An unavailable rendition logs why instead of returning silently, on both paths.
- `videoOverridePresent()`: Automatic's selected state asks whether a *video* track is overridden, not
  whether the override map is empty.
- `renderedQualityHeight` + `qualityDisplayLabel`: the decoder's own `onVideoSizeChanged` feeds a rendered
  height, the chip says `720p (showing 540p)` when the request and the picture disagree, and Automatic names
  the measured rendition instead of a bare "Auto". Every change is logged (`Rendered video: 1920x1080
  (1080p)`), which is what makes an unhonoured pin visible on a device.
- `closeMenuForNewItem()`: a new item drops any menu that described the previous one, without the
  resume-offer side effect.
- The FULL-tier probe's `quality-switch-applied` no longer accepts `Player menu QUALITY -> h480` as proof
  that anything was applied; it now requires the engine's own evidence (the rendition the stream reopened
  with, or the track it was pinned to), and a new `quality-render-measured` records the decoder's size.

## Evidence

Unit tests (`QualitySelectionTest`, 11 tests) assert the player's real `TrackSelectionParameters` and the
controller's state, never a label: real heights from the track group, the pinned track index, the subtitle
pin surviving Automatic, Automatic's selected state, an unavailable rendition not being pinned or claimed,
the request-versus-rendered chip, no reopen and no playhead move on the multi-track path, a reopen at the
same position on the progressive path, and a queue move clearing the old menu.

They are not vacuous: reintroducing the two original defects made exactly the two matching tests fail
(`automatic releases the video pin and keeps the subtitle pin`, `automatic reads as selected when only a
subtitle is pinned`), and restoring the fix returned 11/11.

On the Mi Box, with the remote only:

```text
Menu opened: QUALITY (7 options)
menu: ✓  Auto | 1080p | 720p | 480p | 360p | 240p | 144p     <- the item's real renditions
Player menu QUALITY -> h1080
Playing aqz-KE-bpKQ (progressive, 6 renditions, 0 caption tracks) - quality 1080p
Rendered video: 1920x1080 (1080p)                            <- decoder-measured, not inferred
position 84s -> 90s, playing=True                            <- no restart, no lost place
reopened chip: Quality: 1080p                                 <- the selection is identifiable
Player menu QUALITY -> auto
Auto quality: 360p chosen from 360p=343kbps with 23182 kbps measured
Rendered video: 640x360 (360p)                               <- restriction released, Auto re-chose
Captions selection: en ... (after a quality reopen) Captions selection: en
chip afterwards: Subtitles: English | Quality: Auto (360p)   <- the subtitle survived the switch
```

## Regression run

PLAYER tier on this build (-SkipBuild, the installed APK is the one built from this source):
**49 checks, 49 PASS, 0 failures**, `FINAL: PASS` - including every W13.2.1 transport-focus check with
unchanged focus bounds and the W13.3 focus-indicator behaviour, the subtitles/captions/speed/aspect menus,
the transport single-action checks and the catalogue projection.

## Limitations and follow-ups

- **The DASH/override path has no physical source in this library.** The probe video resolves to a
  progressive stream ("progressive, 6 renditions"), so the physical quality switch exercised the reopen
  path; the override path is covered by the unit tests, which assert the override directly. A device that
  can produce an MPD would let the same checks run against `TrackSelectionOverride`.
- Two of my verification passes were inconclusive because of *my* scripted key timing, not the app: the menu
  closes itself after 8 s of no input, so a dump inside that window costs the choice, and the settings row
  stays active after a menu closes, so a later DOWN descends into the transport row. The app log showed the
  app doing exactly what its design says in both cases.
- The `quality-render-measured` and strengthened `quality-switch-applied` assertions live in the FULL tier's
  opt-in probe, which was **not** run in this phase (it needs a temporarily approved multi-rendition video);
  their logic was validated against the real log lines captured here instead.
- Device left as found: `screensaver_enabled=1`; the probe source was removed from the approved library.
