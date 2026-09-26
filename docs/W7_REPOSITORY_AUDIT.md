# W7 — Repository and server audit

**Baseline commit:** `d9aad3f2cb46c12d8e8014b8e0ca877a82b9674d`
(`chore(w7): checkpoint before the webserver UI redesign`, parent `81a2053`), branch `main`,
remotes `fork` = `github.com/kapilmahawar/SafeTubeforKids`, `origin` = `github.com/Prasanna79/parentapproved`.
Working tree clean at the start of W7.

**Scope of this document.** PART 1 of W7 asked for a complete audit of the repository and of the
in-app server *before* any code was written: the structure, every HTTP route, the end-to-end catalog
data flow, and a classification of every piece of Apps / Kiosk / Installed-Apps / app-installation /
app-whitelisting code into "UI-only" and "runtime behaviour". Everything below was read from the
working tree at `d9aad3f`; nothing here is inferred from documentation, and where a document
contradicts the code the code is reported as the truth and the document is listed as stale (§8).

---

## 1. Shape of the repository

| Path | What it is |
|---|---|
| `tv-app/` | The Android TV app (`:app`), Kotlin + Jetpack Compose, Room v9. |
| `tv-app/app/src/main/java/tv/safetubeforkids/app/` | Production sources. Package `tv.safetubeforkids.app`. |
| `tv-app/app/src/main/assets/` | The parent dashboard: `index.html`, `style.css`, `app.js`, `catalog-tree.js`, `catalog-editor.js`, `favicon.svg`. Served by the app itself. |
| `tv-app/app/src/test/java/tv/parentapproved/app/` | JVM/Robolectric/Ktor tests (directory is a legacy path; the **package** inside is `tv.safetubeforkids.app.*`). |
| `tv-app/app/src/androidTest/` | Instrumented tests (asset presence, end-to-end server smoke). |
| `tv-app/scripts/` | `node --test` dashboard model suites + device harnesses (`tv-e2e.ps1`, `ui-test.sh`, `deploy-smoke.sh`). |
| `relay/` | Reverse-proxy connector (`RelayConnector`), plus relay integration tests. |
| `docs/` | Architecture, security model, testing, phase notes. |

Inside `.../safetubeforkids/app/` the relevant packages are `server/` (Ktor), `data/catalog/`
(the catalog model, validator, sync client, thumbnails), `data/cache/` (Room), `kiosk/`,
`timelimits/`, `playback/`, `relay/`, `ui/`, `util/`.

### The one architectural fact everything else follows from

The app runs a Ktor (Netty) server **inside the TV process** on port `8080`
(`SAFE_TUBE_SERVER_PORT`), and it serves three clients: the parent's browser (the dashboard), the
TV's own catalog sync client (`HttpCatalogApi` → `127.0.0.1:8080`), and the relay
(`relay.parentapproved.tv` → `127.0.0.1:8080`, path `/api/*` stripped by
`RelayConnector.mapRelayPathToLocal`). One server, one port, no authentication middleware: every
handler validates its own session with `validateSession(sessionManager)`.

---

## 2. Complete HTTP route inventory (37 registrations)

Bootstrap: `server/SafeTubeServer.kt` — port `:20`, `configureServer()` `:45`, plugins `:47-86`,
route registrations `:88-107`.

| Method + path | File:line | Auth | Reads / writes | Purpose |
|---|---|---|---|---|
| `POST /auth` | `AuthRoutes.kt:42` | none (login) | PIN validate; mints a 90-day session | PIN → bearer token |
| `POST /auth/refresh` | `AuthRoutes.kt:27` | token | rotates the session | keeps a long-lived login alive |
| `GET /playlists` | `PlaylistRoutes.kt:37` | bearer | `channels` | list approved sources |
| `POST /playlists` | `PlaylistRoutes.kt:43` | bearer | writes `channels`; cap 20 | approve a source |
| `DELETE /playlists/{id}` | `PlaylistRoutes.kt:94` | bearer | writes `channels` + `videos` | revoke a source and drop its cache |
| `GET /sources/export` | `PlaylistRoutes.kt:123` | bearer | `channels` | export the approved library |
| `POST /sources/import` | `PlaylistRoutes.kt:140` | bearer | writes `channels` | additive import, never destructive |
| `GET /catalog` | `CatalogRoutes.kt:71` | bearer | `CatalogStore.read()` | the parent's document (also the TV's own sync source) |
| `PUT /catalog` | `CatalogRoutes.kt:77` | bearer | `CatalogStore.write()` | the **single** catalog write path, version-preconditioned |
| `POST /catalog/import/resolve` | `CatalogImportRoutes.kt:72` | bearer | **nothing** (reads `channels` for the `approved` flag) | resolve a playlist for the editor; approves nothing |
| `POST /playback/stop|skip|pause` | `PlaybackRoutes.kt:12,18,24` | bearer | emits a `PlaybackCommand` | remote control of the TV |
| `GET /stats`, `GET /stats/recent` | `StatsRoutes.kt:31,47` | bearer | `play_events` | today's totals, last 20 events |
| `GET /status` | `StatusRoutes.kt:42` | hybrid | `channels`, sessions, recorder | the only route that answers unauthenticated (version + `serverRunning` + `protocolVersion` only) |
| `GET /time-limits` | `TimeLimitRoutes.kt:57` | bearer | `time_limit_config` | limits, bedtime, used/remaining/bonus, lock, pending request |
| `PUT /time-limits` | `TimeLimitRoutes.kt:99` | bearer | writes `time_limit_config` | weekday limits + bedtime |
| `POST /time-limits/lock` | `TimeLimitRoutes.kt:135` | bearer | writes `manuallyLocked`; stops playback on lock | lock / unlock the TV |
| `POST /time-limits/bonus` | `TimeLimitRoutes.kt:152` | bearer | writes `bonusMinutes` | grant extra minutes today |
| `POST /time-limits/request` | `TimeLimitRoutes.kt:178` | **none, deliberately** | nothing (in-memory flag) | the **child's** "more time" button on the TV; no dashboard caller |
| `GET /apps` | `AppsRoutes.kt:47` | bearer | reads installed apps + `app_whitelist`; **writes** `app_whitelist` | list installed apps with whitelist flags |
| `PUT /apps/whitelist` | `AppsRoutes.kt:77` | bearer | writes `app_whitelist.whitelisted` | allow one app, push the DPC lock-task list |
| `GET /apps/kiosk` | `AppsRoutes.kt:102` | bearer | `kiosk_config` | kiosk status |
| `POST /apps/kiosk` | `AppsRoutes.kt:113` | bearer | writes `kiosk_config`; enable/disable kiosk | kiosk on/off + "limits on all apps" |
| `GET /crash-log` | `CrashLogRoutes.kt:23` | bearer | `filesDir/crash_log.txt` | last crash, for a bug report |
| `GET /` | `DashboardRoutes.kt:16` | none | `assets/index.html` | the dashboard shell |
| `GET /{app.js,style.css,favicon.svg}` (+ `/assets/` aliases) | `DashboardRoutes.kt:38-44` | none | assets | the shell's static files |

Notes that shaped the redesign:

- **No route is dead.** Every one of the 37 has a caller in this repository. The five
  `/assets/*` aliases are the only product-unreachable registrations, and they are deliberate
  backward compatibility (their own comment says "legacy").
- **No route sets a cookie.** The `session`-cookie fallback in `validateSession` is read in two
  places and written nowhere; the dashboard always sends the bearer header.
- `GET /catalog` is the only route with two production consumers (browser + the TV's own sync).
- `POST /time-limits/request` is the only route the dashboard never calls: its caller is the TV's
  lock screen (`LockScreen.kt:148`).

---

## 3. The catalog data flow, end to end

### 3.1 Two stores, on purpose

| | Server document | TV runtime copy |
|---|---|---|
| Where | `filesDir/catalog.json` + `filesDir/catalog.version` | Room v9: `catalog_nodes` + `catalog_metadata` |
| Contract | `{schemaVersion: 2, catalogVersion, nodes[]}` | `CatalogNodeEntity` rows, same id space |
| Written by | `PUT /catalog` only | `CatalogSyncService` only |
| Read by | the dashboard, the TV's sync client | the TV UI, `GET /catalog/artwork` |

The dashboard never stores the catalog in the browser; server-side, `catalogVersion` is assigned by
the server (`maxOf(document, versionFloor) + 1`) under one lock, and a stale
`expectedCatalogVersion` is refused with 409 and **zero** changes.

### 3.2 The model

One flat tree: `catalog_nodes` with `parentId` + `position`. Node types `CATEGORY` (root shelf
title, no card, no picture — W6.1), `SUBCATEGORY` (a container card), `VIDEO`. Siblings are always
numbered `0..n-1` per parent; the server validator refuses gaps and duplicates rather than repairing
them, and the TV renders `position ASC, id ASC`.

### 3.3 Every operation, and where it is enforced

| Operation | Client code | Server rule |
|---|---|---|
| Read | `CatalogEditor.reload` → `GET /catalog` | — |
| Add shelf / folder / video | `addCategory` / `addSubcategory` / `addVideo` | type placement, YouTube id round-trip, positions |
| Rename | `rename` (refuses VIDEO) | non-blank title |
| Hide / show | `setEnabled` (only that node) | — |
| Delete | `remove` (whole subtree; clears a picture pointing into it) | — |
| Reorder | `moveUp` / `moveDown` / `moveWithin` | positions must be `0..n-1` |
| Move | `moveTo` (refuses self, own subtree, type mismatch) | parent exists, child type allowed |
| Picture | `setThumbnail` (`AUTO` / `VIDEO`, stored as a **node id**, strict descendant, visible only) | thumbnail rules; `CUSTOM` and any `thumbnailUrl` refused outright |
| Import | `importPlaylistFrom` (resolve first, then publish one document) | playlist-only resolver; publish is a single `PUT` |
| Publish | `save` → `PUT /catalog` with `expectedCatalogVersion` | 400 / 401 / 409 / 500 |

### 3.4 Synchronisation, and the one gap the redesign had to close

`CatalogSyncService.syncCatalog()` is the TV's only sync entry point (Mutex-guarded, invoked 3 s
after start and every 15 minutes). **There is no push from the server to the TV**: after a parent
publishes, the TV is up to 15 minutes behind unless the parent presses Refresh on the TV. A
mobile-first dashboard that says "Saved" while the child's TV still shows yesterday's shelves is
misleading, so W7 adds one action endpoint (`POST /catalog/refresh`) that performs exactly what the
TV's own Refresh button does. It grants no new permission.

The second gap: `GET /catalog` returns the *document*, which deliberately carries no artwork and no
children for an imported playlist (those are materialised on the TV from the approved cache). A
parent's library screen needs both, so W7 adds one read-only endpoint (`GET /catalog/artwork`) that
answers with the TV's own view: pictures per video, and per container a picture plus the number of
videos the child can actually reach. It is a description of the TV's **installed** version and says
so, rather than pretending to describe the draft.

---

## 4. Apps / Kiosk / Installed Apps / app-installation / app-whitelisting — full classification

### 4.1 UI-only (removed by W7)

| What | Where | Consumer |
|---|---|---|
| "Kiosk Mode" + "Installed Apps" cards | `assets/index.html:134-153` | the dashboard page |
| kiosk/apps rendering and handlers (`loadKioskConfig`, `loadAppsList`, `toggleKiosk`, `updateKioskEnforceTime`, `toggleAppWhitelist`) | `assets/app.js:658-780`, called from `loadDashboard` `:1469` | the dashboard page |
| kiosk/apps styles | `assets/style.css:591-612` | the dashboard page |
| `setup-kiosk.sh` reference in the page | `assets/index.html:146` | the dashboard page |

There is **no app-installation endpoint and no app-installation UI** anywhere: `KioskManager`
only enumerates what `PackageManager` already reports, and DPC provisioning is out of band.

### 4.2 Runtime behaviour (kept, and why)

| What | Where | Why it cannot go |
|---|---|---|
| `KioskManager` | `kiosk/KioskManager.kt` | `getLeanbackLaunchIntent` launches the child's app (`HomeScreen.kt:209`); `enforceTimeLimitExpiry` / `restoreAfterTimeLimitExpiry` are driven by time limits (`HomeScreen.kt:93-113`); `isDeviceOwner` gates lock task |
| `app_whitelist`, `kiosk_config` | Room v9 | read by the TV's kiosk home screen (`HomeViewModel.kt:173-193`) and by time-limit enforcement |
| `HomeWatcherService`, `SafeTubeAdmin`, `device_admin.xml`, manifest `lockTaskMode="if_whitelisted"` + HOME/LEANBACK filters | — | the kiosk/launcher behaviour of a provisioned TV |
| TV kiosk home screen (`KioskAppsContent`, `HomeScreen.kt:203-299`) | — | what the child sees on a kiosk device |
| `AppsRoutes.kt` (all four routes) | `server/AppsRoutes.kt` | **judgement call, see §5** |

### 4.3 Genuinely dead code found (independently removable)

`KioskDao.setKioskEnabled`; `WhitelistDao.getByPackageName` / `count` / `deleteAll`;
`HomeWatcherService.lastLaunchedApp` / `lastLaunchTime`; `MainActivity.exitLockTaskIfNeeded`;
`CatalogEditor.depthOf`, `CatalogEditor.toDocument` and `CatalogEditor.problems` (all three
exported and never called — W7 starts calling `problems` and leaves `toDocument` alone, because it
emits `catalogVersion` instead of `expectedCatalogVersion` and adopting it would silently drop the
version precondition); `CatalogTree.escapeHtml`; `CatalogRepository.upsertCategories` /
`upsertItems` / `delete*` (no production caller).

---

## 5. The one judgement call: the four `/apps*` routes stay

**Decision.** W7 deletes the Apps/Kiosk **UI** — markup, script, styles, and every reference from
navigation, dashboards, settings and menus — and keeps `AppsRoutes.kt` registered as it is today.

**Why.**

1. The mandate is about the interface ("from all UI — nav, dashboards, menus, settings, pages,
   mobile + desktop"). After W7 the dashboard contains no link, button or page for apps or kiosk,
   so there is no broken route, dead link, unused button or orphaned UI.
2. `GET /apps:54-63` is the **only** code in the repository that inserts into `app_whitelist`, and
   `PUT /apps/whitelist:88` (`WhitelistDao.setWhitelisted`) is the **only** code that can set
   `whitelisted = 1`. `WhitelistEntity.whitelisted` defaults to `0`, and the TV's kiosk home screen
   renders `whitelistDao().getWhitelisted()`. Deleting these routes would therefore not remove a
   screen — it would permanently empty the child's app row on every provisioned kiosk device, with
   no remaining way to fill it (the `DEBUG_*` broadcasts that could do it live in the **debug**
   source set and are absent from a release build).
3. Keeping them costs nothing at runtime: they are ordinary session-validated JSON routes, they are
   registered only when a `KioskManager` exists, and W7 changes neither their behaviour nor their
   contract.

**Consequence, stated plainly:** a parent can no longer enable kiosk mode or choose the child's
apps from the web page. That is exactly what W7 asked for, and it is the one capability W7 removes
rather than relocates. If the intent was to retire the kiosk feature entirely, the follow-up is to
delete `AppsRoutes.kt`, `KioskManager`, `HomeWatcherService`, `SafeTubeAdmin`, the kiosk home
screen, the two Room tables and `setup-kiosk.sh` — a separate, much larger change that W7's
preservation rules do not authorise.

The five `DEBUG_*` kiosk/apps broadcasts in `debug/DebugReceiver.kt` are likewise **kept**: they are
debug-build diagnostics for a feature that is still shipped, and no harness or test uses them.

---

## 6. What the old dashboard actually was

- `index.html` (330 lines): ten stacked sections in one page, no navigation, no routes, 21 inline
  `onclick` / `onchange` handlers calling globals.
- `app.js` (1 559 lines): one IIFE, ~40 controls, direct `getElementById` everywhere, all state in
  memory, no router, no deep links.
- `style.css` (688 lines): **no CSS custom properties, no `@media`, no dark mode**,
  `#app { max-width: 480px }`, ~17 button classes and `!important` overrides.
- The catalog editor is a *section* of that page next to the content-source list, the kiosk card and
  the crash log, so "manage my library" is one long scroll with no hierarchy.

Nothing above is a functional defect — every capability exists — but none of it is usable one-handed
on a 360 px screen, which is what W7 is for.

---

## 7. Invariants the redesign must not break (checked before and after)

1. Catalog membership grants no playback: `PlaybackAuthorization` reads only `channels` + `videos`.
2. One write path: `PUT /catalog`, whole document, `expectedCatalogVersion`, 409 on stale, atomic.
3. Versions are assigned by the server and are monotonic; the TV refuses a regression.
4. Sibling positions are `0..n-1`; render order `position ASC, id ASC`; never repaired silently.
5. Node identity is stable across rename, reorder, move and sync.
6. No destructive edits: hiding is not deleting; `enabled` is per node only.
7. `schemaVersion` stays 2; v1 PUT keeps failing with 400.
8. Thumbnails: `AUTO` / `VIDEO` only, stored as a node id, strict descendant, visible; no picture URL
   anywhere in a node.
9. The browser stores only the session token, the theme choice and the dismissal flag — never the
   catalog.
10. `POST /catalog/import/resolve` never approves; the dashboard says so in words a parent reads.
11. Room v9, `catalog_nodes`, `PlaybackAuthorization`, playlist import, the server contract, the
    ordering model, Continue Watching, subcategory navigation, D-pad, Back and focus restoration on
    the TV are all untouched by W7.
