# W13.3 — player UI polish and focus quality

Audit and focused fixes for the SafeTube for Kids Android TV player UI. No playback behaviour, no menus and
no layout were redesigned: the defect found was in the *focus indicator*, and it was measured on the TV.

- Baseline: `3b6fdbe`
- Device: Mi Box 4 (MIBOX4), Android 12 / API 31, `adb connect 172.16.1.2:5555`
- PLAYER tier on the fixed build: `test-results/tv/2026-10-03-005137` — **49 checks, 49 PASS, 0 failures**
- Player unit tests: `SeekStepTest` 4/4, `TransportControlLabelsTest` 3/3, `FocusIndicatorContrastTest` 4/4

## What the audit found

Measured on screenshots of the paused player (System.Drawing sampling, 90 points per ring at the radii the
layout puts the ring at), not judged by eye:

| control | ring RGB | fill RGB | ring ↔ fill |
|---|---|---|---|
| Play/Pause | 34,165,89 | 34,165,89 | **1.00:1 — identical pixels** |
| Forward 10s | 19,108,67 | 29,90,65 | 1.12:1 |
| Next | 26,95,49 | 23,80,36 | 1.16:1 |
| Rewind 10s | 30,93,58 | 43,69,51 | 1.20:1 |
| Previous | 25,91,56 | 20,58,36 | 1.43:1 |

1. **The focus ring was invisible on Play/Pause, and far too weak on the other four.** The ring was drawn
   *inside* the disc as `Modifier.border(3.dp, KidFocusRing)` where `KidFocusRing` is the accent at 60%.
   Over the primary control's opaque `KidAccent` disc that composites to the disc's own colour - α·C +
   (1−α)·C = C - so focusing the most-used control changed nothing at all on screen, and the primary is
   accent-filled whether focused or not, so it also looked "selected" while the remote was elsewhere.
2. **The error screen had no usable focus cue either.** Its two actions were stock Material buttons
   differing only by a tonal elevation overlay: the focused Retry measured (56,173,105) against the
   unfocused Back at (34,165,89), i.e. **1.11:1** - a child on the "This video can't be played" screen
   could not tell which of the two OK would press.

Deliberately **not** changed, because no defect was demonstrated there: the transport row's spacing and
alignment (uniform 20px gaps, the primary disc 112px vs 96px and centred - consistent, not a defect), the
menu chips and menu options (accent fill + black text is unambiguous and already means "where you are"),
the seek bar, the top bar, and the library's card rings (a different screen, and their focus state is not
only a ring).

## What changed

- `Color.kt`: `KidFocusRingBright` (stone-100) and `KidFocusRingHalo` (stone-900 at 80%), with the reason
  recorded next to them - a ring that must be visible *on* the accent cannot be the accent.
- `TvPlayerScreen.kt`, `TransportButton`: the indicator is now a bright ring over a dark halo drawn
  **around** the disc, never on it. It is sized with `requiredSize` so it overflows the focusable node:
  the discs, the spacing and every focus target stay exactly where they were (the dumps still report
  `[112,932][208,1028]` … `[592,932][688,1028]`), and the measured disc pixels are unchanged
  (mean |ΔRGB| = 0.00 over 6349 disc pixels, focused vs unfocused on the same paused frame).
- `TvPlayerScreen.kt`, error screen: focus is the accent fill, the language the menu chips already use;
  unfocused is `KidSurface`.
- `FocusIndicatorContrastTest`: pins the invariants - the old ring composites to the accent disc's own
  colour (contrast 1.0), the bright ring clears 2.5:1 against the accent disc and 3:1 against its halo, and
  the focused action clears 3:1 against the unfocused one. A device test cannot assert pixels; these are
  the same properties, computed from the colours the player draws with.

## Verification on the Mi Box

After the fix, every control shows a `245,245,244` ring outside its disc:

| control | ring ↔ fill | ring ↔ halo |
|---|---|---|
| Previous | 8.16:1 | 15.67:1 |
| Rewind 10s | 7.48:1 | 15.29:1 |
| Play/Pause | 2.92:1 | 15.24:1 |
| Forward 10s | 7.45:1 | 15.26:1 |
| Next | 7.24:1 | 15.38:1 |

- **Not clipped:** sampled at 90 points around the circumference of each control, including the leftmost
  (Previous, ring outer edge at x≈100px, inside the screen) and the rightmost (Next).
- **Legible over video:** the ring is bounded by the dark halo, so its outer contrast is ~15:1 whatever the
  video frame is doing; the accent disc is the only place it cannot reach 3:1 (2.92:1), and there the fill
  and the halo carry the cue.
- **One action per OK press:** cleared logcat, one OK on the focused Play/Pause produced exactly
  **1** `TogglePause` line and `playing True → False` - the W13.2 single-action guarantee still holds.
- **Menus unaffected:** `menu-subtitles-opens`, `captions-enable`, `menu-back-closes-menu-only`,
  `speed-menu-changes-speed`, `aspect-menu-changes-fit` all PASS in the same tier run.
- **W13.2.1 focus checks preserved:** `transport-focus-entry`, `-right-sequence`, `-left-sequence`,
  `-idle-retention`, `-vertical-navigation`, `-playback-alive` all PASS, with the same bounds as before the
  change.

## Follow-ups

- The indicator is verified by measurement and pinned by a unit test, but not by an automated *pixel*
  check inside the harness. A screenshot check is possible (the recipe is above) yet needs the video paused
  to be deterministic, so it was not added rather than add a flaky check.
- The library's own focus ring (`KidFocusRing`, 4dp, accent at 60% over artwork) was out of this phase's
  scope and was not measured; it is worth measuring on its own screen.
- Not run, per scope: W6, FULL and EXAMPLE tiers. The PLAYER tier is the only device suite exercised.
