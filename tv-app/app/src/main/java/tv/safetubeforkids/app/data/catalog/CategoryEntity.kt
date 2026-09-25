package tv.safetubeforkids.app.data.catalog

/**
 * A parent-defined shelf shown to the child ("Cartoon", "Music", "Learning", "Stories").
 *
 * [id] is an opaque, stable identifier supplied by the configuration source - never derived from
 * [displayName], so renaming a shelf cannot orphan its items or reorder the home screen.
 * [displayName] is the SafeTube name the parent chose; it is intentionally unrelated to any
 * YouTube channel or playlist title.
 *
 * **This is a value type, not a table.** Since W1b the only catalog storage is the `catalog_nodes`
 * tree, where a shelf is a ROOT `CATEGORY` node: [sortOrder] is that node's `position` and
 * [createdAt] / [updatedAt] are its timestamps, in the render order `position ASC, id ASC`. Nothing
 * persists this class - [CatalogRepository] derives it from the tree on read and translates it back
 * to nodes on write - and it exists so the projection and the sync payload keep their shape.
 */
data class CategoryEntity(
    val id: String,
    val displayName: String,
    val sortOrder: Int,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
