package tv.safetubeforkids.app.server

import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.VideoThumbnailRow
import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeType
import tv.safetubeforkids.app.data.catalog.CatalogRepository
import tv.safetubeforkids.app.data.catalog.CatalogThumbnails
import tv.safetubeforkids.app.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/**
 * What one container looks like to the parent's dashboard: its picture, and how many videos are
 * inside it *as the child sees them*.
 */
@Serializable
data class ContainerArtwork(
    val thumbnailUrl: String = "",
    /** Enabled videos the child can reach inside it, at any depth. */
    val videoCount: Int = 0,
    /** How many videos are hidden inside it - the difference between this and the document. */
    val hiddenVideoCount: Int = 0,
)

@Serializable
data class CatalogArtworkResponse(
    /**
     * The version of the catalog the TV has actually *installed*, which is what these pictures and
     * counts describe. It can be older than the document the dashboard is editing: artwork comes from
     * the TV's approved cache, and a video whose source was approved a minute ago has none yet.
     */
    val installedCatalogVersion: Long = 0,
    /** Video id -> cached artwork url. Only the videos the TV has approved and resolved. */
    val videos: Map<String, String> = emptyMap(),
    /** Catalog node id -> what the TV draws for that container. Only sub-categories have pictures. */
    val containers: Map<String, ContainerArtwork> = emptyMap(),
)

@Serializable
data class CatalogRefreshResponse(
    val status: String,
    val installedCatalogVersion: Long = 0,
    val sourcesRefreshed: Int = 0,
    val message: String = "",
)

/**
 * The two read-mostly endpoints the redesigned dashboard needs and the old one did without.
 *
 * ### Why they exist at all
 *
 * `GET /catalog` returns the parent's *document*, and that document is deliberately small: it holds
 * what the parent configured, not what the TV rendered. Two things a parent needs are therefore not
 * in it:
 *
 *  - **Pictures.** The catalog carries no artwork (the model refuses a `thumbnailUrl` outright), so a
 *    card's picture is looked up at render time from the TV's approved cache. The TV is the only thing
 *    that knows those urls, and the TV is where this server runs - so it answers with them.
 *  - **Counts for imported playlists.** A playlist the parent imported has no child *nodes* on the
 *    server at all: its episodes are materialized on the TV from the approved cache. Counting the
 *    document would say "0 videos" for a 50-episode playlist. The TV's own tree knows the truth, so
 *    this endpoint reports what the child actually sees, using the same
 *    [CatalogThumbnails] resolver the TV renders with - not a second implementation of the rule.
 *
 * Both are **read-only**: nothing here writes a catalog, a version, an approval or a cache row, and
 * neither can be used to make anything playable. `GET /catalog/artwork` is a best-effort description
 * of the TV's *installed* catalog, which may lag the document the parent is editing; that is why it
 * reports its own version rather than pretending to describe the draft.
 *
 * `POST /catalog/refresh` is the one action: it asks the TV to fetch the catalog it has just been
 * sent and to re-resolve the approved sources, which is what turns "I added a playlist" into "it is
 * on the TV and its videos can play". It performs exactly the work the TV's own Refresh button does -
 * no new mechanism, and nothing that grants permission that the approved-source list did not already
 * grant.
 */
fun Route.catalogLibraryRoutes(
    sessionManager: SessionManager,
    catalog: CatalogRepository,
    database: CacheDatabase,
    /**
     * The work `POST /catalog/refresh` performs, injected so the route can be tested without the app's
     * services: it must resolve the approved sources and then sync the catalog, in that order.
     */
    refresh: suspend () -> RefreshOutcome = { RefreshOutcome.Failed("refresh is not wired up") },
) {
    get("/catalog/artwork") {
        if (!validateSession(sessionManager)) return@get

        val tree = catalog.getTree()
        val thumbnails = database.videoDao().observeThumbnailIndex().first()
        val representatives = CatalogThumbnails.representatives(tree)
        val byId = tree.associateBy { it.id }

        // Only the videos the library actually names: the approved cache can hold a whole channel's
        // worth of videos the parent has not curated, and the dashboard has nothing to draw for them.
        val named = tree
            .filter { it.nodeType == CatalogNodeType.VIDEO }
            .mapNotNull { it.youtubeVideoId }
            .toSet()

        val videos = thumbnails
            .filter { it.thumbnailUrl.isNotBlank() && it.videoId in named }
            .associate { it.videoId to it.thumbnailUrl }

        // Only sub-categories: a category is a title with no picture at all, so the dashboard is not
        // told about one (W6.1's rule, on the parent's screen as well as the child's).
        val containers = tree
            .filter { it.nodeType == CatalogNodeType.SUBCATEGORY }
            .associate { node ->
                val descendants = videoDescendants(node.id, byId)
                node.id to ContainerArtwork(
                    thumbnailUrl = representatives[node.id]
                        ?.let { videos[it] }
                        ?: node.youtubePlaylistId?.let { playlistId -> openingVideoOf(thumbnails, playlistId) }
                        ?: "",
                    videoCount = descendants.count { it.enabled },
                    hiddenVideoCount = descendants.count { !it.enabled },
                )
            }

        call.respond(
            CatalogArtworkResponse(
                installedCatalogVersion = catalog.getMetadata()?.catalogVersion ?: 0,
                videos = videos,
                containers = containers,
            )
        )
    }

    post("/catalog/refresh") {
        if (!validateSession(sessionManager)) return@post
        when (val outcome = refresh()) {
            is RefreshOutcome.Refreshed -> call.respond(
                CatalogRefreshResponse(
                    status = "refreshed",
                    installedCatalogVersion = catalog.getMetadata()?.catalogVersion ?: 0,
                    sourcesRefreshed = outcome.sourcesRefreshed,
                    message = "Your TV has the latest version of your library.",
                )
            )

            is RefreshOutcome.NotConnected -> call.respond(
                HttpStatusCode.BadGateway,
                CatalogRefreshResponse(
                    status = "unavailable",
                    message = "The TV could not check for a newer library just now. It will try again on its own.",
                )
            )

            is RefreshOutcome.Failed -> {
                AppLogger.warn("Catalog refresh requested from the dashboard failed: ${outcome.reason}")
                call.respond(
                    HttpStatusCode.BadGateway,
                    CatalogRefreshResponse(status = "failed", message = outcome.reason),
                )
            }
        }
    }
}

/** The result of asking the TV to catch up, in the three shapes the dashboard has to tell apart. */
sealed interface RefreshOutcome {
    /** The TV looked and is now on [installedCatalogVersion] having refreshed [sourcesRefreshed]. */
    data class Refreshed(val installedCatalogVersion: Long, val sourcesRefreshed: Int) : RefreshOutcome

    /** The TV could not reach its own catalog service (or has none): nothing is wrong with the edit. */
    data object NotConnected : RefreshOutcome

    /** The refresh was attempted and refused for a reason worth showing. */
    data class Failed(val reason: String) : RefreshOutcome
}

/** Every video below [parentId], however deep, in catalog order. Videos only - a sub holds videos. */
private fun videoDescendants(
    parentId: String,
    byId: Map<String, CatalogNodeEntity>,
): List<CatalogNodeEntity> {
    val found = mutableListOf<tv.safetubeforkids.app.data.catalog.CatalogNodeEntity>()
    val visited = mutableSetOf<String>()

    fun walk(id: String) {
        byId.values
            .filter { it.parentId == id }
            .sortedWith(compareBy({ it.position }, { it.id }))
            .forEach { child ->
                if (!visited.add(child.id)) return@forEach
                if (child.nodeType == CatalogNodeType.VIDEO) {
                    found += child
                } else {
                    walk(child.id)
                }
            }
    }

    walk(parentId)
    return found
}

/** The opening approved video of a playlist, used only when the tree has nothing to stand for it. */
private fun openingVideoOf(
    thumbnails: List<VideoThumbnailRow>,
    playlistId: String,
): String? = thumbnails.firstOrNull { it.playlistId == playlistId && it.thumbnailUrl.isNotBlank() }?.thumbnailUrl
