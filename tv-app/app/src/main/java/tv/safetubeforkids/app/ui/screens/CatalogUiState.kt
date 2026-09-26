package tv.safetubeforkids.app.ui.screens

import androidx.compose.runtime.Immutable
import tv.safetubeforkids.app.data.cache.ResumableVideoRow
import tv.safetubeforkids.app.data.cache.VideoThumbnailRow
import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogThumbnails
import tv.safetubeforkids.app.data.catalog.CategoryWithItems
import tv.safetubeforkids.app.data.catalog.ContentItemEntity
import tv.safetubeforkids.app.data.catalog.ContentItemType

/** What a card stands for on screen. */
enum class CatalogCardKind {
    PLAYLIST,
    VIDEO,

    /** An entry from Continue Watching, which is always one approved video. */
    CONTINUE_WATCHING,
}

/**
 * One card. [title] is the parent's `displayName` verbatim - never a YouTube title, never an
 * identifier.
 */
@Immutable
data class CatalogCardUi(
    val id: String,
    val title: String,
    val kind: CatalogCardKind,
    val thumbnailUrl: String?,
    val badgeText: String? = null,
    /** Set for a single video (and every Continue Watching entry). */
    val videoId: String? = null,
    /** Set for a playlist card: the YouTube playlist id to open. */
    val playlistId: String? = null,
)

/** One shelf: a parent-defined category, or the Continue Watching row. */
@Immutable
data class CatalogShelfUi(
    val id: String,
    val title: String,
    val cards: List<CatalogCardUi>,
    /**
     * The picture that stands for the shelf itself, when one could be resolved.
     *
     * Null is a normal state - a shelf of nothing but unapproved videos, or a catalog with no eligible
     * video at all - and the header simply draws no image, exactly as a card without artwork draws a
     * placeholder.
     */
    val thumbnailUrl: String? = null,
)

/** Everything the home screen renders, projected from Room. */
@Immutable
data class CatalogUiState(
    val shelves: List<CatalogShelfUi> = emptyList(),
) {
    /** Nothing the parent configured is visible, so the deliberate empty state is shown. */
    val isEmpty: Boolean get() = shelves.isEmpty()
}

/**
 * Projects Room rows into what the TV shows.
 *
 * Kept as a pure function so the catalog rendering rules - order, enabled filtering, empty shelves,
 * display names, artwork lookup - are testable without a database, a screen or a device.
 *
 * ### Rules this encodes, and why
 *
 * - **Order is the parent's.** Categories and items are both ordered here by
 *   `sort_order ASC, id ASC`, the same explicit rule the DAOs use - so the shelf order is the
 *   parent's configuration and cannot drift if a query's `ORDER BY` is ever changed, and nothing
 *   here sorts alphabetically. (`@Relation` cannot express `ORDER BY` for the items, which is why
 *   the rule is re-applied on this side at all.)
 * - **Disabled content is not shown.** A disabled category, or a disabled item, is left in Room but
 *   not rendered. Rows are never deleted for being hidden, so a parent can switch them back on.
 * - **Empty shelves are dropped.** A category with no enabled items would be a heading over a dead
 *   area, so it is omitted rather than rendered empty.
 * - **Artwork comes from the approved cache.** The catalog has no thumbnails; the video the parent
 *   already approved for that identifier supplies one. A missing thumbnail is null, and the card
 *   shows a placeholder of exactly the same size.
 * - **Which video represents a container is the parent's configuration.** A shelf or a sub-category
 *   resolves its picture through [CatalogThumbnails] - `AUTO` picks the first eligible video inside
 *   it in catalog order, `VIDEO` the one the parent named - and the resolved video's artwork is then
 *   looked up the same way a video card's is. A container that resolves to nothing keeps exactly the
 *   artwork it had before this existed (its playlist's opening video, when it has one) and otherwise
 *   shows the placeholder. Nothing here can fail to draw: every path ends in a url or in null.
 * - **Continue Watching is first** and contains only videos the child can still reach: the video
 *   must be half-watched *and* still approved (the query guarantees the latter) *and* still part of
 *   the currently published child-visible catalog. The catalog is what a parent curates, so removing
 *   an item - or disabling it, or the category holding it, or emptying the catalog entirely - takes
 *   it off this shelf too. Otherwise a parent could remove a video from the catalog and still watch
 *   the child reach it from Continue Watching. Order is when the child last watched them.
 *   This is visibility only and does not touch authorization: `PlaybackAuthorization` remains the
 *   single playback gate, and hiding a card here neither grants nor revokes permission, nor deletes
 *   the saved position.
 */
object CatalogUiProjection {

    const val CONTINUE_WATCHING_ID = "shelf-continue-watching"
    const val CONTINUE_WATCHING_TITLE = "Continue Watching"

    /**
     * Mirrors the player's own resume rule (`PlaybackController.RESUME_MIN_MS` /
     * `RESUME_MAX_PERCENT`): the shelf offers what the player would actually offer to resume, so a
     * child never picks a card that then starts from the beginning. Kept here rather than made
     * public on the player so Phase 4 changes no player code; the behaviour is pinned by a test.
     */
    const val RESUME_MIN_POSITION_MS = 20_000L
    const val RESUME_MAX_PERCENT = 95

    fun build(
        catalog: List<CategoryWithItems>,
        thumbnails: List<VideoThumbnailRow>,
        resumable: List<ResumableVideoRow>,
        /**
         * The whole tree, so a container's picture can be resolved from the parent's thumbnail
         * configuration. Empty is a legitimate value - every container then falls back to the artwork
         * it had before thumbnails were configurable.
         */
        tree: List<CatalogNodeEntity> = emptyList(),
    ): CatalogUiState {
        val artwork = ArtworkIndex(thumbnails)
        val representatives = CatalogThumbnails.representatives(tree)
        // Continue Watching is curated by the catalog like every other shelf, so it is filtered
        // against what the parent currently publishes before it is built.
        val visible = VisibleCatalog(catalog)

        val shelves = buildList {
            continueWatchingShelf(resumable, visible)?.let { add(it) }
            catalog
                .sortedWith(compareBy({ it.category.sortOrder }, { it.category.id }))
                .forEach { row -> categoryShelf(row, artwork, representatives)?.let { add(it) } }
        }

        return CatalogUiState(shelves = shelves)
    }

    private fun categoryShelf(
        row: CategoryWithItems,
        artwork: ArtworkIndex,
        representatives: Map<String, String>,
    ): CatalogShelfUi? {
        if (!row.category.enabled) return null

        val cards = row.items
            .asSequence()
            .filter { it.enabled }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { it.toCard(artwork, representatives) }
            .toList()

        // A shelf with nothing to press is a heading over dead space.
        if (cards.isEmpty()) return null

        return CatalogShelfUi(
            id = row.category.id,
            title = row.category.displayName,
            cards = cards,
            // The shelf's own picture: the video the parent chose for the category, or what AUTO
            // picked inside it. Null when there is nothing eligible, and the header then draws no
            // image at all - there is no placeholder to preserve here, because a shelf header never
            // had a picture before.
            thumbnailUrl = representatives[row.category.id]?.let { artwork.forVideo(it) },
        )
    }

    /**
     * Half-watched videos that the parent still publishes to the child.
     *
     * A card is shown only when the video is reachable through the current catalog: either an
     * enabled VIDEO item names it directly, or an enabled PLAYLIST item names the source it belongs
     * to. Anything else is dropped, so an empty or fully-disabled catalog yields no shelf at all and
     * the empty state shows instead.
     */
    private fun continueWatchingShelf(
        resumable: List<ResumableVideoRow>,
        visible: VisibleCatalog,
    ): CatalogShelfUi? {
        val cards = resumable
            .filter { visible.contains(it.videoId, it.playlistId) }
            .map { it.toCard() }
        if (cards.isEmpty()) return null
        return CatalogShelfUi(
            id = CONTINUE_WATCHING_ID,
            title = CONTINUE_WATCHING_TITLE,
            cards = cards,
        )
    }

    private fun ContentItemEntity.toCard(
        artwork: ArtworkIndex,
        representatives: Map<String, String>,
    ): CatalogCardUi = when (type) {
        // A container card: the picture is the video the parent chose (or AUTO picked) *inside* it.
        // When the catalog resolves nothing - a legacy entry with no tree, a container whose videos
        // are all hidden - the playlist's opening approved video is used, which is exactly what this
        // card showed before thumbnails were configurable.
        ContentItemType.PLAYLIST -> CatalogCardUi(
            id = id,
            title = displayName,
            kind = CatalogCardKind.PLAYLIST,
            thumbnailUrl = artwork.forVideo(representatives[id]) ?: artwork.forPlaylist(youtubePlaylistId),
            playlistId = youtubePlaylistId,
        )

        ContentItemType.VIDEO -> CatalogCardUi(
            id = id,
            title = displayName,
            kind = CatalogCardKind.VIDEO,
            thumbnailUrl = artwork.forVideo(youtubeVideoId),
            videoId = youtubeVideoId,
            playlistId = artwork.sourceOf(youtubeVideoId),
        )
    }

    private fun ResumableVideoRow.toCard(): CatalogCardUi = CatalogCardUi(
        id = "$CONTINUE_WATCHING_ID:$videoId",
        title = title,
        kind = CatalogCardKind.CONTINUE_WATCHING,
        thumbnailUrl = thumbnailUrl.takeIf { it.isNotBlank() },
        badgeText = resumeBadge(positionMs, durationMs, durationSeconds),
        videoId = videoId,
        playlistId = playlistId,
    )

    /** "12 min left" where the duration is known, otherwise how far in the child already is. */
    private fun resumeBadge(positionMs: Long, durationMs: Long, durationSeconds: Long): String {
        val totalSeconds = when {
            durationMs > 0 -> durationMs / 1000
            durationSeconds > 0 -> durationSeconds
            else -> 0L
        }
        if (totalSeconds <= 0) return "Watched"
        val remainingMinutes = ((totalSeconds - positionMs / 1000).coerceAtLeast(0) + 59) / 60
        return if (remainingMinutes <= 0) "Watched" else "$remainingMinutes min left"
    }

    /**
     * What the current catalog actually publishes to the child: the video ids named directly by an
     * enabled item, and the playlist ids named by an enabled playlist item.
     *
     * This decides only whether a card is drawn. It confers no permission - a video that is visible
     * here still has to pass `PlaybackAuthorization` to play - and a video hidden here keeps its
     * approval record and its saved position, because nothing is deleted for being unpublished.
     */
    private class VisibleCatalog(catalog: List<CategoryWithItems>) {
        private val videoIds = mutableSetOf<String>()
        private val playlistIds = mutableSetOf<String>()

        init {
            catalog.asSequence()
                .filter { it.category.enabled }
                .flatMap { rows -> rows.items.asSequence() }
                .filter { item -> item.enabled }
                .forEach { item ->
                    when (item.type) {
                        ContentItemType.VIDEO ->
                            item.youtubeVideoId?.takeIf { it.isNotBlank() }?.let { videoIds.add(it) }
                        ContentItemType.PLAYLIST ->
                            item.youtubePlaylistId?.takeIf { it.isNotBlank() }?.let { playlistIds.add(it) }
                    }
                }
        }

        /** Published when the video is named directly, or belongs to a published playlist. */
        fun contains(videoId: String, playlistId: String): Boolean =
            videoId in videoIds || (playlistId.isNotBlank() && playlistId in playlistIds)
    }

    /**
     * Video id -> artwork, and playlist id -> its opening video's artwork. Built once per emission
     * from a single query, so the shelves never issue a lookup per card.
     */
    private class ArtworkIndex(rows: List<VideoThumbnailRow>) {
        private val byVideo: Map<String, VideoThumbnailRow> =
            rows.associateBy { it.videoId }

        // Rows arrive ordered by position within each playlist, so first-wins is the opening video.
        private val byPlaylist: Map<String, VideoThumbnailRow> =
            rows.groupBy { it.playlistId }.mapValues { (_, group) -> group.first() }

        fun forVideo(videoId: String?): String? =
            videoId?.let { byVideo[it]?.thumbnailUrl?.takeIf { url -> url.isNotBlank() } }

        fun forPlaylist(playlistId: String?): String? =
            playlistId?.let { byPlaylist[it]?.thumbnailUrl?.takeIf { url -> url.isNotBlank() } }

        /** Which approved source a single video belongs to, so the player route names it. */
        fun sourceOf(videoId: String?): String? =
            videoId?.let { byVideo[it]?.playlistId }?.takeIf { it.isNotBlank() }
    }
}

/** Where selecting a card should send the existing player. */
data class CatalogPlaybackTarget(
    val videoId: String,
    val playlistId: String,
    val startIndex: Int = 0,
)
