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
data class ResolveLinkRequest(val url: String? = null)

/** One video a link resolved to, in the shape the editor needs to build catalog nodes from. */
@Serializable
data class ResolvedVideoDto(
    val videoId: String,
    val title: String,
    val durationSeconds: Long = 0L,
    /**
     * The picture YouTube itself gave for this video.
     *
     * Reported, never stored: the catalog model refuses a picture URL outright, and what a card
     * shows comes from the approved cache. It is here so the *preview* the parent confirms before
     * adding can look like the thing they pasted, not like a form.
     */
    val thumbnailUrl: String = "",
)

@Serializable
data class ResolvedLinkResponse(
    /** What this link turned out to be: `playlist` or `video`. The editor renders it accordingly. */
    val kind: String,
    val sourceType: String,
    val sourceId: String,
    val title: String,
    val videos: List<ResolvedVideoDto>,
    /** A picture for the whole link: a video's own picture, or a playlist's first usable one. */
    val thumbnailUrl: String = "",
    /** The playlist had more videos than one import takes; the editor has to say so. */
    val truncated: Boolean = false,
    /** Items the playlist listed that cannot become catalog nodes (unavailable/deleted). */
    val unusableItems: Int = 0,
    /**
     * Whether this link is already an approved Content Source.
     *
     * Reported, never changed: resolving a link for the catalog must not approve anything. An
     * imported video that is not in the approved cache becomes a visible catalog entry that cannot
     * play until the parent adds the source, which is the security model working, and the editor says
     * so instead of leaving the parent to wonder.
     */
    val approved: Boolean = false,
)

/**
 * Resolving a YouTube link - a playlist or a single video - so the editor can turn it into catalog
 * entries.
 *
 * **This route never writes anything.** It is not a catalog mutation endpoint - the catalog still
 * changes through exactly one path, `PUT /catalog` with the version the editor read (W2/W3) - and it
 * is deliberately not the `/playlists` endpoint either, which *approves* a source by adding it to the
 * approved cache. Putting something in the catalog is curation; it must not create authorization
 * records, and a video's presence in the catalog must never become a reason it can play.
 *
 * So: parse the link with the project's one parser, resolve it with the project's one resolver, and
 * answer with what it found. The browser then computes the new catalog and publishes it as one
 * document - which means a failed or abandoned add leaves the catalog, and its version, exactly as
 * they were.
 *
 * A **channel** link is refused here on purpose: a channel is a source, not a shelf, and approving a
 * channel is an authorization decision that belongs in the allowed-source list. The message says so
 * rather than leaving the parent staring at a form that will not accept their link.
 */
fun Route.catalogImportRoutes(
    sessionManager: SessionManager,
    database: CacheDatabase,
    resolve: suspend (String, Int) -> ResolvedSource = { playlistId, limit ->
        ContentSourceRepository.resolvePlaylistForImport(playlistId, limit)
    },
    resolveVideo: suspend (String) -> ResolvedSource = { videoId ->
        ContentSourceRepository.resolve("yt_video", videoId)
    },
    importLimit: Int = tv.safetubeforkids.app.data.MAX_VIDEOS_PER_IMPORT,
) {
    post("/catalog/import/resolve") {
        if (!validateSession(sessionManager)) return@post

        val body = try {
            call.receive<ResolveLinkRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unreadable request body"))
            return@post
        }

        val url = body.url
        if (url.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Paste a YouTube playlist or video link"))
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

        val kind: String
        val resolved: ResolvedSource
        try {
            when (source.type) {
                SourceType.YT_PLAYLIST -> {
                    kind = "playlist"
                    resolved = resolve(source.id, importLimit)
                }

                SourceType.YT_VIDEO -> {
                    kind = "video"
                    resolved = resolveVideo(source.id)
                }

                else -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to "A whole channel is not a shelf. Allow that channel in Settings, " +
                                "then add the videos or playlists from it that you want your child to see.",
                        ),
                    )
                    return@post
                }
            }
        } catch (e: Exception) {
            // Nothing was resolved, and nothing was written: the editor keeps whatever the parent was
            // working on and shows the reason.
            AppLogger.error("Link ${source.id} could not be resolved: ${e.message}")
            call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to (e.message ?: "That link could not be resolved")),
            )
            return@post
        }

        call.respond(
            ResolvedLinkResponse(
                kind = kind,
                sourceType = if (kind == "video") "yt_video" else "yt_playlist",
                sourceId = source.id,
                title = resolved.title.ifBlank { source.id },
                videos = resolved.videos.map {
                    ResolvedVideoDto(
                        videoId = it.videoId,
                        title = it.title,
                        durationSeconds = it.durationSeconds,
                        thumbnailUrl = it.thumbnailUrl,
                    )
                },
                thumbnailUrl = resolved.videos.firstOrNull { it.thumbnailUrl.isNotBlank() }?.thumbnailUrl ?: "",
                truncated = resolved.truncated,
                unusableItems = resolved.unusableItems,
                approved = database.channelDao().getBySourceId(source.id) != null,
            )
        )
    }
}
