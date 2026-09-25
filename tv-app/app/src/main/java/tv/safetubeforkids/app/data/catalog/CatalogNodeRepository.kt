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

    /** The nodes a version-1 payload would produce, without writing anything. */
    suspend fun planIngest(
        categories: List<CategoryEntity>,
        items: List<ContentItemEntity>,
    ): List<CatalogNodeEntity> = buildTree(categories, items)

    /**
     * Replaces the stored tree with the one [categories] describes, in one transaction: either the
     * whole catalog is the new one, or the previous one is untouched.
     *
     * Returns the number of nodes written (0 means the tree was already exactly this).
     */
    suspend fun ingest(
        categories: List<CategoryEntity>,
        items: List<ContentItemEntity>,
    ): Int = db.withTransaction {
        val desired = buildTree(categories, items)
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

                // Updated in place, never re-inserted: a REPLACE would delete the row first and the
                // cascade would take the container's children with it.
                else -> {
                    dao.update(node.copy(createdAt = previous.createdAt, updatedAt = now))
                    written++
                }
            }
        }
        written
    }

    /** Appends a node through the ordering service, which owns positions. */
    suspend fun add(node: CatalogNodeEntity): CatalogNodeEntity =
        CatalogOrderingService(db).append(node)

    /**
     * Inserts or updates one node by id, preserving identity: a new node is stamped now, an unchanged
     * node is not rewritten at all (so it keeps its timestamps), and a changed one keeps its
     * `created_at` **and its children** - an existing row is updated in place, never replaced.
     */
    suspend fun put(node: CatalogNodeEntity) {
        val previous = dao.getById(node.id)
        val now = System.currentTimeMillis()
        when {
            previous == null -> dao.insert(node.copy(createdAt = now, updatedAt = now))
            !previous.sameContentAs(node) ->
                dao.update(node.copy(createdAt = previous.createdAt, updatedAt = now))

            else -> Unit
        }
    }

    suspend fun deleteChildrenOf(parentId: String?) = dao.deleteChildrenOf(parentId)

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
     * A category's children as the shape the projection already consumes: a video as a VIDEO item, and a
     * container as the PLAYLIST item it used to be - its playlist id when it has an import source,
     * otherwise its first enabled video, so a container the parent built by hand still opens something
     * rather than presenting a card that can only fail. (W4 replaces this compatibility shape with real
     * container navigation.)
     */
    suspend fun items(categoryId: String): List<ContentItemEntity> {
        val result = mutableListOf<ContentItemEntity>()
        dao.childrenOf(categoryId).forEach { node ->
            when (node.nodeType) {
                CatalogNodeType.VIDEO -> result += node.asItem(categoryId)

                CatalogNodeType.SUBCATEGORY -> {
                    if (!node.youtubePlaylistId.isNullOrBlank()) {
                        result += node.asItem(categoryId)
                    } else {
                        dao.childrenOf(node.id)
                            .firstOrNull { it.nodeType == CatalogNodeType.VIDEO && it.enabled }
                            ?.let { result += it.asItem(categoryId) }
                    }
                }

                CatalogNodeType.CATEGORY -> Unit
            }
        }
        return result
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

    // --- version 1 -> the tree ---------------------------------------------------------------------

    private suspend fun buildTree(
        categories: List<CategoryEntity>,
        contentItems: List<ContentItemEntity>,
    ): List<CatalogNodeEntity> {
        val ordered = categories.sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val nodes = mutableListOf<CatalogNodeEntity>()

        ordered.forEachIndexed { categoryPosition, category ->
            nodes += CatalogNodeEntity(
                id = category.id,
                parentId = null,
                nodeType = CatalogNodeType.CATEGORY,
                title = category.displayName,
                position = categoryPosition,
                enabled = category.enabled,
                createdAt = category.createdAt,
                updatedAt = category.updatedAt,
            )
        }

        val byCategory = contentItems.groupBy { it.categoryId }

        ordered.forEach { category ->
            nodes += itemNodes(category.id, byCategory[category.id].orEmpty())
        }

        // An entry naming a shelf the payload does not contain still becomes a node, with that
        // missing shelf as its parent: the self-referencing foreign key then refuses the whole
        // replacement inside the transaction, so the previous catalog survives intact. Filtering
        // those entries out instead would quietly drop part of a parent's configuration and report
        // the sync as a success.
        ordered.map { it.id }.toSet().let { known ->
            (byCategory.keys - known).sorted().forEach { missingCategoryId ->
                nodes += itemNodes(missingCategoryId, byCategory.getValue(missingCategoryId))
            }
        }

        return nodes
    }

    /** The nodes one shelf's configured entries become, in the parent's order, positions 0..n-1. */
    private suspend fun itemNodes(
        categoryId: String,
        items: List<ContentItemEntity>,
    ): List<CatalogNodeEntity> {
        val nodes = mutableListOf<CatalogNodeEntity>()
        items.sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .forEachIndexed { itemPosition, item ->
                when (item.type) {
                    ContentItemType.VIDEO -> nodes += CatalogNodeEntity(
                        id = item.id,
                        parentId = categoryId,
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
                            parentId = categoryId,
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
