# SafeTube for Kids — project context

Canonical handoff document. It describes **what actually exists in this repository**, not what was
planned. Where something could not be established from the repository or from recorded test
evidence, it says so explicitly (`NOT VERIFIED`, `UNKNOWN`).

Companion documents: [`ARCHITECTURE.md`](ARCHITECTURE.md), [`SECURITY_MODEL.md`](SECURITY_MODEL.md),
[`TESTING.md`](TESTING.md), [`PHASES/`](PHASES/). The older prose handover is `/HANDOVER.md`.

---

## 1. What this is, and why

An Android TV application where a parent decides what a child may watch, and the child can reach
**only** that. There is no search, no home feed, no "related videos", no recommendations, no ads and
no way to navigate to YouTube itself.

> **The fundamental model:** parents define what content is allowed; children can only reach approved
> content through the controlled application experience.

- **Platform.** Android TV. `minSdk 24`, `targetSdk 34`, `compileSdk 36`. Kotlin, Jetpack Compose,
  AndroidX Media3 for playback, Room for local storage, an embedded Ktor server inside the app, and
  NewPipeExtractor to turn an approved YouTube id into playable streams.
- **Reference device.** Xiaomi Mi Box 4 (`MIBOX4`), Android 12 / API 31, 1920x1080, reached over ADB.
- **The intended experience.** The TV shows the parent's shelves and nothing else; the whole thing is
  drivable with a five-button remote; opening a video plays it; a parent configures everything from a
  phone browser.
- **Security objective.** A video may play **only** if the parent approved it. That decision is made
  in exactly one place (`PlaybackAuthorization`) and cannot be influenced by the catalog, the UI, a
  deep link, an intent extra, or the child pressing anything.

**Provenance.** This repository's root commit is by Prasanna K — the fork point of
[ParentApproved.tv](https://github.com/Prasanna79/parentapproved). The approval model, the phone
dashboard and the "approved content only" premise come from upstream; the player, the security
boundary, the catalog and the TV test harness were rebuilt here. Upstream licence retained
(`/LICENSE`).

---

## 2. Current implementation status

`VERIFIED` means it was actually run and observed, and the evidence is named. `IMPLEMENTED` means the
code exists and compiles but was not behaviourally verified. Anything else is stated as-is.

| Area | Status | Evidence / notes |
|---|---|---|
| **Android TV application** | VERIFIED | Installs, launches and drives by remote on the Mi Box. Harness smoke tier 12/12 on the Phase 4 APK (`test-results/tv/2026-09-23-234624/result.json`). |
| **Parent/admin configuration (phone)** | VERIFIED (pre-catalog era) | The TV serves its own dashboard at `/`; approve/remove sources, export/import the library, screen-time settings, kiosk. Verified on device in the Phase 1 era (`/HANDOVER.md`). |
| **Parent configuration of the catalog** | IMPLEMENTED_NOT_VERIFIED as a *UI* | There is **no dashboard UI** for the catalog. It is configured with `PUT /catalog` (curl/HTTP) or the debug broadcast `DEBUG_SYNC_CATALOG`. |
| **Catalog model** | VERIFIED | Three tables, ordering, enabled flags, parent display names, mixed playlist/video, versioning. `CatalogDatabaseTest` (28), `CatalogMigrationTest` (8). |
| **Server catalog API** | VERIFIED | `GET /catalog`, `PUT /catalog` behind the existing session auth. 37 route tests; also exercised against the real TV over HTTP. |
| **Local Room catalog** | VERIFIED | Room v7, tables `categories` / `content_items` / `catalog_metadata`; migration 6→7 proven to preserve all pre-existing data. |
| **Catalog synchronization** | VERIFIED | `CatalogSyncService` + `HttpCatalogApi`; 29 tests over real Room and a real socket, 5 end-to-end tests through a real Netty server. |
| **Local-first startup** | VERIFIED | Device: force-stop + relaunch with the sync failing logged `Catalog sync on startup: ServerUnavailable` while the shelves still rendered. |
| **Background synchronization** | VERIFIED (device) | ~3 s after startup, then every 15 minutes; also triggered by the Refresh button. Observed on the TV (`Catalog sync: installed version 6` after pressing Refresh). |
| **D-pad navigation** | VERIFIED | Device: UP/DOWN between shelves, LEFT/RIGHT within, CENTER selects, BACK returns; each move checked against the accessibility tree. |
| **Playback authorization** | VERIFIED | Unchanged since Phase 1. Device: an unapproved catalog item logged `Blocked playback of unapproved video` and played nothing. |
| **YouTube playback** | VERIFIED | Device: an approved video plays from a catalog card (`Playing MR5XSOdjKMA`), and the harness opens one through the D-pad. |
| **Modern player** | VERIFIED (Phase 1 era) | Media3 player with menus, captions, quality, audio language, speed, fit, resume, approved queue. Full-tier harness green 38/38 on `3a89e88` (2026-09-23 01:40) and at other points; later full runs failed two **audio** checks (see `TESTING.md`). |
| **Physical TV testing** | VERIFIED | 50+ recorded harness runs in `test-results/tv/` (gitignored) plus Phase 2–4 device sessions described in `PHASES/`. |
| **Automated tests** | VERIFIED | 46 classes / **542 tests / 0 failures / 0 errors** on `3962d31` (re-run during this documentation task). 47 unit-test files, 3 instrumented. |
| **Instrumented (`androidTest`) suite** | NOT_RUN (current era) | 3 files exist; not run since the catalog work began. |
| **Android TV emulator** | NOT_RUN (current era) | `tv-app/scripts/tv-emulator.ps1` exists; no recorded run for Phase 2–4. |

---

## 3. Repository map

```text
AI_CONTEXT.md                  AI handoff (root, read first)
HANDOVER.md                    older prose handover: device pitfalls, verified behaviours
README.md                      public description, build, screenshots
LICENSE                        upstream licence (retained)
.gitattributes                 keeps *.png/*.apk binary through line-ending conversion
.github/workflows/ci.yml       CI: gradlew assembleDebug testDebugUnitTest, node --check on app.js
docs/                          these documents + docs/screenshots/
tv-app/                        the Android application (single Gradle module `:app`)
  app/src/main/java/tv/safetubeforkids/app/
    MainActivity.kt            starts the server service, sets Compose content
    SafeTubeApp.kt             Application: init, source refresh, background catalog sync
    ServiceLocator.kt          process-wide wiring (database, sessions, catalog store, sync)
    playback/                  the player + THE security gate
    data/cache/                Room database, entities, DAOs for the approval model
    data/catalog/              the catalog: entities, DAOs, repository, contract, sync
    server/                    embedded Ktor server and all HTTP routes
    ui/                        Compose screens, components, theme, navigation, player UI
    timelimits/  kiosk/  relay/  auth/  util/  debug/
  app/src/test/                47 unit-test files (JUnit + Robolectric + MockWebServer + Ktor test host)
  app/src/androidTest/         3 instrumented files (not run in the current era)
  scripts/tv-e2e.ps1            the TV end-to-end harness (smoke / player / full tiers)
  scripts/tv-emulator.ps1       emulator-based verification
test-results/                  harness artifacts (gitignored, 50+ runs)
```

120 Kotlin files at the Phase 1 baseline (`b130c12`); 152 at `HEAD`.

---

## 4. Room database

```text
class       CacheDatabase  (tv.safetubeforkids.app.data.cache)
name        "parentapproved_cache"
version     7
exportSchema false            (see "Known limitations")
singleton   CacheDatabase.getInstance(context); tests use getInMemoryInstance(context)
```

**Entities (10)** — 7 pre-existing, 3 added in Phase 2:

| Table | Entity | Purpose |
|---|---|---|
| `videos` | `VideoEntity` | The **approved video cache**. Primary key `videoId`; `playlistId` is the source id. This table *is* the permission list. |
| `channels` | `ChannelEntity` | The **approved sources** (playlist / video / channel). Unique index on `source_id`. FK target for the approval model. |
| `play_events` | `PlayEventEntity` | Watch history, used for screen-time accounting and stats. |
| `playback_positions` | `PlaybackPositionEntity` | One row per video: where the child left off (`positionMs`, `durationMs`, `updatedAt`). |
| `time_limit_config` | `TimeLimitConfigEntity` | Singleton row: daily limits, bedtime, manual lock, bonus minutes. |
| `kiosk_config` | `KioskConfigEntity` | Singleton row: kiosk enabled, enforce limits on all apps. |
| `app_whitelist` | `WhitelistEntity` | Apps allowed in kiosk mode. Unique index on `package_name`. |
| `categories` | `CategoryEntity` | **Phase 2.** One parent-defined shelf. |
| `content_items` | `ContentItemEntity` | **Phase 2.** One entry inside a shelf: a playlist or a single video. |
| `catalog_metadata` | `CatalogMetadataEntity` | **Phase 2.** Singleton row of synchronization metadata. |

**DAOs:** `PlaylistCacheDao` (`videos`), `ChannelDao`, `PlayEventDao`, `PlaybackPositionDao`,
`TimeLimitDao`, `KioskDao`, `WhitelistDao`, plus Phase 2's `CategoryDao`, `ContentItemDao`,
`CatalogMetadataDao`.

**Migrations:** `MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`, `MIGRATION_4_5`, `MIGRATION_5_6`,
`MIGRATION_6_7` — all registered in `getInstance`. **There is no
`fallbackToDestructiveMigration` anywhere**; the 6→7 migration is purely additive (three new tables)
and `CatalogMigrationTest` proves the pre-existing data survives it.

### Catalog tables in detail

**`CategoryEntity` → `categories`** — a parent-defined shelf.

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | Stable, opaque, supplied by the configuration source. **Never derived from the display name**, so renaming cannot orphan items or reorder the home screen. |
| `display_name` | TEXT | The name the child sees. Independent of any YouTube title. |
| `sort_order` | INTEGER | Mandatory. Explicit parent ordering. |
| `enabled` | INTEGER | Boolean. Disabled shelves are hidden by the UI but never deleted. |
| `created_at`, `updated_at` | INTEGER | Row bookkeeping (stamped at sync time). |

Index: `index_categories_sort_order` (the table's only access pattern is `ORDER BY sort_order`).

**`ContentItemEntity` → `content_items`** — one entry in a shelf.

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | Stable id. Globally unique across the whole catalog (it is the primary key). |
| `category_id` | TEXT NOT NULL | FK → `categories.id`. |
| `type` | TEXT | `PLAYLIST` or `VIDEO` (stored by enum *name*, never ordinal). |
| `display_name` | TEXT | The parent's name for the entry — authoritative for display. |
| `sort_order` | INTEGER | Item ordering within the shelf. |
| `youtube_playlist_id` | TEXT NULL | Set for `PLAYLIST`. |
| `youtube_video_id` | TEXT NULL | Set for `VIDEO`. |
| `enabled` | INTEGER | Boolean. Hidden but retained when false. |
| `created_at`, `updated_at` | INTEGER | Stamped at sync time. |

- **Foreign key:** `category_id` → `categories(id)` `ON DELETE CASCADE`, so deleting a category
  cannot orphan its items (`Room` enables `PRAGMA foreign_keys = ON` at open; the cascade is tested).
- **Index:** `index_content_items_category_id_sort_order` on `(category_id, sort_order)` — the exact
  shape of the item query. `EXPLAIN QUERY PLAN` in the tests confirms it is used.
- **There is deliberately no approval column** and no FK to `channels`/`videos`. See
  [`SECURITY_MODEL.md`](SECURITY_MODEL.md).

**`CatalogMetadataEntity` → `catalog_metadata`** — one row (`id = 1`).

| Column | Meaning |
|---|---|
| `catalog_version` | The version currently **installed** locally (0 = never synced). Only a successful replacement moves it. |
| `server_version` | The newest version the server has been *seen* to advertise, recorded even when the sync then fails. |
| `last_successful_sync_at` | When the local catalog was last replaced. Unchanged by an `AlreadyCurrent` sync. |
| `last_attempt_at` | When the last attempt started. Newer than `last_successful_sync_at` ⇒ the last attempt did not complete. |

### Ordering, exactly

- Categories: `sort_order ASC, id ASC`.
- Items: `sort_order ASC, id ASC`.
- The tie-break on `id` exists because **duplicate sort orders are legal** (Phase 2 decided this
  deliberately); it also costs one small sort inside a category, which the tests measure and accept.
- Nothing sorts alphabetically, by insertion order, by rowid, by YouTube title, or by play time.
  `Continue Watching` is the one shelf ordered differently, and it orders by `updatedAt DESC`
  (see §8).

---

## 5. Catalog model

```text
Catalog
 ├── Category  (arbitrary parent-chosen name, e.g. "Cartoon", "Bedtime Songs", "Science")
 │    ├── ContentItem  type = PLAYLIST   -> youtubePlaylistId
 │    ├── ContentItem  type = VIDEO      -> youtubeVideoId
 │    └── ...                            (any mixture, any count, parent order)
 ├── Category
 │    └── ...
 └── ...
```

- **Category names are arbitrary.** Nothing in the code knows the words "Cartoon", "Music",
  "Learning" or "Stories" — those appear only in tests and documentation. Nothing hardcodes a
  category count. A parent may configure zero shelves, or twenty.
- **Items are nested inside their category.** An item that references a non-existent category is
  therefore *unrepresentable* in the wire contract rather than merely checked.
- **A shelf may mix playlists and individual videos** in any order; the UI does not separate them.
- **An individual video is not wrapped in a fake playlist.** It carries `youtubeVideoId` and no
  playlist id; a playlist carries `youtubePlaylistId` and no video id. The schema enforces this at
  construction time and the validator enforces it on the wire.
- **`displayName` is stored, not derived.** A parent can point "Nursery Songs" at a playlist actually
  titled "Super Fun Educational Songs 2026 Official Playlist"; the child sees "Nursery Songs". This is
  verified on the device.
- **`enabled`** hides content without deleting it: rows stay in Room so a parent can switch them back
  on. The UI drops disabled categories, disabled items, and shelves left with nothing to press.
- **Schema version** (`schemaVersion = 1`) versions the *wire contract*; the TV refuses a contract
  version it does not speak.
- **Catalog version** (`catalogVersion`) is a monotonically increasing integer assigned **by the
  server**, never by a client. It orders synchronization decisions: newer ⇒ replace, equal ⇒ do
  nothing, older ⇒ refuse.

---

## 6. Catalog synchronization

`CatalogSyncService.syncCatalog()` — one entry point, one fixed order, with a `Mutex` so two
overlapping syncs cannot interleave. It is triggered from `SafeTubeApp` (startup + every 15 minutes)
and by the home screen's Refresh button. It never runs on the UI thread and nothing awaits it on a
render path.

```text
1  fetch          HttpCatalogApi.fetch()  -> GET /catalog with the parent session token
                  (5s connect, 5s read, 10s whole-call timeouts; IOException -> ServerUnavailable)
2  HTTP           200 -> body; 401/403 -> Unauthorized; anything else -> ServerUnavailable(reason)
3  JSON parse     CatalogJson.decodeSnapshot(body); failure -> InvalidResponse(reason)
4  schema check   snapshot.schemaVersion != 1 -> UnsupportedSchema(server, supported)
                  (BEFORE this, the observed serverVersion is recorded -- see below)
5  validate       CatalogPayloadValidator.validate(categories) -- the SAME validator the server runs
                  any problem -> InvalidCatalog(problems), nothing written
6  version check  server <  local -> VersionRegression; nothing written
                  server == local -> AlreadyCurrent;   nothing written
7  map            CatalogMapper.toEntities(categories, syncedAt) -> CategoryEntity/ContentItemEntity
8  transaction    CatalogRepository.replaceCatalog(...) -- delete children, delete parents, insert
                  parents, insert children, upsert metadata, ALL in one Room transaction
9  metadata       catalogVersion + lastSuccessfulSyncAt + lastAttemptAt stamped inside that same
                  transaction, so "installed version" and "synced at" can never disagree
10 result         Updated(newVersion) or LocalWriteFailed(reason)
```

**Result states** (`CatalogSyncResult`, 9 of them):

| State | Meaning | Local catalog |
|---|---|---|
| `Updated(v)` | The local catalog was replaced with the server's, version `v`. | Replaced |
| `AlreadyCurrent(v)` | Server version equals the installed version. Nothing rewritten. | Untouched |
| `ServerUnavailable(reason)` | No connection, timeout, or a non-success HTTP status. | Untouched |
| `Unauthorized` | 401/403, or no session token available. | Untouched |
| `InvalidResponse(reason)` | The body was not a readable catalog document. | Untouched |
| `UnsupportedSchema(server, supported)` | Readable JSON, wrong contract version. | Untouched |
| `InvalidCatalog(problems)` | A complete, readable catalog that failed validation. | Untouched |
| `VersionRegression(server, local)` | The server offered an older catalog than the installed one. | Untouched |
| `LocalWriteFailed(reason)` | The Room transaction failed; it rolled back. | Untouched (rolled back) |

**Metadata semantics on failure.** The observed `serverVersion` is recorded as soon as it is known —
including when validation or the version check then refuses the payload — so a parent (or a support
session) can see "the server is at 11, this TV is at 10". `catalogVersion` moves **only** as part of a
successful replacement, which is what makes it trustworthy as "what the child is actually seeing".

> **Failed synchronization never destroys a valid local catalog.** Every failure path above returns
> before or *inside* the transaction: either nothing is written, or the single transaction rolls back.
> This is verified per state (29 sync tests, 5 end-to-end tests, and device sessions where the sync
> failed while the shelves stayed on screen).

> **An explicit empty catalog is valid.** `{"schemaVersion":1,"catalogVersion":N,"categories":[]}` is
> accepted by the server and installed by the TV (verified on the device: the home showed the
> deliberate empty state). A *malformed* empty payload (`{}`) is not: `categories` and `schemaVersion`
> have no defaults, so `{}` fails to decode and is reported as `InvalidResponse`, leaving the local
> catalog alone.

---

## 7. Server catalog API

The server is **embedded in the TV app** (Ktor + Netty on port 8080) — there is no separate host. It
serves the parent dashboard and the JSON API. See [`ARCHITECTURE.md`](ARCHITECTURE.md).

```text
GET  /catalog     AUTH REQUIRED   -> 200 CatalogSnapshot | 401
PUT  /catalog     AUTH REQUIRED   -> 200 stored snapshot | 400 | 401 | 409
```

- **Authentication** reuses the existing parent scheme: `Authorization: Bearer <session token>` or
  the `session` cookie, validated by the same `SessionManager` that guards `/playlists`,
  `/sources/export`, `/time-limits` and the rest. No second auth scheme was introduced. Tokens are
  obtained from `POST /auth` with the TV's PIN.
- **Request** (`CatalogPutRequest`):

```json
{ "schemaVersion": 1,
  "expectedCatalogVersion": 10,
  "categories": [
    { "id": "cat-music", "displayName": "Music", "sortOrder": 1, "enabled": true,
      "items": [
        { "id": "i-nursery", "type": "PLAYLIST", "displayName": "Nursery Songs", "sortOrder": 0,
          "youtubePlaylistId": "PLxxxxxxxx", "youtubeVideoId": null, "enabled": true },
        { "id": "i-twinkle", "type": "VIDEO", "displayName": "Twinkle Before Bed", "sortOrder": 1,
          "youtubePlaylistId": null, "youtubeVideoId": "MR5XSOdjKMA", "enabled": true }
      ] }
  ] }
```

- `schemaVersion` and `categories` are **required**; a missing key is a 400 rather than an implicit
  empty catalog. `expectedCatalogVersion` is optional (see below). There is **no** authoritative
  `catalogVersion` field in the request: the server owns version assignment, and an unknown
  `catalogVersion` key in the body is ignored rather than honoured.
- **Response**: the stored `CatalogSnapshot` (`schemaVersion`, `catalogVersion`, `categories`) — the
  same shape `GET` returns, so a client can use the PUT response as the new state.
- **Status codes:** `200` stored · `400` unreadable payload, unsupported `schemaVersion`, or invalid
  catalog (with a `details` array naming each problem) · `401` no valid session · `409`
  `expectedCatalogVersion` did not match, returning the version actually stored so the caller can
  retry.
- **Optimistic concurrency:** when `expectedCatalogVersion` is present and differs from the stored
  version, the write is refused with 409 instead of silently overwriting another parent session's
  change.
- **Validation is the same code the TV runs** (`CatalogPayloadValidator`): blank ids, blank names,
  duplicate category ids, duplicate item ids (including across categories, since the item id is the
  Room primary key), unsupported types, `PLAYLIST` without a playlist id, `PLAYLIST` carrying a video
  id, `VIDEO` without a video id, `VIDEO` carrying a playlist id, and structurally unusable YouTube
  ids. YouTube ids are checked by rebuilding the canonical URL and re-parsing it with the app's
  existing `ContentSourceParser`, so the same rules the dashboard already enforced apply here. No new
  length or character rules were invented.
- **Persistence:** `catalog.json`, in the app's private `filesDir`, written by `FileCatalogStore`.
  It is **not** in Room — see §9. The version lives inside the document, so it cannot be reset by a
  restart. Writes go to a temporary file and are moved into place
  (`Files.move(..., REPLACE_EXISTING)`) so a process death mid-write cannot truncate the catalog; a
  filesystem that refuses the move falls back to an in-place write.
- **Concurrency:** one lock in `BaseCatalogStore` covers reads and writes, and the next version is
  derived from the loaded document inside the same critical section that saves it, so two concurrent
  `PUT`s cannot be handed the same version (tested with real threads).
- **A corrupt/unreadable document reads as "nothing configured" (version 0)** rather than erroring.
  That is the safe direction: every TV holding a catalog is above version 0 and refuses to move down,
  so a corrupted document cannot push an empty catalog onto a TV. The flip side is a real operational
  trap — see §12.

---

## 8. Android TV UI (Phase 4)

`MainActivity` → `AppNavigation` (`NavHost`) → `HomeScreen`. Destinations: `home`, `playback/{videoId}/{playlistId}/{startIndex}`,
`settings`, `connect`, `lock/{reason}`. The catalog is the **root** destination; there is no separate
category screen (deliberately — shelf-based navigation is simpler for a child).

- **Home** = the parent's catalog. One `LazyColumn` of shelves, one `LazyRow` per shelf.
- **Category shelves** = one per enabled category, header = `displayName`, ordered
  `sort_order ASC, id ASC`. A category whose enabled items are empty is **dropped** (a heading over
  dead space was judged worse than nothing).
- **Cards** = one per enabled item. `CatalogCard` shows the parent's `displayName`, artwork, and an
  optional badge. A `PLAYLIST` and a `VIDEO` card look and behave the same way; the type only decides
  where the press goes.
- **Continue Watching** = first shelf, when there is anything in it. Its query joins
  `playback_positions` × `videos` × `channels`, so it can only contain videos that are **still
  approved**, ordered by `updatedAt DESC`, limited to positions ≥ 20 s and < 95 % of duration — i.e.
  exactly what the player would offer to resume. If it is empty the shelf does not appear.
- **Empty state** = "No videos yet / Ask a parent to add videos to SafeTube". No spinner, no feed, no
  suggestion, no network wait.
- **Loading state** = none is needed: the screen renders Room's current contents immediately. A
  first-run TV with nothing configured gets the empty state, not a spinner.
- **Disabled state** = hidden (not greyed out, not shown as unavailable).
- **Artwork** = taken from the approved video cache by identifier (a playlist uses its opening
  video's thumbnail) through the existing Coil setup. A card with no cached artwork keeps its exact
  size and shows an icon placeholder, so the shelf never reflows. Artwork is a *local* lookup — the
  catalog UI itself makes no HTTP request.
- **Card geometry (preserved from Phase 1, verified by measurement on the TV)**:
  width **200.dp**, artwork aspect ratio **16:9**, corner radius **12.dp**, focus scale **1.05**,
  focus ring **4.dp**. Measured on the device: artwork 400×225 px, card 400 px wide, ring 8 px = 4.dp,
  focused card 420 px wide. Shelf header typography is `titleMedium` (16 sp SemiBold); card titles are
  `bodySmall` (14 sp Medium) — unchanged.
- **Focus indication** = a ring **and** a scale change (never colour alone). Measured on the device at
  RGB (36,114,67) — the theme's `KidFocusRing` composited — i.e. plainly visible from a couch.
- **Focus restoration** = pressing a card writes down its id; when the screen returns from the player
  it scrolls that shelf into view and re-focuses the card. If the item no longer exists after a sync,
  nothing is forced and normal focus behaviour applies.
- **D-pad behaviour** (verified on the Mi Box, each move checked against the accessibility tree):

  | Key | Behaviour |
  |---|---|
  | `DPAD_DOWN` / `DPAD_UP` | Move between shelves (and into the top bar from the first shelf) |
  | `DPAD_LEFT` / `DPAD_RIGHT` | Move within a shelf, in the parent's item order |
  | `DPAD_CENTER` / `ENTER` | Select the focused card and open the player |
  | `BACK` | Leave the player and return to the catalog, with focus restored |

- **No touchscreen assumptions anywhere.** Every control is a Compose focusable node driven by the
  D-pad; `android.hardware.touchscreen` is declared `required="false"` and there is no gesture, drag
  or long-press handling in the child-facing UI.
- **The child-facing UI exposes no admin function**: no edit, add, remove, reorder, URL entry, search,
  server configuration or sync credential. The parent's Settings and Connect Phone buttons are one
  press away by product decision (documented in `/HANDOVER.md`), and they lead to parent screens that
  *cannot* approve content from the TV.

---

## 9. Why there are two catalogs

```text
SERVER CATALOG                              TV RUNTIME CATALOG
catalog.json in the app's filesDir          Room: categories / content_items / catalog_metadata
owned by the parent's configuration         owned by the TV, written only by the sync
versioned, server-assigned                  versioned, records what it installed
read/written by GET/PUT /catalog            read by the UI on every frame
```

They are deliberately **separate stores**, and the reason is concrete: on this product the server
runs inside the TV app, so if the server store *were* the Room catalog, then "sync" would be copying
a table onto itself. Last-known-good would have nothing to fall back to, a version-regression check
would compare a number with itself, and the whole synchronization design would be vacuous.

The same reasoning is why the server catalog is a file rather than a Room table: it is the parent's
*configuration*, while Room holds the TV's *runtime copy* plus the approval model. Two different
concerns, two different stores, one versioned contract between them.

---

## 10. Local-first behaviour, and what happens when things fail

```text
Application starts
   -> Room opens
   -> local catalog is observed and rendered immediately   (no HTTP on this path)
   -> ~3 s later, a background sync runs
   -> sync validates the COMPLETE payload, then replaces atomically
   -> Room invalidates -> flows re-emit -> the UI redraws on its own
```

Nothing on the render path awaits the network, and there is no `runBlocking` on any UI path.

| Situation | Result |
|---|---|
| Server unavailable / times out | `ServerUnavailable`; local catalog and UI unchanged; retried at the next interval |
| Malformed response (`{}`, HTML, truncated) | `InvalidResponse`; local catalog unchanged |
| Authentication fails (401/403, or no token) | `Unauthorized`; local catalog unchanged |
| Unsupported `schemaVersion` | `UnsupportedSchema`; local catalog unchanged |
| Server version goes backwards | `VersionRegression`; the newer local catalog is kept — **no rollback** |
| A fresh catalog fails validation | `InvalidCatalog`; nothing from it is applied, not even the valid shelves |
| The local write fails mid-transaction | `LocalWriteFailed`; the transaction rolled back; previous catalog intact |
| Server version equals the local one | `AlreadyCurrent`; nothing rewritten, no focus jump, no card flashing |
| Catalog explicitly empty | Installed as an empty catalog; the UI shows its empty state |
| Catalog item not approved | The card renders and can be pressed, and `PlaybackAuthorization` refuses the video |

---

## 11. Debug-only infrastructure

All of these live in `DebugReceiver` and are registered in `src/debug/AndroidManifest.xml`, so they
exist **only in debug builds**. They never appear in the child-facing UI, and they log no secrets.
37 actions are registered; the catalog-relevant ones are:

```text
DEBUG_SYNC_CATALOG              run a catalog sync now and report the result
DEBUG_CATALOG_SYNC_UNAVAILABLE  persisted debug switch that makes the sync's HTTP call fail
                                (release builds ignore it: CatalogSyncDebug.init is a no-op)
DEBUG_DUMP_CATALOG_UI           dump exactly what the home screen would render, via the same projection
DEBUG_CLEAR_RESUME_POSITIONS    forget saved playheads (empties Continue Watching)
DEBUG_GET_STATE_DUMP            sources/videos/events/sessions/catalog counts and version
DEBUG_GET_PIN / DEBUG_PLAY_VIDEO / DEBUG_SIMULATE_OFFLINE / DEBUG_FULL_RESET / ...
```

Example (the device is at `172.16.1.2:5555`):

```bash
adb -s 172.16.1.2:5555 shell am broadcast -a tv.safetubeforkids.app.DEBUG_DUMP_CATALOG_UI -p tv.safetubeforkids.app
adb -s 172.16.1.2:5555 logcat -d -s SafeTube-Intent
```

---

## 12. Known limitations, risks and open questions

Ordered by how much they would bite the next person.

1. **Enabling Room schema export crashes a clean build.** `exportSchema = false` is deliberate.
   Setting it to `true` makes Room's processor serialise the schema bundle, and KSP puts the module's
   `kotlinx-serialization` 1.8.0 (pulled in by Ktor) and `room-migration` 2.8.4's 1.8.1 on one
   classloader, producing
   `AbstractMethodError: ... FieldBundle$$serializer does not define ... typeParametersSerializers()`.
   Fixing it means moving the app's serialization version — a decision nobody has taken. Consequence:
   there is **no `app/schemas/` JSON**, and `MigrationTestHelper` cannot be used as-is. The migration
   is instead tested against a hand-built v6 database whose DDL is copied from Room's own generated
   `CacheDatabase_Impl.kt`, and Room's `onUpgrade` schema validation is the oracle.
2. **Losing the server's `catalog.json` resets its version counter to 0, and every TV already above it
   then refuses every future catalog** (permanent `VersionRegression` until the server's counter
   climbs past the TV's). This was observed during Phase 4 testing and repaired by re-publishing
   repeatedly. It is Phase 3's regression protection behaving as specified, but it is an operational
   trap: the server may want to persist its version independently, or offer an explicit
   parent-initiated rollback.
3. **A parent has no UI for the catalog.** Today it is `PUT /catalog`. Until a dashboard exists, a
   family cannot use the catalog feature without an HTTP client.
4. **Catalog membership is not approval, by design — and that has a UX consequence.** If a parent
   configures a catalog without also approving those videos in the SafeTube approval model, the cards
   render but refuse to play. This is the specified security model, not a bug; it is worth surfacing
   in the eventual dashboard ("this item is not approved yet").
5. **The harness `full` and `player` tiers have not been run against any Phase 2–4 build.** Their last
   recorded runs predate Phase 2, and the newest full run before that failed two audio checks. The
   catalog change rewrote `HomeScreen`, so the player-tier assumptions deserve a fresh run.
   See [`TESTING.md`](TESTING.md).
6. **`adb screencap` cannot capture the playing video on the Mi Box**: the frame comes back 100 %
   black with one distinct colour whenever a video surface is active. Player screenshots need a photo
   of the screen; the TV harness's own player screenshots are affected the same way.
7. **Instrumented tests and the emulator path are unverified in the current era.**
8. **The repository's history has no "phase" labels.** Phases 1–4 are a retroactive framing from task
   instructions; `docs/PHASES/` maps them onto actual commits.
9. **Two pre-existing full-tier failures** (`audio-menu-lists-real-tracks`, `audio-switch-applied`)
   are unresolved and predate the catalog work.
10. **The app version has not moved** through Phases 2–4: still `0.10.0` / `versionCode 16`, the same
    as the Phase 1 release commit. Anything that keys off the version (an update checker, a release
    asset) will not see the catalog work.
11. **`relay/` is inherited code pointed at upstream infrastructure** and is not usable from this
    fork.
12. **Vestigial test-only seam:** `ServiceLocator.initForTest` takes an optional `catalog` parameter
    that nothing calls, and `InMemoryCatalogStore` exists mainly for it. Harmless, but it is dead
    surface.

---

## 13. Secrets and configuration

No secret is stored in this repository, and none may be added. Specifically:

- **The TV's PIN** is generated in memory at every app start (`PinManager`) and displayed on the TV.
  It is never persisted and never committed.
- **Session tokens** are random 32-byte hex values created at runtime and persisted only on the TV
  (private `SharedPreferences`).
- **Release signing** reads `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD` from `local.properties` or the environment — `SECRET_REQUIRED` for a release
  build. `local.properties` is gitignored.
- `BuildConfig.VERSION_CHECK_URL` is empty by design, so the update checker is inert.
- `BuildConfig.RELAY_URL` points at upstream's relay and is not a credential.
- Harness artifacts under `test-results/` may contain device logs (including a runtime PIN); that
  directory is **gitignored** and must stay that way.
