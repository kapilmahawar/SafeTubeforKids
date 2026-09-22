# SafeTube for Kids — handover

State of the work as of commit `66fbd58` on `kapilmahawar/SafeTubeforKids`.

## What this is

An Android TV app where a child can play only the YouTube content a parent approved — no search,
no recommendations, no ads — with a TV-native Media3 player behind a single authorization gate.
Forked from ParentApproved.tv by Prasanna K; the approval model and phone dashboard come from
there, the player, security boundary and test tooling were rebuilt here.

Package id `tv.safetubeforkids.app`. Tested on a Xiaomi Mi Box 4 (`MIBOX4`), Android 12 / API 31,
armeabi-v7a, reachable at `172.16.1.2:5555`.

## How to verify anything

```
tv-app/scripts/tv-e2e.ps1 -SkipBuild -Tier smoke     # ~1.8 min: alive, plays, dashboard answers
tv-app/scripts/tv-e2e.ps1 -SkipBuild -Tier player    # ~3 min:  + menus, captions, resume
tv-app/scripts/tv-e2e.ps1 -SkipBuild                 # ~7.5 min: + autoplay, security, queue
tv-app/scripts/tv-emulator.ps1                       # Android TV 12 emulator, when WHPX is available
```

Set `JAVA_HOME` and `ANDROID_HOME` first, and pass `-Adb <path>\tools\platform-tools\adb.exe`.
Add `-ApiHost 127.0.0.1` when the dashboard is reached through an `adb forward` rather than over
the network. `-QualityProbe` temporarily approves a multi-rendition video to exercise
quality/audio switching, then removes it again.

Device-free checks, which cost the TV nothing and should run on every change:

```
cd tv-app && ./gradlew testDebugUnitTest      # includes SeekStepTest and SourceTransferTest
node --check tv-app/app/src/main/assets/app.js
```

## Verified on real hardware

Full suite last green: **39/39**. Plus targeted dumps for the error state and parent reachability.

- Player: play/pause, pause→resume, timeline, D-pad and media seek (±10s), auto-hide controls,
  buffering indicator, subtitle menu and captions on/off, quality, audio **language** tracks,
  speed, screen fit, resume (position *and* choice), start-over.
- Approved queue: next/previous stay inside it; a finished video advances to the next approved
  item, and at the end of the queue playback stops rather than fetching a recommendation.
- Security: unapproved video blocked with the message *"This video can't be played"* plus
  Retry/Back; unauthenticated `GET`/`POST /playlists` return 401; a `VIEW` deep link is not
  handled by the app; a URL passed as an activity extra starts nothing; the child-facing library
  exposes no search, no URL entry and no source editing.
- Stability: no crash or ANR across full runs.
- Device-free: `SeekStepTest` (escalation curve 10→20→30→60→120s), `SourceTransferTest` (export
  shape, bare array, unreadable payloads, refusal reasons, duplicate collapsing).

## Not done

1. **YouTube-Kids-style redesign** — the largest outstanding item and untouched: library grid and
   player chrome. Do it as a small slice at a time, verifying with `-Tier smoke` and a UI dump
   between slices. Note the session's own limitation: the agent could not view images, so a visual
   change needs either the user's eyes or a UI-dump check of the text/structure.
2. **Remote key-repeat** — `seekStepFor` is unit-tested, but only a physically held remote
   produces the `repeatCount` the feature keys off; `adb shell input keyevent` cannot inject
   repeats. One press of the user's thumb settles it.
3. **Export/import browser buttons** — server side verified on the device (`export` returned both
   sources; re-import gave `skipped=2, added=0`; junk gave per-item refusal reasons). The
   dashboard buttons need a phone browser.
4. **Stale release asset** — `v0.9.3-np0.26.5` still carries `ParentApproved-*.apk`. GitHub has no
   rename API; it needs delete-and-re-upload from a current build.
5. **Latent, unproven** — a duplicate `Dashboard server started` line appeared once after an
   install-then-launch; a clean force-stop + launch produced exactly one, so it is unconfirmed.
   Watch for a second `Ktor server started on port 8080`.

## Pitfalls this codebase has already cost time on

- **Silent no-match edits.** Files are CRLF; a multi-line anchor written with LF does nothing and
  reports nothing. Normalise line endings before a scripted replace, and always read the file back
  to confirm the change landed. This caused: a missed manifest service declaration (which took the
  dashboard offline), a missed `MainActivity` handover (two servers competing for port 8080), and a
  missed harness rename.
- **PowerShell string interpolation.** `"$apiHost:8080"` parses as a drive-qualified variable and
  becomes empty — use `${apiHost}:8080`. A double-quoted replacement string also interpolates its
  own `$variables` at edit time (`$posRestart` vanished, leaving `(( -lt 15)`).
- **Log rotation.** Long verbose runs rotate the default logcat ring buffer and silently ate
  evidence; the suite now raises it with `logcat -G 16M`.
- **Phases inheriting state.** Three separate test failures came from a phase assuming the
  previous phase's menu or player state; each now reopens the video or proves the state first.
- **Two starters, one port.** Anything that starts the dashboard server must go through
  `ServerHolder`, which is idempotent; `MainActivity` starts it directly as well as via the
  service so a foreground-service failure cannot take the dashboard down.
