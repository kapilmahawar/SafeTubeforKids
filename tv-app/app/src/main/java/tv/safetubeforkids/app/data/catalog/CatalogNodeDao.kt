package tv.safetubeforkids.app.data.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The tree, read in the only order it is allowed to render in: `position ASC, id ASC` for the children
 * of one parent. Nothing here reads rows in insertion order, by primary key, or in the order a network
 * response happened to arrive in.
 */
@Dao
interface CatalogNodeDao {

    @Query("SELECT * FROM catalog_nodes WHERE parent_id IS :parentId ORDER BY position ASC, id ASC")
    suspend fun childrenOf(parentId: String?): List<CatalogNodeEntity>

    @Query("SELECT * FROM catalog_nodes WHERE parent_id IS :parentId ORDER BY position ASC, id ASC")
    fun observeChildrenOf(parentId: String?): Flow<List<CatalogNodeEntity>>

    /** Every node, parents before their children, each sibling list in catalog order. */
    @Query("SELECT * FROM catalog_nodes ORDER BY parent_id IS NOT NULL ASC, parent_id ASC, position ASC, id ASC")
    suspend fun all(): List<CatalogNodeEntity>

    @Query("SELECT * FROM catalog_nodes ORDER BY parent_id IS NOT NULL ASC, parent_id ASC, position ASC, id ASC")
    fun observeAll(): Flow<List<CatalogNodeEntity>>

    @Query("SELECT * FROM catalog_nodes ORDER BY parent_id IS NOT NULL ASC, parent_id ASC, position ASC, id ASC")
    @Transaction
    suspend fun tree(): List<CatalogNodeEntity> = all()

    @Query("SELECT * FROM catalog_nodes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CatalogNodeEntity?

    @Query("SELECT COUNT(*) FROM catalog_nodes")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM catalog_nodes WHERE parent_id IS :parentId")
    suspend fun countChildrenOf(parentId: String?): Int

    @Query("SELECT MAX(position) FROM catalog_nodes WHERE parent_id IS :parentId")
    suspend fun maxPosition(parentId: String?): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: CatalogNodeEntity)

    /**
     * Rewrites an existing node **in place**, returning the number of rows changed.
     *
     * Deliberately an `UPDATE` rather than another `INSERT OR REPLACE`: SQLite implements REPLACE by
     * deleting the conflicting row first, and with `ON DELETE CASCADE` that delete takes the node's
     * children with it. A rename or a reorder of a container would therefore silently wipe the
     * episodes inside it. Nothing that already exists is ever written with [insert].
     */
    @Update
    suspend fun update(node: CatalogNodeEntity): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<CatalogNodeEntity>)

    /** Moves a node and/or renumbers it. Positions are only ever written through the ordering service. */
    @Query(
        "UPDATE catalog_nodes SET parent_id = :parentId, position = :position, updated_at = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updatePlacement(id: String, parentId: String?, position: Int, updatedAt: Long)

    @Query("UPDATE catalog_nodes SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE catalog_nodes SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)

    @Query(
        "UPDATE catalog_nodes SET thumbnail_mode = :mode, thumbnail_video_id = :videoId, " +
            "updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun setThumbnail(id: String, mode: ThumbnailMode, videoId: String?, updatedAt: Long)

    @Query("DELETE FROM catalog_nodes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM catalog_nodes WHERE parent_id IS :parentId")
    suspend fun deleteChildrenOf(parentId: String?)
    @Query("DELETE FROM catalog_nodes")
    suspend fun deleteAll()
}
