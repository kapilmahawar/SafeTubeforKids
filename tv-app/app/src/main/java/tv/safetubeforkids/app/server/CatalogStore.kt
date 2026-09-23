package tv.safetubeforkids.app.server

import tv.safetubeforkids.app.data.catalog.CATALOG_SCHEMA_VERSION
import tv.safetubeforkids.app.data.catalog.CatalogCategoryDto
import tv.safetubeforkids.app.data.catalog.CatalogJson
import tv.safetubeforkids.app.data.catalog.CatalogSnapshot
import tv.safetubeforkids.app.data.catalog.emptyCatalogSnapshot
import tv.safetubeforkids.app.util.AppLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What a store did with a write. */
sealed class CatalogStoreResult {
    /** Stored, with the version the server assigned. */
    data class Stored(val snapshot: CatalogSnapshot) : CatalogStoreResult()

    /** `expectedCatalogVersion` did not match; nothing was written. */
    data class VersionConflict(val currentVersion: Long) : CatalogStoreResult()
}

/**
 * The server's authoritative catalog.
 *
 * **Where the catalog lives.** One JSON document on disk - `catalog.json` in the app's `filesDir` -
 * written by [FileCatalogStore]. That is deliberately not a Room table:
 *
 * - this document is the *parent's configuration*, and the Room catalog is the *TV's runtime copy*.
 *   If the server store were the same rows the TV reads, a "sync" would be copying a table onto
 *   itself, "last known good" would have nothing to fall back to, and a version-regression check
 *   would compare a number with itself. The two stores must be separate for any of Phase 3 to mean
 *   anything.
 * - the existing server persists the *approval* model in Room (channels/videos). A catalog is a
 *   different concern with its own version, and the advice not to reach for SQLite on the server
 *   merely because the client uses Room applies here too.
 *
 * A file also survives what the requirement asks about: server restart, app restart and device
 * reboot, because it is in the app's private storage rather than in a singleton.
 */
interface CatalogStore {

    /** The stored catalog. Never null: an unconfigured catalog is version 0 with no categories. */
    fun read(): CatalogSnapshot

    /**
     * Stores [categories] as the new catalog, assigning the next version.
     *
     * The payload is assumed to have been validated already - the route is the boundary and rejects
     * an invalid body with a precise 400 before calling this.
     *
     * A failed write must not invent a version: [CatalogStoreResult.VersionConflict] leaves the
     * stored catalog and its version alone.
     */
    fun write(categories: List<CatalogCategoryDto>, expectedVersion: Long? = null): CatalogStoreResult
}

/**
 * Assigning and guarding the version lives here, once, so both stores version identically.
 *
 * Reads and writes share one lock: the next version is derived from the loaded document inside the
 * same critical section that saves the new one, which is what stops two concurrent `PUT`s from being
 * handed the same version.
 */
abstract class BaseCatalogStore : CatalogStore {

    private val lock = Any()

    final override fun read(): CatalogSnapshot = synchronized(lock) { load() }

    final override fun write(
        categories: List<CatalogCategoryDto>,
        expectedVersion: Long?,
    ): CatalogStoreResult = synchronized(lock) {
        val current = load()

        if (expectedVersion != null && expectedVersion != current.catalogVersion) {
            return@synchronized CatalogStoreResult.VersionConflict(current.catalogVersion)
        }

        val snapshot = CatalogSnapshot(
            schemaVersion = CATALOG_SCHEMA_VERSION,
            catalogVersion = current.catalogVersion + 1,
            categories = categories,
        )
        save(snapshot)
        CatalogStoreResult.Stored(snapshot)
    }

    protected abstract fun load(): CatalogSnapshot

    protected abstract fun save(snapshot: CatalogSnapshot)
}

/**
 * Persists the catalog as one JSON document.
 *
 * The version lives inside the document, so it cannot be reset by a restart: a new process loads the
 * same number it wrote.
 */
class FileCatalogStore(private val file: File) : BaseCatalogStore() {

    override fun load(): CatalogSnapshot {
        if (!file.exists()) return emptyCatalogSnapshot()
        // An unreadable document reads as "nothing configured" (version 0), which is the safe
        // direction: every TV that already holds a catalog is at a version above 0 and refuses to
        // move down, so a truncated or hand-edited server document cannot push an empty catalog onto
        // a TV. The parent simply publishes the catalog again.
        val text = try {
            file.readText()
        } catch (e: Exception) {
            AppLogger.error("Catalog store unreadable: ${e.message}")
            return emptyCatalogSnapshot()
        }
        return CatalogJson.decodeSnapshot(text) ?: run {
            AppLogger.error("Catalog store is not a readable catalog document; treating as empty")
            emptyCatalogSnapshot()
        }
    }

    /** Writes through a temporary file so a process death mid-write cannot truncate the catalog. */
    override fun save(snapshot: CatalogSnapshot) {
        file.parentFile?.mkdirs()
        val text = CatalogJson.encode(snapshot)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // A filesystem that refuses the move still gets the correct contents.
            AppLogger.warn("Catalog store move failed (${e.message}); writing in place")
            file.writeText(text)
            temp.delete()
        }
        AppLogger.log("Catalog stored: version ${snapshot.catalogVersion}, ${snapshot.categories.size} categories")
    }

    companion object {
        const val FILE_NAME = "catalog.json"

        fun inFilesDir(filesDir: File): FileCatalogStore = FileCatalogStore(File(filesDir, FILE_NAME))
    }
}

/**
 * Non-persistent store for `ServiceLocator.initForTest`, which has no `filesDir`. Keeps the same
 * versioning semantics as [FileCatalogStore] because it shares [BaseCatalogStore].
 */
class InMemoryCatalogStore(initial: CatalogSnapshot = emptyCatalogSnapshot()) : BaseCatalogStore() {

    private var current: CatalogSnapshot = initial

    override fun load(): CatalogSnapshot = current

    override fun save(snapshot: CatalogSnapshot) {
        current = snapshot
    }
}

