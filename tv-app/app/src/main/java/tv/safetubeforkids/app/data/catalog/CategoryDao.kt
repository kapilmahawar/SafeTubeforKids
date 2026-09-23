package tv.safetubeforkids.app.data.catalog

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One shelf with its entries, as the TV home screen needs them.
 *
 * Read through a `@Transaction` relation query rather than by combining two flows: Room then reads
 * the parent and its children inside a single transaction, so a catalog replacement can never be
 * observed half-applied. The UI sees either the whole old catalog or the whole new one - never new
 * shelves paired with the previous items.
 *
 * `@Relation` cannot express `ORDER BY`, so [items] arrives in an unspecified order and the UI
 * projection re-applies the same explicit `sort_order ASC, id ASC` rule the DAOs use everywhere
 * else. That rule exists precisely so nothing depends on unspecified SQLite ordering.
 */
data class CategoryWithItems(
    @Embedded val category: CategoryEntity,
    @Relation(parentColumn = "id", entityColumn = "category_id")
    val items: List<ContentItemEntity>,
)

/**
 * Every read orders by `sort_order` explicitly and ties break on `id`, so the shelf order the TV
 * shows is the parent's configured order and never insertion order, rowid order or alphabetical
 * order - and never an unspecified SQLite order when two shelves share a sort order.
 */
@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY sort_order ASC, id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    /**
     * The whole catalog in the parent's order, shelves with their items, read atomically.
     *
     * One query rather than one subscription per shelf: Room observes both `categories` and
     * `content_items` for it, so a replacement re-emits exactly once with a consistent snapshot.
     */
    @Transaction
    @Query("SELECT * FROM categories ORDER BY sort_order ASC, id ASC")
    fun observeCatalog(): Flow<List<CategoryWithItems>>

    @Query("SELECT * FROM categories ORDER BY sort_order ASC, id ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}
