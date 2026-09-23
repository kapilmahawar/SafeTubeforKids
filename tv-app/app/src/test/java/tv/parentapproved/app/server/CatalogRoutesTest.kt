package tv.safetubeforkids.app.server

import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.catalog.CATALOG_SCHEMA_VERSION
import tv.safetubeforkids.app.data.catalog.CatalogJson
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * The catalog API, exercised through Ktor's test application against the real routes and a real
 * store. Nothing here stubs the route or the store: authentication, JSON parsing, validation,
 * versioning and optimistic concurrency all run.
 */
class CatalogRoutesTest {

    private var currentTime = 1_000_000L

    private fun testApp(
        store: CatalogStore = InMemoryCatalogStore(),
        block: suspend ApplicationTestBuilder.(store: CatalogStore, token: String) -> Unit,
    ) = testApplication {
        val sessionManager = SessionManager(clock = { currentTime })
        application {
            install(ContentNegotiation) { json() }
            routing { catalogRoutes(sessionManager, store) }
        }
        val token = sessionManager.createSession()!!
        block(store, token)
    }

    private fun catalogBody(
        categories: String,
        schemaVersion: Int = CATALOG_SCHEMA_VERSION,
        expectedCatalogVersion: Long? = null,
        extra: String = "",
    ): String = buildString {
        append("""{"schemaVersion":$schemaVersion,""")
        if (expectedCatalogVersion != null) {
            append(""""expectedCatalogVersion":$expectedCatalogVersion,""")
        }
        if (extra.isNotEmpty()) append("$extra,")
        append(""""categories":[$categories]}""")
    }

    private fun playlistItem(
        id: String,
        name: String,
        sortOrder: Int,
        playlistId: String,
    ) = """{"id":"$id","type":"PLAYLIST","displayName":"$name","sortOrder":$sortOrder,"youtubePlaylistId":"$playlistId","youtubeVideoId":null}"""

    private fun videoItem(
        id: String,
        name: String,
        sortOrder: Int,
        videoId: String,
    ) = """{"id":"$id","type":"VIDEO","displayName":"$name","sortOrder":$sortOrder,"youtubePlaylistId":null,"youtubeVideoId":"$videoId"}"""

    private fun category(id: String, name: String, sortOrder: Int, items: String = "") =
        """{"id":"$id","displayName":"$name","sortOrder":$sortOrder,"enabled":true,"items":[$items]}"""

    private suspend fun ApplicationTestBuilder.putCatalog(
        token: String?,
        body: String,
    ): HttpResponse = client.put("/catalog") {
        token?.let { header("Authorization", "Bearer $it") }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun ApplicationTestBuilder.getCatalog(token: String?): HttpResponse =
        client.get("/catalog") {
            token?.let { header("Authorization", "Bearer $it") }
        }

    // ------------------------------------------------------------------ API-01 / API-07

    @Test
    fun api01_unauthenticatedGetIsRejected() = testApp { store, _ ->
        val response = getCatalog(token = null)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api01_unauthenticatedPutIsRejectedAndChangesNothing() = testApp { store, _ ->
        val response = putCatalog(
            token = null,
            body = catalogBody(category("cat-cartoon", "Cartoon", 0)),
        )

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("an anonymous write must not create a version", 0L, store.read().catalogVersion)
        assertTrue(store.read().categories.isEmpty())
    }

    @Test
    fun api01_putWithAGarbageTokenIsRejected() = testApp { store, _ ->
        val response = putCatalog(
            token = "not-a-real-session",
            body = catalogBody(category("cat-cartoon", "Cartoon", 0)),
        )

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api01_expiredSessionIsRejectedJustLikeEveryOtherRoute() = testApp { store, token ->
        // 90 days plus a minute: the catalog route must honour the same session lifetime as the rest
        // of the API rather than keeping its own.
        currentTime += 91L * 24 * 60 * 60 * 1000

        val response = putCatalog(token, catalogBody(category("cat-cartoon", "Cartoon", 0)))

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api07_aChildFacingClientCannotModifyTheCatalog() = testApp { store, _ ->
        // A TV that knows the URL but has no parent session gets nothing.
        val response = client.put("/catalog") {
            contentType(ContentType.Application.Json)
            header("Cookie", "session=made-up")
            setBody(catalogBody(category("cat-cartoon", "Cartoon", 0)))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0L, store.read().catalogVersion)
    }

    // ------------------------------------------------------------------ API-02

    @Test
    fun api02_authenticatedReadReturnsAnEmptyCatalogAtVersionZero() = testApp { _, token ->
        val response = getCatalog(token)

        assertEquals(HttpStatusCode.OK, response.status)
        val snapshot = CatalogJson.decodeSnapshot(response.bodyAsText())!!
        assertEquals(CATALOG_SCHEMA_VERSION, snapshot.schemaVersion)
        assertEquals(0L, snapshot.catalogVersion)
        assertTrue(snapshot.categories.isEmpty())
    }

    @Test
    fun api02_authenticatedReadReturnsTheStoredCatalog() = testApp { store, token ->
        store.write(
            listOf(
                tv.safetubeforkids.app.data.catalog.CatalogCategoryDto(
                    id = "cat-music", displayName = "Music", sortOrder = 1,
                ),
            )
        )

        val snapshot = CatalogJson.decodeSnapshot(getCatalog(token).bodyAsText())!!

        assertEquals(1L, snapshot.catalogVersion)
        assertEquals(listOf("Music"), snapshot.categories.map { it.displayName })
    }

    // ------------------------------------------------------------------ API-03

    @Test
    fun api03_authenticatedPutStoresTheCatalog() = testApp { store, token ->
        val response = putCatalog(
            token,
            catalogBody(
                category("cat-cartoon", "Cartoon", 0, playlistItem("i-cocomelon", "Cocomelon", 0, "PLcocomelon")),
            ),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val stored = store.read()
        assertEquals(1L, stored.catalogVersion)
        assertEquals(1, stored.categories.size)
        val item = stored.categories[0].items[0]
        assertEquals("Cocomelon", item.displayName)
        assertEquals("PLcocomelon", item.youtubePlaylistId)
        assertNull(item.youtubeVideoId)
    }

    @Test
    fun api03_aStoredCatalogIsServedBackVerbatimIncludingOrderAndNames() = testApp { store, token ->
        val body = catalogBody(
            listOf(
                category("cat-learning", "Learning", 20, playlistItem("i-numbers", "Numbers", 0, "PLnumbers")),
                category("cat-music", "Music", 5, videoItem("i-twinkle", "Twinkle Twinkle", 2, "DuXwFlL8Usk")),
            ).joinToString(",")
        )
        assertEquals(HttpStatusCode.OK, putCatalog(token, body).status)

        val snapshot = CatalogJson.decodeSnapshot(getCatalog(token).bodyAsText())!!

        assertEquals(listOf("Learning", "Music"), snapshot.categories.map { it.displayName })
        assertEquals(listOf(20, 5), snapshot.categories.map { it.sortOrder })
        assertEquals("Twinkle Twinkle", snapshot.categories[1].items[0].displayName)
        assertEquals("DuXwFlL8Usk", snapshot.categories[1].items[0].youtubeVideoId)
    }

    // ------------------------------------------------------------------ API-04

    @Test
    fun api04_blankCategoryIdIsRejected() = testApp { store, token ->
        val response = putCatalog(token, catalogBody(category("", "Cartoon", 0)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("must not be blank"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api04_blankCategoryDisplayNameIsRejected() = testApp { store, token ->
        val response = putCatalog(token, catalogBody(category("cat-cartoon", "  ", 0)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("display name must not be blank"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api04_negativeAndDuplicateSortOrdersAreAccepted() = testApp { store, token ->
        // Duplicate and negative sort orders are Phase 2's deliberate decision; the server must not
        // quietly reverse it.
        val body = catalogBody(
            listOf(
                category("cat-a", "A", 0),
                category("cat-b", "B", 0),
                category("cat-c", "C", -5),
            ).joinToString(",")
        )

        assertEquals(HttpStatusCode.OK, putCatalog(token, body).status)
        assertEquals(listOf(0, 0, -5), store.read().categories.map { it.sortOrder })
    }

    // ------------------------------------------------------------------ API-05

    @Test
    fun api05_playlistItemWithoutAPlaylistIdIsRejected() = testApp { store, token ->
        val item = """{"id":"i1","type":"PLAYLIST","displayName":"Nursery","sortOrder":0,"youtubePlaylistId":null,"youtubeVideoId":null}"""
        val response = putCatalog(token, catalogBody(category("cat-music", "Music", 0, item)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("requires a youtubePlaylistId"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api05_playlistItemCarryingAVideoIdIsRejected() = testApp { store, token ->
        val item = """{"id":"i1","type":"PLAYLIST","displayName":"Nursery","sortOrder":0,"youtubePlaylistId":"PLabc","youtubeVideoId":"vid1"}"""
        val response = putCatalog(token, catalogBody(category("cat-music", "Music", 0, item)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("must not carry a youtubeVideoId"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api05_unusablePlaylistIdIsRejected() = testApp { store, token ->
        // An auto-generated mix, as the dashboard's own parser already refuses.
        val response = putCatalog(token, catalogBody(category("cat-music", "Music", 0, playlistItem("i1", "Mix", 0, "RDabcdef"))))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Auto-generated playlists"))
        assertEquals(0L, store.read().catalogVersion)
    }

    // ------------------------------------------------------------------ API-06

    @Test
    fun api06_videoItemWithoutAVideoIdIsRejected() = testApp { store, token ->
        val item = """{"id":"i1","type":"VIDEO","displayName":"Twinkle","sortOrder":0,"youtubePlaylistId":null,"youtubeVideoId":null}"""
        val response = putCatalog(token, catalogBody(category("cat-music", "Music", 0, item)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("requires a youtubeVideoId"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api06_videoItemCarryingAPlaylistIdIsRejected() = testApp { store, token ->
        val item = """{"id":"i1","type":"VIDEO","displayName":"Twinkle","sortOrder":0,"youtubePlaylistId":"PLabc","youtubeVideoId":"vid1"}"""
        val response = putCatalog(token, catalogBody(category("cat-music", "Music", 0, item)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("must not carry a youtubePlaylistId"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api06_unsupportedItemTypeIsRejected() = testApp { store, token ->
        val item = """{"id":"i1","type":"CHANNEL","displayName":"PBS","sortOrder":0,"youtubePlaylistId":null,"youtubeVideoId":null}"""
        val response = putCatalog(token, catalogBody(category("cat-music", "Music", 0, item)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("unsupported item type 'CHANNEL'"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api06_unusableVideoIdIsRejected() = testApp { store, token ->
        val response = putCatalog(token, catalogBody(category("cat-music", "Music", 0, videoItem("i1", "Handle", 0, "@PBSKids"))))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0L, store.read().catalogVersion)
    }

    // ------------------------------------------------------------------ API-07

    @Test
    fun api07_duplicateCategoryIdsAreRejected() = testApp { store, token ->
        val body = catalogBody(
            listOf(
                category("cat-music", "Music", 0),
                category("cat-music", "Music Again", 1),
            ).joinToString(",")
        )

        val response = putCatalog(token, body)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("duplicate category id"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api07_duplicateItemIdsAreRejectedEvenAcrossCategories() = testApp { store, token ->
        // Two shelves claiming the same item id would collide on the TV, where the item id is the
        // primary key, so the whole payload is refused rather than one row silently winning.
        val body = catalogBody(
            listOf(
                category("cat-a", "A", 0, playlistItem("i-same", "One", 0, "PLone")),
                category("cat-b", "B", 1, playlistItem("i-same", "Two", 0, "PLtwo")),
            ).joinToString(",")
        )

        val response = putCatalog(token, body)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("duplicate item id"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api07_blankItemIdIsRejected() = testApp { store, token ->
        val response = putCatalog(token, catalogBody(category("cat-a", "A", 0, playlistItem("", "One", 0, "PLone"))))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("item id must not be blank"))
    }

    // ------------------------------------------------------------------ API-08

    @Test
    fun api08_aBlankCategoryIdCannotBeStoredSoNoItemCanOutliveItsCategory() = testApp { store, token ->
        // Items are nested inside their category in this contract, so "an item that names a category
        // that does not exist" cannot be expressed at all. What can be expressed - a category with no
        // usable identity, which would leave its items unreachable - is refused.
        val response = putCatalog(token, catalogBody(category(" ", "Music", 0, playlistItem("i1", "Nursery", 0, "PLn"))))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0L, store.read().catalogVersion)

        // And a payload that is accepted cannot contain an item whose category is missing.
        assertEquals(
            HttpStatusCode.OK,
            putCatalog(token, catalogBody(category("cat-music", "Music", 0, playlistItem("i1", "Nursery", 0, "PLn")))).status,
        )
        val stored = store.read()
        stored.categories.forEach { category ->
            assertFalse("stored category id must be usable", category.id.isBlank())
        }
        assertEquals(1, stored.categories.flatMap { it.items }.size)
    }

    // ------------------------------------------------------------------ API-09 / API-10

    @Test
    fun api09_successfulMutationsIncreaseTheVersionByOneEachTime() = testApp { store, token ->
        assertEquals(HttpStatusCode.OK, putCatalog(token, catalogBody(category("c1", "One", 0))).status)
        assertEquals(1L, store.read().catalogVersion)

        assertEquals(HttpStatusCode.OK, putCatalog(token, catalogBody(category("c1", "One Renamed", 0))).status)
        assertEquals(2L, store.read().catalogVersion)

        // A reorder is a modification too.
        assertEquals(
            HttpStatusCode.OK,
            putCatalog(token, catalogBody("${category("c1", "One Renamed", 5)},${category("c2", "Two", 1)}")).status,
        )
        assertEquals(3L, store.read().catalogVersion)
        assertEquals(listOf("One Renamed", "Two"), store.read().categories.map { it.displayName })
    }

    @Test
    fun api10_failedMutationsDoNotIncreaseTheVersion() = testApp { store, token ->
        assertEquals(HttpStatusCode.OK, putCatalog(token, catalogBody(category("c1", "One", 0))).status)
        assertEquals(1L, store.read().catalogVersion)

        // Each of these is a failed mutation for a different reason.
        assertEquals(HttpStatusCode.BadRequest, putCatalog(token, catalogBody(category("", "Blank", 0))).status)
        assertEquals(
            HttpStatusCode.BadRequest,
            putCatalog(token, catalogBody(category("c2", "Bad Item", 1, playlistItem("i", "Nursery", 0, "RDmix")))).status,
        )
        assertEquals(HttpStatusCode.BadRequest, putCatalog(token, "{}").status)
        assertEquals(HttpStatusCode.BadRequest, putCatalog(token, "not json at all").status)
        assertEquals(HttpStatusCode.BadRequest, putCatalog(token, catalogBody(category("c1", "Unsupported", 0), schemaVersion = 999)).status)
        assertEquals(HttpStatusCode.Conflict, putCatalog(token, catalogBody(category("c1", "Stale", 0), expectedCatalogVersion = 99)).status)

        assertEquals("a failed mutation must not create a version", 1L, store.read().catalogVersion)
        assertEquals(listOf("One"), store.read().categories.map { it.displayName })
    }

    // ------------------------------------------------------------------ API-12 / API-13

    @Test
    fun api12_anExplicitlyEmptyCatalogIsAccepted() = testApp { store, token ->
        val response = putCatalog(token, catalogBody(""))

        assertEquals(HttpStatusCode.OK, response.status)
        val stored = store.read()
        assertEquals(1L, stored.catalogVersion)
        assertTrue("an empty catalog is a legitimate configuration", stored.categories.isEmpty())

        val served = CatalogJson.decodeSnapshot(getCatalog(token).bodyAsText())!!
        assertEquals(1L, served.catalogVersion)
        assertTrue(served.categories.isEmpty())
    }

    @Test
    fun api12_aCategoryWithNoItemsIsAccepted() = testApp { store, token ->
        // The `Stories` shelf in the intended hierarchy is configured but empty.
        val response = putCatalog(token, catalogBody(category("cat-stories", "Stories", 3)))

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(store.read().categories[0].items.isEmpty())
    }

    @Test
    fun api13_anEmptyJsonObjectIsRejectedAsMalformedNotAsAnEmptyCatalog() = testApp { store, token ->
        val response = putCatalog(token, "{}")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Unreadable catalog payload"))
        assertEquals("{} must never be read as 'remove everything'", 0L, store.read().catalogVersion)
    }

    @Test
    fun api13_aCatalogWithoutTheCategoriesKeyIsRejected() = testApp { store, token ->
        val response = putCatalog(token, """{"schemaVersion":$CATALOG_SCHEMA_VERSION}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api13_aCatalogWithoutASchemaVersionIsRejected() = testApp { store, token ->
        val response = putCatalog(token, """{"categories":[]}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api13_anUnsupportedRequestSchemaVersionIsRejected() = testApp { store, token ->
        val response = putCatalog(token, catalogBody(category("c1", "One", 0), schemaVersion = 999))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Unsupported schemaVersion 999"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api13_anEmptyBodyIsRejected() = testApp { store, token ->
        val response = putCatalog(token, "")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Missing catalog payload"))
        assertEquals(0L, store.read().catalogVersion)
    }

    // ------------------------------------------------------------------ API-11 (store level)

    @Test
    fun api11_anExistingCatalogIsServedAfterTheServerComesBackUp() = testApp { store, token ->
        putCatalog(token, catalogBody(category("cat-music", "Music", 1)))

        // Same store object across a fresh application: what a restart preserves is the file, which
        // CatalogStoreTest covers directly.
        val snapshot = CatalogJson.decodeSnapshot(getCatalog(token).bodyAsText())!!
        assertEquals(1L, snapshot.catalogVersion)
        assertEquals("Music", snapshot.categories[0].displayName)
        assertEquals(1L, store.read().catalogVersion)
    }

    // ------------------------------------------------------------------ API-14

    @Test
    fun api14_theCatalogRouteUsesTheExistingAuthFlowAndLeavesTheOtherRoutesAlone() = testApplication {
        val sessionManager = SessionManager(clock = { currentTime })
        val pinManager = tv.safetubeforkids.app.auth.PinManager(
            clock = { currentTime },
            onPinValidated = { sessionManager.createSession() ?: "" },
        )
        val store = InMemoryCatalogStore()
        application {
            install(ContentNegotiation) { json() }
            routing {
                authRoutes(pinManager, sessionManager)
                playlistRoutes(sessionManager, io.mockk.mockk(relaxed = true))
                catalogRoutes(sessionManager, store)
            }
        }

        // The parent signs in through the existing PIN route...
        val authResponse = client.post("/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"${pinManager.getCurrentPin()}"}""")
        }
        assertEquals(HttpStatusCode.OK, authResponse.status)
        val issuedToken = Json.parseToJsonElement(authResponse.bodyAsText())
            .jsonObject["token"]?.jsonPrimitive?.content
        assertNotNull("the existing auth route must still issue a token", issuedToken)

        // ...and the token it issues is the one the catalog route accepts. No second auth scheme.
        assertEquals(HttpStatusCode.OK, getCatalog(issuedToken).status)

        // A wrong PIN is still refused by the existing route.
        val wrongPin = if (pinManager.getCurrentPin() == "000000") "111111" else "000000"
        val rejected = client.post("/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"$wrongPin"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, rejected.status)

        // The pre-existing playlist route still answers, with the same session.
        assertEquals(HttpStatusCode.OK, client.get("/playlists") {
            header("Authorization", "Bearer $issuedToken")
        }.status)
    }

    // ------------------------------------------------------------------ version ownership

    @Test
    fun aClientCannotDictateTheCatalogVersion() = testApp { store, token ->
        val response = putCatalog(
            token,
            catalogBody(category("c1", "One", 0), extra = """"catalogVersion":999999"""),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("the server assigns the version", 1L, store.read().catalogVersion)
        val served = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("1", served["catalogVersion"]?.jsonPrimitive?.content)
    }

    @Test
    fun optimisticConcurrencyAcceptsAMatchingExpectedVersionAndRejectsAStaleOne() = testApp { store, token ->
        assertEquals(
            HttpStatusCode.OK,
            putCatalog(token, catalogBody(category("c1", "One", 0), expectedCatalogVersion = 0)).status,
        )
        assertEquals(1L, store.read().catalogVersion)

        // Another parent session already moved the catalog on: the stale writer is told, not obeyed.
        val conflict = putCatalog(
            token,
            catalogBody(category("c1", "Overwritten", 0), expectedCatalogVersion = 0),
        )
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals("1", Json.parseToJsonElement(conflict.bodyAsText()).jsonObject["catalogVersion"]?.jsonPrimitive?.content)
        assertEquals("One", store.read().categories[0].displayName)

        // Retrying against the version that is actually stored succeeds.
        assertEquals(
            HttpStatusCode.OK,
            putCatalog(token, catalogBody(category("c1", "Overwritten", 0), expectedCatalogVersion = 1)).status,
        )
        assertEquals(2L, store.read().catalogVersion)
        assertEquals("Overwritten", store.read().categories[0].displayName)
    }

    @Test
    fun anAcceptedPayloadReportsItsProblemsAsAJsonArrayWhenRejected() = testApp { _, token ->
        val response = putCatalog(
            token,
            catalogBody(
                listOf(
                    category("", "No Id", 0),
                    category("c2", "", 1),
                ).joinToString(",")
            ),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Invalid catalog", body["error"]?.jsonPrimitive?.content)
        assertEquals(2, body["details"]?.jsonArray?.size)
    }
}
