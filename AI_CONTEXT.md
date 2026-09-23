# AI_CONTEXT — start here

Quick-start handoff for an AI coding agent opening this repository in a **new conversation**.
Read this file, then the four documents below, before changing any code.

---

## Project

| | |
|---|---|
| **Name** | SafeTube for Kids |
| **Repository** | `github.com/kapilmahawar/SafeTubeforKids` |
| **What it is** | An Android TV app where a child can only watch YouTube content a parent approved. No search, no home feed, no recommendations, no ads. |
| **Target platform** | Android TV (Xiaomi Mi Box 4 is the reference device), `minSdk 24`, `targetSdk 34`, `compileSdk 36`. Kotlin + Jetpack Compose + Media3 + Room + an embedded Ktor server. |
| **Package / version** | `tv.safetubeforkids.app` — `versionName 0.10.0`, `versionCode 16` |
| **Objective** | Two things, and they must not be confused: **(1)** the parent decides what content exists, and **(2)** `PlaybackAuthorization` alone decides what may actually play. |

It began as a fork of [ParentApproved.tv](https://github.com/Prasanna79/parentapproved) by Prasanna K
(the repository's root commit is his). The approval model and the phone dashboard come from upstream;
the player, the security boundary, the catalog and the test tooling were rebuilt here.

---

## Current state

**Current phase: Phase 4 complete.** No phase 5 has been specified.

```text
HEAD                 3962d312c27edbcd0e075e9840ff88fe8464b118
Phase 1 baseline     b130c12  release: 0.10.0        (the pre-existing app)
Phase 2             436e06a  local catalog schema + Room 6 -> 7
Phase 3             017d68f  catalog REST API + TV sync client
Phase 4             b8d4110  catalog UI + local-first runtime
docs follow-up      3962d31  example screenshots
```

**Implemented and verified** (details and evidence in `docs/PROJECT_CONTEXT.md`):

- The **security boundary** — `PlaybackAuthorization` is the only gate; unchanged since Phase 1.
- The **Media3 TV player** with resume, quality, captions, audio tracks, speed, fit and an approved queue.
- The **local catalog** — three Room tables, ordering, enabled flags, parent display names, mixed
  playlists and videos, versioned sync metadata.
- The **catalog REST API** on the TV's embedded Ktor server (`GET /catalog`, `PUT /catalog`).
- **Catalog synchronization** — full validation, atomic replacement, last-known-good on every failure.
- The **catalog-driven TV home** — shelves from Room, D-pad navigation, focus restoration, empty state.
- **Local-first startup and background sync** — the screen never waits on the network.
- **542 unit tests, 0 failures**; the TV harness smoke tier **12/12** on the final APK.

**Not implemented / not verified** (do not assume otherwise):

- No parent **web UI** for editing the catalog — it is configured with `PUT /catalog` (or the debug
  broadcast). Parent dashboard CRUD is future work.
- No player redesign. The current player is the Phase 1 player, plus one behaviour change: the resume
  offer no longer pauses playback.
- The harness `full` and `player` tiers have **not** been run against any Phase 2–4 build. Their last
  recorded runs predate Phase 2 (see `docs/TESTING.md`).
- The `androidTest` instrumented suite (3 files) has not been run in this era.
- No Android TV emulator run recorded for Phase 2–4.

---

## Required reading

```text
AI_CONTEXT.md              this file — orientation, rules, current state
docs/PROJECT_CONTEXT.md    canonical handoff: status, database, catalog, sync, API, limitations
docs/ARCHITECTURE.md       the real architecture, layer by layer, with class names
docs/SECURITY_MODEL.md     the authorization boundary and what must not be weakened
docs/TESTING.md            how to run everything, what is actually green, evidence
docs/PHASES/PHASE-<n>.md   what each phase actually did (read the current one last)
HANDOVER.md                the older prose handover; still useful for device pitfalls and history
```

Before proposing changes, inspect the current commit and the code the change would touch. If a
document and the code disagree, **the code is right** — and update the document.

---

## Critical rules

1. **Do not weaken or bypass `PlaybackAuthorization`.** It stays the authoritative playback gate.
   Prefer leaving `PlaybackAuthorization.kt` byte-identical; it has not changed since Phase 1.
2. **Catalog membership must never authorize playback.** The catalog says what the parent
   *configured*; permission comes only from the approved `channels` + `videos` cache.
3. **Never add unrestricted YouTube browsing, search, recommendations, related videos or a URL entry
   to the child-facing app.**
4. **Preserve local-first behaviour.** The TV renders from Room. Startup must not block on HTTP, and
   a sync failure must never clear, corrupt or partially replace the local catalog.
5. **A failed sync leaves the last-known-good catalog intact** — including a version regression, a
   malformed payload, a bad schema and a local write failure. Never "fix" a failure by wiping state.
6. **Replace the catalog only through `CatalogRepository.replaceCatalog`** (one transaction). Do not
   write catalog rows directly, and do not add a second catalog store.
7. **Do not invent test results.** Say `NOT_RUN`, `IMPLEMENTED_NOT_VERIFIED` or `UNKNOWN` instead.
8. **Verify on the physical TV before claiming device behaviour.** ADB commands and the harness are
   in `docs/TESTING.md`; `adb screencap` cannot capture the playing video (it returns a black frame).
9. **D-pad is the only input.** The child-facing UI must be fully usable with
   UP/DOWN/LEFT/RIGHT/CENTER/BACK. Never require touch, long-press, drag or a mouse.
10. **Never commit secrets.** PINs, session tokens, cookies and signing credentials are configured
    externally or generated at runtime. Documentation records only `SECRET_REQUIRED`.
11. **Do not change the Room schema without a real migration** and a migration test. There is no
    `fallbackToDestructiveMigration` anywhere in this project.
12. **Do not rewrite history.** Add commits; the phase commits are the project's memory.

---

## Fast facts an agent usually needs

```text
Build            cd tv-app && ./gradlew assembleDebug
Unit tests       cd tv-app && ./gradlew testDebugUnitTest      (542 tests, needs Robolectric)
Clean + build    cd tv-app && ./gradlew --offline :app:clean :app:testDebugUnitTest :app:assembleDebug
TV harness       ./tv-app/scripts/tv-e2e.ps1 -SkipBuild -Tier smoke -Adb <sdk>\platform-tools\adb.exe
Reference TV     Xiaomi MIBOX4, Android 12 / API 31, 1920x1080, adb 172.16.1.2:5555
Room database    "parentapproved_cache", version 7, 10 entities, migrations 1->2 ... 6->7
Catalog tables   categories, content_items, catalog_metadata
Server store     catalog.json in the app's filesDir (NOT Room)
Sync interval    once ~3s after startup, then every 15 minutes, plus the Refresh button
UI entry         MainActivity -> AppNavigation -> HomeScreen (catalog) -> PlaybackScreen (player)
```
