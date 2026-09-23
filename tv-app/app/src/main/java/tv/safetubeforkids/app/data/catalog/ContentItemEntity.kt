package tv.safetubeforkids.app.data.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters

/**
 * One entry inside a [CategoryEntity]: either a whole YouTube playlist or a single video.
 *
 * This table is configuration, not authorization. It records only *what the parent configured* -
 * a SafeTube name plus the YouTube identifier it points at. It carries no approval flag, no
 * permission column and no reference that could grant playback: whether an identifier may actually
 * play is decided solely by `PlaybackAuthorization`, which consults the approved `channels` /
 * `videos` cache. A catalog row for an unapproved video therefore plays nothing.
 *
 * [displayName] is stored explicitly and independently of the YouTube title: the parent may point
 * "Nursery Songs" at a playlist actually called "Super Fun Educational Songs 2026 Official
 * Playlist", and the child must see the former.
 *
 * The composite index backs the only item query: items of one category, in [sortOrder] order.
 * The foreign key cascades, so deleting a category cannot leave orphaned items behind.
 */
@Entity(
    tableName = "content_items",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["category_id", "sort_order"])],
)
@TypeConverters(CatalogConverters::class)
data class ContentItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "type") val type: ContentItemType,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "youtube_playlist_id") val youtubePlaylistId: String? = null,
    @ColumnInfo(name = "youtube_video_id") val youtubeVideoId: String? = null,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
) {
    init {
        CatalogValidation.requireValidContentIdentity(type, youtubePlaylistId, youtubeVideoId)
    }
}
