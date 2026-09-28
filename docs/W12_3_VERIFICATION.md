# W12.3 — W6 closure, and the full verification matrix

* Baseline `eb829d4398730d23d2259468bb49ede54f793074` (W12.2), verified exact with a clean worktree.
* Device: Mi Box 4 (MIBOX4, Android 12, 1920x1080) - a **shared living-room TV**. That mattered; see §2.
* Production code changed in this phase: **none**. `git diff --stat eb829d4..HEAD -- tv-app/app` is
  empty, and the only files touched are `tv-app/scripts/tv-e2e.ps1` and this document. The APK under
  test is therefore the W12.2 build, and every product behaviour W12 verified is untouched.

```text
W6=PASS  PLAYER=PASS  FULL=PASS  EXAMPLE_STABILITY=PASS   ->  tag W12-STABLE-2026-09-29 created
```

## 1. W6-C navigated by title, which cannot work - and now navigates by the projection

`w6-dpad-reaches-the-first-card` walked down the rows looking for the target title. That cannot succeed
once the row has scrolled out of the hierarchy, because a dump only contains what is rendered. It now
takes the row's index from the **library projection**, presses DOWN towards it, polls the focused card
after every press, and records the whole navigation.

The first run of that form showed why the press count is not simply `index + 1`, and the navigation
history is the evidence:

```text
navigation_history=[1:Home | ... | 1:Refresh | 2:NEW! Fireflies ... / 3 min left | ...]
target_row='CoComelon' target_row_index=1  press_count=2  current_focused_row='Continue Watching'
```

The top bar has focusables of its own (Refresh, Connect Phone, Settings), so the first press moves focus
*along* the bar before it descends into the rows. The bound is the row index plus that allowance, the
check still fails if focus lands in a different row, and a failure prints `target_row`,
`target_row_index`, `expected_card`, `current_focused_card`, `current_focused_row`, `press_count`,
`navigation_history`, `visible_rows`, `focusable_elements` and `foreground`.

## 2. Why this phase's runs were untrustworthy - and the fix that made them trustworthy

Runs were sending D-pad keys while **the Android TV launcher** was in front rather than SafeTube. On a
shared TV with ZEE5, SonyLIV, Hotstar, JioTV, Shemaroo, Spotify and others installed, DOWN + CENTER on
the launcher **starts another app** - ZEE5 was found running and focused:

```text
app was NOT foreground at 'w6' - relaunching before continuing
no playback after sequence: KEYCODE_DPAD_DOWN -> KEYCODE_DPAD_CENTER
mCurrentFocus=Window{... com.graymatrix.did/com.zee5.androidtv.home.presentation.CollectionActivity}
```

Every press now goes through one guarded path (`Test-WrongAppInFront` + `Key`) that checks the focused
package - throttled, and always before a key that activates anything - and when the app is not in front
it relaunches SafeTube and **drops the key**. A stray press can no longer start another app or be spent
on a screen the harness is not testing. The runs below logged that behaviour and stayed clean:

```text
NOT delivering KEYCODE_DPAD_UP: com.google.android.tvlauncher is in front, not tv.safetubeforkids.app
    - relaunching the app instead
```

**Any earlier result produced by a run that pressed keys while the launcher was in front is not
evidence.** ZEE5 was force-stopped and the device left with SafeTube focused.

## 3. The remaining W6 failures, and what closed them

`w6-cards-are-focusable` pressed RIGHT from wherever focus had entered the row. When focus entered on
the row's **second** card, RIGHT had nowhere to go and the CENTER that follows opened the video instead
of the container - one cause behind three failing checks. Focus is now normalised to the row's **first**
card, verified after each press, before the RIGHT walk and again before the CENTER. In the ordinary
case the normaliser is a no-op, which the log shows (`W6 row focus is on the row's first card after
1 LEFT press(es)`, and `0` in the earlier run).

Two things learned while doing it, both recorded in the harness source:

* **LEFT from a focused video card enters that card's inline player controls**, not the sibling card:
  after four LEFT presses the focused label was
  `3:04 / 0:01 / Forward 10 seconds / ... / Pause / 5 of 105 / <title>`. That is why an unverified
  single LEFT press, or a LEFT loop, can end up driving a player rather than returning to the card.
* **The row's second card is a featured-video card that rotates.** One run showed
  `Wheels on the Bus Lullaby` at `5 of 105`; the next showed `This is the Way Bedroom` at `6 of 105`.
  Row membership therefore cannot be tested against the titles captured from the projection at the
  start of the phase, and a park-then-descend return that stops only on the first card's title
  overshoots into the next row (measured: three DOWN presses ending on `Peppa Pig Tales 2026 ...`).
  That variant was reverted rather than left in the tree.

Final W6 result, all ten checks:

```text
w6-1-category-heading-has-no-picture   PASS
w6-1-category-heading-has-no-image     PASS
w6-category-title-not-focusable        PASS
w6-subcategory-is-a-card               PASS
w6-dpad-reaches-the-first-card         PASS   focused 'Wheels on the Bus Lullaby! ... ' after DPAD_DOWN
w6-cards-are-focusable                 PASS   after RIGHT focus is on the row's other card
w6-container-card-does-not-autoplay    PASS   nothing started
w6-container-opens-its-children        PASS   on screen: 'Back'
w6-back-returns-to-the-shelf           PASS
w6-back-restores-the-card-focus        PASS   focused 'CoComelon Animal Songs'
```

## 4. The complete matrix, measured in this phase

| Gate | Result | Evidence |
| --- | --- | --- |
| W6 (10 checks) | **PASS** | player and full runs, `%TEMP%\w123\player6.log`, `full1.log` |
| PLAYER tier | **PASS** | `player6.log` - `FINAL: PASS` |
| FULL tier | **PASS** | `full1.log` - 54 checks, 0 FAIL, 0 UNKNOWN, 0 LIMITED |
| EXAMPLE stability | **PASS** | `example1..3.log` - 36/36, 36/36, 36/36, 0 FAIL each |
| UNIT debug | **PASS** | 930 tests, 0 failures (`testDebugUnitTest`, 64 suites) |
| UNIT release | **PASS** | 930 tests, 0 failures (`testReleaseUnitTest`, 64 suites) |
| DASHBOARD | **PASS** | 213 tests, 213 pass, 0 fail |
| INSTRUMENTED | **PASS** | 28 tests on MIBOX4, 0 skipped, 0 failed |
| SECURITY invariant | **PASS** | `app-foreground-at-security`, `unapproved-video-blocked`, `unapproved-video-not-playing` in the full run |
| DEVICE resolver failure | **PASS** | `resolver-failure-probe.ps1` - 12 checks, 0 failed |

`end-of-video-handling` **PASSED** in this run - the player advanced to the next approved item
(`pRn3fdmSY7w` -> `iALurhct9h0`) - rather than taking the LIMITED branch W12.1 had to report, so the
overshoot guard was exercised and held.

## 5. Commits and tag

* `f80d45e` - W6-C by projection index, and never press into another app.
* `e411cfd` - W12.3 record: W6-C fixed; the foreground-mixup findings.
* `0c727da` - normalise W6 focus to the row's first card, with the rotation and inline-player findings.
* `W12-STABLE-2026-09-29` - annotated tag, pushed to `fork` only. `W10-STABLE-2026-09-28` and
  `W10.1-STABLE-2026-09-28` are untouched.

## 6. What this tag does and does not claim

Claimed: the four gates above pass on the real device, on the W12.2 production code, with the harness
of this phase - and the harness now cannot mistake another app's screen for the app under test.

Not claimed: the harness's focus handling is not yet indifferent to the UI's focus memory. The return to
the row's first card relies on LEFT when focus is on a video card, which works when the card is simply
focused but enters the card's inline player controls once that player is up. It passed in the runs
recorded here; a future red run of `w6-container-opens-its-children` should be read against §3 before
being treated as a product regression. D7 remains observable-PASS with its pixel check UNMEASURED, as
recorded in `docs/W12_CORRECTNESS.md`.
