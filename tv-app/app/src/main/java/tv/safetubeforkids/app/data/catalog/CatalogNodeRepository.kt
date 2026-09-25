package tv.safetubeforkids.app.data.catalog

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import tv.safetubeforkids.app.data.cache.CacheDatabase

/**
 * The node tree as the rest of the app will use it, plus the translation from the version-1 catalog
 * payload the server still speaks.
 *
 * Why this exists separately from [CatalogRepository]: the swap of the storage of record from
 * `categories`/`content_items` to `catalog_nodes` has to be mechanical, and everything it needs is
 * here - ingestion of a version-1 payload into the tree, node reads in render order, node writes, and
 * the derived legacy *views* (`categories()`, `items()`) so the projection and the sync service can keep
 * their current call shapes while the data underneath is nodes.
 *
 * The version-1 translation is the same rule MIGRATION_7_8 applies, so a migrated installation and a
 * freshly synced one produce identical trees:
 *  - a category becomes a ROOT CATEGORY node;
 *  - a PLAYLIST item becomes a SUBCATEGORY container named as the parent named it, keeping the playlist
 *    id as its import source, and the videos already cached for that playlist become its children in
 *    cache order (a playlist is a way to bring videos in, never a child-facing tile);
 *  - a VIDEO item becomes a VIDEO node.
 *
 * Identity is derived, never random (`<category id>`, `<item id>`, `<item id>#<video id>`), so a
 * re-sync of the same payload cannot churn ids - and an unchanged node is not written at all, so it
 * also keeps its `created_at`/`updated_at` and its resume history stays attached to the same item.
 */
class CatalogNodeRepository(private val db: CacheDatabase) {

    private val dao get() = db.catalogNodeDao()

    fun observeTree(): Flow<List<CatalogNodeEntity>> = dao.observeAll()

    suspend fun tree(): List<CatalogNodeEntity> = dao.all()

    suspend fun childrenOf(parentId: String?): List<CatalogNodeEntity> = dao.childrenOf(parentId)

    suspend fun node(id: String): CatalogNodeEntity? = dao.getById(id)

    suspend fun count(): Int = dao.count()

    /** How many nodes a version-1 payload would produce, without writing anything. */
    suspend fun planIngest(categories: List<CategoryEntity>): List<CatalogNodeEntity> =
        buildTree(categories)

    /**
     * Replaces the stored tree with the one [categories] describes, in one transaction: either the
     * whole catalog is the new one, or the previous one is untouched.
     *
     * Returns the number of nodes written (0 means the tree was already exactly this).
     */
    suspend fun ingest(categories: List<CategoryEntity>): Int = db.withTransaction {
        val desired = buildTree(categories)
        val existing = dao.all().associateBy { it.id }
        val now = System.currentTimeMillis()

        val removed = existing.keys - desired.map { it.id }.toSet()
        removed.forEach { dao.deleteById(it) }

        var written = 0
        desired.forEach { node ->
            val previous = existing[node.id]
            when {
                previous == null -> {
                    dao.insert(node.copy(createdAt = now, updatedAt = now))
                    written++
                }

                // Unchanged in every way that matters: leave the row (and its timestamps) alone.
                previous.sameContentAs(node) -> Unit

                else -> {
                    dao.insert(node.copy(createdAt = previous.createdAt, updatedAt = now))
                    written++
                }
            }
        }
        written
    }

    /** Appends a node through the ordering service, which owns positions. */
    suspend fun add(node: CatalogNodeEntity): CatalogNodeEntity =
        CatalogOrderingService(db).append(node)

    suspend fun move(nodeId: String, newParentId: String?, position: Int): Boolean =
        CatalogOrderingService(db).move(nodeId, newParentId, position)

    suspend fun rename(id: String, title: String) {
        val node = dao.getById(id) ?: return
        if (node.title != title) dao.rename(id, title, System.currentTimeMillis())
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val node = dao.getById(id) ?: return
        if (node.enabled != enabled) dao.setEnabled(id, enabled, System.currentTimeMillis())
    }

    /** Deleting a container takes its children with it (the foreign key cascades). */
    suspend fun delete(id: String) = dao.deleteById(id)

    // --- derived legacy views, so the projection and the sync service keep their call shapes --------

    /** The CATEGORY nodes at ROOT, in render order, as the shape the projection already consumes. */
    suspend fun categories(): List<CategoryEntity> = dao.childrenOf(null).map { node ->
        CategoryEntity(
            id = node.id,
            displayName = node.title,
            sortOrder = node.position,
            enabled = node.enabled,
            createdAt = node.createdAt,
            updatedAt = node.updatedAt,
        )
    }

    /**
     * A category's children as the shape the projection already consumes: a container reads as the
     * PLAYLIST item it used to be (its playlist id), a video as a VIDEO item.
     */
    suspend fun items(categoryId: String): List<ContentItemEntity> =
        dao.childrenOf(categoryId).map { node ->
            ContentItemEntity(
                id = node.id,
                categoryId = categoryId,
                type = if (node.nodeType == CatalogNodeType.VIDEO) {
                    ContentItemType.VIDEO
                } else {
                    ContentItemType.PLAYLIST
                },
                displayName = node.title,
                sortOrder = node.position,
                youtubePlaylistId = node.youtubePlaylistId,
                youtubeVideoId = node.youtubeVideoId,
                enabled = node.enabled,
                createdAt = node.createdAt,
                updatedAt = node.updatedAt,
            )
        }

    // --- version 1 -> the tree ---------------------------------------------------------------------

    private suspend fun buildTree(categories: List<CategoryEntity>): List<CatalogNodeEntity> {
        val ordered = categories.sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val nodes = mutableListOf<CatalogNodeEntity>()

        ordered.forEachIndexed { categoryPosition, category ->
            val categoryNode = CatalogNodeEntity(
                id = category.id,
                parentId = null,
                nodeType = CatalogNodeType.CATEGORY,
                title = category.displayName,
                position = categoryPosition,
                enabled = category.enabled,
                createdAt = category.createdAt,
                updatedAt = category.updatedAt,
            )
            nodes += categoryNode

            val items = db.contentItemDao().getByCategory(category.id)
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))

            items.forEachIndexed { itemPosition, item ->
                when (item.type) {
                    ContentItemType.VIDEO -> nodes += CatalogNodeEntity(
                        id = item.id,
                        parentId = category.id,
                        nodeType = CatalogNodeType.VIDEO,
                        title = item.displayName,
                        position = itemPosition,
                        enabled = item.enabled,
                        youtubeVideoId = item.youtubeVideoId,
                        createdAt = item.createdAt,
                        updatedAt = item.updatedAt,
                    )

                    ContentItemType.PLAYLIST -> {
                        // The container the parent named, carrying the playlist as its import source.
                        nodes += CatalogNodeEntity(
                            id = item.id,
                            parentId = category.id,
                            nodeType = CatalogNodeType.SUBCATEGORY,
                            title = item.displayName,
                            position = itemPosition,
                            enabled = item.enabled,
                            youtubePlaylistId = item.youtubePlaylistId,
                            createdAt = item.createdAt,
                            updatedAt = item.updatedAt,
                        )

                        // What that playlist has already brought in, in the approved cache's order.
                        val imported = item.youtubePlaylistId?.let { playlistId ->
                            db.videoDao().getByPlaylist(playlistId)
                        }.orEmpty()

                        imported.forEachIndexed { videoPosition, video ->
                            nodes += CatalogNodeEntity(
                                id = "${item.id}#${video.videoId}",
                                parentId = item.id,
                                nodeType = CatalogNodeType.VIDEO,
                                title = video.title.ifBlank { video.videoId },
                                position = videoPosition,
                                enabled = true,
                                youtubeVideoId = video.videoId,
                                youtubePlaylistId = item.youtubePlaylistId,
                                createdAt = item.createdAt,
                                updatedAt = item.updatedAt,
                            )
                        }
                    }
                }
            }
        }
        return nodes
    }

    /** Everything that makes two nodes the same row, ignoring the timestamps. */
    private fun CatalogNodeEntity.sameContentAs(other: CatalogNodeEntity): Boolean =
        parentId == other.parentId &&
            nodeType == other.nodeType &&
            title == other.title &&
            position == other.position &&
            enabled == other.enabled &&
            youtubeVideoId == other.youtubeVideoId &&
            youtubePlaylistId == other.youtubePlaylistId &&
            thumbnailMode == other.thumbnailMode &&
            thumbnailVideoId == other.thumbnailVideoId &&
            thumbnailUrl == other.thumbnailUrl
}
