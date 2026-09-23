# Phase 4 — Android TV catalog UI and local-first runtime

```text
Base commit   017d68fda3a13f0cbf38472f488d840e37d88a78   (Phase 3)
Final commit  b8d411093bb415c199d8fbc3013169826614070b   feat: implement Android TV catalog UI and local-first runtime
Follow-up     3962d312c27edbcd0e075e9840ff88fe8464b118   docs: add example screenshots and describe the catalog
```

## Objective

Make the TV home screen the parent's catalog, rendered from the local Room database, with the network
off the rendering path — while `PlaybackAuthorization` stays the authoritative playback gate and the
existing player, kiosk, screen-time and approval behaviour keep working.

## Planned Scope

As specified: a catalog-driven home (category shelves, mixed playlist/video, parent ordering, parent
display names), enabled filtering, empty-catalog and empty-category handling, D-pad navigation with
obvious focus and focus restoration, Continue Watching from the existing playback history, local-first
startup with background synchronization, and tests. Out of scope: the parent web dashboard, a player
redesign, and searching/browsing YouTube.

## Implemented

**Catalog-driven home** (`ui/screens/HomeScreen.kt`, rewritten)

- One `LazyColumn` of shelves, one `LazyRow` per shelf, one `CatalogCard` per item.
- Shelf = enabled category, header = `displayName`, ordered `sort_order ASC, id ASC`.
- Card = enabled item, parent order, showing the parent's `displayName`; playlists and individual
  videos mix freely on one shelf.
- A shelf left with no enabled items is **dropped**; a disabled category or item is **hidden** (and
  stays in Room).
- The existing top bar is preserved (`SafeTube for Kids`, Refresh, Connect Phone, Settings) — partly
  because the project's harness identifies the library screen by it.
- The kiosk apps grid and its "Videos" card are preserved; the Videos view now shows the catalog.

**Rendering rules in one place, testable without a device** (`ui/screens/CatalogUiState.kt`) —
`CatalogUiProjection.build(catalog, thumbnails, resumable) -> CatalogUiState`. It re-applies the
parent's ordering (categories *and* items), drops disabled content and empty shelves, resolves artwork,
prepends Continue Watching, and produces `CatalogCardUi` / `CatalogShelfUi` models. Pure function; 23
tests.

**Cards** (`ui/components/CatalogCard.kt`) — new component with the **same geometry as the Phase 1
`VideoCard`**: 200.dp wide, 16:9 artwork, 12.dp corners, 1.05 focus scale, 4.dp focus ring, `bodySmall`
title, 11 sp badge. Artwork comes from the approved cache by identifier; a card with no cached artwork
keeps its exact size and shows an icon placeholder, so shelves never reflow.

**Atomic reads** — `CategoryDao.observeCatalog()` is a `@Transaction` `@Relation` query, so shelves and
their items are read in one transaction and a replacement is never observed half-applied.

**Continue Watching** — `PlaybackPositionDao.observeResumable(minPositionMs, maxPercent)`, a query
joining `playback_positions × videos × channels` with `INNER JOIN`s, ordered `updatedAt DESC`, limited
to positions ≥ 20 s and < 95 % of duration. The joins are the security property: a video whose
approval or source was withdrawn cannot appear.

**Local-first runtime** — `HomeViewModel` combines three Room flows into `catalogState`
(`WhileSubscribed(5_000)`, so database work stops while the player is on screen and the last value is
retained for an instant repaint on return). The UI subscribes to Room; a sync that changes the catalog
makes the flows re-emit and the screen redraws with no imperative reload anywhere.

**Background synchronization** — `SafeTubeApp` runs a sync ~3 s after startup and then every 15
minutes in its existing `appScope` (IO), reusing Phase 3's `CatalogSyncService` and its `Mutex`; the
home screen's Refresh button also requests one. Nothing awaits a sync on a render path, and there is
no `runBlocking` in the UI.

**Focus behaviour** — each card draws a ring **and** a scale change; the pressed card's id is written
down before navigating and re-focused on return (scrolling its shelf into view first). If the item is
gone after a sync, nothing is forced.

**Debug infrastructure** — `DEBUG_DUMP_CATALOG_UI` (dumps exactly what the home screen would render,
via the same projection), `DEBUG_CLEAR_RESUME_POSITIONS`, and catalog information added to
`DEBUG_GET_STATE_DUMP`.

**Harness** — `tv-e2e.ps1`'s resume phase was updated for the changed resume behaviour, and its
control-test loop now clears the resume offer before sending media keys (the offer no longer pauses
playback, so it could otherwise have swallowed the keys).

## Not Implemented

- **No parent dashboard for the catalog.** Still configured with `PUT /catalog` (or the debug
  broadcast). This is the largest remaining product gap: a family cannot use the catalog feature
  without an HTTP client.
- **No category detail screen** — optional in the specification and deliberately skipped; shelf-based
  navigation is simpler for a child.
- **No player redesign.** The player is the Phase 1 player.
- No catalog-search, no URL entry, no recommendations, and no change to how approved content is added.

## Deviations from the original Phase 4 plan

1. **`PlaybackController.kt` was changed, at the user's explicit request mid-phase.** Originally the
   resume prompt paused playback and waited for a choice. It now: starts the video **from the
   beginning immediately** (nothing waits on a decision), offers "Resume from m:ss" alongside it,
   seeks to the saved position only if that option is chosen, and **withdraws the offer after the
   app's existing 8-second menu-idle window** if nothing is chosen, carrying on from the beginning.
   Dismissing the offer no longer seeks (the child asked for the beginning by not asking for
   anything), and the old 30-second `RESUME_CHOICE_TIMEOUT_MS` auto-restart was deleted. This is why
   `PLAYER_CHANGED=YES` in the phase report: it is a deliberate product decision that overrides the
   "do not touch the player" scope note, and it is documented here so it is not mistaken for an
   accident. The player's Media3 setup, resolution, quality, captions, seeking, queue and menus are
   otherwise untouched.
2. **Debug actions were added** (`DEBUG_DUMP_CATALOG_UI`, `DEBUG_CLEAR_RESUME_POSITIONS`) plus a
   debug-only persisted sync-outage switch (`CatalogSyncDebug`), because the catalog cannot be verified
   on a real TV without them — the parent has no catalog UI, and the server lives inside the app.
3. **A test seam was added** to the sync transport: `CatalogApi.fetch()` returns
   `ServerUnavailable` when the debug switch is on. Inert in release builds.
4. **The harness was changed**, not only the app (see above).
5. **The README and HANDOVER were updated** afterwards, because the new screenshots made the missing
   catalog description and the now-wrong resume bullet obvious.

## Security Impact

- **`PlaybackAuthorization.kt` is unchanged** — still byte-identical to the Phase 1 baseline.
- **Catalog membership still cannot authorize playback.** The most direct demonstration is on the
  device: a catalog card for an unapproved video was focused and pressed, and the app logged
  `Blocked playback of unapproved video` / `Playback rejected: This video is not approved`, wrote
  nothing to the player and showed the child-facing error screen.
- Continue Watching cannot become a bypass: its query excludes withdrawn approvals, and selection
  still passes the gate.
- The UI does not pre-judge permission (it renders catalog cards regardless), so the UI cannot drift
  into being a second, weaker gate.
- `Continue Watching` is the only place the UI asks the approval model anything
  (`CatalogRepository.firstApprovedVideoOf`, to choose a playlist's starting video), and that is a
  starting point, not a grant.

## Database Changes

**No schema change.** Room stays at version 7; no new entity and no new migration. Additive read-only
queries were added to existing DAOs:

- `CategoryDao.observeCatalog()` + the `CategoryWithItems` relation POJO (one atomic read of the whole
  catalog).
- `PlaylistCacheDao.observeThumbnailIndex()` + `VideoThumbnailRow` (one query for all card artwork,
  rather than one per card).
- `PlaybackPositionDao.observeResumable(minPositionMs, maxPercent)` + `ResumableVideoRow` (Continue
  Watching).
- `CatalogRepository` gained `observeCatalogWithItems`, `observeVideoThumbnails`,
  `observeResumableVideos` and `firstApprovedVideoOf`.

## API Changes

**None.** No route, payload or status code changed. The catalog API is as Phase 3 left it; there is
still no server endpoint for the TV to push anything.

## UI Changes

- `ui/screens/HomeScreen.kt` — rewritten around catalog shelves, with focus restoration; top bar,
  kiosk grid, time-limit watch and empty state preserved.
- `ui/screens/HomeViewModel.kt` — rewritten to supply catalog and kiosk state; the approved-source
  refresh is retained (it is what makes playback possible at all) and Refresh now also requests a sync.
  It contains no validation, version, JSON or authorization logic.
- `ui/components/CatalogCard.kt` — new.
- `ui/screens/CatalogUiState.kt` — new.
- **Preserved and verified by measurement on the TV:** thumbnail 200.dp / 16:9 / 12.dp, focus scale
  1.05, focus ring 4.dp, shelf header `titleMedium`, card title `bodySmall`. Measured: artwork
  400×225 px, card 400 px wide, ring 8 px, focused card 420 px.
- `AppNavigation.kt`, `TvPlayerScreen.kt`, `SettingsScreen.kt`, `VideoCard.kt`, the theme and
  `PlaybackAuthorization.kt` are **byte-identical** to Phase 3.

## Tests

`506 → 542 tests` (+36), all green on a clean `:app:clean :app:testDebugUnitTest :app:assembleDebug`
(BUILD SUCCESSFUL, 46 classes, 0 failures, 0 errors).

| Class | Tests | Covers |
|---|---|---|
| `CatalogUiProjectionTest` | 23 | parent category order, parent item order, equal-sort-order tie-break, not alphabetical, mixed playlist/video, display names, hidden disabled content, dropped empty shelves, empty catalog, artwork lookup (video and playlist), Continue Watching order/filtering/badges, 20 shelves × 30 items |
| `CatalogHomeFlowTest` | 13 | real Room: the whole-catalog relation read in parent order, every emission during a replacement being wholly one catalog, artwork from the approved cache, Continue Watching filters and approval-driven removal, disabled rows retained, the playlist starting point, the security pair, and a catalog playlist card still unable to play an unapproved playlist |

No existing test was deleted or weakened. The one assertion removed from the harness
(`No resume choice`, which no longer exists) was replaced with assertions for the new rule.

## Physical Device Testing

Xiaomi Mi Box 4, Android 12 / API 31, 1920x1080, driven by ADB (`172.16.1.2:5555`). The APK used for
the final harness run is the committed build
(`sha256 FC0135771A3C4CD0EDF88B2B22F016097BD14F489E465A4761326942DB51C6BE`).

**The project's own harness, unmodified, on the final APK:** `-Tier smoke` → **12/12 PASS**
(`test-results/tv/2026-09-23-234624/result.json`), including `dpad-opens-approved-video` — i.e. the
harness pressed DOWN + CENTER on the *new catalog home* and an approved video started.

**Catalog behaviour, verified by driving the device and reading the accessibility tree and the app log:**

| Check | Observed |
|---|---|
| Cold launch renders the catalog | startup sync installed version 5 (4 categories, 6 items); shelves on screen |
| Parent category order | server order 0,1,2,3 → on screen `Cartoon, Music, Learning` (`Stories` empty, dropped) |
| Parent item order and mixed content | `Music: Nursery Songs / ABC Songs / Twinkle Twinkle / Wheels on Bus` in exactly that order via the card bounds |
| Parent display names | `Bedtime Songs` for a playlist whose YouTube id is `PLsuperFunEducationalSongs2026Official`; `Twinkle Before Bed` for video `MR5XSOdjKMA` |
| D-pad navigation | DOWN/UP between shelves, LEFT/RIGHT within (each move read back from the focus node), CENTER selects, BACK returns |
| Focus indication | ring colour measured **RGB (36,114,67)** = `KidFocusRing` composited, **8 px = 4.dp**, on a card **420 px** wide vs 400 unfocused |
| Focus restoration | Music → Twinkle Twinkle → player → BACK → `Twinkle Twinkle` focused; RIGHT then moved to `Wheels on Bus` |
| Approved video plays from the catalog | `[OK] Playing MR5XSOdjKMA`, `/status playing=true`; a catalog playlist card started its approved queue's first video |
| Unapproved catalog item is blocked | `[WARN] Blocked playback of unapproved video`, `[WARN] Playback rejected`, `/status` nothing, screen showed `This video can't be played` + Retry/Back |
| Disabled shelf and item | absent from the rendered state; all 7 rows (including disabled) still in Room |
| Empty catalog | `isEmpty=true`; screen showed `No videos yet / Ask a parent to add videos to SafeTube` |
| Live reorder while running | the product's Refresh button installed version 6 and the on-screen order changed — no restart |
| Server unavailable + force-stop + relaunch | log showed `Catalog sync on startup: ServerUnavailable`, and the shelves still rendered |
| Server recovery | a catalog published while the TV was "offline" became visible (version 11, new shelves) with no restart and no data clearing |
| Invalid server payload | `InvalidCatalog`, local version stayed 7, shelves unchanged, no blank screen |
| Version regression | server 5 vs local 12 → `VersionRegression`, local stayed 12 |
| Resume behaviour change | played from 0 s (never the saved 69 s) while the offer was up; `Resume chosen: 65s` jumped to 65 s; no input → `Resume offer withdrawn with no choice` after exactly 8 s with playback untouched |
| HOME then return | launcher took over, returning showed the catalog again, 0 crashes |
| Data preservation | approved library unchanged (2 sources, 51 videos; sessions 20; kiosk off; time limits Allowed); play events grew 250 → 264 from the videos played during testing |

**Not verified on the device:** the harness `player` and `full` tiers against this build (see Known
Issues), the emulator, and any visual judgement of the screenshots — the agent that produced them has
no image input, so the frames were checked programmatically (geometry from the accessibility tree,
pixel sampling for the focus ring) rather than by eye.

## Known Issues

1. **RESOLVED — the `full` and `player` harness tiers have now been run against this build.**
   `player` is 32/32 and `full` is 43/43 on three consecutive runs (2026-09-24). Both tiers initially
   failed, and the cause turned out to be a defect in the harness changes this phase made (not in the
   application); see "Post-Phase-4 verification" below and `docs/TESTING.md` §4.
2. **The player cannot be screenshotted on this TV.** With a video surface active, `adb screencap`
   returns a 100 %-black frame with one distinct colour (measured three times: player, settings row,
   open menu). This is also why the committed screenshots contain no player image.
3. **RESOLVED — Continue Watching is now curated by the catalog.** It used to be built from the
   child's own progress alone, so a TV with an empty catalog but a half-watched video showed that
   shelf instead of "No videos yet", and - more seriously - a video the parent had *removed* from the
   catalog stayed reachable from this shelf. A card now appears only when the video is still published:
   named by an enabled item, or belonging to an enabled playlist item inside an enabled category. This
   changes **visibility only**; `PlaybackAuthorization` is untouched and nothing is deleted for being
   unpublished, so the saved position comes back if the parent republishes the video.
4. **The example screenshots carry a `v0.10.0-debug` label** in the corner, because they were taken
   from a debug build (a release build needs a signing keystore the development machine does not have).
5. **A Connect Phone screenshot was captured but deliberately not committed**, because it displays the
   TV's setup PIN and QR code (the PIN rotates on every app start, but there is no reason to publish
   one). It remains in the gitignored artifact directory.
6. Two pre-existing `full`-tier audio checks remain unresolved (see `TESTING.md`).
7. The debug outage switch and the `initForTest(catalog = ...)` seam are test-only surface that a
   future cleanup could remove once the catalog has a parent UI.

## Post-Phase-4 verification and findings resolution

An independent verification pass ran the tiers this phase left unrun, and resolved the two product
findings it recorded. Results, kept deliberately separate by kind:

| Kind | Item | State |
|---|---|---|
| Build | Clean build from the Phase 4 source tree | PASS — reproducible APK |
| Unit tests | `:app:testDebugUnitTest` | **556/556 PASS** (542 baseline + 9 F6 + 5 F7), 46 classes |
| Instrumented | `:app:connectedDebugAndroidTest` on the Mi Box 4 | **19/19 PASS** |
| Harness `player` | Full player tier | **32/32 PASS** |
| Harness `full` | Three consecutive runs | **43/43 PASS** ×3 |
| Harness `smoke` | Device smoke on the final APK | **12/12 PASS** |

### Product findings (both resolved)

- **F6 — Continue Watching was not curated by the catalog.** It is now filtered to videos the parent
  still publishes (see Known Issue 3). Visibility only; `PlaybackAuthorization` is untouched, and
  unpublishing never deletes a position or an approval.
- **F7 — the catalog version counter could be reset by losing the catalog document.** The counter
  lived only inside `catalog.json`, so deleting or reinitialising that file restarted the sequence at
  0; the parent's next publish was then numbered *below* the version a TV already held, the TV refused
  it as a regression, and it stayed stranded until the counter climbed back past it — measured on the
  device as server 1 vs TV 3. The counter is now mirrored into a separate `catalog.version` file as a
  durable high-water mark, and the next version is `max(document, high-water) + 1`. Regression
  protection is unchanged; what changed is that a document reset can no longer rewind the sequence.
  If *both* files are lost the server is indistinguishable from a fresh install and the counter does
  legitimately restart — that residual case is documented and covered by a test rather than hidden.

### Harness defects (introduced by this phase's own harness edit, all repaired)

1. The "ignore the offer" phase added here saved a position below `RESUME_MIN_MS` (20 s), which is not
   resumable, so the four *choice* tests after it found no offer and failed. The choice tests now run
   first and the destructive phase last.
2. `resume-continues-position` compared against a stale baseline; it now reads back the value the app
   reports as resumable.
3. Keys were sent even with no offer on screen, landing on the player and toggling pause.
4. Fixed sleeps raced the state they awaited (`captions-enable`, `autoplay-advances-to-next-approved`,
   `end-of-video-handling`), now event-synchronised (`Wait-LogMatch`, `Wait-QueueAdvance`), with a
   deadline derived from the video's remaining duration.
5. `end-of-video-handling` could report a **silent false pass** when the embedded server dropped a
   status request: the null baseline made "did it move on?" trivially true. It now retries and, failing
   that, records a failure. Fixing this immediately exposed a real second defect (a fixed 150 s
   deadline that a video playing from 0 s could not meet), which is why the tiers were re-run after it.

### Pre-existing failures (unchanged, not Phase 4)

`audio-menu-lists-real-tracks` and `audio-switch-applied`. They fail identically before and after this
phase; the resolver reports "0 audio" for the videos tested, so the menu offers no real tracks.
Recorded, not fixed here, and not attributed to Phase 4.

### Environment limitation

The Android TV emulator cannot run on this host: `emulator -accel-check` exits 3 with "Virtualization
extension is not supported". The AVD and TV system image are present, so this is environmental. The
physical TV remains authoritative.

### Unexecuted review

`VISUAL_REVIEW=NOT_RUN`. The agent has no image input, and `adb screencap` returns a 100 %-black frame
while a video surface is active. Geometry and pixels are measured programmatically, which is **not** a
visual pass and is not reported as one.

## Final Commit

```text
b8d411093bb415c199d8fbc3013169826614070b   feat: implement Android TV catalog UI and local-first runtime
18 files changed, 1963 insertions(+), 326 deletions(-)

3962d312c27edbcd0e075e9840ff88fe8464b118   docs: add example screenshots and describe the catalog
6 files changed, 52 insertions(+), 5 deletions(-)
```

## Verification Status

| Claim | Status |
|---|---|
| Catalog-driven home, category/item ordering, mixed content, display names | VERIFIED — device (accessibility tree) + 23 projection tests |
| Enabled filtering, empty catalog, empty category | VERIFIED — device + tests |
| D-pad navigation, focus indication, focus restoration | VERIFIED — device (geometry and pixel measurement) |
| Local-first startup (no network on the render path) | VERIFIED — device (offline relocal, `ServerUnavailable` logged, shelves rendered) |
| Background sync (startup + 15 min + Refresh) | VERIFIED — device log and live reorder |
| Live UI update from a sync, without a restart | VERIFIED — device |
| Failure paths leave the local catalog alone (unavailable, invalid, regression) | VERIFIED — device + unit tests |
| Continue Watching | VERIFIED — device (shelf rendered with resume badges) and tests; see Known Issue 3 for the empty-catalog nuance |
| Catalog entry does not grant playback | VERIFIED — device + tests |
| Approved video plays from the catalog | VERIFIED — device + harness |
| Thumbnail/title dimensions unchanged | VERIFIED — measured on the device (400×225 px artwork, 400 px card) |
| `PlaybackAuthorization` unchanged | VERIFIED — `git diff` |
| Player otherwise unchanged | VERIFIED with one documented exception (resume behaviour, at the user's request) |
| `full`/`player` harness tiers | **NOT_RUN** on this build |
| Screenshots inspected visually | **NOT_RUN** — impossible for the agent that produced them |
| Emulator, instrumented tests, second device | **NOT_RUN** |
