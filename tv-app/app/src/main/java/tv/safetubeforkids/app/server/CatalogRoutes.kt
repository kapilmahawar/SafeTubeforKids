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
 * The parent-facing catalog API, speaking contract version 2 - the node tree.
 *
 * Two endpoints, both behind the existing parent authentication - a session token as
 * `Authorization: Bearer …` or the `session` cookie, exactly like `/playlists` and
 * `/sources/export`. No second authentication scheme was introduced, and a child-facing TV cannot
 * write here: the TV's own sync client is read-only and this route rejects it without a token just
 * as it rejects a browser without one.
 *
 * `GET` returns the whole catalog in one response on purpose. The TV replaces its local catalog from
 * a single coherent snapshot, so it never has to assemble a catalog from a series of requests that
 * could straddle a parent's edit - and the dashboard renders exactly the document the TV would
 * receive.
 *
 * `PUT` replaces the whole catalog. That single endpoint is enough for everything the future
 * dashboard editor needs - create, rename, delete and reorder any node - without an endpoint per
 * operation, and it is the one write path whose transactionality and concurrency can be reasoned
 * about in one place.
 *
 * ### The order of operations, which is the point
 *
 * ```
 * read the body -> parse -> check the schema version -> validate the WHOLE tree
 *   -> check expectedCatalogVersion -> persist catalog + new version -> report success
 * ```
 *
 * Nothing is written before the whole document has been accepted, so "invalid" and "partially
 * applied" cannot happen together. The version check happens before any write, so a stale request
 * makes **zero** catalog changes rather than changes that are rolled back. And success is only
 * reported after the store has returned, so a persistence failure is a 500 with the previous catalog
 * and version still in place - never a 200 for something that is not on disk.
 *
 * ### Version handling
 * The server assigns the version. `PUT /catalog` has no `catalogVersion` field at all, so a client
 * cannot propose one, and an unknown `catalogVersion` key in the body is ignored.
 * `expectedCatalogVersion` is the opposite thing - a precondition - and never becomes the stored
 * version. A stored version is only ever produced by a successful write, so a rejected payload, a
 * stale request and a failed write all leave it exactly where it was.
 *
 * ### Status codes
 * `200` stored (with the new snapshot, including its version), `400` unreadable payload, unsupported
 * schema version or invalid tree, `401` no valid session, `409` `expectedCatalogVersion` did not
 * match the stored version (with that version in the body, so the caller can `GET /catalog` for the
 * current document), `500` the catalog could not be persisted.
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

        // A body that declares a version this server does not speak is refused as a *version* problem
        // before anything else is read. A version-1 document has no `nodes` at all, so decoding it
        // first would fail on a missing field and the parent would be told its payload was unreadable
        // instead of being told to update. It is still never interpreted as version 2.
        val declared = CatalogJson.declaredSchemaVersion(body)
        if (declared != null && declared != CATALOG_SCHEMA_VERSION) {
            call.respond(
                HttpStatusCode.BadRequest,
                CatalogErrorResponse(
                    error = "Unsupported schemaVersion $declared; this server speaks $CATALOG_SCHEMA_VERSION",
                ),
            )
            return@put
        }

        // A missing schemaVersion or nodes fails to parse. That is deliberate: `{}` must be a
        // malformed payload rather than an instruction to empty the catalog.
        val request = CatalogJson.decodePutRequest(body)
        if (request == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                CatalogErrorResponse(
                    error = "Unreadable catalog payload; schemaVersion and nodes are required",
                ),
            )
            return@put
        }

        // Checked again on what was actually decoded, so the store can never be handed a document from
        // a contract this build does not speak.
        if (request.schemaVersion != CATALOG_SCHEMA_VERSION) {
            call.respond(
                HttpStatusCode.BadRequest,
                CatalogErrorResponse(
                    error = "Unsupported schemaVersion ${request.schemaVersion}; this server speaks $CATALOG_SCHEMA_VERSION",
                ),
            )
            return@put
        }

        when (val outcome = CatalogPayloadValidator.validate(request.nodes)) {
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
                val stored = try {
                    store.write(outcome.nodes, request.expectedCatalogVersion)
                } catch (e: Exception) {
                    // Nothing was persisted: the document on disk, and therefore the version, are
                    // still the previous ones. Reporting success here would be a lie the parent acts
                    // on, so this is a 500 and the catalog stays as it was.
                    AppLogger.error("Catalog could not be persisted: ${e.message}")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        CatalogErrorResponse(error = "Catalog could not be persisted"),
                    )
                    return@put
                }

                when (stored) {
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
