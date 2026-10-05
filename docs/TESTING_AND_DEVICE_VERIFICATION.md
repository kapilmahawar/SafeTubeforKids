# Testing and device verification

How SafeTube is tested, which tests prove what, and the rules for using a real television. The short
version: automated tests live in CI, device observations live in reports, and the two are never presented
as each other.

## The layers

| Layer | Where it runs | What it can prove | What it cannot |
|---|---|---|---|
| JVM unit tests (`app/src/test`) | CI, on every push, in both variants | Logic: the catalog, authorization, quality policy, seek and resume, captions, the dashboard's model and guards, the player's key mapping | Anything about a television |
| Dashboard checks (`tv-app/scripts/*.test.js`) | CI | That the hand-written dashboard JavaScript parses, that its model behaves, and that cross-file structure (page vs server asset list, the player's key contract) has not drifted | That a browser renders it |
| Instrumentation tests (`app/src/androidTest`) | A device, by hand | Behaviour of real Android components on a real device | Nothing in CI: **they are not run there**, and running them against a real television erases that television's app data |
| Device scripts (`tv-app/scripts`) | A device, by hand | What the app actually does on hardware | Reproducibility on other hardware |
| CI | Every push | The JVM and dashboard layers, plus a debug build | Device behaviour, by construction — there is no device attached |

Run locally:

```bash
cd tv-app
./gradlew testDebugUnitTest
./gradlew testReleaseUnitTest
./gradlew assembleDebug
```

The release variant matters. It has caught timing races that pass in debug, where the machine is under
different load.

## The two device scripts, and the line between them

### `tv-e2e.ps1` — the established harness: powerful, and destructive to state

A tiered script (`smoke`, `player`, `full`, `example`) that drives the app with remote keys and asserts
what it finds, including through the app's own debug reporting. Its `player` and `full` tiers contain the
playback, resume, caption, quality, speed, aspect and transport-focus checks.

**Two things make it unsafe for a family television:**

1. Its `example` and `full` tiers load `scripts/fixtures/example-kids-library.yaml` **over the real
   catalog**. Anything a parent built is replaced.
2. Its playback entry point starts videos with a debug broadcast rather than by navigating the UI, and
   its catalog fixtures add sources that were never approved by anyone.

Use it only on a device whose catalog and data may be thrown away — an emulator, a spare box, or a
television you are willing to reset.

### `tv-safe-player-verify.ps1` — the read-only path: what it does, and what it does not

Written for a television that must be left exactly as it was found. It only does what a remote does:

```text
launch the app -> identify the library -> park focus -> walk the shelves with D-pad keys
   -> open an existing approved item -> reach the real player -> observe it through dumps
   -> BACK, HOME
```

It calls no debug endpoint, sends no debug playback intent, writes no catalog or credential, clears no
data, installs nothing and manipulates no network traffic. What it *observes* is the player's own state:
the deepest focused node and its bounds, the transport controls present, the setting chips, and the
clock.

Two practical hazards it was written around, both learned the hard way:

- **`uiautomator` sometimes returns a hierarchy with no nodes at all** on this app's screens — most
  reliably while a per-second countdown (a time limit) is animating and it can never reach an idle state.
  A failed dump must be retried, and must never be read as "the app is not there".
- **The library's top bar names itself in content descriptions, not text**, so a text-only check reads a
  perfectly good library screen as "not the library".

Its first full run reached the real player on the Mi Box from the library UI alone, and the run is
recorded in the W14.2 report. The script's own end-to-end certification, and the player checks it enables,
are the work of the next phase.

## Device verification on the family Mi Box

The reference device is a **Mi Box 4 running Android 12 (API 31)** at 1080p. It is a television somebody
uses, so it is treated as evidence to be preserved, not as a test rig.

**Safe on it:** installing a debug APK with `adb install -r` (updates in place, keeps data — verify the
signing certificate first), launching the app, reading `/auth/state` and `/status`, taking accessibility
dumps and screenshots, navigating with remote keys, and playing an already-approved cached video through
the normal UI.

**Never on it:**

- `connectedAndroidTest` or any `connected*` / `uninstall*` Gradle task. The connected-test task installs
  the app and the androidTest APK and **uninstalls them when it finishes** — erasing the parent PIN, the
  Recovery Code and the whole library. This is not hypothetical: it happened, and the family installation
  had to be set up again. The Gradle build now refuses those tasks unless a run explicitly opts in.
- `pm clear`, uninstalling, or a factory reset.
- Loading a fixture catalog, or importing a library file to "test" with.
- Adding or removing sources, or editing the catalog, to make a test easier — including "temporarily".
- Sending credential-mutating debug broadcasts, or using a debug endpoint to bypass authentication.
- Manipulating the network: no proxy, no interception, no blocking, no traffic rewriting.

When a check needs one of those, it is **blocked**, and it is reported as blocked with the mechanical
reason. A blocked check is an honest result; a manufactured pass is not.

## What has been verified on a device, and what has not

**Observed on the Mi Box:** the library and its navigation, the collection screens, first-run setup with
the PIN and the Recovery Code, parent access on the TV and in the dashboard, playback of an approved
video, a refusal to play an unapproved one, and the transport row taking focus and holding it through the
auto-hide window.

**Not verified on a device, and known to be so:** caption and audio menu paths, seek boundaries read from
the player, the playback rate after a re-prepare, the error/retry screen, DASH adaptive switching (no
approved source is adaptive), automatic quality upgrades, and caption rendering at the pixel level.

**Not covered anywhere:** other Android TV hardware, other Android versions and other resolutions. The
verified target is the Mi Box 4 on Android 12, and nothing in this repository claims more.

## Reading playback state without touching the app (W14.5)

The player checks that stayed BLOCKED were blocked by *observation*, not by the product. The playback
clock is a Compose text node that exists only while the overlay is composed, the transport controls
likewise, and the same activity hosts both the library and the player — so a dump alone cannot always say
which screen is up, and the overlay auto-hides four seconds after the last input.

Two read-only sources already exist. Neither is a debug endpoint, neither needs a product change, and
neither writes anything.

**1. The app's own log lines** (`adb logcat -d`, filtered to the app's tag). The controller already logs
what a device check needs:

| Line | What it proves |
|---|---|
| `Rendered video: WxH (Hp)` | a video is actually rendering, so the player is up with no overlay needed |
| `Player isPlaying=true|false` | playback state |
| `Menu opened: <MENU> (N options)` | which menu is open — the fact that stops a chip walk from walking into the wrong one |
| `Player menu <MENU> -> <id>`, `Captions selection: …`, `Audio track selected: …` | what a menu choice actually applied, rather than what a menu looked like |
| `Seek <delta>s -> <clamped>s` | the controller's own clamped result: the number a seek-boundary check is about |
| `Approved queue finished` | the player is leaving the queue |

**2. The persisted playback position.** `playback_positions(videoId, positionMs, durationMs, updatedAt)`
in the app's own database, and a seek persists the *clamped* position. A read-only copy — taken through
the app's own debuggable sandbox with the copy deleted afterwards, never a write — therefore answers both
boundaries directly: zero at the start, the recorded duration at the end.

With those two, the four states are distinguishable without guessing: library (no recent playback lines,
library dump), player with the overlay hidden (`Rendered video` / `isPlaying` lines and no transport
labels), player with the overlay visible (transport labels present), and player exited (`Approved queue
finished`, or the app no longer in front).

What remains unverified for exactly this reason: the checks have not been *run* against those sources yet.
The sources are established, not the results.

### Getting focus back to the surface before a seek (W14.6)

A device session on the Mi Box reached the player and identified it from the logs alone — `Rendered video:
640x360 (360p)` and `Player isPlaying=true` — but then sent 25 LEFT and 25 RIGHT presses for the seek
boundaries and produced **no `Seek` log lines at all**. The dump said why: the deepest focused node was
`[112,932][208,1028]`, which is the transport row's *Previous* control, not the surface. Left and Right
were therefore doing what the W13.2a contract says they do there — walking the transport graph — and no
seek was ever asked for. The lesson is worth keeping in both directions: the absent log line is the proof
that no seek happened, which is exactly why the log rather than the seek bar is the evidence to trust.

So the order matters, and it is:

1. identify the player (log lines, with the transport descriptions as confirmation);
2. look at the deepest focused bounds. Anything in the bottom deck — roughly `y` 920–1040, the transport
   row — means a transport control holds focus;
3. press UP or DOWN once to leave the row. The product requests surface focus when the row is left, so
   this is a product behaviour, not a workaround;
4. confirm the deepest focused node is the full-screen surface `[0,0][1920,1080]`;
5. only then send LEFT/RIGHT, and verify each seek by its own `Seek <delta>s -> <clamped>s` line.

Never press RIGHT to walk toward a setting chip without first proving where focus is: the same key is a
seek from the surface, a settings move from the settings row, and a transport move inside the transport
row. A walk that skips step 4 is a blind walk, whatever the intention behind it.

## Rules for a contributor

1. Never run a destructive tier or an instrumentation task against a device that is not yours to erase.
2. If a device check cannot be done safely, say `BLOCKED` and give the reason. Do not improvise on
   somebody's television, and do not describe a unit test as if it were a device result.
3. Before installing anything, check the package, the version and the signing certificate, and confirm the
   install is an in-place update that keeps data.
4. Leave the device as you found it: same catalog, same credentials, same settings, app closed to its own
   launcher screen when you are done.
5. After a device session, re-check `/auth/state` and the app's data — an install or a stray task that
   changed them is a defect in the process, and must be reported rather than smoothed over.
