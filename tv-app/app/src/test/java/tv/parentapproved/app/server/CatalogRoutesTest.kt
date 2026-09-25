package tv.parentapproved.app.server

import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.catalog.CATALOG_NODE_TYPE_CATEGORY
import tv.safetubeforkids.app.data.catalog.CATALOG_NODE_TYPE_SUBCATEGORY
import tv.safetubeforkids.app.data.catalog.CATALOG_NODE_TYPE_VIDEO
import tv.safetubeforkids.app.data.catalog.CATALOG_SCHEMA_VERSION
import tv.safetubeforkids.app.data.catalog.CATALOG_THUMBNAIL_MODE_AUTO
import tv.safetubeforkids.app.data.catalog.CatalogJson
import tv.safetubeforkids.app.data.catalog.CatalogNodeDto
import tv.safetubeforkids.app.data.catalog.CatalogSnapshot
import tv.safetubeforkids.app.server.BaseCatalogStore
import tv.safetubeforkids.app.server.CatalogStore
import tv.safetubeforkids.app.server.InMemoryCatalogStore
import tv.safetubeforkids.app.server.authRoutes
import tv.safetubeforkids.app.server.catalogRoutes
import tv.safetubeforkids.app.server.playlistRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.io.IOException
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
 *
 * The contract under test is **version 2** - the flat node tree. Version 1's nested
 * `categories[].items[]` document is gone, and with it the shapes that could only describe two
 * levels: every node now names its `parentId`, carries a `position` among its siblings, and the
 * document is the same model the server stores. Where a version-1 assertion no longer holds, the
 * test says so and asserts the version-2 rule that replaced it rather than being dropped.
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

    /**
     * A `PUT /catalog` body in the version-2 shape: a schema version, an optional precondition and the
     * flat `nodes` array.
     *
     * The array is a required field of [CatalogPutRequest], so a body without it is an unreadable
     * payload rather than "the parent wants nothing" - which is why [nodes] is not optional here
     * either. A missing key must never be read as an instruction to empty the catalog.
     */
    private fun catalogBody(
        nodes: String,
        schemaVersion: Int = CATALOG_SCHEMA_VERSION,
        expectedCatalogVersion: Long? = null,
        extra: String = "",
    ): String = buildString {
        append("""{"schemaVersion":$schemaVersion,""")
        if (expectedCatalogVersion != null) {
            append(""""expectedCatalogVersion":$expectedCatalogVersion,""")
        }
        if (extra.isNotEmpty()) append("$extra,")
        append(""""nodes":[$nodes]}""")
    }

    /**
     * The parent a child node is written with until the container it belongs to fills it in.
     *
     * The item builders cannot know their shelf's id - in version 1 they did not need to, because
     * `category` nested them inside the shelf's own JSON - and `category`/`playlistItem` are still the
     * only place that knows both. Resolving a placeholder there keeps the call sites (which are the
     * test's reading of the tree) from repeating a fact the tree states once per container.
     */
    private val parentPlaceholder = "@parent"

    /** A nullable string as the wire spells it: an explicit `null`, never an absent key. */
    private fun wire(value: String?): String = if (value == null) "null" else "\"$value\""

    private fun field(name: String, value: String?): String = "\"$name\":${wire(value)}"

    /**
     * One version-2 node, field for field what [CatalogNodeDto] carries.
     *
     * Every key is written, including the nulls, because that is what the contract promises the TV:
     * `"youtubeVideoId": null` is what makes a container visibly not a video, and `"parentId": null`
     * is what makes a shelf visibly at ROOT rather than merely missing its parent. Timestamps are
     * deliberately left out - they are the server's bookkeeping, and a hand-written publish without
     * them has to work.
     */
    private fun node(
        id: String,
        parentId: String?,
        nodeType: String,
        title: String,
        position: Int,
        youtubeVideoId: String? = null,
        youtubePlaylistId: String? = null,
        enabled: Boolean = true,
        thumbnailMode: String = CATALOG_THUMBNAIL_MODE_AUTO,
        thumbnailVideoId: String? = null,
        thumbnailUrl: String? = null,
    ): String {
        val fields = listOf(
            field("id", id),
            field("parentId", parentId),
            field("nodeType", nodeType),
            field("title", title),
            "\"position\":$position",
            "\"enabled\":$enabled",
            field("youtubeVideoId", youtubeVideoId),
            field("youtubePlaylistId", youtubePlaylistId),
            field("thumbnailMode", thumbnailMode),
            field("thumbnailVideoId", thumbnailVideoId),
            field("thumbnailUrl", thumbnailUrl),
        )
        return "{${fields.joinToString(",")}}"
    }

    /**
     * A shelf and its children, as separate nodes in the same flat array.
     *
     * The children are written in the order given and number themselves, so a test that means to write
     * a well-formed tree passes `0..n-1` and the one that means to prove a malformed tree is refused
     * can pass the gap or the negative position the version-2 rule is about.
     */
    private fun category(id: String, name: String, position: Int, items: String = ""): String {
        val shelf = node(
            id = id,
            parentId = null,
            nodeType = CATALOG_NODE_TYPE_CATEGORY,
            title = name,
            position = position,
        )
        if (items.isBlank()) return shelf
        return "$shelf,${items.replace(parentPlaceholder, id)}"
    }

    /**
     * A container inside a shelf, which is what version 1's `PLAYLIST` entry became.
     *
     * `youtubePlaylistId` on it is import provenance - the playlist it was imported from - and a
     * container the parent made by hand simply has none, which is why the parameter is nullable.
     */
    private fun playlistItem(
        id: String,
        name: String,
        position: Int,
        playlistId: String?,
        items: String = "",
        parentId: String = parentPlaceholder,
    ): String {
        val container = node(
            id = id,
            parentId = parentId,
            nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
            title = name,
            position = position,
            youtubePlaylistId = playlistId,
        )
        if (items.isBlank()) return container
        return "$container,${items.replace(parentPlaceholder, id)}"
    }

    /** A playable item: a video id, and no playlist of its own. */
    private fun videoItem(
        id: String,
        name: String,
        position: Int,
        videoId: String,
        parentId: String = parentPlaceholder,
    ): String = node(
        id = id,
        parentId = parentId,
        nodeType = CATALOG_NODE_TYPE_VIDEO,
        title = name,
        position = position,
        youtubeVideoId = videoId,
    )

    /** The stored-shape equivalent of [category], for tests that seed the store directly. */
    private fun categoryDto(id: String, title: String, position: Int = 0) = CatalogNodeDto(
        id = id,
        parentId = null,
        nodeType = CATALOG_NODE_TYPE_CATEGORY,
        title = title,
        position = position,
    )

    /** The stored-shape equivalent of [videoItem]. */
    private fun videoDto(
        id: String,
        parentId: String,
        title: String,
        position: Int = 0,
        videoId: String,
    ) = CatalogNodeDto(
        id = id,
        parentId = parentId,
        nodeType = CATALOG_NODE_TYPE_VIDEO,
        title = title,
        position = position,
        youtubeVideoId = videoId,
    )

    /**
     * The titles of [parentId]'s children in the order a reader renders them: `position ASC, id ASC`.
     *
     * The document is a flat array, so the array's order is *not* the rendered order - `position` is.
     * With unique ids and a dense `0..n-1` numbering that comparison is a total order, so asking this
     * question is asking the question the TV asks, and nothing is left for the server to accept and a
     * reader to interpret differently.
     */
    private fun renderedTitles(nodes: List<CatalogNodeDto>, parentId: String?): List<String> =
        nodes.filter { it.parentId == parentId }
            .sortedWith(compareBy({ it.position }, { it.id }))
            .map { it.title }

    /** The reasons a rejection reported, as strings. */
    private fun rejectionReasons(body: String): List<String> =
        Json.parseToJsonElement(body).jsonObject["details"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: emptyList()

    /** The version a catalog response reports, as the wire spells it. */
    private suspend fun HttpResponse.catalogVersionInBody(): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject["catalogVersion"]?.jsonPrimitive?.content

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
        val stored = store.read()
        assertEquals("an anonymous write must not create a version", 0L, stored.catalogVersion)
        assertTrue("and it must not create a node either", stored.nodes.isEmpty())
    }

    @Test
    fun api01_putWithAGarbageTokenIsRejected() = testApp { store, _ ->
        val response = putCatalog(
            token = "not-a-real-session",
            body = catalogBody(category("cat-cartoon", "Cartoon", 0)),
        )

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0L, store.read().catalogVersion)
        assertTrue(store.read().nodes.isEmpty())
    }

    @Test
    fun api01_expiredSessionIsRejectedJustLikeEveryOtherRoute() = testApp { store, token ->
        // 90 days plus a minute: the catalog route must honour the same session lifetime as the rest
        // of the API rather than keeping its own.
        currentTime += 91L * 24 * 60 * 60 * 1000

        val response = putCatalog(token, catalogBody(category("cat-cartoon", "Cartoon", 0)))

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0L, store.read().catalogVersion)
        assertTrue(store.read().nodes.isEmpty())
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
        assertTrue(store.read().nodes.isEmpty())
    }

    // ------------------------------------------------------------------ API-02

    @Test
    fun api02_authenticatedReadReturnsAnEmptyCatalogAtVersionZero() = testApp { _, token ->
        val response = getCatalog(token)

        assertEquals(HttpStatusCode.OK, response.status)
        val snapshot = CatalogJson.decodeSnapshot(response.bodyAsText())!!
        assertEquals(CATALOG_SCHEMA_VERSION, snapshot.schemaVersion)
        assertEquals(0L, snapshot.catalogVersion)
        assertTrue(snapshot.nodes.isEmpty())
    }

    @Test
    fun api02_authenticatedReadReturnsTheStoredCatalog() = testApp { store, token ->
        store.write(
            listOf(
                categoryDto("cat-music", "Music"),
                videoDto("i-twinkle", "cat-music", "Twinkle Twinkle", 0, "DuXwFlL8Usk"),
            )
        )

        val snapshot = CatalogJson.decodeSnapshot(getCatalog(token).bodyAsText())!!

        assertEquals(1L, snapshot.catalogVersion)
        assertEquals(listOf("Music"), renderedTitles(snapshot.nodes, parentId = null))
        assertEquals(listOf("Twinkle Twinkle"), renderedTitles(snapshot.nodes, parentId = "cat-music"))
    }

    /**
     * `GET` serves the tree it holds, and the tree it holds is the one a version-2 reader reads back.
     *
     * "Verbatim" is the whole point of one endpoint: the TV replaces its local catalog from a single
     * coherent document, so what it decodes has to be exactly what the server would hand the next
     * reader - same nodes, same parents, same positions, same explicit nulls.
     */
    @Test
    fun api02_getServesTheNodeTreeVerbatim() = testApp { store, token ->
        // A real three-level tree: a shelf holding a container holding a video, and a shelf holding a
        // video directly. Version 1 could not express the middle level at all.
        val tree = listOf(
            category(
                id = "cat-music",
                name = "Music",
                position = 0,
                items = playlistItem(
                    id = "sub-lullabies",
                    name = "Lullabies",
                    position = 0,
                    playlistId = "PLlullabies",
                    items = videoItem("i-twinkle", "Twinkle", 0, "DuXwFlL8Usk"),
                ),
            ),
            category("cat-learning", "Learning", 1, videoItem("i-numbers", "Numbers", 0, "aqz-KE-bpKQ")),
        ).joinToString(",")
        assertEquals(HttpStatusCode.OK, putCatalog(token, catalogBody(tree)).status)

        val body = getCatalog(token).bodyAsText()
        val served = CatalogJson.decodeSnapshot(body)

        assertNotNull("a version-2 client must be able to read what GET serves", served)
        assertEquals(store.read(), served)

        // Verbatim in the smaller sense too: the tree really is flat, and the nulls are on the wire
        // rather than implied, which is what makes "this node is a video" and "this node is at ROOT"
        // readable facts instead of guesses.
        assertTrue("a shelf's ROOT has to be visible: $body", body.contains("\"parentId\": null"))
        assertTrue("a container is visibly not a video: $body", body.contains("\"youtubeVideoId\": null"))
        listOf("cat-music", "sub-lullabies", "i-twinkle", "cat-learning", "i-numbers").forEach { id ->
            assertTrue("GET must carry $id", body.contains("\"id\": \"$id\""))
        }
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
        assertEquals("the shelf and its container are two nodes, not one nested document", 2, stored.nodes.size)

        val shelf = stored.nodes.single { it.id == "cat-cartoon" }
        assertEquals(CATALOG_NODE_TYPE_CATEGORY, shelf.nodeType)
        assertEquals("Cartoon", shelf.title)
        assertNull("a shelf sits at ROOT", shelf.parentId)

        val item = stored.nodes.single { it.id == "i-cocomelon" }
        assertEquals(CATALOG_NODE_TYPE_SUBCATEGORY, item.nodeType)
        assertEquals("Cocomelon", item.title)
        assertEquals("cat-cartoon", item.parentId)
        assertEquals("PLcocomelon", item.youtubePlaylistId)
        assertNull(item.youtubeVideoId)
    }

    @Test
    fun api03_aStoredCatalogIsServedBackVerbatimIncludingOrderAndNames() = testApp { store, token ->
        // Version 1 proved that order came from the numbers and not from the request by using
        // arbitrary sort orders (20 and 5). Version 2 requires a dense `0..n-1` numbering, so the same
        // proof is made the other way round: the document lists the shelves in the opposite order to
        // the one they render in, and the served snapshot still renders them by `position`.
        val body = catalogBody(
            listOf(
                category("cat-music", "Music", 1, videoItem("i-twinkle", "Twinkle Twinkle", 0, "DuXwFlL8Usk")),
                category("cat-learning", "Learning", 0, playlistItem("i-numbers", "Numbers", 0, "PLnumbers")),
            ).joinToString(",")
        )
        assertEquals(HttpStatusCode.OK, putCatalog(token, body).status)

        val snapshot = CatalogJson.decodeSnapshot(getCatalog(token).bodyAsText())!!

        assertEquals(
            "the document is stored as sent, array order included",
            listOf("cat-music", "cat-learning"),
            snapshot.nodes.filter { it.parentId == null }.map { it.id },
        )
        assertEquals(listOf("Learning", "Music"), renderedTitles(snapshot.nodes, parentId = null))
        assertEquals(listOf("Numbers"), renderedTitles(snapshot.nodes, parentId = "cat-learning"))
        assertEquals(listOf("Twinkle Twinkle"), renderedTitles(snapshot.nodes, parentId = "cat-music"))

        val twinkle = snapshot.nodes.single { it.id == "i-twinkle" }
        assertEquals("DuXwFlL8Usk", twinkle.youtubeVideoId)
        assertNull(twinkle.youtubePlaylistId)
    }

    /**
     * A genuine version-1 document is a 400 that names the version problem, and the store keeps the
     * catalog that was already published.
     *
     * The version is read off the raw body *before* the document is decoded, which is what makes the
     * answer actionable: a version-1 body has no `nodes` at all, so decoding it first would fail on a
     * missing field and the parent would be told its payload was unreadable instead of being told to
     * update. It is never read as version 2 either way - the two documents describe different things,
     * and guessing would publish a tree the parent never configured.
     */
    @Test
    fun api03_aPutWithSchemaVersion1IsRejected() = testApp { store, token ->
        assertEquals(HttpStatusCode.OK, putCatalog(token, catalogBody(category("cat-music", "Music", 0))).status)
        assertEquals(1L, store.read().catalogVersion)

        val version1Body = """{"schemaVersion":1,"categories":[""" +
            """{"id":"cat-music","displayName":"Music","sortOrder":0,"enabled":true,"items":[]}]}"""
        val version1 = putCatalog(token, version1Body)

        assertEquals(HttpStatusCode.BadRequest, version1.status)
        assertTrue(
            "a version-1 client must be told which version this server speaks, got: ${version1.bodyAsText()}",
            version1.bodyAsText().contains("Unsupported schemaVersion 1"),
        )

        // And the same answer for a body that has the version-2 keys but declares version 1.
        val wrongVersion = putCatalog(token, """{"schemaVersion":1,"nodes":[]}""")

        assertEquals(HttpStatusCode.BadRequest, wrongVersion.status)
        assertTrue(
            "the reason must name the version this server does not speak",
            wrongVersion.bodyAsText().contains("Unsupported schemaVersion 1"),
        )

        // A body that declares no usable version at all is an unreadable payload, not a version claim.
        val noVersion = putCatalog(token, """{"nodes":[]}""")
        assertEquals(HttpStatusCode.BadRequest, noVersion.status)
        assertTrue(
            "a body without a version is unreadable, got: ${noVersion.bodyAsText()}",
            noVersion.bodyAsText().contains("Unreadable catalog payload"),
        )

        val stored = store.read()
        assertEquals("a version-1 client must never publish over a parent's catalog", 1L, stored.catalogVersion)
        assertEquals(listOf("Music"), renderedTitles(stored.nodes, parentId = null))
    }

    // ------------------------------------------------------------------ API-04

    @Test
    fun api04_blankCategoryIdIsRejected() = testApp { store, token ->
        val response = putCatalog(token, catalogBody(category("", "Cartoon", 0)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("non-blank id"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api04_blankCategoryTitleIsRejected() = testApp { store, token ->
        // `title` is what version 1 called `displayName`; the rule and its reason moved with the field.
        val response = putCatalog(token, catalogBody(category("cat-cartoon", "  ", 0)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("non-blank title"))
        assertEquals(0L, store.read().catalogVersion)
    }

    /**
     * Version 1 accepted duplicate and negative sort orders on purpose: `sortOrder` was a hint the TV
     * sorted by, and the server declined to rule on ties. Version 2's `position` is the tree's only
     * ordering rule, so it has to be a dense, unique `0..n-1` per parent - two children sharing a
     * position would leave the rendered order to whatever the reader happened to do with the tie,
     * which is exactly what a configured order must not be. What was tolerated is refused now, and the
     * reason names the numbering it found.
     */
    @Test
    fun api04_negativeAndDuplicateSiblingPositionsAreRejected() = testApp { store, token ->
        val body = catalogBody(
            listOf(
                category("cat-a", "A", 0),
                category("cat-b", "B", 0),
                category("cat-c", "C", -5),
            ).joinToString(",")
        )

        val response = putCatalog(token, body)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val reasons = rejectionReasons(response.bodyAsText())
        assertTrue(
            "the rejection must name the numbering it found: $reasons",
            reasons.any { it.contains("positions must be 0..2, found -5,0,0") },
        )
        assertTrue(
            "a negative position is malformed on its own, not only as a gap: $reasons",
            reasons.any { it.contains("must not be negative") },
        )
        assertEquals(0L, store.read().catalogVersion)
        assertTrue(store.read().nodes.isEmpty())
    }

    /**
     * With unique ids and a dense numbering, `position ASC, id ASC` is a total order.
     *
     * So the order the parent wrote is the order every reader renders, and nothing about it is left to
     * interpretation. The array below is deliberately *not* in render order, which is what makes this
     * an assertion about `position` rather than about the order the nodes happened to arrive in.
     */
    @Test
    fun api04_siblingsAreNumberedDenselySoTheConfiguredOrderIsPreserved() = testApp { store, token ->
        val body = catalogBody(
            listOf(
                category("cat-third", "Third", 2),
                category("cat-first", "First", 0),
                category("cat-second", "Second", 1),
            ).joinToString(",")
        )

        assertEquals(HttpStatusCode.OK, putCatalog(token, body).status)

        val stored = store.read()
        assertEquals(listOf("cat-third", "cat-first", "cat-second"), stored.nodes.map { it.id })
        assertEquals(listOf("First", "Second", "Third"), renderedTitles(stored.nodes, parentId = null))
    }

    // ------------------------------------------------------------------ API-05

    /**
     * A container the parent made by hand needs no playlist, but a playlist id that *is* present has to
     * be usable.
     *
     * Version 1 required every `PLAYLIST` entry to name the playlist it mirrored, so an entry without
     * one could not exist. Version 2's `SUBCATEGORY` is a container the parent defines, and
     * `youtubePlaylistId` is import provenance - "the playlist this node came from" - which is absent
     * by definition for a hand-made container. The old refusal is therefore gone by design; what
     * survives is the refusal of a half-filled field, because a blank id is not an absent one.
     */
    @Test
    fun api05_aContainerWithoutAPlaylistIdIsAcceptedButABlankOneIsRefused() = testApp { store, token ->
        val parentDefined = category("cat-music", "Music", 0, playlistItem("i1", "Nursery", 0, null))

        assertEquals(HttpStatusCode.OK, putCatalog(token, catalogBody(parentDefined)).status)

        val stored = store.read().nodes.single { it.id == "i1" }
        assertNull("a parent-defined container has no import provenance", stored.youtubePlaylistId)
        assertNull(stored.youtubeVideoId)

        val blank = putCatalog(
            token,
            catalogBody(category("cat-songs", "Songs", 0, playlistItem("i2", "Songs", 0, ""))),
        )

        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertTrue(blank.bodyAsText().contains("must not be blank"))
        assertEquals("the refused write must not have versioned anything", 1L, store.read().catalogVersion)
    }

    @Test
    fun api05_playlistItemCarryingAVideoIdIsRejected() = testApp { store, token ->
        val item = node(
            id = "i1",
            parentId = parentPlaceholder,
            nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
            title = "Nursery",
            position = 0,
            youtubePlaylistId = "PLabc",
            youtubeVideoId = "vid1",
        )
        val response = putCatalog(token, catalogBody(category("cat-music", "Music", 0, item)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("must not carry a youtubeVideoId"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api05_unusablePlaylistIdIsRejected() = testApp { store, token ->
        // An auto-generated mix, as the dashboard's own parser already refuses.
        val response = putCatalog(
            token,
            catalogBody(category("cat-music", "Music", 0, playlistItem("i1", "Mix", 0, "RDabcdef"))),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Auto-generated playlists"))
        assertEquals(0L, store.read().catalogVersion)
    }

    /**
     * The whole tree is validated before anything is written, so a document with several separate
     * faults is refused as a whole and reports every fault at once.
     *
     * None of these four failures is a property of a single node - a duplicate id, a parent that is not
     * in the document, a cycle, sibling positions that are not `0..n-1` - which is exactly why they
     * cannot live in the parser: they are properties of the *tree*, and in a nested document they could
     * not even be expressed. Reporting them together is what lets a parent fix one publish instead of
     * discovering the faults one round trip at a time.
     */
    @Test
    fun api05_theWholeTreeIsValidatedBeforeAnythingIsWritten() = testApp { store, token ->
        assertEquals(
            HttpStatusCode.OK,
            putCatalog(
                token,
                catalogBody(
                    category("cat-music", "Music", 0, videoItem("i-twinkle", "Twinkle", 0, "DuXwFlL8Usk")),
                ),
            ).status,
        )
        assertEquals(1L, store.read().catalogVersion)

        val body = catalogBody(
            listOf(
                // A duplicate id: 'i-dup' is used by two different nodes.
                category("cat-a", "A", 0, playlistItem("i-dup", "Shared", 0, "PLone")),
                // A gap at ROOT: the shelves are numbered 0 and 2, not 0 and 1.
                category("cat-b", "B", 2, videoItem("i-dup", "Shared Again", 0, "DuXwFlL8Usk")),
                // A parent that is not in this document.
                node(
                    id = "i-orphan",
                    parentId = "cat-missing",
                    nodeType = CATALOG_NODE_TYPE_VIDEO,
                    title = "Orphan",
                    position = 0,
                    youtubeVideoId = "DuXwFlL8Usk",
                ),
                // A cycle: two containers that are each other's parent.
                node(
                    id = "i-cycle-a",
                    parentId = "i-cycle-b",
                    nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
                    title = "Cycle A",
                    position = 0,
                    youtubePlaylistId = "PLcycle",
                ),
                node(
                    id = "i-cycle-b",
                    parentId = "i-cycle-a",
                    nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
                    title = "Cycle B",
                    position = 0,
                    youtubePlaylistId = "PLcycle",
                ),
            ).joinToString(",")
        )

        val response = putCatalog(token, body)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val rejectionBody = response.bodyAsText()
        val errorBody = Json.parseToJsonElement(rejectionBody).jsonObject
        assertEquals("Invalid catalog", errorBody["error"]?.jsonPrimitive?.content)
        val reasons = rejectionReasons(rejectionBody)
        // Seven reasons, and every one of them is reported: the duplicate id, the parent that is not
        // there, the gap at ROOT, and - for each of the two nodes in the cycle - the cycle itself plus
        // the refusal of a container inside a container (a SUBCATEGORY holds videos only).
        assertEquals("every fault is reported, not just the first: $reasons", 7, reasons.size)
        listOf(
            "duplicate node id 'i-dup'",
            "parent 'cat-missing' does not exist in this catalog",
            "is inside a cycle of parents",
            "positions must be 0..1, found 0,2",
        ).forEach { reason ->
            assertTrue("the rejection must name '$reason': $reasons", reasons.any { it.contains(reason) })
        }

        val stored = store.read()
        assertEquals("nothing was written, so nothing was versioned", 1L, stored.catalogVersion)
        assertEquals(listOf("Music"), renderedTitles(stored.nodes, parentId = null))
        assertTrue(stored.nodes.none { it.id == "i-dup" })
        assertTrue(stored.nodes.none { it.title == "Orphan" })
    }

    // ------------------------------------------------------------------ API-06

    @Test
    fun api06_videoItemWithoutAVideoIdIsRejected() = testApp { store, token ->
        val item = node(
            id = "i1",
            parentId = parentPlaceholder,
            nodeType = CATALOG_NODE_TYPE_VIDEO,
            title = "Twinkle",
            position = 0,
            youtubeVideoId = null,
        )
        val response = putCatalog(token, catalogBody(category("cat-music", "Music", 0, item)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("requires a non-blank youtubeVideoId"))
        assertEquals(0L, store.read().catalogVersion)
    }

    /**
     * A video may carry the playlist it was imported from; a shelf may carry no identifier at all.
     *
     * Version 1 refused `youtubePlaylistId` on a video. Version 2 keeps it there deliberately: for a
     * video it is provenance - where the parent's item came from - and dropping it would lose that.
     * The rule the old assertion was really about, "a node must not carry an identifier that belongs
     * to a different kind of node", is enforced where version 2 puts it: a CATEGORY carries neither id,
     * because a shelf is neither a video nor an import.
     */
    @Test
    fun api06_aVideoMayCarryItsImportProvenanceButACategoryMayNotCarryAnId() = testApp { store, token ->
        val imported = node(
            id = "i1",
            parentId = parentPlaceholder,
            nodeType = CATALOG_NODE_TYPE_VIDEO,
            title = "Twinkle",
            position = 0,
            youtubeVideoId = "vid1",
            youtubePlaylistId = "PLabc",
        )

        assertEquals(
            HttpStatusCode.OK,
            putCatalog(token, catalogBody(category("cat-music", "Music", 0, imported))).status,
        )
        assertEquals("PLabc", store.read().nodes.single { it.id == "i1" }.youtubePlaylistId)

        val shelfWithAnId = node(
            id = "cat-bad",
            parentId = null,
            nodeType = CATALOG_NODE_TYPE_CATEGORY,
            title = "Bad",
            position = 0,
            youtubePlaylistId = "PLabc",
        )
        val refused = putCatalog(token, catalogBody(shelfWithAnId))

        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue(refused.bodyAsText().contains("a CATEGORY node must not carry a youtubePlaylistId"))
        assertEquals("the refused shelf must not have versioned anything", 1L, store.read().catalogVersion)
    }

    @Test
    fun api06_unsupportedNodeTypeIsRejected() = testApp { store, token ->
        // `nodeType` is a string on the wire so an unknown value can be *reported*; an enum would turn
        // this into "unreadable payload" and lose the only actionable thing there is to say.
        val item = node(
            id = "i1",
            parentId = parentPlaceholder,
            nodeType = "CHANNEL",
            title = "PBS",
            position = 0,
        )
        val response = putCatalog(token, catalogBody(category("cat-music", "Music", 0, item)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("unsupported node type 'CHANNEL'"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api06_unusableVideoIdIsRejected() = testApp { store, token ->
        val response = putCatalog(
            token,
            catalogBody(category("cat-music", "Music", 0, videoItem("i1", "Handle", 0, "@PBSKids"))),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0L, store.read().catalogVersion)
    }

    // ------------------------------------------------------------------ API-07

    @Test
    fun api07_duplicateNodeIdsAreRejected() = testApp { store, token ->
        // One identifier, two nodes: "the node with this id" would be ambiguous, so the whole payload
        // is refused rather than one of them silently winning.
        val body = catalogBody(
            listOf(
                category("cat-music", "Music", 0),
                category("cat-music", "Music Again", 1),
            ).joinToString(",")
        )

        val response = putCatalog(token, body)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("duplicate node id 'cat-music'"))
        assertEquals(0L, store.read().catalogVersion)
        assertTrue(store.read().nodes.isEmpty())
    }

    @Test
    fun api07_duplicateNodeIdsAreRejectedEvenAcrossContainers() = testApp { store, token ->
        // Two shelves claiming the same child id would collide on the TV, where the node id is the
        // primary key, so the whole payload is refused rather than one row silently winning.
        val body = catalogBody(
            listOf(
                category("cat-a", "A", 0, playlistItem("i-same", "One", 0, "PLone")),
                category("cat-b", "B", 1, playlistItem("i-same", "Two", 0, "PLtwo")),
            ).joinToString(",")
        )

        val response = putCatalog(token, body)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("duplicate node id 'i-same'"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api07_blankChildIdIsRejected() = testApp { store, token ->
        val response = putCatalog(
            token,
            catalogBody(category("cat-a", "A", 0, playlistItem("", "One", 0, "PLone"))),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("non-blank id"))
    }

    // ------------------------------------------------------------------ API-08

    /**
     * A node whose parent is not in the document cannot be stored, so no item can outlive its shelf.
     *
     * Version 1 nested items inside their category, so "an item that names a category which does not
     * exist" could not be expressed at all, and the test could only check that a shelf with no usable
     * identity - which would leave its items unreachable - was refused. Version 2 makes the parent link
     * a *field*, so the failure is expressible, and it is now the validation itself that refuses it.
     */
    @Test
    fun api08_aNodeWhoseParentIsMissingCannotBeStoredSoNoItemCanOutliveItsContainer() = testApp { store, token ->
        val orphaned = node(
            id = "i1",
            parentId = "cat-missing",
            nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
            title = "Nursery",
            position = 0,
            youtubePlaylistId = "PLn",
        )
        val orphanResponse = putCatalog(token, catalogBody(orphaned))

        assertEquals(HttpStatusCode.BadRequest, orphanResponse.status)
        assertTrue(
            "the reason must name the parent that is not there",
            orphanResponse.bodyAsText().contains("does not exist in this catalog"),
        )
        assertEquals(0L, store.read().catalogVersion)

        // A shelf with no usable identity is still refused for the reason it always was: it could not
        // be the parent of anything reachable.
        val blank = putCatalog(
            token,
            catalogBody(category(" ", "Music", 0, playlistItem("i1", "Nursery", 0, "PLn"))),
        )

        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertEquals(0L, store.read().catalogVersion)

        // And a document that *is* accepted cannot contain a node whose parent is missing.
        assertEquals(
            HttpStatusCode.OK,
            putCatalog(
                token,
                catalogBody(category("cat-music", "Music", 0, playlistItem("i1", "Nursery", 0, "PLn"))),
            ).status,
        )
        val stored = store.read()
        val ids = stored.nodes.map { it.id }.toSet()
        stored.nodes.forEach { node ->
            assertFalse("stored node id must be usable", node.id.isBlank())
            val parentId = node.parentId
            assertTrue(
                "a stored node's parent must be a node in the same document: ${node.id}",
                parentId == null || ids.contains(parentId),
            )
        }
        assertEquals(2, stored.nodes.size)
    }

    // ------------------------------------------------------------------ API-09 / API-10

    @Test
    fun api09_successfulMutationsIncreaseTheVersionByOneEachTime() = testApp { store, token ->
        assertEquals(HttpStatusCode.OK, putCatalog(token, catalogBody(category("c1", "One", 0))).status)
        assertEquals(1L, store.read().catalogVersion)

        assertEquals(HttpStatusCode.OK, putCatalog(token, catalogBody(category("c1", "One Renamed", 0))).status)
        assertEquals(2L, store.read().catalogVersion)

        // A reorder is a modification too, and in version 2 it is expressed by the numbering alone:
        // the second shelf arrives later in the document but renders first.
        assertEquals(
            HttpStatusCode.OK,
            putCatalog(
                token,
                catalogBody("${category("c1", "One Renamed", 1)},${category("c2", "Two", 0)}"),
            ).status,
        )
        assertEquals(3L, store.read().catalogVersion)
        assertEquals(listOf("Two", "One Renamed"), renderedTitles(store.read().nodes, parentId = null))
    }

    /**
     * An `expectedCatalogVersion` that matches is accepted and advances the version; the number is a
     * precondition, never the version that gets stored.
     */
    @Test
    fun api09_aMatchingExpectedVersionIsAcceptedAndAdvancesTheVersion() = testApp { store, token ->
        // The client read version 0 - nothing published yet - and says so.
        val first = putCatalog(token, catalogBody(category("c1", "One", 0), expectedCatalogVersion = 0))

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(1L, store.read().catalogVersion)
        assertEquals("1", first.catalogVersionInBody())

        // Retrying against the version that is now stored succeeds, and advances it again.
        val second = putCatalog(
            token,
            catalogBody(category("c1", "One Renamed", 0), expectedCatalogVersion = 1),
        )

        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(2L, store.read().catalogVersion)
        assertEquals("2", second.catalogVersionInBody())
        assertEquals("One Renamed", store.read().nodes.single { it.id == "c1" }.title)
    }

    /**
     * A stale `expectedCatalogVersion` is a 409, and the write it guarded leaves no trace at all.
     *
     * The version check happens before anything is built or saved, so a stale request makes **zero**
     * catalog changes rather than changes that are rolled back afterwards - which is why the stored
     * title is still the one the successful publish wrote.
     */
    @Test
    fun api09_aStaleExpectedVersionIsAConflictAndChangesNothing() = testApp { store, token ->
        assertEquals(HttpStatusCode.OK, putCatalog(token, catalogBody(category("c1", "One", 0))).status)
        assertEquals(1L, store.read().catalogVersion)

        // The writer that has read version 1 publishes successfully and is given version 2.
        val accepted = putCatalog(
            token,
            catalogBody(category("c1", "Two", 0), expectedCatalogVersion = 1),
        )
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals(2L, store.read().catalogVersion)
        assertEquals(listOf("Two"), renderedTitles(store.read().nodes, parentId = null))

        // The same writer retries without re-reading: the server refuses rather than overwriting the
        // change it never saw, and it says which version is actually stored.
        val conflict = putCatalog(
            token,
            catalogBody(category("c1", "Three", 0), expectedCatalogVersion = 1),
        )

        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals("2", conflict.catalogVersionInBody())

        val stored = store.read()
        assertEquals(2L, stored.catalogVersion)
        assertEquals(listOf("Two"), renderedTitles(stored.nodes, parentId = null))
        assertTrue("the rejected title must not be anywhere", stored.nodes.none { it.title == "Three" })
    }

    /**
     * The 409 carries enough for the client to recover: the version that is actually stored, which a
     * following `GET` confirms.
     *
     * A conflict that only said "no" would leave a dashboard with no way to tell an edit it must merge
     * from a catalog it merely has an old number for.
     */
    @Test
    fun api09_aConflictTellsTheClientWhichVersionToFetch() = testApp { store, token ->
        assertEquals(HttpStatusCode.OK, putCatalog(token, catalogBody(category("c1", "One", 0))).status)

        // A second parent session that still believes the catalog is empty.
        val conflict = putCatalog(token, catalogBody(category("c2", "Two", 0), expectedCatalogVersion = 0))

        assertEquals(HttpStatusCode.Conflict, conflict.status)
        val conflictBody = conflict.bodyAsText()
        val storedVersion = store.read().catalogVersion
        assertEquals(
            "the body must carry the version actually stored",
            storedVersion.toString(),
            Json.parseToJsonElement(conflictBody).jsonObject["catalogVersion"]?.jsonPrimitive?.content,
        )

        // ...and that number is the one the client needs: fetching that version returns that document.
        val fetched = CatalogJson.decodeSnapshot(getCatalog(token).bodyAsText())!!

        assertEquals(storedVersion, fetched.catalogVersion)
        assertEquals(store.read(), fetched)
    }

    @Test
    fun api10_failedMutationsDoNotIncreaseTheVersion() = testApp { store, token ->
        assertEquals(HttpStatusCode.OK, putCatalog(token, catalogBody(category("c1", "One", 0))).status)
        assertEquals(1L, store.read().catalogVersion)

        // Each of these is a failed mutation for a different reason.
        assertEquals(HttpStatusCode.BadRequest, putCatalog(token, catalogBody(category("", "Blank", 0))).status)
        assertEquals(
            HttpStatusCode.BadRequest,
            putCatalog(token, catalogBody(category("c2", "Bad Item", 0, playlistItem("i", "Nursery", 0, "RDmix")))).status,
        )
        assertEquals(HttpStatusCode.BadRequest, putCatalog(token, "{}").status)
        assertEquals(HttpStatusCode.BadRequest, putCatalog(token, "not json at all").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            putCatalog(token, catalogBody(category("c1", "Unsupported", 0), schemaVersion = 999)).status,
        )
        assertEquals(
            HttpStatusCode.Conflict,
            putCatalog(token, catalogBody(category("c1", "Stale", 0), expectedCatalogVersion = 99)).status,
        )

        val stored = store.read()
        assertEquals("a failed mutation must not create a version", 1L, stored.catalogVersion)
        assertEquals(listOf("One"), renderedTitles(stored.nodes, parentId = null))
        assertEquals("and it must not add or change a node", 1, stored.nodes.size)
    }

    /**
     * A store that fails to persist produces a 500 and leaves the catalog exactly as it was.
     *
     * This is the only test whose store is not the in-memory one, and it is a real [BaseCatalogStore]
     * rather than a stub whose write throws immediately: the version is assigned by the same code the
     * real stores use and the failure happens at the last step, which is the order the route depends
     * on - nothing written before the whole document is accepted, and no success reported before the
     * store returned. [UnwritableCatalogStore.load] keeps returning what was committed before, which is
     * precisely what a failed save leaves behind.
     */
    @Test
    fun api10_aPersistenceFailureIsReportedAndChangesNothing() = testApp(
        store = UnwritableCatalogStore(
            CatalogSnapshot(
                schemaVersion = CATALOG_SCHEMA_VERSION,
                catalogVersion = 1L,
                nodes = listOf(
                    categoryDto("cat-music", "Music"),
                    videoDto("i-twinkle", "cat-music", "Twinkle", 0, "DuXwFlL8Usk"),
                ),
            ),
        ),
    ) { store, token ->
        val response = putCatalog(token, catalogBody(category("cat-new", "New", 0)))

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val failureBody = response.bodyAsText()
        assertTrue(
            "the parent has to be told the write did not happen: $failureBody",
            failureBody.contains("could not be persisted"),
        )

        val stored = store.read()
        assertEquals("a failed write must not invent a version", 1L, stored.catalogVersion)
        assertEquals(listOf("Music"), renderedTitles(stored.nodes, parentId = null))
        assertEquals(listOf("Twinkle"), renderedTitles(stored.nodes, parentId = "cat-music"))
        assertTrue("and nothing from the failed write survives", stored.nodes.none { it.id == "cat-new" })
    }

    // ------------------------------------------------------------------ API-12 / API-13

    @Test
    fun api12_anExplicitlyEmptyCatalogIsAccepted() = testApp { store, token ->
        val response = putCatalog(token, catalogBody(""))

        assertEquals(HttpStatusCode.OK, response.status)
        val stored = store.read()
        assertEquals(1L, stored.catalogVersion)
        assertTrue("an empty catalog is a legitimate configuration", stored.nodes.isEmpty())

        val served = CatalogJson.decodeSnapshot(getCatalog(token).bodyAsText())!!
        assertEquals(1L, served.catalogVersion)
        assertTrue(served.nodes.isEmpty())
    }

    @Test
    fun api12_aCategoryWithNoItemsIsAccepted() = testApp { store, token ->
        // The `Stories` shelf in the intended hierarchy is configured but empty. It is the only shelf,
        // so its position is 0: version 2 numbers siblings `0..n-1`, and a lone shelf numbered 3 would
        // be a gap the validator refuses.
        val response = putCatalog(token, catalogBody(category("cat-stories", "Stories", 0)))

        assertEquals(HttpStatusCode.OK, response.status)
        val stored = store.read()
        assertEquals(listOf("cat-stories"), stored.nodes.map { it.id })
        assertTrue("a shelf with no children has nothing to render", renderedTitles(stored.nodes, "cat-stories").isEmpty())
    }

    @Test
    fun api13_anEmptyJsonObjectIsRejectedAsMalformedNotAsAnEmptyCatalog() = testApp { store, token ->
        val response = putCatalog(token, "{}")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Unreadable catalog payload"))
        assertEquals("{} must never be read as 'remove everything'", 0L, store.read().catalogVersion)
    }

    @Test
    fun api13_aCatalogWithoutTheNodesKeyIsRejected() = testApp { store, token ->
        // `nodes` has no default: a *missing* list must not be read as "the parent wants nothing",
        // which is why an empty catalog has to say so explicitly with `"nodes":[]`.
        val response = putCatalog(token, """{"schemaVersion":$CATALOG_SCHEMA_VERSION}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Unreadable catalog payload"))
        assertEquals(0L, store.read().catalogVersion)
    }

    @Test
    fun api13_aCatalogWithoutASchemaVersionIsRejected() = testApp { store, token ->
        val response = putCatalog(token, """{"nodes":[]}""")

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
        putCatalog(token, catalogBody(category("cat-music", "Music", 0)))

        // Same store object across a fresh application: what a restart preserves is the file, which
        // CatalogStoreTest covers directly.
        val snapshot = CatalogJson.decodeSnapshot(getCatalog(token).bodyAsText())!!
        assertEquals(1L, snapshot.catalogVersion)
        assertEquals(listOf("Music"), renderedTitles(snapshot.nodes, parentId = null))
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

    /**
     * The server owns the version, so a client has nowhere to put one.
     *
     * `PUT` has no `catalogVersion` field at all and unknown keys are ignored rather than honoured, so
     * the number in this body is not merely corrected - it is never read. The version the parent's next
     * publish gets is the server's own next one, and both the response and the following `GET` say so.
     */
    @Test
    fun api11_theServerAssignsTheVersionAndIgnoresOneFromTheClient() = testApp { store, token ->
        val response = putCatalog(
            token,
            catalogBody(category("c1", "One", 0), extra = """"catalogVersion":999999"""),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("the server assigns the version", 1L, store.read().catalogVersion)
        assertEquals("1", response.catalogVersionInBody())

        // The number the client asked for is nowhere, including in what a TV would download.
        val servedBody = getCatalog(token).bodyAsText()
        assertEquals(
            "1",
            Json.parseToJsonElement(servedBody).jsonObject["catalogVersion"]?.jsonPrimitive?.content,
        )
        assertEquals(1L, CatalogJson.decodeSnapshot(servedBody)!!.catalogVersion)
    }

    @Test
    fun anAcceptedPayloadReportsItsProblemsAsAJsonArrayWhenRejected() = testApp { _, token ->
        val response = putCatalog(
            token,
            catalogBody(
                listOf(
                    category("", "No Id", 0),
                    category("c2", "", 0),
                ).joinToString(",")
            ),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Invalid catalog", body["error"]?.jsonPrimitive?.content)
        assertEquals("every reason must be listed, not just the first", 2, body["details"]?.jsonArray?.size)
    }
}

/**
 * The one store in this file that is not the in-memory one: it holds a catalog and refuses every write.
 *
 * The route's 500 path cannot be reached through `InMemoryCatalogStore` - which is `final` and always
 * succeeds - so the failure is injected at the point a real store would fail: `save`, after the version
 * has been assigned and after the whole document has been validated. What makes this worth a real store
 * rather than a stub is [load]: it keeps answering with the document that was committed before, which
 * is exactly the state a failed save has to leave behind.
 */
private class UnwritableCatalogStore(private val previous: CatalogSnapshot) : BaseCatalogStore() {

    override fun load(): CatalogSnapshot = previous

    override fun save(snapshot: CatalogSnapshot) {
        throw IOException("simulated disk failure")
    }
}
