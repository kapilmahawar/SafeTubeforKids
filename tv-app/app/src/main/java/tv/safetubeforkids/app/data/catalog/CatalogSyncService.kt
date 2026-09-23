package tv.safetubeforkids.app.data.catalog

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.safetubeforkids.app.util.AppLogger

/**
 * Downloads the parent's catalog and installs it locally.
 *
 * The flow is fixed, and the order is the point:
 *
 * ```
 * fetch -> parse -> validate the COMPLETE payload -> compare versions -> map -> replaceCatalog (one transaction)
 * ```
 *
 * Nothing is written before the whole payload has been accepted. A single invalid item therefore
 * cannot half-replace the local catalog: the sync fails, and the TV keeps serving the catalog it
 * already had. The replacement itself is [CatalogRepository.replaceCatalog], Phase 2's single
 * transaction - there is no second replacement mechanism here.
 *
 * Completely independent of Compose and of any UI: a caller can invoke [syncCatalog] from app
 * startup, from a background worker or from a parent's manual refresh, and the local catalog is
 * readable throughout because it lives in Room.
 *
 * ### What each failure leaves behind
 *
 * | outcome | local catalog | `catalogVersion` | `serverVersion` | `lastSuccessfulSyncAt` | `lastAttemptAt` |
 * |---|---|---|---|---|---|
 * | updated | replaced | server's | server's | now | now |
 * | already current | untouched | unchanged | server's | unchanged | now |
 * | server unavailable / unauthorized / unreadable | untouched | unchanged | unchanged | unchanged | now |
 * | unsupported schema | untouched | unchanged | unchanged | unchanged | now |
 * | invalid catalog | untouched | unchanged | server's | unchanged | now |
 * | version regression | untouched | unchanged | server's | unchanged | now |
 * | local write failed | rolled back to the previous one | unchanged | server's | unchanged | now |
 *
 * `catalogVersion` only ever moves as part of a successful replacement, which is why it can be
 * trusted as "what the TV is actually showing".
 */
class CatalogSyncService(
    private val api: CatalogApi,
    private val repository: CatalogRepository,
    private val supportedSchemaVersion: Int = CATALOG_SCHEMA_VERSION,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Serializes syncs. Two overlapping runs could otherwise both decide a replacement is needed and
     * write different versions, with the older one landing last; one at a time means the second run
     * re-reads the version the first one installed and correctly finds nothing to do.
     */
    private val mutex = Mutex()

    suspend fun syncCatalog(): CatalogSyncResult = mutex.withLock {
        val attemptedAt = clock()
        val localVersion = repository.getMetadata()?.catalogVersion ?: 0L
        AppLogger.log("Catalog sync started (local version $localVersion)")
        repository.markSyncAttempt(attemptedAt)

        val snapshot = when (val fetch = api.fetch()) {
            is CatalogFetch.Unauthorized -> {
                AppLogger.warn("Catalog sync: server refused the session")
                return@withLock CatalogSyncResult.Unauthorized
            }

            is CatalogFetch.Unavailable -> {
                AppLogger.warn("Catalog sync: server unavailable - ${fetch.reason}")
                return@withLock CatalogSyncResult.ServerUnavailable(fetch.reason)
            }

            is CatalogFetch.Malformed -> {
                AppLogger.warn("Catalog sync: unreadable response - ${fetch.reason}")
                return@withLock CatalogSyncResult.InvalidResponse(fetch.reason)
            }

            is CatalogFetch.Ok -> fetch.snapshot
        }

        AppLogger.log(
            "Catalog sync: server version ${snapshot.catalogVersion}, schema ${snapshot.schemaVersion}"
        )

        if (snapshot.schemaVersion != supportedSchemaVersion) {
            AppLogger.warn(
                "Catalog sync: unsupported schema ${snapshot.schemaVersion} " +
                    "(this build speaks $supportedSchemaVersion)"
            )
            return@withLock CatalogSyncResult.UnsupportedSchema(
                serverSchemaVersion = snapshot.schemaVersion,
                supportedSchemaVersion = supportedSchemaVersion,
            )
        }

        // Recorded as soon as it is known, so a later failure still leaves an honest
        // "server is at N, this TV is at M" for whatever reports sync state.
        repository.markServerVersion(snapshot.catalogVersion)

        val categories = when (val outcome = CatalogPayloadValidator.validate(snapshot.categories)) {
            is CatalogPayloadValidator.Outcome.Invalid -> {
                AppLogger.error(
                    "Catalog sync: invalid catalog - ${CatalogPayloadValidator.describe(outcome.problems)}"
                )
                return@withLock CatalogSyncResult.InvalidCatalog(outcome.problems)
            }

            is CatalogPayloadValidator.Outcome.Valid -> outcome.categories
        }

        // A server that went backwards is not a reason to lose a newer local catalog. Rolling back
        // would need to be something a parent asks for deliberately, not something that happens
        // because a server was restored from an old backup.
        if (snapshot.catalogVersion < localVersion) {
            AppLogger.warn(
                "Catalog sync: refusing to downgrade the local catalog " +
                    "from $localVersion to ${snapshot.catalogVersion}"
            )
            return@withLock CatalogSyncResult.VersionRegression(
                serverVersion = snapshot.catalogVersion,
                localVersion = localVersion,
            )
        }

        if (snapshot.catalogVersion == localVersion) {
            AppLogger.log("Catalog sync: already current at version $localVersion")
            return@withLock CatalogSyncResult.AlreadyCurrent(localVersion)
        }

        return@withLock replaceLocally(categories, snapshot.catalogVersion, attemptedAt)
    }

    private suspend fun replaceLocally(
        categories: List<CatalogCategoryDto>,
        serverVersion: Long,
        syncedAt: Long,
    ): CatalogSyncResult = try {
        val mapped = CatalogMapper.toEntities(categories, syncedAt = syncedAt)
        when (
            val written = repository.replaceCatalog(
                categories = mapped.categories,
                contentItems = mapped.items,
                // The whole metadata row is replaced by this call, so the server version observed for
                // this payload is carried across explicitly rather than dropped: right after a
                // successful replacement the installed version and the observed server version are
                // the same number.
                metadata = CatalogMetadataEntity(
                    catalogVersion = serverVersion,
                    serverVersion = serverVersion,
                ),
                syncedAt = syncedAt,
            )
        ) {
            is CatalogWriteResult.Written -> {
                AppLogger.success(
                    "Catalog sync: installed version $serverVersion " +
                        "(${mapped.categories.size} categories, ${mapped.items.size} items)"
                )
                CatalogSyncResult.Updated(serverVersion)
            }

            is CatalogWriteResult.Rejected -> {
                AppLogger.error("Catalog sync: local replacement refused - ${written.reason}")
                CatalogSyncResult.LocalWriteFailed(written.reason)
            }
        }
    } catch (e: Exception) {
        // replaceCatalog runs inside one Room transaction, so a failure here has already rolled back
        // and the previously installed catalog is still the one being read.
        AppLogger.error("Catalog sync: local replacement failed - ${e.message}")
        CatalogSyncResult.LocalWriteFailed(e.message ?: e::class.java.simpleName)
    }
}
