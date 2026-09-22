package tv.safetubeforkids.app.server

import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.ChannelEntity
import tv.safetubeforkids.app.data.ExportedSource
import tv.safetubeforkids.app.data.ImportFailure
import tv.safetubeforkids.app.data.ImportSummary
import tv.safetubeforkids.app.data.SourceTransfer
import tv.safetubeforkids.app.util.ContentSourceParser
import tv.safetubeforkids.app.util.ParseResult
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class AddPlaylistRequest(val url: String? = null)

@Serializable
data class PlaylistResponse(
    val id: Long,
    val sourceType: String,
    val sourceId: String,
    val sourceUrl: String,
    val displayName: String,
    val videoCount: Int,
    val addedAt: Long,
    val status: String,
)

private const val MAX_SOURCES = 20

fun Route.playlistRoutes(sessionManager: SessionManager, database: CacheDatabase) {
    get("/playlists") {
        if (!validateSession(sessionManager)) return@get
        val channels = database.channelDao().getAll()
        call.respond(channels.map { it.toResponse() })
    }

    post("/playlists") {
        if (!validateSession(sessionManager)) return@post

        val body = try {
            call.receive<AddPlaylistRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
            return@post
        }

        val url = body.url
        if (url.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "URL is required"))
            return@post
        }

        val parseResult = ContentSourceParser.parse(url)
        if (parseResult is ParseResult.Rejected) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to parseResult.message,
                "cta" to "Try pasting a YouTube video, playlist, or channel URL",
            ))
            return@post
        }

        val source = (parseResult as ParseResult.Success).source
        val dao = database.channelDao()

        // Check duplicate
        if (dao.getBySourceId(source.id) != null) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "This source is already added"))
            return@post
        }

        // Check max
        if (dao.count() >= MAX_SOURCES) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Maximum of $MAX_SOURCES sources reached"))
            return@post
        }

        val entity = ChannelEntity(
            sourceType = source.type.name.lowercase(),
            sourceId = source.id,
            sourceUrl = source.canonicalUrl,
            displayName = source.id, // Will be updated on first resolve
        )
        val id = dao.insert(entity)
        val saved = entity.copy(id = id)
        call.respond(HttpStatusCode.Created, saved.toResponse())
    }

    delete("/playlists/{id}") {
        if (!validateSession(sessionManager)) return@delete

        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid source ID"))
            return@delete
        }

        val dao = database.channelDao()
        val existing = dao.getAll().find { it.id == id }
        if (existing == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Source not found"))
            return@delete
        }

        dao.deleteById(id)
        database.videoDao().deleteByPlaylist(existing.sourceId)
        call.respond(HttpStatusCode.OK, mapOf("success" to true))
    }
}

/**
 * Export the approved library as JSON, and import one back.
 *
 * Import is deliberately additive and never destructive: existing sources are reported as
 * skipped rather than replaced, and every refusal comes back with a reason.
 */
fun Route.sourceTransferRoutes(sessionManager: SessionManager, database: CacheDatabase) {
    get("/sources/export") {
        if (!validateSession(sessionManager)) return@get
        val sources = database.channelDao().getAll().map { entity ->
            ExportedSource(
                sourceType = entity.sourceType,
                sourceId = entity.sourceId,
                sourceUrl = entity.sourceUrl,
                displayName = entity.displayName,
                videoCount = entity.videoCount,
            )
        }
        call.respondText(
            text = SourceTransfer.export(sources, System.currentTimeMillis()),
            contentType = ContentType.Application.Json,
        )
    }

    post("/sources/import") {
        if (!validateSession(sessionManager)) return@post

        val payload = try {
            call.receiveText()
        } catch (e: Exception) {
            ""
        }

        val parsed = SourceTransfer.parse(payload)
            ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Unreadable export payload"),
            )

        val (candidates, failures) = SourceTransfer.validate(SourceTransfer.distinctBySourceId(parsed))
        val dao = database.channelDao()
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = failures.toMutableList()

        for (source in candidates) {
            if (dao.count() >= MAX_SOURCES) {
                failed += ImportFailure(source.sourceId, "source limit of $MAX_SOURCES reached")
                continue
            }
            if (dao.getBySourceId(source.sourceId) != null) {
                skipped += source.sourceId
                continue
            }
            // Re-validate the URL through the same parser the dashboard uses, so an edited or
            // hand-written export cannot introduce something the app would not otherwise accept.
            when (ContentSourceParser.parse(source.sourceUrl)) {
                is ParseResult.Rejected -> failed += ImportFailure(
                    source.sourceId,
                    "URL not accepted: ${source.sourceUrl}",
                )
                is ParseResult.Success -> {
                    dao.insert(
                        ChannelEntity(
                            sourceType = source.sourceType,
                            sourceId = source.sourceId,
                            sourceUrl = source.sourceUrl,
                            displayName = source.displayName.ifBlank { source.sourceId },
                        )
                    )
                    added += source.sourceId
                }
            }
        }

        call.respond(
            ImportSummary(added = added, skipped = skipped, failed = failed),
        )
    }
}
private fun ChannelEntity.toResponse() = PlaylistResponse(
    id = id,
    sourceType = sourceType,
    sourceId = sourceId,
    sourceUrl = sourceUrl,
    displayName = displayName,
    videoCount = videoCount,
    addedAt = addedAt,
    status = status,
)
