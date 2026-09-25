package tv.safetubeforkids.app.server

import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.ContentSourceRepository
import tv.safetubeforkids.app.data.ResolvedSource
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.util.AppLogger
import tv.safetubeforkids.app.util.ContentSourceParser
import tv.safetubeforkids.app.util.ParseResult
import tv.safetubeforkids.app.util.SourceType
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ResolvePlaylistRequest(val url: String? = null)

/** One video a playlist listed, in the shape the editor needs to build catalog nodes from. */
@Serializable
data class ResolvedVideoDto(
    val videoId: String,
    val title: String,
    val durationSeconds: Long = 0L,
)

@Serializable
data class ResolvedPlaylistResponse(
    val sourceType: String,
    val sourceId: String,
    val title: String,
    val videos: List<ResolvedVideoDto>,
    /** The playlist had more videos than one import takes; the editor has to say so. */
    val truncated: Boolean = false,
    /** Items the playlist listed that cannot become catalog nodes (unavailable/deleted). */
    val unusableItems: Int = 0,
    /**
     * Whether this playlist is already an approved Content Source.
     *
     * Reported, never changed: resolving a playlist for the catalog must not approve anything. An
     * imported video that is not in the approved cache becomes a visible catalog entry that cannot
     * play until the parent adds the source, which is the security model working, and the editor says
     * so instead of leaving the parent to wonder.
     */
    val approved: Boolean = false,
)

/**
 * Resolving a YouTube playlist so the editor can import it as catalog nodes.
 *
 * **This route never writes anything.** It is not a catalog mutation endpoint - the catalog still
 * changes through exactly one path, `PUT /catalog` with the version the editor read (W2/W3) - and it
 * is deliberately not the `/playlists` endpoint either, which *approves* a source by adding it to the
 * approved cache. Importing a playlist into the catalog is curation; it must not create authorization
 * records, and a video's presence in the catalog must never become a reason it can play.
 *
 * So: parse the URL with the project's one parser, resolve it with the project's one resolver (NewPipe,
 * every page, unusable items skipped), and answer with the videos. The browser then computes the new
 * catalog and publishes it as one document - which means a failed or abandoned import leaves the
 * catalog, and its version, exactly as they were.
 */
fun Route.catalogImportRoutes(
    sessionManager: SessionManager,
    database: CacheDatabase,
    resolve: suspend (String, Int) -> ResolvedSource = { playlistId, limit ->
        ContentSourceRepository.resolvePlaylistForImport(playlistId, limit)
    },
    importLimit: Int = tv.safetubeforkids.app.data.MAX_VIDEOS_PER_IMPORT,
) {
    post("/catalog/import/resolve") {
        if (!validateSession(sessionManager)) return@post

        val body = try {
            call.receive<ResolvePlaylistRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unreadable request body"))
            return@post
        }

        val url = body.url
        if (url.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Paste a YouTube playlist URL or id"))
            return@post
        }

        // The same parser the "add source" form and the catalog validator use, so a URL refused here
        // is one the rest of the app would refuse too - with the same words.
        val parsed = ContentSourceParser.parse(url)
        if (parsed is ParseResult.Rejected) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to parsed.message))
            return@post
        }
        val source = (parsed as ParseResult.Success).source
        if (source.type != SourceType.YT_PLAYLIST) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Only a YouTube playlist can be imported; that is a ${source.type.name.lowercase().removePrefix("yt_")}"),
            )
            return@post
        }

        val resolved = try {
            resolve(source.id, importLimit)
        } catch (e: Exception) {
            // Nothing was resolved, and nothing was written: the editor keeps whatever the parent was
            // working on and shows the reason.
            AppLogger.error("Playlist ${source.id} could not be resolved: ${e.message}")
            call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to (e.message ?: "The playlist could not be resolved")),
            )
            return@post
        }

        call.respond(
            ResolvedPlaylistResponse(
                sourceType = "yt_playlist",
                sourceId = source.id,
                title = resolved.title,
                videos = resolved.videos.map {
                    ResolvedVideoDto(videoId = it.videoId, title = it.title, durationSeconds = it.durationSeconds)
                },
                truncated = resolved.truncated,
                unusableItems = resolved.unusableItems,
                approved = database.channelDao().getBySourceId(source.id) != null,
            )
        )
    }
}
