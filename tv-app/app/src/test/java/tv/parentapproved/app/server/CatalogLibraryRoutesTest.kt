package tv.safetubeforkids.app.server

import androidx.room.Room
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.VideoEntity
import tv.safetubeforkids.app.data.catalog.CatalogMetadataEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeType
import tv.safetubeforkids.app.data.catalog.CatalogRepository
import tv.safetubeforkids.app.data.catalog.ThumbnailMode

/**
 * The two endpoints the redesigned dashboard needs beyond the catalog document itself:
 * `GET /catalog/artwork` (the pictures and counts only the TV knows) and `POST /catalog/refresh`
 * (do now what the TV's own Refresh button does).
 *
 * What matters here is not the JSON shape but the two properties that make these endpoints safe to
 * add to a security-sensitive server: **neither writes anything**, and neither can make a video
 * playable that the approved sources did not already allow. `refresh` performs the TV's own work and
 * is therefore injected - the route's job is to report the outcome honestly, in the three shapes the
 * dashboard has to tell apart.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogLibraryRoutesTest {

    private lateinit var db: CacheDatabase
    private lateinit var catalog: CatalogRepository

    private val installedVersion = 7L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        catalog = CatalogRepository(db)

        runBlocking {
            catalog.replaceTree(tree(), CatalogMetadataEntity(catalogVersion = installedVersion))
            db.videoDao().insertAll(
                listOf(
                    VideoEntity("vidA", "PLcartoons", "First", "https://img/aaa.jpg", 60, 0),
                    VideoEntity("vidB", "PLcartoons", "Hidden one", "https://img/bbb.jpg", 60, 1),
                    VideoEntity("vidC", "PLsongs", "Song", "https://img/ccc.jpg", 60, 0),
                    // A video the catalog knows nothing about: it must not appear in the answer.
                    VideoEntity("vidOrphan", "PLother", "Orphan", "https://img/ddd.jpg", 60, 0),
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * One shelf, two folders in it, and three videos - one of them hidden - plus a folder whose
     * picture is chosen explicitly. Small enough to reason about, complete enough that every branch
     * of the artwork answer is exercised.
     */
    private fun tree(): List<CatalogNodeEntity> = listOf(
        CatalogNodeEntity(id = "cat", nodeType = CatalogNodeType.CATEGORY, title = "Cartoons", position = 0),
        CatalogNodeEntity(
            id = "folder", parentId = "cat", nodeType = CatalogNodeType.SUBCATEGORY,
            title = "Songs", position = 0, youtubePlaylistId = "PLcartoons",
        ),
        CatalogNodeEntity(
            id = "v1", parentId = "folder", nodeType = CatalogNodeType.VIDEO,
            title = "First", position = 0, youtubeVideoId = "vidA", youtubePlaylistId = "PLcartoons",
        ),
        CatalogNodeEntity(
            id = "v2", parentId = "folder", nodeType = CatalogNodeType.VIDEO,
            title = "Hidden one", position = 1, enabled = false,
            youtubeVideoId = "vidB", youtubePlaylistId = "PLcartoons",
        ),
        CatalogNodeEntity(
            id = "folder2", parentId = "cat", nodeType = CatalogNodeType.SUBCATEGORY,
            title = "Favourites", position = 1,
            thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "v3",
        ),
        CatalogNodeEntity(
            id = "v3", parentId = "folder2", nodeType = CatalogNodeType.VIDEO,
            title = "Song", position = 0, youtubeVideoId = "vidC", youtubePlaylistId = "PLsongs",
        ),
    )

    private fun testApp(
        refresh: suspend () -> RefreshOutcome = { RefreshOutcome.Refreshed(installedVersion, 0) },
        block: suspend ApplicationTestBuilder.(token: String) -> Unit,
    ) = testApplication {
        val sessionManager = SessionManager(clock = { 1_000L })
        application {
            install(ContentNegotiation) { json() }
            routing { catalogLibraryRoutes(sessionManager, catalog, db, refresh) }
        }
        block(sessionManager.createSession()!!)
    }

    private suspend fun ApplicationTestBuilder.artwork(token: String?) = client.get("/catalog/artwork") {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun ApplicationTestBuilder.refreshCall(token: String?) = client.post("/catalog/refresh") {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    /** Nothing about approvals may move: the whole point of these routes is that they are read-mostly. */
    private fun approvalState() = runBlocking {
        Triple(db.channelDao().count(), db.videoDao().count(), catalog.getMetadata()?.catalogVersion)
    }

    // --- access ------------------------------------------------------------------------------

    @Test
    fun neitherEndpointAnswersWithoutASession() = testApp { _ ->
        assertEquals(HttpStatusCode.Unauthorized, artwork(null).status)
        assertEquals(HttpStatusCode.Unauthorized, refreshCall(null).status)
        assertEquals(Triple(0, 4, installedVersion), approvalState())
    }

    // --- artwork -----------------------------------------------------------------------------

    @Test
    fun artworkReportsThePicturesTheTvAlreadyHas() = testApp { token ->
        val response = artwork(token)
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val videos = body["videos"]!!.jsonObject

        assertEquals("the version this describes is the TV's, not a draft", installedVersion,
            body["installedCatalogVersion"]!!.jsonPrimitive.content.toLong())
        assertEquals("https://img/aaa.jpg", videos["vidA"]!!.jsonPrimitive.content)
        assertEquals("https://img/ccc.jpg", videos["vidC"]!!.jsonPrimitive.content)
        assertEquals("a hidden video still has a picture", "https://img/bbb.jpg", videos["vidB"]!!.jsonPrimitive.content)
        assertEquals("only the videos the library names, never the whole approved cache",
            3, videos.size)
        assertNull("a video outside the library has no place here", videos["vidOrphan"])
    }

    @Test
    fun aFolderIsDescribedByItsPictureAndByWhatTheChildCanReach() = testApp { token ->
        val body = Json.parseToJsonElement(artwork(token).bodyAsText()).jsonObject
        val folder = body["containers"]!!.jsonObject["folder"]!!.jsonObject

        // AUTO, so the picture is the first video inside that the child can actually see.
        assertEquals("https://img/aaa.jpg", folder["thumbnailUrl"]!!.jsonPrimitive.content)
        assertEquals("one video is reachable", 1, folder["videoCount"]!!.jsonPrimitive.content.toInt())
        assertEquals("and one is hidden", 1, folder["hiddenVideoCount"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun aFolderWhosePictureWasChosenReportsTheChosenOne() = testApp { token ->
        val body = Json.parseToJsonElement(artwork(token).bodyAsText()).jsonObject
        val folder = body["containers"]!!.jsonObject["folder2"]!!.jsonObject

        assertEquals("https://img/ccc.jpg", folder["thumbnailUrl"]!!.jsonPrimitive.content)
        assertEquals(1, folder["videoCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, folder["hiddenVideoCount"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun aShelfIsNeverGivenAPicture() = testApp { token ->
        val body = Json.parseToJsonElement(artwork(token).bodyAsText()).jsonObject
        val containers = body["containers"]!!.jsonObject

        // A shelf is a title on the TV, not a card: the dashboard is not told about a picture for one
        // because there is none to tell it about (W6.1's rule, on the parent's screen too).
        assertFalse("a category must not appear among the containers", containers.containsKey("cat"))
        assertEquals("only the two folders", 2, containers.size)
    }

    @Test
    fun artworkWritesNothingAtAll() = testApp { token ->
        val before = approvalState()
        artwork(token)
        assertEquals("no approval, no cache row and no version moved", before, approvalState())
    }

    // --- refresh ----------------------------------------------------------------------------

    @Test
    fun refreshAsksTheTvToCatchUpAndReportsTheVersionItLandedOn() = testApp(
        refresh = { RefreshOutcome.Refreshed(9L, 2) }
    ) { token ->
        val response = refreshCall(token)
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("refreshed", body["status"]!!.jsonPrimitive.content)
        assertEquals("the TV is now on the version it fetched", installedVersion,
            body["installedCatalogVersion"]!!.jsonPrimitive.content.toLong())
        assertEquals(2, body["sourcesRefreshed"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["message"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun aTvThatCannotBeReachedIsReportedAsUnavailableAndNotAsAFailure() = testApp(
        refresh = { RefreshOutcome.NotConnected }
    ) { token ->
        val response = refreshCall(token)

        // 502: the request was fine and the edit stands - only the "come and get it" call did not land.
        assertEquals(HttpStatusCode.BadGateway, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("unavailable", body["status"]!!.jsonPrimitive.content)
        assertTrue(body["message"]!!.jsonPrimitive.content.contains("on its own"))
    }

    @Test
    fun aRefusedRefreshSaysWhy() = testApp(
        refresh = { RefreshOutcome.Failed("The TV already has a newer library than the one it was sent.") }
    ) { token ->
        val response = refreshCall(token)

        assertEquals(HttpStatusCode.BadGateway, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("failed", body["status"]!!.jsonPrimitive.content)
        assertTrue(body["message"]!!.jsonPrimitive.content.contains("newer library"))
    }

    @Test
    fun refreshWritesNoApprovalOfItsOwn() = testApp(refresh = { RefreshOutcome.Refreshed(9L, 1) }) { token ->
        val before = approvalState()
        refreshCall(token)
        assertEquals("refreshing caches what an approved source already allows, and nothing more",
            before, approvalState())
    }

    @Test
    fun theRoutesDoNotAnswerAnythingButTheirOwnMethod() = testApp { token ->
        val posted = client.post("/catalog/artwork") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val got = client.get("/catalog/refresh") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.MethodNotAllowed, posted.status)
        assertEquals(HttpStatusCode.MethodNotAllowed, got.status)
        assertNotNull(approvalState())
    }
}
