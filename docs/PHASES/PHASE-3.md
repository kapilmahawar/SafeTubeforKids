# Phase 3 — parent catalog API and Android TV catalog synchronization

```text
Base commit   436e06ac5b4ebb047f074f2192c0f52384be5cbb   (Phase 2)
Final commit  017d68fda3a13f0cbf38472f488d840e37d88a78   feat: add catalog sync and server api
```

## Objective

Let a parent publish a versioned catalog to SafeTube's server, and let the TV download it, validate it
as untrusted input, and install it locally in a way that cannot damage the catalog the TV is already
using — while keeping the TV's playback permission model untouched.

## Planned Scope

The server-side catalog model, a versioned JSON contract, authenticated read/replace endpoints, a TV
sync client with full-payload validation, atomic local replacement, last-known-good behaviour on every
failure mode, and tests. Out of scope: the TV catalog UI, the parent web dashboard UI, any player
redesign, and any change to `PlaybackAuthorization`.

## Implemented

**The wire contract** (`data/catalog/CatalogContract.kt`) — `schemaVersion = 1`;
`CatalogSnapshot { schemaVersion, catalogVersion, categories }`;
`CatalogCategoryDto { id, displayName, sortOrder, enabled, items }`;
`CatalogItemDto { id, type, displayName, sortOrder, youtubePlaylistId?, youtubeVideoId?, enabled }`;
`CatalogPutRequest { schemaVersion, expectedCatalogVersion?, categories }`. One `CatalogJson`
serializer is used by both ends, with `explicitNulls` so `"youtubeVideoId": null` is visible on the
wire.
Deliberate shape decisions: items are **nested** inside their category (so an item referencing a
missing category is unrepresentable); `type` is a string so an unsupported value yields a named error
instead of an unreadable payload; `schemaVersion` and `categories` have **no defaults**, so `{}` fails
to decode instead of meaning "empty catalog"; `items` *does* default to empty because a configured-but
-empty shelf is legitimate.

**Validation** (`CatalogPayloadValidator`) — one validator, run by **both** the server before storing
and the TV before installing. Rejects blank ids and names, duplicate category ids, duplicate item ids
(including across categories, since the item id is the Room primary key), unsupported types, a
`PLAYLIST` without a playlist id, a `PLAYLIST` carrying a video id, a `VIDEO` without a video id, a
`VIDEO` carrying a playlist id, and structurally unusable YouTube ids. YouTube ids are validated by
rebuilding the canonical URL and re-parsing it through the project's existing `ContentSourceParser`,
so no new id rules were invented. It reports **all** problems, each addressed to its field, and
deliberately allows duplicate and negative sort orders (Phase 2's decision).

**Server storage** (`server/CatalogStore.kt`) — `CatalogStore` with `BaseCatalogStore` holding the
versioning and an instance lock, plus `FileCatalogStore` (`catalog.json` in the app's `filesDir`,
temp-file write then `Files.move(REPLACE_EXISTING)`) and `InMemoryCatalogStore`. The server assigns
versions (`current + 1`); an unreadable document reads as version 0, which is the safe direction
because a TV above 0 refuses to move down.

**Server endpoints** (`server/CatalogRoutes.kt`) — `GET /catalog` and `PUT /catalog`, both behind the
**existing** session authentication. `400` for unreadable/unsupported/invalid payloads (with a
`details` array), `401` unauthenticated, `409` when `expectedCatalogVersion` does not match. The
request has no authoritative version field, so a client cannot dictate one.

**Sync client** (`data/catalog/`) — `CatalogApi` / `HttpCatalogApi` (OkHttp, read-only, 5 s connect /
5 s read / 10 s whole-call timeouts, bearer token), `CatalogSyncResult` (9 states), `CatalogMapper`
(DTO → Room entities), and `CatalogSyncService.syncCatalog()` implementing the fixed order
fetch → parse → validate the **complete** payload → compare versions → map → replace atomically,
guarded by a `Mutex`.

**Wire-up** — `ServiceLocator.catalogStore` (file-backed) and a lazy `catalogSyncService` pointing at
`http://127.0.0.1:8080` with a single reused self-session token. `SafeTubeServer.configureServer`
registers the routes. **Nothing in production called the sync in this phase**; the only trigger was
the new debug broadcast `DEBUG_SYNC_CATALOG`.

**Debug infrastructure** — `DEBUG_SYNC_CATALOG`, `DEBUG_CATALOG_SYNC_UNAVAILABLE` (a persisted
debug-only switch that makes the sync's HTTP call fail, since the server lives inside the app and a
real outage cannot be staged from outside) and `DEBUG_DUMP_CATALOG_UI`… (the last was added in
Phase 4); plus catalog state in `DEBUG_GET_STATE_DUMP`.

## Not Implemented

- No catalog UI on the TV and no parent dashboard for the catalog (both were out of scope).
- No production sync trigger — deliberately deferred to Phase 4.
- No targeted CRUD endpoints (a whole-catalog `PUT` covers create/rename/delete/reorder for both
  categories and items).
- No HTTP caching / ETag (explicitly excluded; a version comparison is enough).

## Security Impact

- `PlaybackAuthorization.kt` is still **byte-identical** to the Phase 1 baseline.
- The catalog tables still carry no approval data, and the sync never writes to `channels` or `videos`.
  A synced catalog entry for an unapproved video remains unplayable — asserted by
  `CatalogSyncServiceTest.aSyncedCatalogEntryDoesNotGrantPlaybackPermission`, which approves a video
  through the existing model, confirms it plays, then removes the approval while the catalog entry
  stays and confirms it is rejected again.
- The TV's sync client is **read-only**: it has no code path that can modify the parent's
  configuration.
- `GET /catalog` requires authentication, so the family's configured content is not readable by anyone
  on the LAN. `PUT /catalog` cannot be written anonymously (tested).
- The TV validates the server's answer itself rather than trusting it: the same validator runs on both
  sides, and the TV's validation happens before anything is written.

## Database Changes

**None.** Room stays at version 7; no new entity, DAO or migration. The phase used Phase 2's tables
and `replaceCatalog`. The only Phase 2 file touched was a documentation comment on
`CatalogRepository.replaceCatalog` (explaining that the metadata row is replaced wholesale) — no
behaviour change.

## API Changes

Two new authenticated endpoints were added; no existing route, payload or status code was altered:

```text
GET /catalog   -> 200 CatalogSnapshot | 401
PUT /catalog   -> 200 CatalogSnapshot | 400 | 401 | 409
```

## UI Changes

**None.** The app's UI files are unchanged; nothing in the app called the sync, so no screen behaves
differently after this phase.

## Tests

`381 → 506 tests` (+125), all green on a clean `:app:clean :app:testDebugUnitTest :app:assembleDebug`.

| Class | Tests | Covers |
|---|---|---|
| `CatalogRoutesTest` | 37 | API-01…API-14: unauthenticated GET/PUT rejected, expiry honoured, a forged cookie rejected, read/write, every validation failure, empty vs malformed-empty, version increments and non-increments, optimistic concurrency, a client-supplied version ignored, and one run driving `/auth` → `/playlists` → `/catalog` with a single session |
| `CatalogPayloadValidatorTest` | 29 | every accepted shape (incl. duplicate/negative sort orders, short video ids) and every refusal, with field-addressed reasons |
| `CatalogSyncServiceTest` | 29 | real Room + real OkHttp + MockWebServer: fresh install, upgrade, server unavailable (500 and connection refused), malformed bodies, invalid payload, version regression, same version, empty catalog, a mid-transaction failure induced with a SQLite trigger, ordering, mixed content, display names, last-known-good, the security pair, and two overlapping syncs not interleaving |
| `CatalogContractTest` | 13 | the JSON boundary on real serialized text: required keys, explicit nulls, empty vs malformed empty, unknown keys, round-trip stability |
| `CatalogStoreTest` | 12 | persistence across a new store object (a restart), version climbing across restarts, the on-disk document being the contract, corrupt-document recovery, conflict-without-write, 8 concurrent writers getting 8 distinct versions |
| `CatalogEndToEndTest` | 5 | nothing stubbed: real Netty server, real routes, real file store, real HTTP, real Room — plus a real server restart over the same storage directory |

## Physical Device Testing

Verified on the Xiaomi Mi Box 4 (Android 12 / API 31) by driving the real server and the real sync
client over HTTP:

- `GET /catalog` unauthenticated → **401**; `PUT /catalog` unauthenticated → **401**.
- Authenticated `GET` → the stored catalog; `PUT` → 200 with a new version and the content stored
  verbatim (order, parent names, explicit nulls, an empty `Stories` shelf).
- `PUT {}` → **400**; a `PLAYLIST` carrying a video id → **400**; a stale
  `expectedCatalogVersion` → **409**; afterwards the version was unchanged, so failed mutations do not
  create versions.
- **Server restart persistence:** `catalog.json` had an identical md5 before and after a force-stop
  and relaunch, and `GET` returned the same version and content.
- **The real sync client on the TV** (via `DEBUG_SYNC_CATALOG`): `Updated` to version 1 with 4
  categories / 6 items; a reorder produced `Updated` to 2 with the on-screen order changed; a third
  sync reported `AlreadyCurrent` with `lastSuccessfulSyncAt` unchanged; a **force-stop and relaunch**
  left the local catalog intact.
- **Last-known-good on the device:** an invalid payload (written straight into the server's document,
  simulating an older/buggy server) produced `InvalidCatalog`, left the local version at 2, and left
  every shelf in place.
- The device was left with a coherent catalog, and the approved library (2 sources, 51 videos) and
  play history were untouched throughout.

## Known Issues

1. **The embedded-server oddity is inherent, not a defect.** The SafeTube server runs inside the TV
   app, so "sync" is the TV fetching from itself over HTTP. This is what the Phase 3 specification
   asked for (a real HTTP/JSON boundary that a future remote server can replace), and it is why the
   server store and the Room catalog are deliberately separate. It does mean the sync cannot be
   tested against a genuinely remote host on this setup.
2. **Losing the server's `catalog.json` resets the version counter to 0**, after which every TV above
   0 refuses every catalog (permanent `VersionRegression`) until the server's counter climbs past the
   TV's. Observed and worked around during device testing by re-publishing until the counter passed
   the TV's version. This is the regression protection working as specified, but it is an operational
   trap.
3. `ServiceLocator.catalogSyncService` creates and caches one session for the TV's own use. Combined
   with the 20-session cap that evicts the oldest, a TV that somehow created a session per sync would
   log a parent out; the cached token avoids that, but the coupling is worth remembering.
4. The TV's self-session is created with the same authority as a parent session. It is only ever used
   for a read, and the client has no write path, so the blast radius is a read of the family's own
   catalog.

## Final Commit

```text
017d68fda3a13f0cbf38472f488d840e37d88a78   feat: add catalog sync and server api
19 files changed, 3701 insertions(+), 2 deletions(-)
```

## Verification Status

| Claim | Status |
|---|---|
| Server API with existing auth, versioning, concurrency, persistence | VERIFIED — 37 route tests, 12 store tests, plus real HTTP against the TV |
| Catalog survives a server restart | VERIFIED — on the device (identical md5) and in `CatalogEndToEndTest` |
| Full-payload validation before any write | VERIFIED — 29 sync tests + 29 validator tests |
| Atomic replacement (rollback on failure) | VERIFIED — SQLite-trigger test |
| Last-known-good on every failure state | VERIFIED — per-state tests, plus the device invalid-payload test |
| Version regression refused | VERIFIED — test + device |
| Same version not rewritten | VERIFIED — test |
| Unsupported schema refused | VERIFIED — test |
| TV sync client works on real hardware | VERIFIED — Phase 3 device session |
| `PlaybackAuthorization` unchanged | VERIFIED — `git diff` |
| UI unchanged | VERIFIED — `git diff` |
| The TV UI showing the catalog | NOT_IMPLEMENTED (Phase 4) |
