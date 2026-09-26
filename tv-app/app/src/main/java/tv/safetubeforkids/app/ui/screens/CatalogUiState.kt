package tv.safetubeforkids.app.ui.screens

import androidx.compose.runtime.Immutable
import tv.safetubeforkids.app.data.cache.ResumableVideoRow
import tv.safetubeforkids.app.data.cache.VideoThumbnailRow
import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeType
import tv.safetubeforkids.app.data.catalog.CatalogThumbnails

/** What a card stands for on screen, and therefore what pressing it does. */
enum class CatalogCardKind {
    /** A sub-category: a card that opens the container, never a video that plays. */
    CONTAINER,

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
    /** Set for a container card: the catalog node id to open. Never played directly. */
    val containerId: String? = null,
    /**
     * The playlist a container imports, when it has one.
     *
     * Provenance and the last-resort artwork lookup - not a destination. Pressing a container opens the
     * container; a playlist is a way videos arrived, and since W6 it is never a way to start playing.
     */
    val playlistId: String? = null,
)

/** One shelf: a parent-defined category, or the Continue Watching row. */
@Immutable
data class CatalogShelfUi(
    val id: String,
    val title: String,
    val cards: List<CatalogCardUi>,
    /**
     * The picture beside this row's heading, when the row is an *open container*.
     *
     * That is the only case there is: a sub-category has a thumbnail (its own W5 picture) and its
     * screen shows it above the videos inside. A **category shelf never has one** - a category is a
     * title, and [CatalogUiProjection.categoryShelves] neither resolves nor sets a picture for it - so
     * this is null for every shelf on the home screen, and for the Continue Watching row.
     */
    val headingPicture: String? = null,
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
 * One container, opened: a sub-category's own screen.
 *
 * A sub-category is a group, so opening it shows the group - the same card row the home screen draws,
 * built from the same rules - under the container's own name. It is deliberately *not* a video: W6's
 * whole point is that pressing a container never starts playing the first thing inside it.
 */
@Immutable
data class CatalogContainerUi(
    val id: String,
    val title: String,
    /**
     * The container's own picture: the sub-category's W5 thumbnail, shown above the videos inside it.
     *
     * This is the sub-category's picture, never a category's - a category cannot be opened, so it has
     * no screen to show anything on.
     */
    val thumbnailUrl: String? = null,
    val cards: List<CatalogCardUi> = emptyList(),
) {
    /** A container with nothing to show - empty, or everything in it hidden. */
    val isEmpty: Boolean get() = cards.isEmpty()

    /** The same shape the shelf renderer draws, so both screens use one implementation. */
    fun asShelf(): CatalogShelfUi =
        CatalogShelfUi(id = id, title = title, cards = cards, headingPicture = thumbnailUrl)
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
 * - **Which video represents a card is the parent's configuration.** A sub-category resolves its
 *   picture through [CatalogThumbnails] - `AUTO` picks the first eligible video inside it in catalog
 *   order, `VIDEO` the one the parent named - and the resolved video's artwork is then looked up the
 *   same way a video card's is. A container that resolves to nothing keeps exactly the artwork it had
 *   before this existed (its playlist's opening video, when it has one) and otherwise shows the
 *   placeholder. Nothing here can fail to draw: every path ends in a url or in null.
 * - **A category is a title and nothing else.** A `CATEGORY` becomes the heading of a shelf: no card,
 *   no icon, no picture - not a resolved representative video, not a placeholder, not a decoration -
 *   and it is never focusable. A `SUBCATEGORY` becomes one selectable card, whether the parent built
 *   it by hand or it imports a playlist, and pressing it opens the container rather than starting
 *   anything. Flattening a container into its first video is what W6 removed: a group is not an
 *   episode. The only picture a shelf row ever carries is an *open container's* own - see
 *   [CatalogShelfUi.headingPicture].
 * - **Only sub-categories are asked for a picture.** [CatalogThumbnails.representatives] answers for
 *   sub-categories alone, so a category thumbnail cannot be resolved here even by accident.
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

    /**
     * What the child sees when they open the app.
     *
     * The **tree is the catalog**, so the tree is what this reads: one shelf per enabled category at
     * ROOT, in `position ASC, id ASC`, each holding its own enabled children in the same order. There is
     * no intermediate "item" shape in this path any more, and that is deliberate - the compatibility
     * value type has no way to say "a container the parent built by hand" (a playlist item must name a
     * playlist), so projecting through it is what used to force such a container to be shown as its first
     * video. Reading the tree removes the possibility rather than working around it.
     */
    fun build(
        tree: List<CatalogNodeEntity>,
        thumbnails: List<VideoThumbnailRow>,
        resumable: List<ResumableVideoRow>,
    ): CatalogUiState {
        val artwork = ArtworkIndex(thumbnails)
        val representatives = CatalogThumbnails.representatives(tree)
        // Continue Watching is curated by the catalog like every other shelf, so it is filtered
        // against what the parent currently publishes before it is built.
        val visible = VisibleCatalog(tree)

        val shelves = buildList {
            continueWatchingShelf(resumable, visible)?.let { add(it) }
            categoryShelves(tree, artwork, representatives).forEach { add(it) }
        }

        return CatalogUiState(shelves = shelves)
    }

    /**
     * One container, opened by the child.
     *
     * Built by the same rule as a shelf, one level down: the container's own enabled children in
     * catalog order, each becoming the card it would be on the home screen.
     *
     * Null when [containerId] is not a container at all (an unknown id, or a shelf: a category is a
     * heading, not a destination), which is what keeps a stale or hand-built navigation argument from
     * opening a screen that has nothing to show.
     */
    fun container(
        containerId: String,
        tree: List<CatalogNodeEntity>,
        thumbnails: List<VideoThumbnailRow>,
    ): CatalogContainerUi? {
        val node = tree.firstOrNull { it.id == containerId } ?: return null
        if (node.nodeType != CatalogNodeType.SUBCATEGORY) return null

        val artwork = ArtworkIndex(thumbnails)
        val representatives = CatalogThumbnails.representatives(tree)

        return CatalogContainerUi(
            id = node.id,
            title = node.title,
            thumbnailUrl = representatives[node.id]?.let { artwork.forVideo(it) }
                ?: artwork.forPlaylist(node.youtubePlaylistId),
            cards = cardsOf(node.id, tree, artwork, representatives),
        )
    }

    /** Every shelf on the home screen: one per enabled category, in the parent's order. */
    private fun categoryShelves(
        tree: List<CatalogNodeEntity>,
        artwork: ArtworkIndex,
        representatives: Map<String, String>,
    ): List<CatalogShelfUi> = tree
        .filter { it.parentId == null && it.nodeType == CatalogNodeType.CATEGORY && it.enabled }
        .sortedWith(compareBy({ it.position }, { it.id }))
        .mapNotNull { category ->
            val cards = cardsOf(category.id, tree, artwork, representatives)
            // A shelf with nothing to press is a heading over dead space.
            if (cards.isEmpty()) return@mapNotNull null

            CatalogShelfUi(
                id = category.id,
                // A category is a shelf/group *title*: it is the heading above its children and is
                // never a card of its own, never focusable and never clickable. It has no picture
                // either - not a resolved representative video, not an icon, not a decoration - and
                // nothing here asks for one, which is why this constructor sets no `headingPicture`.
                title = category.title,
                cards = cards,
            )
        }

    /** A node's own enabled children, in catalog order, as the cards they are. */
    private fun cardsOf(
        parentId: String,
        tree: List<CatalogNodeEntity>,
        artwork: ArtworkIndex,
        representatives: Map<String, String>,
    ): List<CatalogCardUi> = tree
        .filter { it.parentId == parentId && it.enabled }
        .sortedWith(compareBy({ it.position }, { it.id }))
        .mapNotNull { child -> cardFor(child, artwork, representatives) }

    /**
     * One node as the card it is on screen.
     *
     * A `VIDEO` becomes a video card, with the artwork the approved cache holds for its identifier and
     * the source that identifier belongs to. A `SUBCATEGORY` becomes a container card - whether it
     * imports a playlist or the parent built it by hand - whose picture is resolved by
     * [CatalogThumbnails], the same W5 rule as everywhere else and never a second resolver. A
     * `CATEGORY` is a shelf title, so it is never a card; it is dropped rather than drawn as something
     * it is not.
     */
    private fun cardFor(
        node: CatalogNodeEntity,
        artwork: ArtworkIndex,
        representatives: Map<String, String>,
    ): CatalogCardUi? = when (node.nodeType) {
        CatalogNodeType.VIDEO -> CatalogCardUi(
            id = node.id,
            title = node.title,
            kind = CatalogCardKind.VIDEO,
            thumbnailUrl = artwork.forVideo(node.youtubeVideoId),
            videoId = node.youtubeVideoId?.takeIf { it.isNotBlank() },
            playlistId = artwork.sourceOf(node.youtubeVideoId),
        )

        CatalogNodeType.SUBCATEGORY -> CatalogCardUi(
            id = node.id,
            title = node.title,
            kind = CatalogCardKind.CONTAINER,
            thumbnailUrl = artwork.forVideo(representatives[node.id])
                ?: artwork.forPlaylist(node.youtubePlaylistId),
            containerId = node.id,
            playlistId = node.youtubePlaylistId?.takeIf { it.isNotBlank() },
        )

        CatalogNodeType.CATEGORY -> null
    }

    /**
     * Half-watched videos that the parent still publishes to the child.
     *
     * A card is shown only when the video is reachable through the current catalog, and the tree is
     * what says so: either an enabled video node the tree reaches through enabled containers, or a
     * video belonging to a playlist an enabled container imports. Anything else is dropped, so an empty
     * or fully-disabled catalog yields no shelf at all and the empty state shows instead.
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
     * What the current catalog actually publishes to the child, read from the tree.
     *
     * A video is published when it is an enabled `VIDEO` node the tree reaches from an enabled category,
     * through enabled containers; a container's imported playlist is published when the container
     * itself is enabled, which keeps a half-watched episode offered even before its node has been
     * materialized.
     *
     * This decides only whether a card is drawn. It confers no permission - a video that is visible
     * here still has to pass `PlaybackAuthorization` to play - and a video hidden here keeps its
     * approval record and its saved position, because nothing is deleted for being unpublished.
     */
    private class VisibleCatalog(tree: List<CatalogNodeEntity>) {
        private val videoIds = mutableSetOf<String>()
        private val playlistIds = mutableSetOf<String>()

        init {
            val byId = tree.associateBy { it.id }

            tree.asSequence()
                .filter { it.nodeType == CatalogNodeType.VIDEO }
                .filter { it.youtubeVideoId?.isNotBlank() == true }
                .filter { reachableFromAShelf(it, byId) }
                .forEach { videoIds.add(it.youtubeVideoId!!) }

            tree.asSequence()
                .filter { it.nodeType == CatalogNodeType.SUBCATEGORY }
                .filter { reachableFromAShelf(it, byId) }
                .mapNotNull { it.youtubePlaylistId?.takeIf { playlistId -> playlistId.isNotBlank() } }
                .forEach { playlistIds.add(it) }
        }

        /** A node is published when it and every container above it are enabled, up to its shelf. */
        private fun reachableFromAShelf(
            node: CatalogNodeEntity,
            byId: Map<String, CatalogNodeEntity>,
        ): Boolean {
            if (!node.enabled) return false

            var current: CatalogNodeEntity? = node
            var guard = 0
            while (current != null && guard++ < 64) {
                if (!current.enabled) return false
                if (current.nodeType == CatalogNodeType.CATEGORY) return current.parentId == null
                current = current.parentId?.let { byId[it] }
            }
            return false
        }

        /** Published when the tree reaches the video, or it belongs to a published playlist. */
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
