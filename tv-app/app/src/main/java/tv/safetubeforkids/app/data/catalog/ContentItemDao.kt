package tv.safetubeforkids.app.data.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Reads order by `sort_order` explicitly, with `id` as the tie-break, so a playlist and a single
 * video can share one shelf in the order the parent configured (the mixed Music shelf from the
 * Phase 2 tests: playlist, playlist, video, video).
 */
@Dao
interface ContentItemDao {

    @Query(
        "SELECT * FROM content_items WHERE category_id = :categoryId " +
            "ORDER BY sort_order ASC, id ASC"
    )
    fun observeByCategory(categoryId: String): Flow<List<ContentItemEntity>>

    @Query(
        "SELECT * FROM content_items WHERE category_id = :categoryId " +
            "ORDER BY sort_order ASC, id ASC"
    )
    suspend fun getByCategory(categoryId: String): List<ContentItemEntity>

    @Query("SELECT * FROM content_items ORDER BY category_id ASC, sort_order ASC, id ASC")
    suspend fun getAll(): List<ContentItemEntity>

    @Query("SELECT * FROM content_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ContentItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ContentItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ContentItemEntity>)

    @Update
    suspend fun update(item: ContentItemEntity)

    @Query("DELETE FROM content_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM content_items WHERE category_id = :categoryId")
    suspend fun deleteByCategory(categoryId: String)

    @Query("DELETE FROM content_items")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM content_items WHERE category_id = :categoryId")
    suspend fun countByCategory(categoryId: String): Int

    @Query("SELECT COUNT(*) FROM content_items")
    suspend fun count(): Int
}
