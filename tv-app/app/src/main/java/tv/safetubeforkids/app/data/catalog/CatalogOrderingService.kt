package tv.safetubeforkids.app.data.catalog

import androidx.room.withTransaction
import tv.safetubeforkids.app.data.cache.CacheDatabase

/**
 * The one implementation of "put this node there" for the whole tree.
 *
 * Containers and videos move through the same code: there is no separate category reorder, video
 * reorder or playlist reorder path, because `parent_id + position` means the same thing everywhere.
 *
 * Positions are contiguous from 0 within each parent, and every mutation that touches more than one
 * sibling runs inside one database transaction - so a move either commits completely or leaves the
 * previous order exactly as it was, with no half-renumbered sibling list in between.
 *
 * [move] takes an arbitrary insertion index on purpose: the dashboard's Move up / Move down / Move to
 * controls use it today, and a drag-and-drop editor can call the same method later without the data
 * model changing.
 */
class CatalogOrderingService(private val db: CacheDatabase) {

    private val dao get() = db.catalogNodeDao()

    suspend fun childrenOf(parentId: String?): List<CatalogNodeEntity> = dao.childrenOf(parentId)

    suspend fun node(id: String): CatalogNodeEntity? = dao.getById(id)

    /** Appends [node] as the last child of the parent it names, renumbering nothing else. */
    suspend fun append(node: CatalogNodeEntity): CatalogNodeEntity = db.withTransaction {
        val placed = node.copy(position = (dao.maxPosition(node.parentId) ?: -1) + 1)
        dao.insert(placed)
        placed
    }

    /** Appends a list in the order given, so an import's own order becomes the initial catalog order. */
    suspend fun appendAll(nodes: List<CatalogNodeEntity>): List<CatalogNodeEntity> = db.withTransaction {
        var next = (dao.maxPosition(nodes.firstOrNull()?.parentId) ?: -1) + 1
        nodes.map { node -> node.copy(position = next++).also { dao.insert(it) } }
    }

    /**
     * Moves [nodeId] into [newParentId] at [position], then renumbers both sibling lists so they stay
     * contiguous. [position] is clamped to the destination list, and an out-of-range or negative index
     * therefore cannot corrupt anything.
     *
     * Returns true when the node existed and was placed; false when there is no such node.
     */
    suspend fun move(nodeId: String, newParentId: String?, position: Int): Boolean = db.withTransaction {
        val node = dao.getById(nodeId) ?: return@withTransaction false
        val oldParentId = node.parentId
        val now = System.currentTimeMillis()

        val destination = dao.childrenOf(newParentId)
            .filterNot { it.id == nodeId }
            .toMutableList()
        val target = position.coerceIn(0, destination.size)
        destination.add(target, node)

        destination.forEachIndexed { index, child ->
            if (child.position != index || child.parentId != newParentId) {
                dao.updatePlacement(child.id, newParentId, index, now)
            }
        }

        if (oldParentId != newParentId) {
            dao.childrenOf(oldParentId)
                .filterNot { it.id == nodeId }
                .forEachIndexed { index, child ->
                    if (child.position != index) dao.updatePlacement(child.id, oldParentId, index, now)
                }
        }

        true
    }

    /** Moves a node one place earlier among its siblings; a no-op at the top of the list. */
    suspend fun moveUp(nodeId: String): Boolean = nudge(nodeId, -1)

    /** Moves a node one place later among its siblings; a no-op at the end of the list. */
    suspend fun moveDown(nodeId: String): Boolean = nudge(nodeId, +1)

    private suspend fun nudge(nodeId: String, delta: Int): Boolean {
        val node = dao.getById(nodeId) ?: return false
        val target = (node.position + delta).coerceAtLeast(0)
        return move(nodeId, node.parentId, target)
    }

    /** Renumbers a sibling list to 0..n-1 without changing its order. */
    suspend fun normalise(parentId: String?): Int = db.withTransaction {
        val now = System.currentTimeMillis()
        var changed = 0
        dao.childrenOf(parentId).forEachIndexed { index, child ->
            if (child.position != index) {
                dao.updatePlacement(child.id, parentId, index, now)
                changed++
            }
        }
        changed
    }
}
