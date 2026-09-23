# Phase 2 — local catalog schema, migration and repository foundation

```text
Base commit   b130c1262538e61941648a1606f787ca86498346   (release: 0.10.0)
Final commit  436e06ac5b4ebb047f074f2192c0f52384be5cbb   feat: add local catalog schema
```

## Objective

Create the local database foundation for a hierarchical content model, so that a parent can later
define named shelves containing playlists and individual videos — with no UI, no server API and no
synchronization in this phase.

## Planned Scope

Persistent model only: a `CategoryEntity` and a `ContentItemEntity` (playlist **or** single video),
a catalog metadata/version row, DAOs with explicit ordering, a repository with a transactional
replacement, a real Room migration, and tests. Explicitly out of scope in the task: any new Home UI,
any category UI, any parent web UI, any server catalog API, any sync, any player change, any change to
`PlaybackAuthorization`, and any replacement of the existing Room architecture.

## Implemented

**Entities** (`tv.safetubeforkids.app.data.catalog`)

- `CategoryEntity` → `categories`: `id` (TEXT PK, stable and **not** derived from the name),
  `display_name`, `sort_order` (mandatory), `enabled`, `created_at`, `updated_at`. Index on
  `sort_order`.
- `ContentItemEntity` → `content_items`: `id` (TEXT PK), `category_id` (FK → `categories.id`,
  `ON DELETE CASCADE`), `type` (`PLAYLIST` | `VIDEO`), `display_name`, `sort_order`,
  nullable `youtube_playlist_id`, nullable `youtube_video_id`, `enabled`, `created_at`, `updated_at`.
  Composite index on `(category_id, sort_order)`.
- `CatalogMetadataEntity` → `catalog_metadata`: a singleton row (`id = 1`) with `catalog_version`,
  `server_version`, `last_successful_sync_at`, `last_attempt_at`.
- `ContentItemType` enum plus `CatalogConverters`, storing the type **by name** so a stored row can
  never be reinterpreted by reordering the enum.
- `CatalogValidation` — the playlist/video identity rule (a `PLAYLIST` needs a playlist id and no
  video id; a `VIDEO` the reverse), enforced in the entity's `init` so a malformed entity cannot even
  be constructed, with a non-throwing entry point for a future sync layer to pre-screen payloads.

**DAOs** — `CategoryDao`, `ContentItemDao`, `CatalogMetadataDao`. Every read states its order
explicitly (`sort_order ASC, id ASC`), with the `id` tie-break making the order total when two rows
share a sort order.

**Repository** — `CatalogRepository`, holding the database only: observable reads, upserts, deletes,
`markSyncSucceeded` / `markSyncAttempt` / `markServerVersion`, and
`replaceCatalog(categories, contentItems, metadata, syncedAt)` which performs delete + insert +
metadata upsert inside **one** `withTransaction`. It documents that the metadata row is replaced as a
whole rather than merged.

**Migration** — `MIGRATION_6_7`: purely additive (three `CREATE TABLE`s and their indexes), so every
pre-existing table and row survives untouched.

**Gradle** — `testImplementation("org.robolectric:robolectric:4.16.1")`, because the assertions this
phase needed (SQL `ORDER BY`, foreign-key cascade, migration output) cannot be proven by the
hand-written fake DAOs the pre-existing tests used. Test-only change; no production dependency moved.

## Not Implemented

Everything the task excluded, all still absent at the end of this phase: no server catalog API, no
sync client, no catalog UI, no parent dashboard for the catalog. `CatalogSyncService` did not exist.

## Security Impact

- **`PlaybackAuthorization.kt` was not touched** (`git diff` lists three modified files; it is not one
  of them).
- The catalog tables deliberately contain **no approval flag, no permission column and no foreign key
  to `channels`/`videos`** — the catalog cannot become a second authorization mechanism.
- A test (`aCatalogEntryDoesNotGrantPlaybackPermission`) pins this: an unapproved video placed in the
  catalog is still `Rejected`, and withdrawing the approval while the catalog row stays keeps it
  rejected.
- Ordering decisions were made deliberately: duplicate sort orders remain **legal** (the `id`
  tie-break resolves them), so the schema does not silently reject a valid parent configuration.

## Database Changes

`parentapproved_cache`: **6 → 7**. Three new tables (`categories`, `content_items`,
`catalog_metadata`), two new indexes, one new foreign key. No existing table, column, index or row
altered. No destructive fallback anywhere.

## API Changes

**None.** No route was added or changed in this phase.

## UI Changes

**None.** `HomeScreen`, `VideoCard`, the player, the theme and the navigation are byte-identical to
`b130c12`; thumbnail geometry (200.dp, 16:9, 12.dp, 1.05, 4.dp ring) and the `titleMedium` shelf
header are untouched.

## Tests

`337 → 381 tests` (+44), all green, on a clean `:app:clean :app:testDebugUnitTest :app:assembleDebug`.

| Class | Tests | Covers |
|---|---|---|
| `CatalogDatabaseTest` | 28 | ordering with deliberately unsorted inserts, observe-flows, playlist/video round trips, six malformed shapes, blank-field rejection, display-name independence, cascade delete, FK refusal, metadata semantics, the mixed four-item shelf, the full task hierarchy, `EXPLAIN QUERY PLAN` proving the composite index is used, and that a catalog entry grants no playback |
| `CatalogMigrationTest` | 8 | a genuine v6 database migrated to v7, `user_version`, the new tables, exact columns/indexes/foreign key via `PRAGMA`, every pre-existing row read back through the real DAOs, `PlaybackAuthorization` still approving the migrated approved video, and no double migration |
| `CatalogValidationTest` | 8 | the identity rule, pure |

The migration test's v6 fixture DDL is copied from Room's own generated `CacheDatabase_Impl.kt`, and
Room's `onUpgrade` schema validation is the oracle: a pass means the migration produced exactly the
schema Room expects for all ten tables.

## Physical Device Testing

**None in this phase.** Phase 2 has no UI and no network surface, so there was nothing to observe on a
TV. The APK built, and the smoke tier was run later on Phase 2/3 code (12/12) — see
`docs/TESTING.md`.

## Known Issues

1. **Room schema export is off and cannot easily be turned on.** `exportSchema = true` makes a clean
   `kspDebugKotlin` crash with
   `AbstractMethodError: ... FieldBundle$$serializer does not define ... typeParametersSerializers()`,
   because KSP puts the module's `kotlinx-serialization` 1.8.0 (via Ktor) and `room-migration` 2.8.4's
   1.8.1 on one classloader. Consequence: no `app/schemas/` JSON, and `MigrationTestHelper` cannot be
   used as-is; the migration test uses the workaround described above. This was discovered by running
   a *clean* build — an incremental build had passed.
2. A test was silently not running for a while: an edit collapsed a blank line so `@Test` ended up
   inside a comment. It was caught by reconciling the per-class test count, and the counts in this
   document are per class for that reason.
3. Vestigial test seam: `ServiceLocator.initForTest(..., catalog = ...)` and `InMemoryCatalogStore`
   exist mainly for tests; nothing in production calls the parameter.

## Final Commit

```text
436e06ac5b4ebb047f074f2192c0f52384be5cbb   feat: add local catalog schema
16 files changed, 1874 insertions(+), 3 deletions(-)
```

Amended once before any push, after the clean-build failure above was fixed (the schema-export
attempt was reverted), so the phase landed as a single commit.

## Verification Status

| Claim | Status |
|---|---|
| Room 6 → 7 with existing data preserved | VERIFIED — `CatalogMigrationTest` on real SQLite |
| Ordering by `sort_order, id` | VERIFIED — DAO tests with unsorted inserts |
| Mixed playlist/video in one shelf | VERIFIED — DAO test |
| Parent display name independent of the YouTube id | VERIFIED — DAO test |
| Index actually used | VERIFIED — `EXPLAIN QUERY PLAN` assertion |
| Catalog entry does not grant playback | VERIFIED — DAO/repository test |
| No UI/player/authorization change | VERIFIED — `git diff` file list |
| Clean build + all tests | VERIFIED — 381 tests, 0 failures |
| Anything on a physical TV | NOT_RUN (nothing to exercise) |
