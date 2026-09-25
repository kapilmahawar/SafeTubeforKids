package tv.safetubeforkids.app.server

import tv.safetubeforkids.app.data.catalog.CATALOG_SCHEMA_VERSION
import tv.safetubeforkids.app.data.catalog.CatalogJson
import tv.safetubeforkids.app.data.catalog.CatalogNodeDto
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
 *
 * **The browser is not part of this.** The dashboard reads the catalog from here; nothing about the
 * catalog is ever written to browser storage.
 */
interface CatalogStore {

    /** The stored catalog. Never null: an unconfigured catalog is version 0 with no nodes. */
    fun read(): CatalogSnapshot

    /**
     * Stores [nodes] as the new catalog, assigning the next version.
     *
     * The payload is assumed to have been validated already - the route is the boundary and rejects
     * an invalid body with a precise 400 before calling this.
     *
     * A failed write must not invent a version: [CatalogStoreResult.VersionConflict] leaves the
     * stored catalog and its version alone, and a persistence failure throws, which the route turns
     * into a 500 - in both cases the previously committed catalog and version are still the ones
     * being read.
     */
    fun write(nodes: List<CatalogNodeDto>, expectedVersion: Long? = null): CatalogStoreResult
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
        nodes: List<CatalogNodeDto>,
        expectedVersion: Long?,
    ): CatalogStoreResult = synchronized(lock) {
        val current = load()

        // Optimistic concurrency, checked before anything is built or written: a stale request must
        // make zero catalog changes, not a change that is rolled back afterwards.
        if (expectedVersion != null && expectedVersion != current.catalogVersion) {
            return@synchronized CatalogStoreResult.VersionConflict(current.catalogVersion)
        }

        // The next version comes from a high-water mark, not from the document alone. If the
        // server's catalog document is lost or reset, a document-only counter would restart at 0 and
        // hand the parent's next publish a version *below* the one a TV already holds - the TV would
        // then refuse it as a regression and stay stranded until the counter climbed back past it.
        // Taking the larger of the document version and the store's own floor keeps the sequence
        // monotonic across a reset, so a genuine recovery is accepted instead of refused.
        val base = maxOf(current.catalogVersion, versionFloor())

        val snapshot = CatalogSnapshot(
            schemaVersion = CATALOG_SCHEMA_VERSION,
            // The server owns this number. A client can ask for a version to match, never propose one.
            catalogVersion = base + 1,
            nodes = stamped(nodes),
        )
        save(snapshot)
        CatalogStoreResult.Stored(snapshot)
    }

    /**
     * The stored document is the whole catalog, version included, so the nodes keep whatever the
     * parent's document said. The timestamps are the server's own bookkeeping, so a node that arrives
     * without them is stamped rather than rejected - which is what makes a version-1 document, or a
     * hand-written `curl`, produce a complete tree instead of a row of zeros.
     */
    private fun stamped(nodes: List<CatalogNodeDto>): List<CatalogNodeDto> {
        val now = clock()
        return nodes.map { node ->
            if (node.createdAt == 0L || node.updatedAt == 0L) {
                node.copy(
                    createdAt = if (node.createdAt == 0L) now else node.createdAt,
                    updatedAt = if (node.updatedAt == 0L) now else node.updatedAt,
                )
            } else {
                node
            }
        }
    }

    /** Overridable so a test can pin the clock the way it pins everything else. */
    protected open fun clock(): Long = System.currentTimeMillis()

    /**
     * A floor the next assigned version must stay above, independent of the stored document.
     *
     * Defaults to 0 for a store whose state cannot be lost separately from its catalog. A persistent
     * store overrides this with its own durable high-water mark.
     */
    protected open fun versionFloor(): Long = 0L

    protected abstract fun load(): CatalogSnapshot

    protected abstract fun save(snapshot: CatalogSnapshot)
}

/**
 * Persists the catalog as one JSON document, plus the version counter as a second, tiny file.
 *
 * The version lives *inside* the document for the TV's benefit, but the counter used to assign the
 * next one is also mirrored into its own file. That separation is the fix for the reset trap: losing
 * or reinitialising `catalog.json` no longer rewinds the version sequence, because the counter
 * outlives it and the next publish is still assigned a number above every one already issued.
 *
 * Deleting both files still resets the counter, which is why the recovery path is documented rather
 * than assumed: with both gone the server is indistinguishable from a brand-new install, and the TV
 * correctly keeps its own copy until the parent publishes again.
 */
class FileCatalogStore(
    private val file: File,
    private val versionFile: File = File(file.parentFile, VERSION_FILE_NAME),
) : BaseCatalogStore() {

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
        return when (val document = CatalogJson.decodeStoredDocument(text)) {
            is CatalogJson.StoredDocument.Version2 -> document.snapshot

            // The document on disk predates this build; it is still this server's committed catalog,
            // so it is read (translated to the node tree, version kept) rather than discarded. It is
            // written back as version 2 by the next successful publish, so nothing has to be rewritten
            // on a read path.
            is CatalogJson.StoredDocument.UpgradedFromVersion1 -> {
                AppLogger.log(
                    "Catalog store: read a version-1 document as version ${document.snapshot.catalogVersion} " +
                        "(${document.snapshot.nodes.size} nodes); it will be stored as version 2 on the next publish"
                )
                document.snapshot
            }

            CatalogJson.StoredDocument.Unreadable -> {
                AppLogger.error("Catalog store is not a readable catalog document; treating as empty")
                emptyCatalogSnapshot()
            }
        }
    }

    /**
     * The durable high-water mark. Unreadable or absent counts as 0, which degrades to the old
     * document-only behaviour rather than inventing a version.
     */
    override fun versionFloor(): Long = try {
        if (versionFile.exists()) versionFile.readText().trim().toLongOrNull() ?: 0L else 0L
    } catch (e: Exception) {
        AppLogger.warn("Catalog version counter unreadable: ${e.message}")
        0L
    }

    /**
     * Writes through a temporary file so a process death mid-write cannot truncate the catalog.
     *
     * A failure here propagates: the caller must not report success for a catalog that is not on
     * disk, and the previous document - and therefore the previous version - is still what [load]
     * reads.
     */
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
        // Mirror the counter out of the document so a later loss of the document cannot rewind it.
        // A failure here costs only the extra protection, never correctness - and it happens after the
        // document is committed, so it cannot report a stored catalog that is not there.
        try {
            versionFile.parentFile?.mkdirs()
            versionFile.writeText(snapshot.catalogVersion.toString())
        } catch (e: Exception) {
            AppLogger.warn("Catalog version counter could not be persisted: ${e.message}")
        }
        AppLogger.log("Catalog stored: version ${snapshot.catalogVersion}, ${snapshot.nodes.size} nodes")
    }

    companion object {
        const val FILE_NAME = "catalog.json"

        /** The version high-water mark, kept beside the document so the two can be lost separately. */
        const val VERSION_FILE_NAME = "catalog.version"

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
