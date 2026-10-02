# W13.2.1 — transport-focus regression, adb resolution, catalogue fixture

Test-infrastructure phase around the W13.2 transport-focus fix. **No player code changed**: the product
fix is commit `e68ac86`, and this phase makes it verifiable by the harness instead of by hand.

- Baseline: `e68ac86`
- Harness work: `e8c9828` (and the record commit that follows it)
- Device: Mi Box 4 (MIBOX4), Android 12 / API 31, over `adb connect 172.16.1.2:5555`
- Final PLAYER run: `test-results/tv/2026-10-03-001028` — **48 PASS, 0 FAIL, `FINAL: PASS`**

## 1. Automated transport-focus regression

A new PLAYER-tier block (`=== W13.2: transport row D-pad focus ===`) drives the remote through the row
and asserts what the *focused node* is, which no API reports. One `uiautomator` dump per D-pad step is
unavoidable for a focus test, so the block runs after every API-based assertion in the tier.

`Get-DeepestFocused` picks the smallest focused rectangle. uiautomator marks every ancestor focused,
including the full-screen surface container, so the deepest focused node is the only one that says where
the remote actually is — during W13.2 the last focused node in document order reported the *surface* with
the transport row's label attached to it, which reads like "the row is focused" while it is not.

From the final run:

```text
PASS: transport-focus-video Eqo0U_VkhR0 runs 184s from source PLb8WrhcvGhOjFm2xrfaUZq3ytKuWQL5wE
PASS: transport-focus-entry DOWN,DOWN -> 'Previous' ([112,932][208,1028])
PASS: transport-focus-right-sequence Previous > Rewind 10 seconds > Play/Pause > Forward 10 seconds > Next
PASS: transport-focus-left-sequence Forward 10 seconds > Play/Pause > Rewind 10 seconds > Previous
PASS: transport-focus-idle-retention 'Previous' held through 6s idle (was 'Previous' after; bounds [112,932][208,1028])
PASS: transport-focus-vertical-navigation UP -> '<video title>' ([0,0][1920,1080]); DOWN,DOWN -> 'Previous'
PASS: transport-focus-playback-alive video=Eqo0U_VkhR0 playing=True at 92s of 184s
```

Notes on why each assertion is shaped the way it is:

- The video must outlast the walk. Candidates come from the app's own approved sources and the duration
  is read back and asserted (`>=180s`); the run skipped `pRn3fdmSY7w` at 179s and used `Eqo0U_VkhR0` at
  184s. During W13.2 a 27s clip made "pause and resume" look verified when the clip had simply ended.
- `transport-focus-playback-alive` re-checks the same video is still playing afterwards, so a walk over a
  stopped player cannot pass.
- UP is asserted as "the focused node is *not* a transport control", so a no-op UP fails rather than
  passing because something is still focused.
- An unreadable dump is retried up to three times and reported; it is never read as "focus is not on the
  row".

## 2. adb resolution without `ANDROID_HOME`

`$adb` could be the bare name `adb`. This script defines a function called `Adb`, and PowerShell resolves
a function before an application of the same name, so `& $adb` inside that function called the function
itself:

```text
REPRODUCED: RuntimeException: The script failed due to call depth overflow.
```

That is why a run with `ANDROID_HOME` unset died before the first device check, while the same script
worked with it set (a full path was used and nothing was shadowed).

`Resolve-AdbExecutable` now always returns an executable path: an explicit path, `ANDROID_HOME` /
`ANDROID_SDK_ROOT`, then `PATH` via `-CommandType Application` (which cannot return the function). A bare
name passed with `-Adb` is resolved the same way. The run then proves the binary answers one `version`
call before anything depends on it, and reports `ADB_TEST: BLOCKED` with a reason if it cannot, instead of
failing every check for an unrelated-looking reason.

Verified end to end with `ANDROID_HOME` and `ANDROID_SDK_ROOT` unset:

```text
adb: C:\...\toolchain\android-sdk\platform-tools\adb.exe [Android Debug Bridge version 1.0.41]
PASS: install      PASS: app-launches      PASS: api-reachable
```

## 3. The screen saver (found while running the focus test)

The harness had no screen-saver handling at all. The TV runs a photo screen saver that takes the
foreground when idle, and a run started while it was up spent itself against the photo frame: `monkey`
relaunches the app *behind* the dream, every dump showed the frame's weather text, and the tier died with
`the app could not be returned to the library` followed by `PLAYER_FEATURE_TEST: BLOCKED` (23:19 run,
`com.furnaghan.android.photoscreensaver` resumed). A focus test in particular cannot work through it.

The run now:

- disables `secure screensaver_enabled` for the duration,
- wakes the TV (`KEYCODE_WAKEUP`) when the dream is already resumed, at startup and inside the wrong-app
  guard, so a key is never spent on a photo frame,
- restores the household's setting on every exit path (verified: `screen saver: disabled for this run
  (was '1')` … `screen saver: restored to '1'`).

## 4. `w6-catalog-projection`

`GET /catalog` returns the stored document, not the UI projection, so the check reads the app's projection
dump — which is emitted as a **single log entry**, and logcat keeps about 4 KB of one entry. Whichever
shelf arrives first therefore decides what the check can see. `shelf-continue-watching` is built from
resumable videos, and on this TV it consumed the whole budget on its own (9 shelf ids, all
continue-watching, first shelf 11 cards), leaving the tolerant reader with exactly the one shelf the check
excludes by design — hence `no shelf with cards to navigate` while the app's projection was correct.

The check now clears the saved playheads through the app's own `DEBUG_CLEAR_RESUME_POSITIONS` ("forgets
every saved playhead, which is what empties Continue Watching" — the app's action for reaching the state a
fresh install starts in) before reading the projection, and reports the payload size, the shelf count the
app reported against the number that arrived, and whether the capture was truncated. The assertion is
unchanged, and it now records a PASS as well as a FAIL.

Final run — the PASS record for this check is added by this commit, so that run reported the same facts in
the free-text line only:

```text
resume positions cleared before the projection: {"clearedResumePositions":true}
projection payload 4012 chars; app reports 6 shelf(s), 5 arrived [ex-cocomelon, ex-bluey, ex-peppa, ex-chuchu, ex-mixed]
```

plus the ten `w6-*` hierarchy checks that only run once a shelf with cards has been captured.

## Reproducing

```powershell
# from the repository root, with or without ANDROID_HOME set
powershell -NoProfile -ExecutionPolicy Bypass -File .\tv-app\scripts\tv-e2e.ps1 -Tier PLAYER -SkipBuild
```

## Follow-ups

- The CI run for the harness commit failed in `Unit tests and debug build` (127s, the only failure in the
  last 40 runs; the same Kotlin tree passed at 140s in the previous run, and every Kotlin unit test passes
  locally). Nothing in the Gradle build reads this script, so the diff cannot explain it; the step's log
  needs authentication to read. Re-running CI is the next step.
- The `Dump`/focus helpers add one `uiautomator` dump per D-pad step. The block sits after the API-based
  assertions because repeated dumps can destabilise the app's embedded server.
- Not run in this phase, per scope: W6, FULL and EXAMPLE tiers.
