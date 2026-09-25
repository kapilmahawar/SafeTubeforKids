package tv.safetubeforkids.app.data.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/** What a catalog node is. One tree, one ordering rule, three kinds of child. */
enum class CatalogNodeType {
    /** A top-level shelf. Its parent is ROOT (parent_id NULL). */
    CATEGORY,

    /** A parent-defined container inside a category. It holds videos, never another container. */
    SUBCATEGORY,

    /** A playable item. It may sit directly in a category or inside a sub-category. */
    VIDEO,
}

/**
 * How a container gets its tile artwork.
 *
 * Only [AUTO] and [VIDEO] are implemented: arbitrary image URLs would need the dashboard's CSP to be
 * widened and an upload path that does not exist, so [CUSTOM] is reserved rather than half-built.
 */
enum class ThumbnailMode {
    /** The first enabled video descendant in catalog order - deterministic and stable across syncs. */
    AUTO,

    /** The thumbnail of the video named by `thumbnail_video_id`. */
    VIDEO,

    /** Reserved. Not implemented, and deliberately not reachable from the dashboard. */
    CUSTOM,
}

/** Stores node types and thumbnail modes by name, as the rest of the catalog schema does. */
class CatalogNodeConverters {
    @TypeConverter fun nodeTypeToString(value: CatalogNodeType): String = value.name
    @TypeConverter fun stringToNodeType(value: String): CatalogNodeType = CatalogNodeType.valueOf(value)
    @TypeConverter fun thumbnailModeToString(value: ThumbnailMode): String = value.name
    @TypeConverter fun stringToThumbnailMode(value: String): ThumbnailMode = ThumbnailMode.valueOf(value)
}

/**
 * One node of the parent's content tree.
 *
 * The tree is ROOT -> CATEGORY -> (SUBCATEGORY | VIDEO) -> VIDEO, and `parent_id + position` is the
 * only ordering mechanism there is: no per-type order columns, no fractional keys, no alphabetical or
 * insertion-order fallbacks. Siblings render `ORDER BY position ASC, id ASC`, where `id` only breaks a
 * tie two positions should never share.
 *
 * The node is **configuration, not permission**, exactly like the two tables it replaces: it carries
 * no approval flag and nothing here can grant playback. `PlaybackAuthorization` still decides that
 * from the approved `channels` / `videos` cache, so a node existing is never a reason a video plays.
 *
 * Identity is stable on purpose ([id] never changes): rename, reorder, move and synchronization all
 * keep it, so playlist media and resume history stay attached to the same item.
 */
@Entity(
    tableName = "catalog_nodes",
    foreignKeys = [
        ForeignKey(
            entity = CatalogNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["parent_id", "position"])],
)
@TypeConverters(CatalogNodeConverters::class)
data class CatalogNodeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    @ColumnInfo(name = "node_type") val nodeType: CatalogNodeType,
    val title: String,
    val position: Int,
    val enabled: Boolean = true,
    @ColumnInfo(name = "youtube_video_id") val youtubeVideoId: String? = null,
    /** Import provenance: the playlist this node came from, or the playlist a container imports. */
    @ColumnInfo(name = "youtube_playlist_id") val youtubePlaylistId: String? = null,
    @ColumnInfo(name = "thumbnail_mode") val thumbnailMode: ThumbnailMode = ThumbnailMode.AUTO,
    @ColumnInfo(name = "thumbnail_video_id") val thumbnailVideoId: String? = null,
    @ColumnInfo(name = "thumbnail_url") val thumbnailUrl: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
) {
    init {
        CatalogNodeValidation.requireValid(this)
    }
}

/**
 * The rules SQLite cannot express for the tree, kept in one place and enforced at both ends: the
 * entity refuses to exist while malformed, and [problemWith] returns a reason so the sync layer can
 * screen an untrusted payload *before* it becomes entities.
 */
object CatalogNodeValidation {

    /**
     * Human-readable reason the node is malformed, or `null` when it is valid.
     *
     * Takes the fields rather than the entity for the same reason [CatalogValidation] does: a caller
     * screening an untrusted payload must be able to ask about values *before* an entity exists, and the
     * malformed cases must be reachable by a test.
     */
    fun problemWith(
        id: String,
        parentId: String?,
        nodeType: CatalogNodeType,
        title: String,
        position: Int,
        youtubeVideoId: String?,
        youtubePlaylistId: String?,
    ): String? = when (nodeType) {
        CatalogNodeType.CATEGORY -> when {
            parentId != null -> "a CATEGORY node must sit at ROOT (parentId must be null)"
            youtubeVideoId != null -> "a CATEGORY node must not carry a youtubeVideoId"
            youtubePlaylistId != null -> "a CATEGORY node must not carry a youtubePlaylistId"
            else -> identityProblem(id, title, position)
        }

        CatalogNodeType.SUBCATEGORY -> when {
            parentId == null -> "a SUBCATEGORY node needs a parent"
            youtubeVideoId != null -> "a SUBCATEGORY node must not carry a youtubeVideoId"
            else -> identityProblem(id, title, position)
        }

        CatalogNodeType.VIDEO -> when {
            parentId == null -> "a VIDEO node needs a parent (a category or a sub-category)"
            youtubeVideoId.isNullOrBlank() -> "a VIDEO node requires a non-blank youtubeVideoId"
            else -> identityProblem(id, title, position)
        }
    }

    fun problemWith(node: CatalogNodeEntity): String? = problemWith(
        id = node.id,
        parentId = node.parentId,
        nodeType = node.nodeType,
        title = node.title,
        position = node.position,
        youtubeVideoId = node.youtubeVideoId,
        youtubePlaylistId = node.youtubePlaylistId,
    )

    fun isValid(node: CatalogNodeEntity): Boolean = problemWith(node) == null

    /** @throws IllegalArgumentException when the node is malformed. */
    fun requireValid(node: CatalogNodeEntity) {
        val problem = problemWith(node)
        require(problem == null) { "Malformed catalog node: $problem" }
    }

    /** The container a node may live in: ROOT takes only categories, a category takes anything else. */
    fun childTypeAllowedIn(parentType: CatalogNodeType?): Set<CatalogNodeType> = when (parentType) {
        null -> setOf(CatalogNodeType.CATEGORY)
        CatalogNodeType.CATEGORY -> setOf(CatalogNodeType.SUBCATEGORY, CatalogNodeType.VIDEO)
        CatalogNodeType.SUBCATEGORY -> setOf(CatalogNodeType.VIDEO)
        CatalogNodeType.VIDEO -> emptySet()
    }

    private fun identityProblem(id: String, title: String, position: Int): String? = when {
        id.isBlank() -> "a node needs a non-blank id"
        title.isBlank() -> "a node needs a non-blank title"
        position < 0 -> "a node position must not be negative (got $position)"
        else -> null
    }
}
