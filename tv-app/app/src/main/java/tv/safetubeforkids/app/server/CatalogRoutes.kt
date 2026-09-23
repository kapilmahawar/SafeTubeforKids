package tv.safetubeforkids.app.server

import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.catalog.CATALOG_SCHEMA_VERSION
import tv.safetubeforkids.app.data.catalog.CatalogJson
import tv.safetubeforkids.app.data.catalog.CatalogPayloadValidator
import tv.safetubeforkids.app.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/** The error body used by both catalog endpoints, in the shape the rest of the API already uses. */
@Serializable
data class CatalogErrorResponse(
    val error: String,
    val details: List<String> = emptyList(),
    /** Present on a 409 so the caller can retry against the version actually stored. */
    val catalogVersion: Long? = null,
)

/**
 * The parent-facing catalog API.
 *
 * Two endpoints, both behind the existing parent authentication - a session token as
 * `Authorization: Bearer …` or the `session` cookie, exactly like `/playlists` and
 * `/sources/export`. No second authentication scheme was introduced, and a child-facing TV cannot
 * write here: the TV's own sync client is read-only and this route rejects it without a token just
 * as it rejects a browser without one.
 *
 * `GET` returns the whole catalog in one response on purpose. The TV replaces its local catalog from
 * a single coherent snapshot, so it never has to assemble a catalog from a series of requests that
 * could straddle a parent's edit.
 *
 * `PUT` replaces the whole catalog. That single endpoint is enough for everything the future
 * dashboard needs - create, rename, delete and reorder a category; create, rename, delete and
 * reorder a playlist or video item - without adding an endpoint per operation.
 *
 * ### Version handling
 * The server assigns the version. `PUT /catalog` has no `catalogVersion` field at all, so a client
 * cannot propose one, and an unknown `catalogVersion` key in the body is ignored. A stored version
 * is only ever produced by a successful write, so a rejected payload cannot move it.
 *
 * ### Status codes
 * `200` stored (with the new snapshot), `400` unreadable or invalid payload, `401` no valid session,
 * `409` `expectedCatalogVersion` did not match the stored version.
 */
fun Route.catalogRoutes(sessionManager: SessionManager, store: CatalogStore) {

    get("/catalog") {
        if (!validateSession(sessionManager)) return@get
        val snapshot = store.read()
        call.respondText(CatalogJson.encode(snapshot), ContentType.Application.Json)
    }

    put("/catalog") {
        if (!validateSession(sessionManager)) return@put

        val body = try {
            call.receiveText()
        } catch (e: Exception) {
            ""
        }
        if (body.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                CatalogErrorResponse(error = "Missing catalog payload"),
            )
            return@put
        }

        // A missing schemaVersion or categories fails to parse. That is deliberate: `{}` must be a
        // malformed payload rather than an instruction to empty the catalog.
        val request = CatalogJson.decodePutRequest(body)
        if (request == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                CatalogErrorResponse(
                    error = "Unreadable catalog payload; schemaVersion and categories are required",
                ),
            )
            return@put
        }

        if (request.schemaVersion != CATALOG_SCHEMA_VERSION) {
            call.respond(
                HttpStatusCode.BadRequest,
                CatalogErrorResponse(
                    error = "Unsupported schemaVersion ${request.schemaVersion}; this server speaks $CATALOG_SCHEMA_VERSION",
                ),
            )
            return@put
        }

        when (val outcome = CatalogPayloadValidator.validate(request.categories)) {
            is CatalogPayloadValidator.Outcome.Invalid -> {
                val details = outcome.problems.map { it.toString() }
                AppLogger.warn("Catalog rejected: ${CatalogPayloadValidator.describe(outcome.problems)}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    CatalogErrorResponse(error = "Invalid catalog", details = details),
                )
                return@put
            }

            is CatalogPayloadValidator.Outcome.Valid -> {
                when (val stored = store.write(outcome.categories, request.expectedCatalogVersion)) {
                    is CatalogStoreResult.VersionConflict -> {
                        call.respond(
                            HttpStatusCode.Conflict,
                            CatalogErrorResponse(
                                error = "Catalog version conflict",
                                catalogVersion = stored.currentVersion,
                            ),
                        )
                    }

                    is CatalogStoreResult.Stored -> {
                        call.respondText(
                            CatalogJson.encode(stored.snapshot),
                            ContentType.Application.Json,
                        )
                    }
                }
            }
        }
    }
}
