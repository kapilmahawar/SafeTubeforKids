package tv.safetubeforkids.app.data.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A parent-defined shelf shown to the child ("Cartoon", "Music", "Learning", "Stories").
 *
 * [id] is an opaque, stable identifier supplied by the configuration source - never derived from
 * [displayName], so renaming a shelf cannot orphan its items or reorder the home screen.
 * [displayName] is the SafeTube name the parent chose; it is intentionally unrelated to any
 * YouTube channel or playlist title.
 *
 * The index exists for the table's only access pattern: everything reads the shelves in
 * [sortOrder] order.
 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["sort_order"])],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)
