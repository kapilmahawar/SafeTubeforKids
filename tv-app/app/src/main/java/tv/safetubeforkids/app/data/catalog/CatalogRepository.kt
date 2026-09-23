package tv.safetubeforkids.app.data.catalog

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import tv.safetubeforkids.app.data.cache.CacheDatabase

/** Outcome of a catalog mutation that can be refused. */
sealed class CatalogWriteResult {
    data object Written : CatalogWriteResult()
    data class Rejected(val reason: String) : CatalogWriteResult()
}

/**
 * Local catalog access - the only thing the future TV UI and the future sync layer need to talk to.
 *
 * It reads and writes Room and nothing else. It performs no HTTP, holds no server address and
 * knows nothing about playback: the architecture this is built for is
 *
 * ```
 * Server -> (Phase 3) Catalog Sync -> CatalogRepository -> Room -> (Phase 4) Android TV UI
 * ```
 *
 * **This is not an authorization mechanism.** A row here says a parent configured an item, not
 * that the child may play it. Only `PlaybackAuthorization` decides that, from the approved
 * `channels` / `videos` cache, so adding a catalog entry can never by itself grant playback.
 */
class CatalogRepository(private val db: CacheDatabase) {

    private val categoryDao get() = db.categoryDao()
    private val contentItemDao get() = db.contentItemDao()
    private val metadataDao get() = db.catalogMetadataDao()

    // ---------------------------------------------------------------- reads

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    suspend fun getCategories(): List<CategoryEntity> = categoryDao.getAll()

    suspend fun getCategory(id: String): CategoryEntity? = categoryDao.getById(id)

    fun observeItems(categoryId: String): Flow<List<ContentItemEntity>> =
        contentItemDao.observeByCategory(categoryId)

    suspend fun getItems(categoryId: String): List<ContentItemEntity> =
        contentItemDao.getByCategory(categoryId)

    suspend fun getItem(id: String): ContentItemEntity? = contentItemDao.getById(id)

    fun observeMetadata(): Flow<CatalogMetadataEntity?> = metadataDao.observe()

    suspend fun getMetadata(): CatalogMetadataEntity? = metadataDao.get()

    // ------------------------------------------------------------- mutations

    suspend fun upsertCategory(category: CategoryEntity): CatalogWriteResult =
        upsertCategories(listOf(category))

    suspend fun upsertCategories(categories: List<CategoryEntity>): CatalogWriteResult {
        categories.firstOrNull { it.id.isBlank() }?.let {
            return CatalogWriteResult.Rejected("a category requires a non-blank id")
        }
        categories.firstOrNull { it.displayName.isBlank() }?.let {
            return CatalogWriteResult.Rejected("category '${it.id}' requires a non-blank displayName")
        }
        db.withTransaction { categoryDao.insertAll(categories) }
        return CatalogWriteResult.Written
    }

    suspend fun deleteCategory(id: String) {
        // The foreign key cascades, so the category's items go with it and cannot be orphaned.
        categoryDao.deleteById(id)
    }

    suspend fun upsertItem(item: ContentItemEntity): CatalogWriteResult =
        upsertItems(listOf(item))

    suspend fun upsertItems(items: List<ContentItemEntity>): CatalogWriteResult {
        structuralProblem(items)?.let { return CatalogWriteResult.Rejected(it) }
        db.withTransaction { contentItemDao.insertAll(items) }
        return CatalogWriteResult.Written
    }

    suspend fun deleteItem(id: String) = contentItemDao.deleteById(id)

    suspend fun deleteItemsInCategory(categoryId: String) =
        contentItemDao.deleteByCategory(categoryId)

    // -------------------------------------------------------------- metadata

    suspend fun upsertMetadata(metadata: CatalogMetadataEntity) {
        metadataDao.upsert(metadata.copy(id = CatalogMetadataEntity.SINGLETON_ID))
    }

    /** Records that a synchronization completed at [catalogVersion]. */
    suspend fun markSyncSucceeded(catalogVersion: Long, syncedAt: Long = System.currentTimeMillis()) {
        if (metadataDao.markSyncSucceeded(catalogVersion, syncedAt) == 0) {
            metadataDao.upsert(
                CatalogMetadataEntity(
                    catalogVersion = catalogVersion,
                    serverVersion = catalogVersion,
                    lastSuccessfulSyncAt = syncedAt,
                    lastAttemptAt = syncedAt,
                )
            )
        }
    }

    /** Stamps the start of a synchronization attempt; a failure simply never stamps success. */
    suspend fun markSyncAttempt(attemptedAt: Long = System.currentTimeMillis()) {
        if (metadataDao.markSyncAttempt(attemptedAt) == 0) {
            metadataDao.upsert(CatalogMetadataEntity(lastAttemptAt = attemptedAt))
        }
    }

    suspend fun markServerVersion(version: Long) {
        if (metadataDao.markServerVersion(version) == 0) {
            metadataDao.upsert(CatalogMetadataEntity(serverVersion = version))
        }
    }

    // ------------------------------------------------------------- replacement

    /**
     * Last-known-good foundation: swaps the whole catalog in one transaction.
     *
     * Everything is validated *before* any write, and the delete/insert/metadata writes all run
     * inside a single [withTransaction], so a payload that fails halfway leaves the previously
     * stored catalog exactly as it was - the TV keeps working from the last good catalog. The
     * catalog is never left deleted-but-not-replaced, and nothing here needs the server, the
     * browser or the network.
     *
     * Referential integrity is intentionally left to the foreign key rather than re-implemented in
     * Kotlin: an item naming a category that the payload does not contain fails inside the
     * transaction and rolls the whole replacement back.
     *
     * Phase 3 supplies the payload; this method does no downloading.
     *
     * [metadata] becomes the **whole** catalog metadata row; it is not merged into the existing one,
     * so any field the caller wants to survive the replacement must be carried on the value it
     * passes. The singleton id and both sync timestamps are stamped here, inside the same transaction
     * as the data, so a successful replacement and its "successfully synced at" can never disagree.
     *
     * @param syncedAt when the synchronization that produced this payload completed, stamped into
     *   `last_successful_sync_at` in the same transaction as the data.
     */
    suspend fun replaceCatalog(
        categories: List<CategoryEntity>,
        contentItems: List<ContentItemEntity>,
        metadata: CatalogMetadataEntity,
        syncedAt: Long = System.currentTimeMillis(),
    ): CatalogWriteResult {
        categories.firstOrNull { it.id.isBlank() }?.let {
            return CatalogWriteResult.Rejected("a category requires a non-blank id")
        }
        categories.firstOrNull { it.displayName.isBlank() }?.let {
            return CatalogWriteResult.Rejected("category '${it.id}' requires a non-blank displayName")
        }
        structuralProblem(contentItems)?.let { return CatalogWriteResult.Rejected(it) }
        contentItems.forEach { item ->
            if (item.id.isBlank()) {
                return CatalogWriteResult.Rejected("a content item requires a non-blank id")
            }
            if (item.categoryId.isBlank()) {
                return CatalogWriteResult.Rejected("item '${item.id}' requires a non-blank categoryId")
            }
            if (item.displayName.isBlank()) {
                return CatalogWriteResult.Rejected("item '${item.id}' requires a non-blank displayName")
            }
        }

        db.withTransaction {
            // Children first, so the replacement never depends on cascade behaviour to stay legal.
            contentItemDao.deleteAll()
            categoryDao.deleteAll()
            categoryDao.insertAll(categories)
            contentItemDao.insertAll(contentItems)
            metadataDao.upsert(
                metadata.copy(
                    id = CatalogMetadataEntity.SINGLETON_ID,
                    lastSuccessfulSyncAt = syncedAt,
                    lastAttemptAt = syncedAt,
                )
            )
        }
        return CatalogWriteResult.Written
    }

    /**
     * The structural rules the entity itself cannot state: a blank id or name is perfectly valid
     * Kotlin, so it has to be refused here.
     *
     * The playlist/video identity rule is deliberately absent - [ContentItemEntity] will not
     * construct while it is violated, so there is no malformed identity to check for at this point.
     * A future sync layer maps untrusted payloads through
     * [CatalogValidation.contentIdentityProblem] *before* building entities, which is why that
     * check returns a reason instead of throwing.
     */
    private fun structuralProblem(items: List<ContentItemEntity>): String? {
        items.forEach { item ->
            if (item.id.isBlank()) return "a content item requires a non-blank id"
            if (item.categoryId.isBlank()) return "item '${item.id}' requires a non-blank categoryId"
            if (item.displayName.isBlank()) return "item '${item.id}' requires a non-blank displayName"
        }
        return null
    }
}
