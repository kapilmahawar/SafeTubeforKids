# Architecture

The real architecture, with the class names that exist in the repository at
`3962d312c27edbcd0e075e9840ff88fe8464b118`. Everything here is in the single Gradle module
`:app` (`tv-app/app`), package root `tv.safetubeforkids.app`.

---

## 1. The whole picture

```text
                        PHONE (browser)                  REMOTE (D-pad)
                              │                                │
                        HTTP :8080                             │
                              ▼                                ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  ANDROID TV APP  (one Android process, one APK)                              │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │ EMBEDDED KTOR SERVER (Netty, port 8080)   server/                      │  │
│  │   AuthRoutes        POST /auth, /auth/refresh        (PIN -> session)  │  │
│  │   PlaylistRoutes    /playlists, /sources/export, /sources/import       │  │
│  │   CatalogRoutes     GET /catalog, PUT /catalog        (Phase 3)        │  │
│  │   PlaybackRoutes    /playback/pause|skip|stop                          │  │
│  │   StatsRoutes       /stats, /stats/recent                              │  │
│  │   TimeLimitRoutes   /time-limits ...                                   │  │
│  │   AppsRoutes        /apps, /apps/kiosk, /apps/whitelist                │  │
│  │   StatusRoutes      /status          CrashLogRoutes  /crash-log        │  │
│  │   DashboardRoutes   /  (the static parent dashboard)                   │  │
│  │        │                                                               │  │
│  │        ├── CatalogStore  (catalog.json in filesDir)   <- PARENT config │  │
│  │        └── CacheDatabase (Room)                       <- approvals etc │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌──────────────┐   ┌──────────────────┐   ┌──────────────────────────────┐  │
│  │ Compose UI   │   │ Sync             │   │ Player                       │  │
│  │ ui/          │   │ data/catalog/    │   │ playback/                    │  │
│  │              │   │                  │   │                              │  │
│  │ HomeScreen   │◀──│ CatalogSync      │   │ PlaybackController           │  │
│  │  ├ shelves   │   │  Service         │   │   └─ PlaybackAuthorization ◀─┼── THE GATE
│  │  └ cards     │   │ CatalogApi       │   │        └─ VideoResolver      │  │
│  │ PlaybackScreen│  │ (OkHttp)         │   │             └─ Media3        │  │
│  │ TvPlayerScreen│  └────────┬─────────┘   └──────────────────────────────┘  │
│  └───────┬──────┘            │                                              │
│          │                   ▼                                              │
│          │          ┌─────────────────────────────────────────────┐         │
│          └─────────▶│ Room: CacheDatabase ("parentapproved_cache")│         │
│     observe only    │  approvals: channels, videos, ...           │         │
│                     │  catalog: categories, content_items,        │         │
│                     │           catalog_metadata                  │         │
│                     └─────────────────────────────────────────────┘         │
└──────────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼  NewPipeExtractor + OkHttp (approved ids only)
                            Internet / YouTube
```

The important reading of that picture: **the UI touches Room; the sync touches the server and Room;
the player touches the approval tables through `PlaybackAuthorization`; the catalog never touches the
player.** There is no path from a catalog row to a playing video that skips the gate.

---

## 2. Layers and what each owns

| Layer | Real classes | Owns | Must not |
|---|---|---|---|
| **Android entry** | `SafeTubeApp`, `MainActivity`, `ServiceLocator` | Process init, server startup, background work wiring, process-wide singletons | Contain business rules |
| **UI (child)** | `HomeScreen`, `HomeViewModel`, `CatalogCard`, `AppCard`, `VideoCard`, `CatalogUiState`/`CatalogUiProjection` | Rendering the catalog, focus, D-pad, empty states, turning a card press into a player destination | Perform HTTP; decide permission; touch DAOs or the player directly |
| **UI (player)** | `TvPlayerScreen`, `PlayerMenu`, `DpadKeyHandler` | Drawing the player and mapping remote keys to controller calls | Hold media state or start playback itself |
| **UI (parent)** | `ConnectScreen`, `SettingsScreen`, `LockScreen` | PIN/QR display, device settings, time-limit lock screen | Approve content from the TV |
| **Navigation** | `AppNavigation` (`NavHost`) | The five destinations | Instantiate a player around authorization |
| **Playback** | `PlaybackController` | Authorization call, queue, play/pause, seek, watch-time accounting, resume persistence, menus, media3 wiring | Be bypassed by any other caller of `VideoResolver` |
| **Security** | **`PlaybackAuthorization`** | **The only allow/deny decision for playback** | — it is 73 lines and has not changed since Phase 1 |
| **Media resolution** | `VideoResolver`, `StreamSelector`, `PlayerMedia`, `AutoQuality`, `NewPipeDownloader` | Turning an approved id into playable streams; rendition policy | Consult YouTube for anything not already approved |
| **Catalog data** | `CategoryEntity`, `ContentItemEntity`, `CatalogMetadataEntity`, `CategoryDao`, `ContentItemDao`, `CatalogMetadataDao`, `CatalogRepository` | The local catalog: tables, ordered reads, the single transactional replacement | Grant playback; parse the wire format |
| **Catalog contract** | `CatalogContract` (`CatalogSnapshot`, `CatalogCategoryDto`, `CatalogItemDto`, `CatalogPutRequest`, `CatalogJson`), `CatalogPayloadValidator`, `CatalogMapper` | The versioned wire format, validation rules, DTO→entity mapping | Persist anything |
| **Catalog sync** | `CatalogSyncService`, `CatalogApi`/`HttpCatalogApi`, `CatalogSyncResult` | Fetch, parse, validate, compare versions, map, replace atomically, report one of 9 outcomes | Render; be awaited by a render path |
| **Server catalog** | `CatalogStore`/`BaseCatalogStore`/`FileCatalogStore`/`InMemoryCatalogStore`, `CatalogRoutes` | The parent's authoritative configuration, version assignment, optimistic concurrency, persistence | Be the TV's runtime catalog |
| **Approval model** | `ContentSourceRepository`, `ChannelDao`, `PlaylistCacheDao`, `SourceTransfer` | Resolving approved sources into the `videos` cache; export/import | Be replaced by the catalog |
| **Server** | `SafeTubeServer`, `ServerHolder`, `ServerService`, `BootReceiver`, all `*Routes` | HTTP surface, dashboard, session auth | Start twice on port 8080 (there is one idempotent owner) |
| **Auth** | `PinManager`, `SessionManager`, `SessionPersistence`, `SharedPrefs*` | PIN validation, rate limiting, 90-day sessions | Persist the PIN |
| **Screen time** | `TimeLimitManager`, `RoomTimeLimitStore`, `RoomWatchTimeProvider` | Daily limits, bedtime, bonus, manual lock | Be enforced outside the player/UI checks |
| **Kiosk** | `KioskManager`, `SafeTubeAdmin`, `HomeWatcherService` | App whitelist, lock task, device-owner behaviour | — |
| **Debug** | `DebugReceiver` (37 actions), `CatalogSyncDebug`, `OfflineSimulator`, `BandwidthOverride` | Device verification hooks; debug builds only | Expose secrets; exist in release |

---

## 3. Data flow 1 — the catalog reaches the screen

```text
parent                    server (in-app)                 TV
──────                    ───────────────                 ──
PUT /catalog  ─────────▶  CatalogRoutes.put
                          CatalogPayloadValidator.validate   (same rules the TV runs)
                          BaseCatalogStore.write             (assigns version +1, one lock)
                          FileCatalogStore.save              (temp file -> atomic move)
                          catalog.json                       ← THE PARENT'S CONFIGURATION
                                                             │
                                      GET /catalog ◀─────────┘  (CatalogSyncService, background)
                                            │
                                            ▼
                             CatalogJson.decodeSnapshot          parse
                             CatalogPayloadValidator.validate    validate WHOLE payload
                             version compare                     newer / equal / older
                             CatalogMapper.toEntities            DTO -> Room rows
                             CatalogRepository.replaceCatalog    ONE transaction
                                            │
                                            ▼
                             Room  categories / content_items / catalog_metadata
                                            │  (Room invalidation)
                                            ▼
                             CategoryDao.observeCatalog()   @Transaction + @Relation
                                            │
                             CatalogRepository.observeCatalogWithItems()
                             CatalogRepository.observeVideoThumbnails()
                             CatalogRepository.observeResumableVideos()
                                            │  combine
                                            ▼
                             CatalogUiProjection.build(...)   order, enabled, empties, artwork
                                            │
                                            ▼
                             HomeViewModel.catalogState  (StateFlow)
                                            │
                                            ▼
                             HomeScreen / CatalogCard         ← NO NETWORK ON THIS PATH
```

**Why one relation query.** `CategoryDao.observeCatalog()` is a `@Transaction` `@Relation` query, so
Room reads the shelves and their items inside a single transaction. Combined with the single
transaction on the write side, the UI can never observe "new categories with the old items" or half a
catalog. This is asserted by a test that collects every emission during a replacement.

---

## 4. Data flow 2 — a card press becomes playback

```text
CatalogCard onClick
   │
   ▼
HomeViewModel.onCardSelected(card)
   │  PLAYLIST -> CatalogRepository.firstApprovedVideoOf(playlistId)   (a starting point, NOT a grant)
   │  VIDEO    -> the item's own youtubeVideoId
   ▼
onPlayVideo(videoId, playlistId, 0)      (the same callback the Phase 1 UI used)
   │
   ▼
NavHost -> Routes.PLAYBACK -> PlaybackScreen(videoId, playlistId, startIndex)
   │
   ▼
PlaybackController.start(...) -> boot(...)
   │
   ▼
PlaybackAuthorization.authorize(db, videoId)          ◀── THE SECURITY BOUNDARY
   │        videoDao().getByVideoId(videoId)              present in the approved cache?
   │        channelDao().getBySourceId(entity.playlistId) its parent source still approved?
   │
   ├── Rejected -> errorMessage = "This video can't be played"; nothing is prepared; no media call
   │
   └── Approved -> PlaybackAuthorization.approvedQueue(db, sourceId)   the only queue the player may walk
                   VideoResolver.resolve(videoId)   (NewPipe: approved id -> streams)
                   PlayerMedia.mediaSource(...) -> Media3 -> the TV screen
```

A catalog `PLAYLIST` card starts the first video of that playlist's approved queue, so
next/previous afterwards walk the whole approved playlist — the Phase 1 queue behaviour, unchanged.

---

## 5. Data flow 3 — startup and background work

```text
SafeTubeApp.onCreate
  ├── CrashHandler.install
  ├── NewPipe.init(NewPipeDownloader)
  ├── ServiceLocator.init(context)
  │      ├── CacheDatabase.getInstance(context)          Room opens (lazy)
  │      ├── FileCatalogStore.inFilesDir(filesDir)       the server-side catalog document
  │      ├── CatalogSyncDebug.init(context)              debug-only, no-op in release
  │      ├── SessionManager(SharedPrefsSessionPersistence)
  │      ├── PinManager / RelayConfig / RelayConnector / TimeLimitManager / KioskManager
  │      └── PlayEventRecorder.init(database)
  ├── appScope (SupervisorJob + IO) coroutine: HomeWatcherService if kiosk is on (delayed 5s)
  ├── appScope coroutine: refresh the approved sources (ContentSourceRepository.resolveAllChannels)
  │      -> this is what fills `videos`, i.e. what makes anything playable at all
  └── appScope coroutine: catalog sync loop
         -> delay 3s, syncCatalog(), delay 15 min, repeat

MainActivity.onCreate
  ├── ServerService.start(this)      foreground service keeps the dashboard alive
  ├── ServerHolder.start(this)       idempotent belt-and-braces (one owner of port 8080)
  └── setContent { SafeTubeTheme { AppNavigation() } }

HomeScreen composes
  ├── HomeViewModel.start()  -> subscribes to three Room flows; kiosk poll every 10s;
  │                             approved-source refresh when the channel signature changes
  └── time-limit watch loop (5s) -> onBlocked -> LockScreen
```

Nothing above blocks on HTTP. The only synchronous-feeling step is Room opening, which is local.

---

## 6. The two stores, and why

| | Server catalog | TV runtime catalog |
|---|---|---|
| Where | `catalog.json` in the app's private `filesDir` | Room tables `categories` / `content_items` / `catalog_metadata` |
| Written by | `PUT /catalog` (a parent, authenticated) | `CatalogSyncService` only, via `CatalogRepository.replaceCatalog` |
| Read by | `GET /catalog`; the TV's sync | The UI, every frame, through Room flows |
| Version | Assigned by the server, `current + 1` | Records the version it installed |
| Authority | What the parent **configured** | What the TV is **showing**, and (separately) what is approved |

If these were the same store, synchronization would be copying a table onto itself: last-known-good
would have nothing to fall back to, and the version-regression rule would compare a number with
itself. Keeping them separate is what makes every Phase 3 behaviour meaningful.

---

## 7. Threading and lifecycle

- **Room** is the only persistence on the TV. Reads for the UI are `Flow`s; writes go through suspend
  functions; the catalog replacement is a `suspend` transaction (`androidx.room.withTransaction`).
- **`SafeTubeApp.appScope`** (`SupervisorJob + Dispatchers.IO`) owns background work: the approved-source
  refresh, the catalog sync loop and the home watcher. It lives as long as the process, which the
  foreground `ServerService` (`START_STICKY`, `dataSync`) keeps alive in practice.
- **`HomeViewModel.viewModelScope`** owns UI-adjacent work: the catalog flow combination
  (`SharingStarted.WhileSubscribed(5_000)`, so database work stops while the player is on screen and
  the last value is retained for an instant repaint on return), the kiosk poll, the approved-source
  refresh, and the on-press playback target resolution.
- **`PlaybackController`** is given the composition's `CoroutineScope`; its background loops (quality
  recovery, playhead publishing, position saving, time-limit watch) all guard against a released
  player, because they outlive the screen by a few frames.
- **The sync serialises itself** with a `Mutex`, so the startup attempt, the periodic attempt and a
  Refresh press cannot interleave.
- **The server** is a singleton with one owner (`ServerHolder`), started by both `MainActivity` and
  `ServerService` on purpose, and by `BootReceiver` on boot.

---

## 8. HTTP surface (complete, at `HEAD`)

```text
POST   /auth                      PIN -> session token
POST   /auth/refresh              rotate a session
GET    /catalog                   AUTH  the parent's catalog
PUT    /catalog                   AUTH  replace the parent's catalog   (Phase 3)
GET    /playlists                 AUTH  approved sources
POST   /playlists                 AUTH  add an approved source
DELETE /playlists/{id}            AUTH  remove an approved source
GET    /sources/export            AUTH  export the approved library as JSON
POST   /sources/import            AUTH  import an approved library (additive, never destructive)
POST   /playback/pause|skip|stop  AUTH  drive the player remotely
GET    /stats, /stats/recent      AUTH  watch statistics
GET/PUT /time-limits, /time-limits/lock|bonus   AUTH  screen time; /time-limits/request for the TV
POST   /apps/kiosk, PUT /apps/whitelist, GET /apps   AUTH  kiosk configuration
GET    /status                    public minimal; full detail when authenticated
GET    /crash-log                 AUTH  the crash log
GET    /                          the parent dashboard HTML; assets at /$file
```

Authentication everywhere is the same session check (`validateSession`): bearer token or `session`
cookie. Nothing new was introduced for the catalog.

---

## 9. Where a new developer should look first

| To change… | Start at |
|---|---|
| What the child sees on the home screen | `ui/screens/CatalogUiState.kt` (the projection — all rendering rules live here) and `ui/screens/HomeScreen.kt` |
| The look of a card | `ui/components/CatalogCard.kt` (dimensions must stay 200.dp / 16:9 / 12.dp / 1.05 / 4.dp) |
| What the catalog can contain | `data/catalog/CatalogContract.kt`, then `CatalogPayloadValidator.kt`, then the entities |
| How the TV decides to sync | `SafeTubeApp.kt` (the loop) and `ui/screens/HomeViewModel.kt` (`refresh()`) |
| The sync algorithm | `data/catalog/CatalogSyncService.kt` |
| The server's catalog storage | `server/CatalogStore.kt`, `server/CatalogRoutes.kt` |
| Who may play what | `playback/PlaybackAuthorization.kt` — and read `SECURITY_MODEL.md` first |
| The player | `playback/PlaybackController.kt`, `ui/player/TvPlayerScreen.kt`, `playback/VideoResolver.kt` |
| The database | `data/cache/CacheDatabase.kt` (version + migrations) and the entity files |
| The TV test harness | `tv-app/scripts/tv-e2e.ps1`, `debug/DebugReceiver.kt`, `docs/TESTING.md` |
