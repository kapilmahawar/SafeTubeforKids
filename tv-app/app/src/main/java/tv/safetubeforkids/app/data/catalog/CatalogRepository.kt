package tv.safetubeforkids.app.data.catalog

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.ResumableVideoRow
import tv.safetubeforkids.app.data.cache.VideoThumbnailRow

/** Outcome of a catalog mutation that can be refused. */
sealed class CatalogWriteResult {
    data object Written : CatalogWriteResult()
    data class Rejected(val reason: String) : CatalogWriteResult()
}

/**
 * A category with its children, as the TV home screen consumes it.
 *
 * This is a **derived view**, not storage: it used to be a Room relation over `categories` +
 * `content_items`, and it is now assembled from `catalog_nodes`. Nothing persists it, and it carries no
 * approval information - only `PlaybackAuthorization` decides what may play.
 */
data class CategoryWithItems(
    val category: CategoryEntity,
    val items: List<ContentItemEntity> = emptyList(),
)

/**
 * Local catalog access - the only thing the TV UI and the sync layer need to talk to.
 *
 * Since W1b the **sole** catalog storage is `catalog_nodes`: one ordered tree, ordered by
 * `parent_id + position`. This class holds no tables of its own; every read is derived from nodes and
 * every write goes through [CatalogNodeRepository] / [CatalogOrderingService]. The older
 * `CategoryEntity` / `ContentItemEntity` shapes survive only as the value types the projection already
 * consumes, and are never persisted.
 *
 * **This is not an authorization mechanism.** A node says a parent configured an item, not that the
 * child may play it; only `PlaybackAuthorization` decides that, from the approved `channels` / `videos`
 * cache.
 */
class CatalogRepository(private val db: CacheDatabase) {

    private val nodes = CatalogNodeRepository(db)
    private val metadataDao get() = db.catalogMetadataDao()

    // ---------------------------------------------------------------- reads

    fun observeCategories(): Flow<List<CategoryEntity>> = nodes.observeTree().map { tree ->
        tree.filter { it.parentId == null && it.nodeType == CatalogNodeType.CATEGORY }
            .map { it.asCategory() }
    }

    suspend fun getCategories(): List<CategoryEntity> = nodes.categories()

    suspend fun getCategory(id: String): CategoryEntity? =
        nodes.node(id)?.takeIf { it.nodeType == CatalogNodeType.CATEGORY }?.asCategory()

    fun observeItems(categoryId: String): Flow<List<ContentItemEntity>> =
        nodes.observeTree().map { tree -> tree.itemsOf(categoryId) }

    suspend fun getItems(categoryId: String): List<ContentItemEntity> = nodes.items(categoryId)

    suspend fun getItem(id: String): ContentItemEntity? {
        val node = nodes.node(id) ?: return null
        return nodes.items(node.parentId ?: return null).firstOrNull { it.id == id }
    }

    fun observeMetadata(): Flow<CatalogMetadataEntity?> = metadataDao.observe()

    /**
     * The whole catalog for the TV home screen: every shelf with its children, in parent order, read
     * from one tree snapshot so a replacement is never seen half-applied.
     */
    fun observeCatalogWithItems(): Flow<List<CategoryWithItems>> = nodes.observeTree().map { tree ->
        tree.filter { it.parentId == null && it.nodeType == CatalogNodeType.CATEGORY }
            .map { CategoryWithItems(it.asCategory(), tree.itemsOf(it.id)) }
    }

    /** The whole tree, for callers that want the real shape rather than the compatibility view. */
    fun observeTree(): Flow<List<CatalogNodeEntity>> = nodes.observeTree()

    suspend fun getTree(): List<CatalogNodeEntity> = nodes.tree()

    /**
     * Artwork for the cached videos, so catalog cards can show a picture even though the catalog itself
     * carries only a name and a YouTube identifier. Local Room data; no network.
     */
    fun observeVideoThumbnails(): Flow<List<VideoThumbnailRow>> =
        db.videoDao().observeThumbnailIndex()

    /**
     * Videos that are half-watched *and* still approved, most recent first, for Continue Watching.
     * See [ResumableVideoRow] for why the join keeps that shelf from becoming a permission.
     */
    fun observeResumableVideos(minPositionMs: Long, maxPercent: Int): Flow<List<ResumableVideoRow>> =
        db.playbackPositionDao().observeResumable(minPositionMs, maxPercent)

    /**
     * The first video of [playlistId]'s approved queue, or null when that playlist has nothing
     * authorized behind it. This chooses where a container card *starts*; it grants nothing.
     */
    suspend fun firstApprovedVideoOf(playlistId: String): String? =
        tv.safetubeforkids.app.playback.PlaybackAuthorization.approvedQueue(db, playlistId)
            .firstOrNull()
            ?.videoId

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
        db.withTransaction {
            categories.forEach { category ->
                nodes.put(nodeFor(category, position = category.sortOrder))
            }
        }
        return CatalogWriteResult.Written
    }

    suspend fun deleteCategory(id: String) {
        // The node foreign key cascades, so the category's children go with it and cannot be orphaned.
        nodes.delete(id)
    }

    suspend fun upsertItem(item: ContentItemEntity): CatalogWriteResult =
        upsertItems(listOf(item))

    suspend fun upsertItems(items: List<ContentItemEntity>): CatalogWriteResult {
        structuralProblem(items)?.let { return CatalogWriteResult.Rejected(it) }
        db.withTransaction {
            items.forEach { item -> nodes.put(nodeFor(item)) }
        }
        return CatalogWriteResult.Written
    }

    suspend fun deleteItem(id: String) = nodes.delete(id)

    suspend fun deleteItemsInCategory(categoryId: String) = nodes.deleteChildrenOf(categoryId)

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
     * Last-known-good foundation: swaps the whole catalog tree in one transaction.
     *
     * Everything is validated *before* any write, and the tree replacement and the metadata write run
     * inside a single [withTransaction], so a payload that fails halfway leaves the previously stored
     * catalog exactly as it was - the TV keeps working from the last good catalog, and the tree is never
     * left deleted-but-not-replaced.
     *
     * The node ids are derived (`<category id>`, `<item id>`, `<item id>#<video id>`), so a successful
     * replacement keeps the identity of everything that did not change: unchanged nodes are not
     * rewritten and keep their timestamps, while changed nodes keep their `created_at`.
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
            if (item.categoryId.isBlank()) {
                return CatalogWriteResult.Rejected("item '${item.id}' requires a non-blank categoryId")
            }
        }

        db.withTransaction {
            nodes.ingest(categories, contentItems)
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

    // -------------------------------------------------------------- mapping

    private fun nodeFor(category: CategoryEntity, position: Int? = null) = CatalogNodeEntity(
        id = category.id,
        parentId = null,
        nodeType = CatalogNodeType.CATEGORY,
        title = category.displayName,
        position = position ?: category.sortOrder,
        enabled = category.enabled,
        createdAt = category.createdAt,
        updatedAt = category.updatedAt,
    )

    private fun nodeFor(item: ContentItemEntity) = CatalogNodeEntity(
        id = item.id,
        parentId = item.categoryId,
        nodeType = if (item.type == ContentItemType.VIDEO) {
            CatalogNodeType.VIDEO
        } else {
            CatalogNodeType.SUBCATEGORY
        },
        title = item.displayName,
        position = item.sortOrder,
        enabled = item.enabled,
        youtubeVideoId = item.youtubeVideoId,
        youtubePlaylistId = item.youtubePlaylistId,
        createdAt = item.createdAt,
        updatedAt = item.updatedAt,
    )

    private fun CatalogNodeEntity.asCategory() = CategoryEntity(
        id = id,
        displayName = title,
        sortOrder = position,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun List<CatalogNodeEntity>.itemsOf(categoryId: String): List<ContentItemEntity> {
        val direct = filter { it.parentId == categoryId }
        return direct.mapNotNull { node ->
            when (node.nodeType) {
                CatalogNodeType.VIDEO -> node.asItem(categoryId)

                CatalogNodeType.SUBCATEGORY -> if (!node.youtubePlaylistId.isNullOrBlank()) {
                    node.asItem(categoryId)
                } else {
                    // A hand-built container has no playlist to start: it opens its first enabled video.
                    filter { it.parentId == node.id && it.nodeType == CatalogNodeType.VIDEO && it.enabled }
                        .minByOrNull { it.position }
                        ?.asItem(categoryId)
                }

                CatalogNodeType.CATEGORY -> null
            }
        }
    }

    private fun CatalogNodeEntity.asItem(categoryId: String) = ContentItemEntity(
        id = id,
        categoryId = categoryId,
        type = if (nodeType == CatalogNodeType.VIDEO) ContentItemType.VIDEO else ContentItemType.PLAYLIST,
        displayName = title,
        sortOrder = position,
        youtubePlaylistId = youtubePlaylistId,
        youtubeVideoId = youtubeVideoId,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    /**
     * The structural rules the value type itself cannot state: a blank id or name is perfectly valid
     * Kotlin, so it has to be refused here. The playlist/video identity rule is deliberately absent -
     * [ContentItemEntity] will not construct while it is violated.
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
