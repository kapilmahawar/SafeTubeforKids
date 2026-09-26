package tv.parentapproved.app.server

import androidx.room.Room
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.ResolvedSource
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.ChannelEntity
import tv.safetubeforkids.app.data.cache.VideoEntity
import tv.safetubeforkids.app.data.models.VideoItem
import tv.safetubeforkids.app.server.ResolveLinkRequest
import tv.safetubeforkids.app.server.catalogImportRoutes

/**
 * Resolving a playlist for the editor's import.
 *
 * The route exists because the dashboard cannot talk to YouTube and the TV's server can. It is
 * **read-only on purpose**: it is not a catalog mutation endpoint (the catalog changes through exactly
 * one path, `PUT /catalog`, with the version the editor read) and it is not the `/playlists` endpoint
 * either (which *approves* a source). Importing a playlist into the catalog is curation; a video's
 * presence in the catalog must never become a reason it can play.
 *
 * `channels` and `videos` are therefore asserted to be untouched by every resolution here - that is the
 * security boundary this route sits on - and the resolver is injected so the tests never touch YouTube.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogImportRoutesTest {

    private lateinit var db: CacheDatabase
    private var currentTime = 1_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun testApp(
        resolve: suspend (String, Int) -> ResolvedSource,
        resolveVideo: suspend (String) -> ResolvedSource = { id ->
            ResolvedSource(title = "One video", videos = listOf(video(id, "One video")))
        },
        block: suspend ApplicationTestBuilder.(token: String) -> Unit,
    ) = testApplication {
        val sessionManager = SessionManager(clock = { currentTime })
        application {
            install(ContentNegotiation) { json() }
            routing { catalogImportRoutes(sessionManager, db, resolve = resolve, resolveVideo = resolveVideo) }
        }
        block(sessionManager.createSession()!!)
    }

    private suspend fun ApplicationTestBuilder.resolvePlaylist(token: String?, url: String) =
        client.post("/catalog/import/resolve") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(ResolveLinkRequest.serializer(), ResolveLinkRequest(url = url)))
        }

    private fun video(id: String, title: String, duration: Long = 60L) =
        VideoItem(videoId = id, title = title, thumbnailUrl = "https://img/$id.jpg", durationSeconds = duration, playlistId = "PLimported", position = 0)

    private val okResolver: suspend (String, Int) -> ResolvedSource = { _, _ ->
        ResolvedSource(
            title = "Imported Playlist",
            videos = listOf(video("vidX", "Video X"), video("vidY", "Video Y", 120L)),
        )
    }

    private fun approvalState() = runBlocking {
        db.channelDao().count() to db.videoDao().count()
    }

    // --- access and input ---------------------------------------------------------------------

    @Test
    fun resolvingWithoutASessionIsRejected() = testApp(okResolver) { _ ->
        assertEquals(HttpStatusCode.Unauthorized, resolvePlaylist(null, "https://www.youtube.com/playlist?list=PLx").status)
        assertEquals("nobody resolved anything", 0 to 0, approvalState())
    }

    @Test
    fun aBlankUrlIsRejected() = testApp(okResolver) { token ->
        val response = resolvePlaylist(token, "   ")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Paste a YouTube playlist or video link"))
    }

    @Test
    fun aMalformedOrNonYoutubeUrlIsRejectedWithTheParsersReason() = testApp(okResolver) { token ->
        listOf("not a url", "https://vimeo.com/12345", "https://www.youtube.com/watch?v=").forEach { input ->
            val response = resolvePlaylist(token, input)
            assertEquals("'$input' must be refused", HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("error"))
        }

        assertEquals("a refused URL resolves nothing and stores nothing", 0 to 0, approvalState())
    }

    @Test
    fun aVideoLinkResolvesAsOneVideo() = testApp(okResolver) { token ->
        val response = resolvePlaylist(token, "https://www.youtube.com/watch?v=DuXwFlL8Usk")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // The dashboard renders a video link as one video, and a playlist link as a playlist, from
        // this one field - there is no second endpoint for the second case.
        assertEquals("video", body["kind"]!!.jsonPrimitive.content)
        assertEquals("yt_video", body["sourceType"]!!.jsonPrimitive.content)
        assertEquals("DuXwFlL8Usk", body["sourceId"]!!.jsonPrimitive.content)
        assertEquals(1, body["videos"]!!.jsonArray.size)

        assertEquals("a video link approves nothing either", 0 to 0, approvalState())
    }

    @Test
    fun aChannelLinkIsRefusedWithAWayForward() = testApp(okResolver) { token ->
        val response = resolvePlaylist(token, "https://www.youtube.com/@CoComelon")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val message = response.bodyAsText()

        // A channel is a source, not a shelf: approving one belongs in the allowed-source list, and
        // the refusal says where rather than leaving the parent staring at a form. The wording names
        // no concept the dashboard stopped using - "shelf" became "category" in W8, so the sentence
        // must not rely on it.
        assertTrue(message.contains("can't be added here"))
        assertTrue(message.contains("Settings"))
        assertFalse("the refusal must not use the retired word", message.contains("shelf"))
        assertEquals(0 to 0, approvalState())
    }

    // --- a successful resolution --------------------------------------------------------------

    @Test
    fun aPlaylistResolvesIntoTheVideosAnImportWouldAdd() = testApp(okResolver) { token ->
        val response = resolvePlaylist(token, "https://www.youtube.com/playlist?list=PLimported")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals("yt_playlist", body["sourceType"]!!.jsonPrimitive.content)
        assertEquals("playlist", body["kind"]!!.jsonPrimitive.content)
        assertEquals("PLimported", body["sourceId"]!!.jsonPrimitive.content)
        assertEquals("Imported Playlist", body["title"]!!.jsonPrimitive.content)
        assertEquals(false, body["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, body["approved"]!!.jsonPrimitive.content.toBoolean())

        val videos = body["videos"]!!.jsonArray
        assertEquals(2, videos.size)
        assertEquals("vidX", videos[0].jsonObject["videoId"]!!.jsonPrimitive.content)
        assertEquals("Video X", videos[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("vidY", videos[1].jsonObject["videoId"]!!.jsonPrimitive.content)
        assertEquals(120L, videos[1].jsonObject["durationSeconds"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun theResponseIsNotACatalogDocument() = testApp(okResolver) { token ->
        val body = Json.parseToJsonElement(resolvePlaylist(token, "PLimported").bodyAsText()).jsonObject

        // The resolve endpoint never speaks the catalog contract: no nodes, no version. The editor
        // builds those, and the only thing that stores them is PUT /catalog.
        assertFalse(body.containsKey("nodes"))
        assertFalse(body.containsKey("schemaVersion"))
        assertFalse(body.containsKey("catalogVersion"))
    }

    @Test
    fun aBarePlaylistIdIsAcceptedJustLikeAUrl() = testApp(okResolver) { token ->
        assertEquals(HttpStatusCode.OK, resolvePlaylist(token, "PLimported").status)
    }

    @Test
    fun aTruncatedResolutionSaysSoRatherThanLookingComplete() = testApp({ _, _ ->
        ResolvedSource(title = "Long", videos = listOf(video("vidX", "X")), truncated = true, unusableItems = 2)
    }) { token ->
        val body = Json.parseToJsonElement(resolvePlaylist(token, "PLlong").bodyAsText()).jsonObject

        assertEquals(true, body["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2, body["unusableItems"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun theLimitForOneImportIsTheImportsOwnAndNotTheApprovedCaches() = testApp({ _, limit ->
        assertEquals(
            "an import takes more than an approved source caches, and is capped all the same",
            tv.safetubeforkids.app.data.MAX_VIDEOS_PER_IMPORT,
            limit,
        )
        ResolvedSource(title = "T", videos = emptyList())
    }) { token ->
        assertEquals(HttpStatusCode.OK, resolvePlaylist(token, "PLimported").status)
    }

    // --- the security boundary ------------------------------------------------------------------

    @Test
    fun resolvingAPlaylistApprovesNothing() = testApp(okResolver) { token ->
        resolvePlaylist(token, "https://www.youtube.com/playlist?list=PLimported")

        // No channel row, no cached video: exactly the state the TV's authorization depends on, and a
        // playlist import must leave it that way. The videos become catalog entries that cannot play.
        assertEquals("no source was approved", 0, runBlocking { db.channelDao().count() })
        assertEquals("no video was cached", 0, runBlocking { db.videoDao().count() })
    }

    @Test
    fun anAlreadyApprovedSourceIsReportedButNotChanged() = testApp(okResolver) { token ->
        runBlocking {
            db.channelDao().insert(
                ChannelEntity(
                    sourceType = "yt_playlist",
                    sourceId = "PLimported",
                    sourceUrl = "https://www.youtube.com/playlist?list=PLimported",
                    displayName = "Imported Playlist",
                )
            )
            db.videoDao().insertAll(listOf(VideoEntity("vidX", "PLimported", "Video X", "thumb", 60, 0)))
        }

        val body = Json.parseToJsonElement(resolvePlaylist(token, "PLimported").bodyAsText()).jsonObject

        assertEquals("the editor can tell the parent these videos will play", true, body["approved"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("and nothing about the approval changed", 1 to 1, approvalState())
    }

    @Test
    fun aSourceThatWasNeverApprovedIsReportedAsSuchEvenWhenItsVideoIs() = testApp(okResolver) { token ->
        runBlocking {
            db.videoDao().insertAll(listOf(VideoEntity("vidX", "PLimported", "Video X", "thumb", 60, 0)))
        }

        val body = Json.parseToJsonElement(resolvePlaylist(token, "PLimported").bodyAsText()).jsonObject

        // A cached video whose source is gone is not playable, so it must not be reported as approved.
        assertEquals(false, body["approved"]!!.jsonPrimitive.content.toBoolean())
    }

    // --- failure --------------------------------------------------------------------------------

    @Test
    fun aResolutionFailureIsReportedAndNothingIsStored() = testApp({ _, _ -> throw IOException("playlist not found") }) { token ->
        val response = resolvePlaylist(token, "PLmissing")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertTrue(response.bodyAsText().contains("playlist not found"))
        assertEquals(0 to 0, approvalState())
    }

    @Test
    fun anOfflineDeviceReportsTheResolutionFailureRatherThanAnEmptyPlaylist() = testApp({ _, _ ->
        throw IOException("Simulated offline")
    }) { token ->
        val response = resolvePlaylist(token, "PLimported")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertTrue(response.bodyAsText().contains("offline"))
    }

    @Test
    fun anUnreadableBodyIsRejected() = testApp(okResolver) { token ->
        val response = client.post("/catalog/import/resolve") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("not json at all")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun theRouteDoesNotAnswerAnythingButPost() = testApp(okResolver) { token ->
        val response = client.get("/catalog/import/resolve") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        // Nothing was resolved by a GET, and nothing is stored. Ktor answers 405 for a method the route
        // does not accept, which is the point: there is no read-only way in that would resolve.
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertEquals(0 to 0, approvalState())
    }
}
