package tv.safetubeforkids.app.data.catalog

/**
 * One entry inside a [CategoryEntity]: either a whole YouTube playlist or a single video.
 *
 * This is configuration, not authorization. It records only *what the parent configured* - a
 * SafeTube name plus the YouTube identifier it points at. It carries no approval flag, no permission
 * column and no reference that could grant playback: whether an identifier may actually play is
 * decided solely by `PlaybackAuthorization`, which consults the approved `channels` / `videos` cache.
 * A catalog entry for an unapproved video therefore plays nothing.
 *
 * [displayName] is stored explicitly and independently of the YouTube title: the parent may point
 * "Nursery Songs" at a playlist actually called "Super Fun Educational Songs 2026 Official
 * Playlist", and the child must see the former.
 *
 * **This is a value type, not a table.** Since W1b the only catalog storage is the `catalog_nodes`
 * tree, where an entry is a child of its shelf's `CATEGORY` node - `SUBCATEGORY` for a playlist,
 * `VIDEO` for a single video - with [sortOrder] as that node's `position`. Nothing persists this
 * class: [CatalogRepository] and [CatalogNodeRepository] derive it from the tree on read and
 * translate it back to nodes on write. The parent/child foreign key that used to keep entries from
 * being orphaned is now the tree's self-referencing `parent_id` key, which cascades the same way.
 */
data class ContentItemEntity(
    val id: String,
    val categoryId: String,
    val type: ContentItemType,
    val displayName: String,
    val sortOrder: Int,
    val youtubePlaylistId: String? = null,
    val youtubeVideoId: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    init {
        CatalogValidation.requireValidContentIdentity(type, youtubePlaylistId, youtubeVideoId)
    }
}
