package tv.safetubeforkids.app.ui.screens

import androidx.compose.runtime.Immutable
import tv.safetubeforkids.app.data.cache.ResumableVideoRow
import tv.safetubeforkids.app.data.cache.VideoThumbnailRow
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
 * - **Continue Watching is first** and contains only videos that are still approved (the query
 *   guarantees it), ordered by when the child last watched them.
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
    ): CatalogUiState {
        val artwork = ArtworkIndex(thumbnails)

        val shelves = buildList {
            continueWatchingShelf(resumable)?.let { add(it) }
            catalog
                .sortedWith(compareBy({ it.category.sortOrder }, { it.category.id }))
                .forEach { row -> categoryShelf(row, artwork)?.let { add(it) } }
        }

        return CatalogUiState(shelves = shelves)
    }

    private fun categoryShelf(row: CategoryWithItems, artwork: ArtworkIndex): CatalogShelfUi? {
        if (!row.category.enabled) return null

        val cards = row.items
            .asSequence()
            .filter { it.enabled }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { it.toCard(artwork) }
            .toList()

        // A shelf with nothing to press is a heading over dead space.
        if (cards.isEmpty()) return null

        return CatalogShelfUi(
            id = row.category.id,
            title = row.category.displayName,
            cards = cards,
        )
    }

    private fun continueWatchingShelf(resumable: List<ResumableVideoRow>): CatalogShelfUi? {
        if (resumable.isEmpty()) return null
        val cards = resumable.map { it.toCard() }
        return CatalogShelfUi(
            id = CONTINUE_WATCHING_ID,
            title = CONTINUE_WATCHING_TITLE,
            cards = cards,
        )
    }

    private fun ContentItemEntity.toCard(artwork: ArtworkIndex): CatalogCardUi = when (type) {
        ContentItemType.PLAYLIST -> CatalogCardUi(
            id = id,
            title = displayName,
            kind = CatalogCardKind.PLAYLIST,
            thumbnailUrl = artwork.forPlaylist(youtubePlaylistId),
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
