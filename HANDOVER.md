# SafeTube for Kids — handover

State of the work as of commit `9abddbe` on `kapilmahawar/SafeTubeforKids` (Phases 1-5).

> **Read "Open defect" below before doing anything else.** There is exactly one known product defect
> outstanding, it is reproducible, and it is the thing to fix next. Everything else marked verified in
> this document still holds.

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

Current tier baselines (Phase 4 verified, Phase 5 re-verified on the Mi Box 4):

```
unit tests        564/564 PASS   (556 Phase 4 baseline + 8 Phase 5 authorization tests)
instrumented       19/19 PASS
smoke              12/12 PASS
player tier        32/32 PASS
full tier          43/43 PASS    but NOT stable — see "Open defect"
```

The individual claims below come from the pre-Phase-2 harness (whose suite was "39/39"); they remain
accurate unless a later section supersedes them. The player tier and full tier were first run against
a Phase 2/3/4/5 build only on 2026-09-24, because Phase 4 rewrote `HomeScreen` and nobody had
re-run them; both are now green, which is how the defect below was found.

- Player: play/pause, pause→resume, timeline, D-pad and media seek (±10s), auto-hide controls,
  buffering indicator, subtitle menu and captions on/off, quality, audio **language** tracks,
  speed, screen fit, resume (position *and* choice), start-over.
- Resume offer semantics (changed on the user's request): the video **starts playing from the
  beginning immediately** and the offer sits over it, so nothing waits on a decision. Choosing
  "Resume" seeks to the saved position (`Resume chosen: 65s into …`); leaving the offer alone
  withdraws it after the menu idle window and playback carries on from the beginning
  (`Resume offer withdrawn with no choice`). Dismissing it never seeks - the child asked for the
  beginning by not asking for anything. "Start over" still deletes the saved position.
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
- Parent screens: the Settings and Connect Phone buttons in the child library are deliberately NOT
  gated, by product decision. A PIN gate was built and verified working (gate shown, attempts-limited
  rejection, unlock), then removed: the dashboard asks for a PIN that the TV regenerates on every app
  start, so gating the screen that displays it strands a parent who cannot recall the code. The
  reasoning accepted instead is that small children are unlikely to open a web dashboard, and the
  child-facing app has no URL entry, no search and no way to approve content, so the surface a child
  can reach cannot add unapproved videos. **Do not re-add a gate here without asking first.**
  Verified separately: the release manifest contains no `DebugReceiver` and is not debuggable, so the
  debug entry points cannot exist in a build a family installs.
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
6. **Parent access — decided, do not re-litigate.** The TV shows the current PIN and QR code on the
   Connect Phone screen, and Settings stays one press away from the child library. That was chosen
   over a PIN gate (which was built, verified and then removed) because `PinManager` generates its
   PIN in memory at every app start: a parent who cannot recall the code has no other way to
   retrieve it. Accepted residual: a curious child can reach Reset PIN, Clear Sessions and Clear
   Events from that screen. The developer tools there (offline simulation, log panel, clear log) are
   debug-build only. If a stronger boundary is ever wanted, the fix is a phone-driven unlock - the
   dashboard already holds a session - rather than hiding the code on the TV.

## Open defect — stale now-playing state after playback completion

**Confirmed, reproducible, NOT yet fixed. This is the next thing to fix.**

When a video reaches its natural end, the app keeps reporting a live now-playing record instead of
clearing it. Captured after playback had already stopped
(`test-results/tv/2026-09-24-094701/api-playing.json`):

```json
"currentlyPlaying": {
    "videoId": "e_04ZrNroTo", "playlistId": "PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT",
    "positionSec": 5, "durationSec": 229, "playing": true
}
```

while the same run's log repeats `Player isPlaying=false`. So anything that asks "is something
playing?" is told **yes** when nothing is.

Five `full`-tier assertions fail together, and only in runs that reach this state:

```
stopped-before-security        requires PlayingNow to be null
deeplink-plays-nothing         requires PlayingNow to be null
extras-cannot-start-playback   requires PlayingNow to be null
back-returns-to-library        requires PlayingNow to be null
next-is-approved-queue         NEXT must change the video; it does not
```

**How it was isolated.** Three consecutive full-tier runs on the *same byte-identical APK*:

| Run | `end-of-video-handling` ended as | Result |
|---|---|---|
| 1 `2026-09-24-094701` | "playback stopped" | FAIL — those 5 |
| 2 `2026-09-24-095942` | "advanced to next approved item" | **PASS 43/43** |
| 3 `2026-09-24-101151` | "playback stopped" | FAIL — those 5 |

Perfect correlation across three runs: the failures appear exactly when playback reaches the end and
stops, and never when the queue advances instead. This is state-dependent, not random flakiness —
which is why it is worth fixing rather than re-running until it goes green. It also explains the
older note that `back-returns-to-library` "fails only in runs where the probe phase also fails":
those were runs that happened to end playback.

**Why it is not a Phase 5 regression.** The APK is byte-identical to the Phase 4 verified binary
(`BA54D0AFC9B48C7873448AF6C34991825A7F58A9299FE62F630A563C21E2C7E4`, device-side `sha256sum`
confirmed), and Phase 5 changed no production source. Those Phase 4 runs never reached
`STATE_ENDED` — end-of-video always advanced — so this state had simply never been exercised before.

**Security impact: none demonstrated.** `unapproved-video-blocked`, `unapproved-video-not-playing`,
`api-refuses-unauth-read/write` and `no-view-deeplink-handler` passed in all three runs. The stale
record refers to the *same approved* video, so no unauthorized content ever played. The two deep-link
assertions fail on their "nothing is playing" half, not on their security half.

**Where to fix it.** Clear the reported now-playing state when playback completes — in
`PlaybackController`, on `Player.STATE_ENDED` — so that "ended" and "stopped" are indistinguishable
to anything asking whether something is playing. Then re-run the whole gate: unit tests,
instrumented, player tier, three consecutive full tiers, and the physical-TV sequence. Do **not**
paper over it in the harness: the harness is asking the right question.

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
- **A stale PIN reads as a broken app.** The harness took the *first* `"pin"` match from the
  `SafeTube-Intent` log buffer. That buffer outlives the app process and the PIN is regenerated on
  every start, so after any manual poking the run authenticated with a dead PIN: auth failed, and
  with it `api-reachable`, library readiness and the whole navigation phase. It now takes the newest
  match. Anything that reads a rotating value out of logcat needs the same care.
- **A full session store used to wedge the dashboard.** `createSession` returned null once 20
  sessions existed, and the auth route turned that into `success: true` with an empty token - no
  error a parent could act on, just a dashboard that stopped working, permanently, because sessions
  last 90 days. It now evicts the oldest session and still issues a token; `SessionEvictionTest`
  pins that, and the old test that asserted the refusal was updated rather than deleted.
- **The dashboard outlives the app, but the boot path is unproven.** `ServerService` (foreground,
  `START_STICKY`) keeps port 8080 answering with the TV UI closed - verified on the device by
  sending the app to the launcher and still getting `/status`. `BootReceiver` now starts that
  service on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, and `dumpsys package` confirms it is
  registered with `RECEIVE_BOOT_COMPLETED` declared. What is NOT verified is a real reboot: the
  shell is not allowed to send `BOOT_COMPLETED` (`am broadcast` refuses), so the only honest test is
  power-cycling the TV and checking `http://172.16.1.2:8080/status` before opening the app.
- **When every resolution fails, suspect YouTube, not the app.** A long day of automated runs ended
  with the suite reporting `dpad-opens-approved-video` FAIL and "the remote cannot play anything",
  which looked like a regression at the same commit that had just passed 39/39. The log held the
  answer: `Resolution failed for ...: YouTube probably temporarily blocked anonymous watch access
  with this IP, got error LOGIN_REQUIRED: "Sign in to confirm that you're not a bot"`. The
  child-facing behaviour was correct throughout - the player showed "Couldn't play this video" with
  Retry/Back, which is the error path it is supposed to show - and the approved library was
  untouched. Waited out, it recurs. Before blaming the app, grep the log for `LOGIN_REQUIRED`.
  **Fixed since:** the harness now detects this condition itself, prints
  `NOTE: YouTube is refusing anonymous watch access from this IP (LOGIN_REQUIRED) - playback phases
  will fail for that reason, not because of the app`, skips the playback phases and reports
  `FINAL: BLOCKED` with exit code 3 instead of a misleading navigation FAIL. It cleared on its own
  again on 2026-09-24 and playback was re-verified the same day (player tier 32/32), so treat it as
  an external, recurring condition and never as a product failure.

## Verification notes (rounds 52-54)

- The approved queue is now covered for real: with a 50-video queue under test,
  `next-is-approved-queue` and `previous-is-approved-queue` pass. They used to be skipped as
  NOT APPLICABLE because the queue source held a single video.
- Auto quality is verified against a real measurement, not only the debug override: on the
  six-rendition probe video the app measured 3200 kbps and chose 480p, which is exactly the policy
  (70 percent of 3200 is 2240; 480p costs 1098, 720p costs 4206).
- The opt-in `-QualityProbe` phase fails for a reason of its own, and it is NOT the video. Driven by
  hand - approve, resolve, play by broadcast - the same video resolves with 6 qualities and plays
  with `quality Auto (480p)`. Resolution, caching and the video are therefore all exonerated, and
  what remains is the phase's own sequencing: the state the app is in when its `PlayVideo` fires and
  its menu keys are sent. Next time, take a UI dump immediately after that broadcast instead of
  adding more waits.
- `back-returns-to-library` fails only in runs where the probe phase also fails. Treat it as
  collateral until the probe is fixed, not as a regression.
- `uiautomator dump` returns no text while the video surface is on screen (nodes come back as
  "Skipping invisible child ... ViewFactoryHolder"), so player-screen assertions must read the app
  log rather than UI dumps.
- YouTube throttling: see the earlier note. It clears on its own; the harness now names it.

## Verification notes (Phase 5 closure, 2026-09-24)

- **Phase 5 was a verification phase, not a player rewrite.** The brief asked to modernize the player
  onto AndroidX Media3. The audit found it already was Media3: `media3-exoplayer`,
  `media3-exoplayer-dash` and `media3-ui` pinned at `1.11.1`, with `AppNavigation` → `PlaybackScreen`
  → `TvPlayerScreen` rendering `androidx.media3.ui.PlayerView` (built-in controller disabled, custom
  Compose TV controls) driven by `androidx.media3.exoplayer.ExoPlayer`. Every item on the brief's own
  P5.1 list was already implemented, so rewriting it would have risked focus handling, the nine resume
  sub-cases and 43 tier assertions for no functional gain. Phase 5 therefore added the missing tests:
  `PlaybackAuthorizationTest` (8 tests) pins the invariants a Media3 migration must never break —
  including the one that matters most, where a video whose source the parent withdrew *stays in the
  approved cache and remains fully resolvable* and the gate must still refuse it. That is
  "a playable URL is not proof of authorization", made executable.
- The player tier passed **32/32** for the first time against a Phase 5 build, and the full tier
  passed **43/43** once (run 2 below); it is the end-of-video state in the "Open defect" section that
  keeps three consecutive passes out of reach.
- `-QualityProbe` is still required to exercise quality/audio; without it those assertions are
  skipped. The audio findings remain a **test-media limitation** (the resolver reports `0 audio`),
  not a player capability gap, and no tracks were fabricated to make them pass.
- The older `back-returns-to-library` note is now explained rather than merely observed: it fails
  whenever playback has reached its end. See "Open defect".
- **Instrumented tests uninstall the app.** `:app:connectedDebugAndroidTest` removes the package when
  it finishes, deleting `/data/data/...` — the Room database *and* `catalog.json`. Re-seed afterwards
  through the app's own API (PIN → `POST /auth` → `POST /playlists`) and do not fabricate the parent's
  catalog. Related trap: `adb shell install <path>` is not a valid install — it must be host-side
  `adb install -r <path>`. Getting that wrong leaves the app uninstalled, so later probes emit no
  logs at all, which is easily misread as a YouTube block (it was, once, in this very session).