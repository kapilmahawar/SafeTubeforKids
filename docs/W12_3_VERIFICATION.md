# W12.3 — W6-C closure: what was found, and where the verification stands

* Baseline: `eb829d4398730d23d2259468bb49ede54f793074` (W12.2). Worktree clean at start.
* Device: Mi Box 4 — a **shared living-room TV**, which turned out to matter (see §2).
* Production code changed: none. Harness only.

## 1. W6-C: fixed, and verified passing

Navigating vertically by looking for the target title cannot work once the row has scrolled out of the
hierarchy, because a dump only contains what is rendered. `w6-dpad-reaches-the-first-card` now derives
the row's index from the **library projection** and presses DOWN towards it, polling the focused card
after every press and recording the entire navigation.

The first run of that form showed why the press count is not simply `index + 1`, and the evidence is in
the navigation history itself:

```text
navigation_history=[1:Home | ... | 1:Refresh | 2:NEW! Fireflies ... / 3 min left | ...]
target_row='CoComelon' target_row_index=1  press_count=2  current_focused_row='Continue Watching'
```

The top bar has focusables of its own (Refresh, Connect Phone, Settings), so the first press moves focus
*along* the bar before it descends into the rows. The bound is therefore the row index plus that
allowance, the check still fails if focus lands in a different row, and a failure prints
`target_row`, `target_row_index`, `expected_card`, `current_focused_card`, `current_focused_row`,
`press_count`, `navigation_history`, `visible_rows` and `focusable_elements`.

Verified on the device in a clean run:

```text
PASS: w6-dpad-reaches-the-first-card focused 'Wheels on the Bus Lullaby! ... ' after KEYCODE_DPAD_DOWN
```

## 2. The reason this phase's runs were untrustworthy — and the fix

Runs were sending D-pad keys while **the Android TV launcher** was in front, not SafeTube. On a shared
TV with ZEE5, SonyLIV, Hotstar, JioTV, Shemaroo, Spotify and others installed, DOWN + CENTER on the
launcher **starts another app** — ZEE5 was found running and focused - and every assertion after that was
measuring the wrong program. The harness's own log shows the pattern:

```text
app was NOT foreground at 'w6' - relaunching before continuing     (detected at the start of a phase)
no playback after sequence: KEYCODE_DPAD_DOWN -> KEYCODE_DPAD_CENTER  (keys going to the launcher)
mCurrentFocus=Window{... com.graymatrix.did/com.zee5.androidtv.home.presentation.CollectionActivity}
```

Every key press now goes through one guarded path (`Test-WrongAppInFront` + `Key`) that checks the
focused package - throttled, and always before a key that activates something - and when the app is not
in front it relaunches SafeTube and **drops the key**. A stray press can no longer reach another app or
be spent on a screen the harness is not testing. The clean run logged exactly that behaviour:

```text
NOT delivering KEYCODE_DPAD_UP: com.google.android.tvlauncher is in front, not tv.safetubeforkids.app
    - relaunching the app instead
```

The device was left with SafeTube focused and ZEE5 stopped.

**Any earlier result from a run that pressed keys while the launcher was in front is suspect** and must
not be used as evidence. Runs whose diagnostics name `foreground=tv.safetubeforkids.app` (the W6-C
diagnostics, for example) were clean at that moment.

## 3. W6-A and W6-B: still failing, and why

With the guard in place and W6-C passing, the same run shows the two remaining checks failing for a
reason the diagnostics make plain:

```text
FAIL: w6-cards-are-focusable the row holds 2 card(s); after RIGHT focus is on 'nothing'
FAIL: w6-container-opens-its-children container 'CoComelon Animal Songs' (id ex-cocomelon-animal)
      shows its first child 'NEW! Fireflies ...' of 12 [...] on screen: 'Refresh'
FAIL: w6-back-returns-to-the-shelf / w6-back-restores-the-card-focus
```

The row walk stops on whichever card focus *entered* the row on - the diagnostics show it was the row's
**second** card (the video), not its first. So RIGHT had nowhere to go, and the CENTER that follows went
to the video rather than to the collection, which is why the container never opened and the two BACK
checks failed with it. The fix is to normalise focus to the row's **first** card (LEFT until the focused
label is the row's first card, verified) before both the RIGHT-walk and the CENTER. I wrote that change,
saw it land in the wrong place and break the file, and reverted to the last committed harness rather
than leave a broken script: the committed state is the one with the guard and the verified W6-C.

## 4. Where the verification stands

```text
W6_DPAD_FIRST_CARD=PASS          (verified on device with the guard in place)
W6_CARDS_FOCUSABLE=FAIL          (needs the row's-first-card normalisation above)
W6_CONTAINER_CHILDREN=FAIL       (same cause; the container is never opened)
W6=PASS/FAIL -> FAIL
PLAYER / FULL / EXAMPLE x3       not re-run after this phase's harness change
UNIT_DEBUG=930/930  UNIT_RELEASE=930/930  DASHBOARD=213/213  INSTRUMENTED=28/28  (unchanged, all green)
```

**No W12 stable tag was created.** `W6 != PASS`, and the tiers have not been re-run since the harness
changed; the point of this phase was to close that gap, and it is not closed.

## 5. Commits

* `test: fix W6 row navigation by projection index, and never press into another app` - the two harness
  fixes above (W6-C navigation and the foreground guard), pushed to `fork`.

Next, in order: normalise focus to the row's first card before the RIGHT-walk and the CENTER, re-run the
player tier until W6 is green, then the full tier and three example runs, then tag.
