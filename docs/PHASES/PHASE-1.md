# Phase 1 — the pre-existing approved-content TV app

> **Read this first.** "Phase 1" is a **retroactive label**. No commit in this repository is named
> after a phase, and there is no Phase 1 specification in the repository. Phases 2–4 were specified in
> task instructions and are visible as commits (`436e06a`, `017d68f`, `b8d4110`); Phase 1 is simply
> everything that already existed at the release commit that Phase 2 started from. This document
> reconstructs it from git history, `/HANDOVER.md` (written during that era) and the code at
> `b130c12`. Items that could not be established are marked **UNKNOWN** rather than guessed.

## Objective

Build and stabilise an Android TV app in which a child can watch only the YouTube content a parent
approved, with a TV-native Media3 player, a phone-run parent dashboard served by the TV itself, and a
single authorization boundary that cannot be bypassed.

*Inferred from the delivered software and `/HANDOVER.md`; no original objective document exists in the
repository.*

## Planned Scope

**NOT DOCUMENTED.** No Phase 1 plan, specification or task description is present in this repository.
What follows is reconstructed from the sequence of commits.

## Implemented

The repository's history begins with a root commit by **Prasanna K** (2026-04-08,
`118be19 feat: kiosk mode — lock TV to whitelisted apps with parent dashboard control`) — the fork
point of [ParentApproved.tv](https://github.com/Prasanna79/parentapproved). Everything after that is
this fork's own work. 57 commits precede the Phase 1 release commit.

**Approval model and parent configuration**

- Approved sources in Room: `channels` (`yt_playlist` / `yt_video` / `yt_channel`, unique `source_id`)
  with the resolved videos cached in `videos`.
- Resolution with NewPipeExtractor, no API key and no sign-in (`ContentSourceRepository`,
  `VideoResolver`, `NewPipeDownloader`); a source resolves into up to 200 cached videos.
- The parent dashboard: static HTML/CSS/JS served by the TV at `/`, with approval, removal,
  export/import of the library as JSON (`SourceTransfer`, `/sources/export`, `/sources/import`).
- PIN → session authentication (`PinManager`, `SessionManager`), rate-limited PIN attempts, 90-day
  sessions with oldest-first eviction.

**The security boundary**

- **`PlaybackAuthorization`** — the single gate. A video plays only while it is in the `videos` cache
  *and* its parent source is still in `channels`. Its `approvedQueue` is the only queue the player may
  walk. This file has not changed since.

**Player** (built during Phase 1 and reported verified on the real TV in `/HANDOVER.md`)

- Media3/ExoPlayer with a Compose TV UI: play/pause, timeline with position and duration, D-pad and
  media-key seeking with an escalating hold-to-accelerate curve, auto-hiding controls, buffering
  indicator, error state with Retry/Back.
- Menus: subtitles (YouTube's own tracks) on/off, quality (pinned or Auto following measured
  bandwidth, including stall step-down and recovery step-up), audio **language** tracks, playback
  speed, screen fit.
- Resume from a saved playhead, with a "Resume from m:ss / Start over" prompt; a finished video is not
  offered for resume (`RESUME_MIN_MS = 20 s`, `RESUME_MAX_PERCENT = 95`).
- An **approved queue**: next/previous and end-of-video autoplay walk only the approved cache in the
  parent's order, and stop at the end rather than fetching a recommendation.

**Other product features**

- Screen time: daily limits, bedtime, bonus minutes, manual lock (`TimeLimitManager`, Room-backed),
  enforced in the player and on the home screen.
- Kiosk mode: whitelist of apps, lock-task, device-owner behaviour (`KioskManager`, `SafeTubeAdmin`,
  `HomeWatcherService`).
- The dashboard server kept alive by a foreground service, started by both `MainActivity` and
  `ServerService`, and on boot by `BootReceiver`; a single idempotent owner (`ServerHolder`) so two
  starters cannot fight over port 8080.
- Additional API surface: playback control (`/playback/*`), statistics (`/stats`), time limits,
  kiosk apps, `/status`, `/crash-log`.
- The child-facing library UI: `HomeScreen` with one shelf per approved source, `VideoCard` at
  200.dp / 16:9 / 12.dp radius / 1.05 focus scale, shelf headers at `titleMedium`.

**Testing and tooling**

- `tv-app/scripts/tv-e2e.ps1` — an ADB harness driving a real TV with remote keys only, in cumulative
  `smoke` / `player` / `full` tiers, asserting against the app's log and `/status`, writing artifacts
  per run. Plus `tv-emulator.ps1` for emulator verification.
- 337 unit tests, 3 instrumented test files, and the debug broadcast surface (`DebugReceiver`).
- Rebrand commits renaming the app, package and package id to `tv.safetubeforkids.app`.

## Not Implemented

- **No catalog.** There was no hierarchical parent-configured content model: the home screen was
  driven by the approved *sources*, and a video could only be reached through its source. (This is
  what Phases 2–4 added.)
- No parent web UI for anything other than sources / export-import / screen time / kiosk.
- No relay functionality that works: `relay/` is inherited code pointed at upstream infrastructure.
- No release publishing pipeline beyond a debug APK attached to a GitHub release.

## Security Impact

Phase 1 **created** the security model that the later phases had to preserve:

- `PlaybackAuthorization` as the only playback gate, with the approved cache as the only queue.
- The child-facing app offers no search, no URL entry, no channel browsing and no way to approve
  content. The Settings and Connect Phone buttons are deliberately reachable; a PIN gate on them was
  built, verified working and then **removed** because the PIN rotates on every app start, which
  stranded a parent who could not recall it. `/HANDOVER.md` records that decision and says not to
  re-add a gate without asking.
- Deep links: the manifest declares no `VIEW`/`BROWSABLE` filter, and a URL passed as an activity
  extra starts nothing (both later asserted by the harness).
- Release builds contain no `DebugReceiver` and are not debuggable.

## Database Changes

Room database `parentapproved_cache`, brought to **version 6** through migrations 1→2 … 5→6, with
seven entities: `videos`, `play_events`, `channels`, `time_limit_config`, `kiosk_config`,
`app_whitelist`, `playback_positions`. Notable history visible in the migrations: `playlists` was
introduced at v2 and folded into `channels` at v3, and a `flushed` column was dropped from
`play_events` at v2.

## API Changes

The embedded Ktor server (Netty, port 8080) with the routes listed in `ARCHITECTURE.md` §8 — all of
them except `GET/PUT /catalog`. Session-authenticated except that `/status` answers a minimal public
response to an anonymous caller.

## UI Changes

The whole child-facing and parent-facing UI: the Compose home screen with approved-source shelves, the
player and its menus, the Connect Phone screen (PIN + QR), Settings, the lock screen, and the web
dashboard's static assets.

## Tests

- **337 unit tests, 0 failures** — measured at `b130c12` immediately before Phase 2 began (the
  Phase 2 work started from that number).
- Playback policy suites (`AutoQualityTest`, `StreamSelectorTest`, `DpadKeyHandlerTest`,
  `SeekStepTest`, `PlaybackCommandBusTest`), auth/session suites, server route suites, time limits,
  relay, export/import, parser, QR code.
- 3 instrumented files: `FullFlowTest`, `DashboardAssetTest`, `DebugReceiverIntentTest`.

## Physical Device Testing

Xiaomi Mi Box 4, Android 12 / API 31, armeabi-v7a, 1920x1080. Per `/HANDOVER.md` (the project's record
from that era) and the artifacts in `test-results/tv/`:

- `full` tier reported as last green **39/39**, plus targeted dumps; and repeated green runs are in
  the artifacts (e.g. 38/38 on `3a89e88`, `cf4075e`, `acc07db`).
- Verified behaviours listed there include play/pause, seek, resume (position and choice), the
  subtitle/quality/audio/speed/fit menus, auto-quality behaviour measured against the debug bandwidth
  override, the approved queue, unapproved-video blocking, dashboard reachability, and stability with
  no crash or ANR.

**Honest caveat.** The newest `full`-tier runs on record (`2026-09-23-021013`, `-015838`, `-021923`)
**failed** 2–5 checks each, including `audio-menu-lists-real-tracks` and `audio-switch-applied` in all
three. So "the full suite is green" is true of earlier rounds and not of the last runs before Phase 2.
See `docs/TESTING.md` §4.

## Known Issues

- The two full-tier audio checks above were failing at the end of the Phase 1 era and are still
  unresolved.
- "Latent, unproven": a duplicate `Dashboard server started` line was seen once after
  install-then-launch; a clean force-stop and launch produced exactly one. Unconfirmed.
- The boot path for the server (`BootReceiver`) is registered and declared, but a real reboot was
  never verifiable from the shell (the shell may not send `BOOT_COMPLETED`).
- A curious child can reach Reset PIN / Clear Sessions / Clear Events from the parent screens — an
  accepted residual, documented in `/HANDOVER.md`.
- `relay/` points at upstream infrastructure and is not usable.
- YouTube throttling intermittently breaks resolution (`LOGIN_REQUIRED`); the harness names it.

## Final Commit

```text
b130c1262538e61941648a1606f787ca86498346   release: 0.10.0     (2026-09-23)
```

`versionName 0.10.0`, `versionCode 16`.

## Verification Status

| Claim | Status |
|---|---|
| 337 unit tests pass at `b130c12` | **VERIFIED** — measured in this session before Phase 2 began |
| The app installs, launches and plays on the Mi Box | **VERIFIED** — smoke tier 12/12 on Phase 2/3/4 builds of the same code base, and Phase 1-era artifacts |
| The player's menus, resume, queue, autoplay | **VERIFIED (Phase 1 era)** — `full`/`player` tier artifacts; two audio checks failing in the last runs |
| The dashboard works in a browser | VERIFIED per `/HANDOVER.md` (the user confirmed Export list in a real browser); not re-verified in this session |
| A real device reboot brings the server back | **NOT_RUN / BLOCKED** — cannot be triggered from the shell |
| Phase 1's original plan and objective | **UNKNOWN** — not in the repository |
