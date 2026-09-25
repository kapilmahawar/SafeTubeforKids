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
    ): Int = db.withTransaction { applyTree(buildTree(categories, items)) }

    /**
     * Replaces the stored tree with [serverNodes] - the tree a version-2 document described - in one
     * transaction.
     *
     * This is W2's authoritative write path. It writes **exactly** the nodes it is given (plus the
     * episodes the TV has already cached for the containers those nodes import, which only the TV can
     * know), and it writes **nothing** of the previous tree that the new one does not contain: a
     * replacement is a replacement, which is what makes "the catalog is what the parent published"
     * true rather than approximately true.
     *
     * The nodes may be listed in any order. A document describes a tree through `parentId`, so the
     * write reorders them parent-first rather than requiring the server to serialise them that way.
     */
    suspend fun replaceTree(serverNodes: List<CatalogNodeEntity>): Int =
        db.withTransaction { applyTree(materializeImports(serverNodes)) }

    /**
     * The tree as it will be stored, for callers that want to see it before it is written.
     */
    suspend fun planReplaceTree(serverNodes: List<CatalogNodeEntity>): List<CatalogNodeEntity> =
        materializeImports(serverNodes)

    /**
     * The difference between the stored tree and [desired], applied. Must be called inside a
     * transaction - both callers open one, and the deletes and inserts have to commit together.
     *
     * Unchanged nodes are not rewritten at all, which is what keeps their `created_at`: resume
     * history, thumbnails and playlist provenance stay attached to the same item across syncs and
     * across a migration. Changed nodes are updated in place and keep their `created_at`.
     */
    private suspend fun applyTree(desired: List<CatalogNodeEntity>): Int {
        val existing = dao.all().associateBy { it.id }
        val now = System.currentTimeMillis()

        val removed = existing.keys - desired.map { it.id }.toSet()
        removed.forEach { dao.deleteById(it) }

        var written = 0
        inWriteOrder(desired).forEach { node ->
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
        return written
    }

    /**
     * Parents before their children, then the render order within each level.
     *
     * The inserts run behind the tree's self-referencing foreign key, so a child written before its
     * parent is refused - and the document is allowed to list them in any order, because the tree is
     * defined by `parentId`. The depth walk is bounded because a hand-built list could contain a
     * cycle; a cycle is refused by the foreign key anyway, and this only has to not hang on one.
     */
    private fun inWriteOrder(nodes: List<CatalogNodeEntity>): List<CatalogNodeEntity> {
        val byId = nodes.associateBy { it.id }

        fun depthOf(node: CatalogNodeEntity): Int {
            var depth = 0
            var parentId = node.parentId
            while (parentId != null && depth < MAX_WRITE_DEPTH) {
                depth++
                parentId = byId[parentId]?.parentId
            }
            return depth
        }

        return nodes.sortedWith(
            compareBy({ depthOf(it) }, { it.parentId ?: "" }, { it.position }, { it.id })
        )
    }

    /**
     * The parent's tree, plus what a playlist import has already brought in.
     *
     * A `SUBCATEGORY` node that carries a `youtubePlaylistId` is an *import*: the parent configured
     * "this playlist", and the videos it produced live in the TV's approved cache, not on the
     * server, which is why the server's document cannot contain them. Without this the episodes
     * would vanish from the tree the first time a TV synced a version-2 document - and with them the
     * ordering, provenance and thumbnails that the container's children carry today.
     *
     * The rules, so that a document and a cache can never produce the same episode twice:
     *  - children the document configured come first, in the document's order;
     *  - an episode whose `youtubeVideoId` is already among them is not added again;
     *  - the imported episodes follow, in cache order, numbered continuously from the last configured
     *    child - so the sibling list stays canonical `0..n-1`.
     */
    private suspend fun materializeImports(desired: List<CatalogNodeEntity>): List<CatalogNodeEntity> {
        val configuredChildren = desired.groupBy { it.parentId }
        val imported = mutableListOf<CatalogNodeEntity>()

        desired.filter { it.nodeType == CatalogNodeType.SUBCATEGORY }.forEach { container ->
            val playlistId = container.youtubePlaylistId?.takeIf { it.isNotBlank() } ?: return@forEach
            val videos = db.videoDao().getByPlaylist(playlistId)
            if (videos.isEmpty()) return@forEach

            val children = configuredChildren[container.id].orEmpty()
            val alreadyThere = children.mapNotNull { it.youtubeVideoId }.toSet()

            videos.filterNot { it.videoId in alreadyThere }
                .forEachIndexed { index, video ->
                    imported += CatalogNodeEntity(
                        id = "${container.id}#${video.videoId}",
                        parentId = container.id,
                        nodeType = CatalogNodeType.VIDEO,
                        title = video.title.ifBlank { video.videoId },
                        position = children.size + index,
                        enabled = true,
                        youtubeVideoId = video.videoId,
                        youtubePlaylistId = container.youtubePlaylistId,
                        createdAt = container.createdAt,
                        updatedAt = container.updatedAt,
                    )
                }
        }

        return desired + imported
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
        // The legacy value type is stricter than the tree: a VIDEO item must not carry a playlist id,
        // and it refuses to be constructed while one is there. The tree *does* keep provenance on an
        // imported video (which playlist brought it in), so the compatibility view drops it on videos
        // and keeps it on the containers it means something for. Dropping it here loses nothing: the
        // projection reads a video's identifier, not where it came from.
        youtubePlaylistId = youtubePlaylistId.takeIf { nodeType == CatalogNodeType.SUBCATEGORY },
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
    private fun CatalogNodeEntity.sameContentAs(other: CatalogNodeEntity): Boolean =        parentId == other.parentId &&
            nodeType == other.nodeType &&
            title == other.title &&
            position == other.position &&
            enabled == other.enabled &&
            youtubeVideoId == other.youtubeVideoId &&
            youtubePlaylistId == other.youtubePlaylistId &&
            thumbnailMode == other.thumbnailMode &&
            thumbnailVideoId == other.thumbnailVideoId &&
            thumbnailUrl == other.thumbnailUrl

    private companion object {
        /**
         * How far the write-order walk follows parents before giving up. The tree's own rules bound
         * the depth to three levels (ROOT -> CATEGORY -> SUBCATEGORY -> VIDEO); this only exists so a
         * pathological list cannot make the walk run forever.
         */
        const val MAX_WRITE_DEPTH = 8
    }
}
