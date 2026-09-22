# SafeTube for Kids — handover

State of the work as of commit `793afd0` on `kapilmahawar/SafeTubeforKids`.

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
- Resume prompt semantics: it waits for a decision, any remote interaction hands it a full window
  again, and thirty seconds with no choice starts the video from the beginning
  (`No resume choice after 30s - starting over`).
- Held-key seek: the escalation curve is unit-tested and the user confirmed on the real remote that
  its key-repeat drives it.
- Error and retry: with the offline simulator on, playing an approved video shows *"Couldn't play
  this video"* with Retry/Back. Retry while still offline keeps the error rather than bypassing the
  gate, and Retry once back online plays the video — recovery verified on the TV.
- Approved queue: next/previous stay inside it; a finished video advances to the next approved
  item, and at the end of the queue playback stops rather than fetching a recommendation.
- Security: unapproved video blocked with the message *"This video can't be played"* plus
  Retry/Back; unauthenticated `GET`/`POST /playlists` return 401; a `VIEW` deep link is not
  handled by the app; a URL passed as an activity extra starts nothing; the child-facing library
  exposes no search, no URL entry and no source editing.
- Parent access, verified on the TV: the Settings and Connect Phone entries in the child library now
  demand the PIN before opening (`PIN gate shown: Parents only`, a wrong PIN logged as rejected with
  its remaining attempts, the right one unlocking; `PIN gate shown: Pair this TV` once a parent has
  paired). Before this, a single OK press on the child screen opened the parent tools, which printed
  the PIN in plain text and offered Reset PIN, Clear Sessions and Clear Events - a child could read
  the PIN, take over the dashboard, or erase the watch history. The pairing code remains readable
  until a parent has paired, because that is how the first phone gets in. The release manifest
  contains no `DebugReceiver` and is not debuggable, so the debug entry points cannot exist in a
  build a family installs.
- Dashboard: the TV serves its own dashboard at `/` and that response, plus `app.js` and
  `style.css`, are byte-identical to this repo's assets; every `getElementById` target resolves. The
  user confirmed **Export list** works in a browser.
- Auto quality on the real TV, using the debug bandwidth override (`DEBUG_SET_BANDWIDTH`): at
  25000 kbps it chose 1080p, at 4000 kbps 480p and at 900 kbps 360p, from a video whose renditions
  report `1080p=6933kbps, 720p=4206kbps, 480p=1098kbps, 360p=359kbps, 240p=245kbps, 144p=111kbps`
  - in each case the best rendition inside 70% of the stated bandwidth. The stall step-down also
  fired for real: `Auto quality: stalled at 360p (900 kbps measured) - stepping down to 240p`.
  Recovery is verified in the same run: started at 900 kbps (360p), that stall dropped it to 240p,
  and once the override read 25000 kbps it climbed back -
  `Auto quality: connection recovered - stepping up from 240p to 1080p`. A climb requires
  [AutoQuality.STEP_UP_AFTER_MS] of playback uninterrupted by a stall and clears a stricter budget
  (50% of the measurement against the 70% used to open), at most twice per video.
- Stability: no crash or ANR across full runs.
- Device-free: `SeekStepTest` (escalation curve 10→20→30→60→120s), `SourceTransferTest` (export
  shape, bare array, unreadable payloads, refusal reasons, duplicate collapsing).

## Not done

1. **YouTube-Kids-style redesign** — in progress, one slice at a time, each verified with a tier and
   then shown to the user, who is the only one who can judge it (the agent cannot view images).
   Landed so far: the player's bottom slab became a rounded deck inset from the edges, the progress
   bar is thicker, play/pause is a filled accent disc, the settings chips are pills, the top bar is
   a gradient scrim, the menu panel is rounded and the seek badge is an accent pill; the key-map
   hint line is gone. Reverted at the user's request, because they read as too large: the video
   card size and the shelf header (back to `titleMedium` on one combined line). Still untouched: the
   library grid and shelf styling beyond that header.
2. ~~Remote key-repeat~~ — settled: confirmed by the user on the real remote.
3. **Export/import browser buttons** — server side verified on the device (`export` returned both
   sources; re-import gave `skipped=2, added=0`; junk gave per-item refusal reasons). The static
   half is now verified too: the TV serves its dashboard at `/` (note `/index.html` is a 404 by
   design — `DashboardRoutes` serves the HTML at the root and assets at `/$file`) and that response
   is byte-identical to `assets/index.html`; `app.js` and `style.css` are byte-identical as well, so
   the device runs exactly the dashboard in this repo. Every one of the 61 `getElementById` targets
   exists in the HTML and `showTransfer(message, isError)` matches its call sites, so the buttons are
   wired. **Export list** was then confirmed working by the user in a real browser, which closes
   that half end to end. The import half still awaits a human tap and an OS file-picker choice.
4. ~~Stale release asset~~ — settled: the old release was deleted and `v0.9.3` carries
   `SafeTubeforKids-0.9.3-debug.apk`.
5. **Latent, unproven** — a duplicate `Dashboard server started` line appeared once after an
   install-then-launch; a clean force-stop + launch produced exactly one, so it is unconfirmed.
   Watch for a second `Ktor server started on port 8080`.
6. **Parent access, residual tension** — the PIN gate closes the opportunistic bypass, but the
   pairing code is still printed on the TV before a parent has paired, so a child who deliberately
   opens Connect Phone on a never-paired TV can read it. Gating that surface completely would make
   first pairing impossible. The proper fix is a phone-driven unlock: the dashboard, which already
   holds a session, should be able to reveal the PIN or unlock the TV. Related observation:
   `PinManager.currentPin` is generated in memory at construction, so the PIN changes on every app
   start. Sessions are persisted, so an already-paired phone keeps working - but a parent who has
   forgotten the PIN and needs the TV settings has to clear the app's data to start over. Decide
   whether rotating the pairing code per start is intended before relying on it.

## Pitfalls this codebase has already cost time on

- **Silent no-match edits.** Files are CRLF; a multi-line anchor written with LF does nothing and
  reports nothing. Normalise line endings before a scripted replace, and always read the file back
  to confirm the change landed. This caused: a missed manifest service declaration (which took the
  dashboard offline), a missed `MainActivity` handover (two servers competing for port 8080), and a
  missed harness rename. It has an encoding twin: `Get-Content -Raw` decodes these UTF-8 files as
  Latin-1, so an anchor containing `→`, `±` or `—` never matches while the script still reports
  success. Prefer the file tools for edits, or read with `-Encoding utf8`.
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
- **Two APK filenames.** Gradle writes `app/build/outputs/apk/debug/app-debug.apk`; the harness
  copies that to `SafeTubeforKids-debug.apk` for the release asset. Installing the *renamed* copy
  after a plain build therefore reinstalls an old APK and the new code appears to do nothing - this
  cost a full verification round (`quality Auto` missing from the log while the build was green).
  Install `app-debug.apk`, or let the harness install for you.
- **Debug broadcasts must be registered twice.** `DebugReceiver` handles an action *and* the debug
  manifest lists it in the receiver's `intent-filter`. Adding only the `when` branch gives a
  silently dropped broadcast: the action never runs and nothing is logged.
- **Bitrate units differ by stream type.** NewPipe's `VideoStream.getBitrate()` is bits per second
  while this app budgets in kbps, so an unnormalised value makes every rendition look unaffordable
  and Auto pins to the floor. `AutoQuality` normalises anything above 100_000 as per-second.
  `VideoStream` has no `getAverageBitrate()` at all - that one is on `AudioStream`.
- **A measurement is not a verdict.** The first bandwidth reading of a stream is often low simply
  because little has been downloaded, so Auto keeps a floor (`MIN_AUTO_HEIGHT`) for its first
  choice and relies on the 8s stall step-down to go lower once the connection has proven itself.
