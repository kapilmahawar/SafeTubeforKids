# Testing

What is tested, how to run it, and what has actually been observed. Claims in this document are tied
to a named command or a recorded artifact; anything unverified says so.

The project's own older prose on device pitfalls is `/HANDOVER.md` — read it before running the TV
harness for the first time.

---

## 1. Test stacks

| Stack | Used for | Dependency |
|---|---|---|
| JUnit 4 | Every unit test | `junit:junit:4.13.2` |
| **Robolectric 4.16.1** | Tests that need a real Android runtime **and a real SQLite**: Room DAOs, the catalog flows, migrations, end-to-end sync | `org.robolectric:robolectric:4.16.1` |
| MockWebServer | The sync client over a real socket with controlled bodies/status codes | `com.squareup.okhttp3:mockwebserver:4.12.0` |
| Ktor test host | Server routes through the real `Application` | `io.ktor:ktor-server-test-host:3.1.1` |
| mockk | Fakes where a real object would drag in Android | `io.mockk:mockk:1.13.8` |
| kotlinx-coroutines-test | Suspend/Flow tests | `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3` |
| AndroidX test (instrumented) | The 3 `androidTest` files | `androidx.test.ext:junit`, `androidx.test:runner` |

Robolectric was added in Phase 2 for a specific reason: the pre-existing DAO tests used hand-written
fake DAOs, which cannot prove `ORDER BY`, foreign-key cascade, or that a migration produces the schema
Room expects. The catalog tests required the real thing.

---

## 2. Commands

```bash
# Unit tests (the fast, device-free check; this is what CI runs)
cd tv-app && ./gradlew testDebugUnitTest

# Lint-free, from-scratch verification used as the acceptance check for a phase
cd tv-app && ./gradlew --offline :app:clean :app:testDebugUnitTest :app:assembleDebug

# Debug APK only
cd tv-app && ./gradlew assembleDebug
#   -> tv-app/app/build/outputs/apk/debug/app-debug.apk     (install THIS file, not a renamed copy)

# One class, or one method
cd tv-app && ./gradlew testDebugUnitTest --tests "*CatalogSyncServiceTest*"
cd tv-app && ./gradlew testDebugUnitTest --tests "*CatalogDatabaseTest.sync01*"

# Instrumented suite (needs a device/emulator; not run in the catalog era)
cd tv-app && ./gradlew connectedDebugAndroidTest

# ...or just the one class that guards the dashboard assets
cd tv-app && ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=tv.safetubeforkids.app.server.DashboardAssetTest

# The dashboard JavaScript has no build step, so CI parses it
node --check tv-app/app/src/main/assets/app.js

# The dashboard's model and its guards, as plain Node tests (no browser, no dependencies)
cd tv-app && node --test scripts/dashboard-*.test.js
```

**`connectedDebugAndroidTest` uninstalls the app when it finishes.** That wipes the app's private
data: `files/catalog.json`, and the Room database holding the approved sources, watch history, resume
positions and time-limit configuration. It happened during W8 and cost a device's history. Do not
run it as the last thing before a device demo, and if you do run it, re-approve the sources and
publish the catalog again afterwards.

The three dashboard suites are `dashboard-catalog-editor.test.js` (the model: every mutation and the
save/reload conversation), `dashboard-catalog-import.test.js` (the import rules and the ordering they
produce) and `dashboard-catalog-ui.test.js` (the shell that ships: the theme rule, the token palette,
the action table, what the browser is allowed to store, and that no Apps/Kiosk surface is left in any
dashboard file). They run against the real asset files, so a page and a script that disagree fail
here rather than on a phone.

CI (`.github/workflows/ci.yml`) runs `./gradlew --no-daemon --stacktrace assembleDebug
testDebugUnitTest`, checks `app.js` parses, and uploads the debug APK.

`--offline` is used in this project's own acceptance runs because all dependencies are already in the
Gradle cache. **If a build needs a dependency that is not cached, drop `--offline`** rather than
pretending the offline build succeeded. `app/build.gradle.kts` pins Room 2.8.4, Media3 1.11.1,
Ktor 3.1.1 and NewPipeExtractor v0.26.5.

---

## 3. Current results

Measured on `3962d31` during the documentation task, with
`./gradlew --offline :app:testDebugUnitTest --rerun`:

```text
BUILD SUCCESSFUL
classes = 46     tests = 542     failures = 0     errors = 0     skipped = 0
```

Test count history (each measured, not estimated):

| Point | Tests | Source |
|---|---|---|
| Phase 1 baseline (`b130c12`) | 337 | measured before Phase 2 began |
| Phase 2 (`436e06a`) | 381 | +44 catalog database/migration/validation |
| Phase 3 (`017d68f`) | 506 | +125 contract, validator, sync, routes, store, end-to-end |
| Phase 4 (`b8d4110`) | 542 | +36 UI projection and catalog home flows |
| `3962d31` | 542 | re-run to confirm |
| W7 redesign (`a6f51ba`) | 846 | +13: `GET /catalog/artwork` and `POST /catalog/refresh`, the resolver's single-video link, and the redesigned dashboard shell. Measured on `testDebugUnitTest` and `testReleaseUnitTest`, both 846/846, plus 117 dashboard JavaScript tests |
| W8 polish | 846 | no Kotlin changed; the dashboard suites went 117 → 128 (`dashboard-catalog-ui.test.js` gained 11 guards for the loading state, the vocabulary, the reorder arrows, the remove copy, the error translator and the artwork fallback) |

**205 of the 542 tests are catalog-era** (Phases 2–4) and live in 11 classes:

```text
CatalogDatabaseTest            28   Room DAOs/entities/repository against real SQLite (Robolectric)
CatalogMigrationTest            8   v6 -> v7 migration on a real SQLite database, data preservation
CatalogValidationTest           8   the playlist/video identity rule, pure
CatalogContractTest            13   the JSON wire format, real serialized text
CatalogPayloadValidatorTest    29   every validation rule, accepted and refused
CatalogSyncServiceTest         29   the sync client: real Room + real OkHttp + MockWebServer
CatalogRoutesTest              37   GET/PUT /catalog, auth, validation, versioning, concurrency
CatalogStoreTest               12   persistence across restart, version assignment, threads
CatalogEndToEndTest             5   real Netty server + real HTTP + real Room, incl. a restart
CatalogUiProjectionTest        23   rendering rules: order, enabled, empties, names, Continue Watching
CatalogHomeFlowTest            13   the flows the home screen observes, against real Room
```

The remaining 337 tests are the Phase 1 suites — auth/PIN/sessions (35), the embedded server's other
routes (75), playback policy (`AutoQualityTest` 23, `StreamSelectorTest`, `DpadKeyHandlerTest`,
`SeekStepTest`, `PlaybackCommandBusTest`), time limits (49), relay (60), and the data/util suites.

### What the important suites actually assert

- **`CatalogMigrationTest`** builds a genuine version-6 database, opens it with Room at version 7 and
  only `MIGRATION_6_7` registered, and then relies on Room's own `onUpgrade` schema validation as the
  oracle — a passing test means the migration produced exactly the schema Room expects for **all ten**
  tables, not just the new ones. It also reads back a seeded row in every pre-existing table through
  the real DAOs, and checks that `PlaybackAuthorization` still approves the migrated approved video.
- **`CatalogSyncServiceTest`** runs the sync against a real socket, and induces a mid-transaction
  failure with a SQLite trigger to prove the rollback leaves the last-known-good catalog intact. It
  also probes that two overlapping syncs do not interleave (the test fails without the mutex).
- **`CatalogEndToEndTest`** starts a real Ktor/Netty server on an ephemeral port, publishes a catalog
  over HTTP, runs the real sync client against it, and asserts the local Room rows — including a real
  server restart (stop the engine, start a new one over the same storage directory).
- **`CatalogRoutesTest`** uses Ktor's `testApplication` exactly as the pre-existing route tests do:
  real routes, real store, real `SessionManager`. Covers unauthenticated rejection, expiry, every
  validation failure, version increments and non-increments, empty-vs-malformed-empty, optimistic
  concurrency, and that a client cannot dictate the version.
- **`CatalogHomeFlowTest`** contains the security pair (catalog entry without approval → rejected;
  with approval → approved; approval withdrawn while the catalog row stays → rejected) and the atomic
  read test that checks every emission during a replacement is either wholly the old catalog or wholly
  the new one.

---

## 4. Physical Android TV testing

```text
Device        Xiaomi Mi Box 4 (ro.product.model = MIBOX4, product = oneday)
Android       12   (ro.build.version.release = 12),  API 31
ABI           armeabi-v7a
Display       1920x1080, density 320
ADB           172.16.1.2:5555      (test-results/tv/2026-09-23-234624/device.txt)
```

The harness (`tv-app/scripts/tv-e2e.ps1`) builds or reuses the debug APK, installs it, launches it and
drives the app **with remote keys only** — no touch, no intents for the child-facing flow — asserting
against the app's own log and its `/status` API rather than against screenshots.

```powershell
$env:ANDROID_HOME = "<android-sdk>"
./tv-app/scripts/tv-e2e.ps1 -SkipBuild -Tier smoke -Adb "$env:ANDROID_HOME\platform-tools\adb.exe"
#   -Tier smoke | player | full      (cumulative)
#   -Serial <ip>:5555  -ApiHost <host>  -ClearState  -QualityProbe  -LaunchWaitSec <n>
```

On Windows, PowerShell script execution may need `Set-ExecutionPolicy -Scope Process -ExecutionPolicy
Bypass` first.

**Exit codes.** `0` every assertion passed, `1` an assertion failed, `2` blocked (device or APK missing),
`3` blocked (the app could not be driven), `4` `RESULT=HARNESS_PRECONDITION_FAILURE` — the run could not
be performed at all, so the app was never measured and the result must not be read as either a product
pass or a product failure. There are two of those, each with its own `REASON=` line: an empty approved
library (`library empty — re-seed before running playback tiers`), and a queue under test that is not
multi-item (a blank `QUEUE_SOURCE` or `QUEUE_SIZE < 2`) when the multi-item NEXT/BACK witness runs.

**Tiers.** `smoke` ≈ 1.8 min (install, launch, dashboard reachable, D-pad opens a video, playback
running, play/pause, seek both ways, media keys reach the player, survives HOME, no crash).
`player` ≈ 3 min adds the menus (subtitles, captions on/off, speed, fit), resume/start-over.
`full` ≈ 7.5 min adds autoplay/queue behaviour, end-of-video, and the security phase
(unapproved video blocked, unauthenticated API reads/writes refused, no `VIEW` deep-link handler,
deep link plays nothing, extras cannot start playback), plus quality/audio checks.

Each run writes to `test-results/tv/<timestamp>/` (**gitignored**): `device.txt`, `commit.txt`,
`install.log`, `test.log`, `logcat.txt`, `result.json` (one key per assertion), `api-*.json`, and
screenshots including `09-final-ui.xml`.

**Closure evidence in `test.log`.** The `full` tier prints the queue it actually observed
(`QUEUE_PROBE_DEBUG` → `currentVideoId`, `currentPlaylistId`, `currentTitle`, `currentSource`,
`playlistLookupId`, `queueCount`, then `QUEUE_SOURCE`/`QUEUE_SIZE` and the endpoints used), the NEXT
witness (`NEXT_BEFORE`, `NEXT_AFTER`, `NEXT_CHANGED`, `NEXT_AFTER_AUTHORIZED`) and the BACK witness
(`BACK_FROM_PLAYBACK`, `BACK_RETURNED_TO_LIBRARY`, `FOREGROUND_AFTER_BACK`). The queue is resolved from
the app's own `/status` and matched against `/playlists`; nothing is hard-coded and a blank source is a
precondition failure rather than `NOT APPLICABLE`.

### What has been run, and the results on record

There are 50+ recorded runs in `test-results/tv/` (gitignored). Summarised honestly:

| Tier | Last recorded run | Result | Commit |
|---|---|---|---|
| **smoke** | `2026-09-23-234624` | **12/12 PASS** | `b8d4110` (Phase 4) |
| smoke | `2026-09-23-233503` | 12/12 PASS | `b8d4110` |
| smoke | `2026-09-23-110339`, `-105000` | 12/12 PASS | Phase 2/3 APKs |
| smoke | many earlier runs | mostly 12/12; a handful of failures during Phase 1 development | pre-Phase-2 |
| **player** | `2026-09-22-235808` | **28/28 PASS** | `40ecbf8` (pre-Phase-2) |
| player | `2026-09-22-235131` | 25 PASS / 3 FAIL (resume checks) | `40ecbf8` |
| **full** | `2026-09-23-021923` | **40 PASS / 2 FAIL** — `audio-menu-lists-real-tracks`, `audio-switch-applied` | pre-Phase-2 |
| full | `2026-09-23-021013`, `-015838` | 4–5 FAIL (quality + audio + back) | pre-Phase-2 |
| full | `2026-09-23-014058` | **38/38 PASS** (last fully green full run) | `3a89e88` |
| full | `2026-09-23-005502`, `-010835` | 38/38 PASS | `cf4075e`, `acc07db` |
| **smoke** | `2026-09-24-011529`, `-005836` | **12/12 PASS** | Phase 4 source (`fd73ff8` tree) |
| **player** | `2026-09-24-033155` | **32/32 PASS** | Phase 4 source + repaired harness |
| **full** | `2026-09-24-044035`, `-045016`, `-045950` | **43/43 PASS** ×3 consecutive | Phase 4 source + repaired harness |
| **player** | `2026-09-24-093938` | **32/32 PASS** (first pass on a Phase 5 build) | `9abddbe` |
| **full** | `2026-09-24-095942` | **43/43 PASS** | `9abddbe` |
| full | `2026-09-24-094701`, `-101151` | **38 PASS / 5 FAIL** — stale now-playing state after playback completion; see `HANDOVER.md` → "Open defect" | `9abddbe` |
| full | `2026-09-24-055858`, `-060050` | BLOCKED — YouTube `LOGIN_REQUIRED` (external; cleared later the same day) | `9abddbe` |

**Resolved.** The gap this document used to flag is closed: `player` and `full` have now been run
against Phase 4 builds. `player` is 32/32 and `full` is 43/43 on three consecutive runs.

Reaching that required repairing defects the Phase 4 harness commit (`b8d4110`) introduced, which the
first Phase 4 tier runs exposed:

1. **The resume precondition was destroyed by the test itself.** The "ignore the offer" phase added in
   `b8d4110` left the video playing from the beginning, saving a position below `RESUME_MIN_MS` (20 s).
   A position that low is not resumable at all, so the four *choice* tests that followed found no offer
   on screen and failed. The choice tests now run **first**, while a ≥20 s position is known saved, and
   the destructive no-input phase runs **last**.
2. **A stale baseline.** `resume-continues-position` compared against a position measured several
   phases earlier. It now establishes the precondition itself and compares against the value the app
   *reports as resumable*, read back from its own log (`Resumable position for <id>: <n>s`).
3. **A stray key press.** The harness used to send `DPAD_CENTER` even when no offer was on screen;
   that landed on the player and toggled pause, poisoning later steps. Keys are now sent only after the
   offer is confirmed on screen, and a missing offer is recorded as a failure instead.
4. **Fixed sleeps raced the state they were waiting for** (`captions-enable`,
   `autoplay-advances-to-next-approved`, `end-of-video-handling`). The captions menu has an ~8 s idle
   timeout, so a fixed sleep could spend the very window the key press needed; and remote seeking to
   the end of a video consumes most of a fixed wait, which is why one run measured "228s of 229s" —
   one second short. These now synchronise on the event: `Wait-LogMatch` for the menu, and
   `Wait-QueueAdvance` for the queue actually moving on. `end-of-video-handling` also refuses to
   compare against an unreadable baseline, which had been turning an occasionally dropped status
   request into a silent false pass.

### Phase 4 device verification (manual ADB, not the harness)

Phase 4's catalog behaviour was verified by driving the device directly with `adb shell input
keyevent` and reading the accessibility tree (`uiautomator dump`) and the app log. Highlights, with
the exact signal observed:

- Catalog rendered with the parent's order and names; `adb` focus reports showed
  `Music → Nursery Songs → ABC Songs → Twinkle Twinkle → Wheels on Bus`.
- Focus ring measured by pixel sampling: RGB (36,114,67) = the theme's `KidFocusRing` composited,
  8 px = 4.dp, on a card 420 px wide vs 400 unfocused (the 1.05 scale).
- The card geometry matched Phase 1 exactly: artwork 400×225 px (16:9), card 400 px wide (200.dp).
- Pressing an unapproved catalog card produced `Blocked playback of unapproved video` and played
  nothing; pressing an approved one played it.
- Force-stop + relaunch with the sync failing logged `Catalog sync on startup: ServerUnavailable`
  while the shelves still rendered.
- Live reorder through the product's own Refresh button installed a new version and the screen order
  changed without an app restart.

### Known harness and device limitations

1. **`adb screencap` cannot capture the playing video on this TV.** With a video surface active, the
   frame comes back 100 % black with exactly one distinct colour (measured three times). Player
   screenshots need a photo of the screen. The harness's own player screenshots are affected.
2. **`uiautomator dump` returns no text while the video surface is on screen**, so player-screen
   assertions must read the app log instead (the harness does).
3. **Repeated `uiautomator dump` calls can destabilise the app's embedded server** — the harness takes
   its dumps at the end, after the API assertions have run.
4. **YouTube throttles anonymous access** from an IP that has asked too often; resolution then fails
   with `LOGIN_REQUIRED` and playback phases fail for that reason. Before blaming the app, grep the
   log for `LOGIN_REQUIRED`. The harness now names it.
5. **Two full-tier checks fail and are unresolved:** `audio-menu-lists-real-tracks` and
   `audio-switch-applied`. They predate the catalog work.
6. **The debug version label** (`v0.10.0-debug`) is drawn in the corner of debug builds and appears in
   every screenshot taken from one.
7. **`assembleRelease` needs a signing keystore** (`SECRET_REQUIRED`: `RELEASE_STORE_FILE`,
   `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` in `local.properties` or the
   environment). Without it the release type falls back to a non-existent keystore and fails, so a
   release-build screenshot cannot be produced on a machine that has no keystore.

---

## 5. Test infrastructure the project relies on

- **`DebugReceiver`** — 37 debug-only broadcast actions, registered in
  `app/src/debug/AndroidManifest.xml` **and** handled in the receiver's `when`. Both places are
  required; adding only one produces a silently dropped broadcast. Catalog-relevant actions and their
  use are listed in `PROJECT_CONTEXT.md` §11. Example:

  ```bash
  adb -s 172.16.1.2:5555 shell am broadcast -a tv.safetubeforkids.app.DEBUG_SYNC_CATALOG -p tv.safetubeforkids.app
  adb -s 172.16.1.2:5555 logcat -d -s SafeTube-Intent
  ```

- **`CatalogSyncDebug`** — a *persisted* debug switch that makes the sync's HTTP call fail, so
  "the server is unavailable, force-stop, relaunch" can be tested on a product whose server lives
  inside the app. Inert in release builds.
- **`OfflineSimulator`, `BandwidthOverride`** — pre-existing debug overrides for the resolver and the
  quality policy.
- **`CatalogEndToEndTest`** — the project's only test with nothing stubbed: real Netty, real routes,
  real file store, real HTTP, real Room.
- **`DEBUG_DUMP_CATALOG_UI`** — dumps exactly what the home screen would render (via the same
  projection the UI uses), which is how catalog rendering is asserted on a device where the screen
  cannot always be read.

---

## 6. Gaps an agent should close before claiming more than "unit tests pass"

```text
DONE      the harness 'player' and 'full' tiers against a Phase 4 build — player 32/32,
          full 43/43 on three consecutive runs (2026-09-24), after repairing the harness
DONE      the instrumented androidTest files in the catalog era — 19/19 PASS on the Mi Box 4
BLOCKED   tv-app/scripts/tv-emulator.ps1 — `emulator -accel-check` exits 3 ("Virtualization
          extension is not supported") on this host; the AVD and TV system image are present, so
          this is an environment limitation, not an application failure
NOT_RUN   a run on a second Android TV device or a different Android version
NOT_RUN   a release build (no keystore on the development machine)
OPEN      the two full-tier audio failures — reproduced unchanged since before Phase 2; the resolver
          reports "0 audio" for the videos tested, so the audio menu offers no real tracks. Kept
          separate from the catalog work: no evidence links them to Phase 4.
NOT_RUN   visual review of any screenshot — the agent has no image input. Geometry and pixels are
          measured programmatically instead, which is not a visual pass.
UNKNOWN   behaviour on a TV with a very large catalog (hundreds of items) beyond the projection
          test, which exercises 20 shelves x 30 items in the JVM only
```
