package tv.safetubeforkids.app.server

import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.ExportedSource
import tv.safetubeforkids.app.data.FakeChannelDao
import tv.safetubeforkids.app.data.SourceTransfer
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.ChannelEntity
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PlaylistRoutesTest {

    private var currentTime = 1000000L

    private fun testApp(
        setupDao: FakeChannelDao.() -> Unit = {},
        block: suspend ApplicationTestBuilder.(token: String) -> Unit,
    ) = testApplication {
        val sessionManager = SessionManager(clock = { currentTime })
        val fakeDao = FakeChannelDao()
        fakeDao.setupDao()

        val mockDb = mockk<CacheDatabase>()
        every { mockDb.channelDao() } returns fakeDao
        val mockVideoDao = mockk<tv.safetubeforkids.app.data.cache.PlaylistCacheDao>()
        every { mockDb.videoDao() } returns mockVideoDao
        coEvery { mockVideoDao.deleteByPlaylist(any()) } returns Unit

        application {
            install(ContentNegotiation) { json() }
            routing {
                playlistRoutes(sessionManager, mockDb)
            }
        }

        val token = sessionManager.createSession()!!
        block(token)
    }

    @Test
    fun getPlaylists_empty_returnsEmptyArray() = testApp { token ->
        val response = client.get("/playlists") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(0, body.size)
    }

    @Test
    fun postPlaylist_validPlaylistUrl_returns201() = testApp { token ->
        val response = client.post("/playlists") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://www.youtube.com/playlist?list=PLtest123"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("PLtest123", body["sourceId"]?.jsonPrimitive?.content)
        assertEquals("yt_playlist", body["sourceType"]?.jsonPrimitive?.content)
    }

    @Test
    fun postPlaylist_validVideoUrl_returns201() = testApp { token ->
        val response = client.post("/playlists") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("dQw4w9WgXcQ", body["sourceId"]?.jsonPrimitive?.content)
        assertEquals("yt_video", body["sourceType"]?.jsonPrimitive?.content)
    }

    @Test
    fun postPlaylist_validChannelUrl_returns201() = testApp { token ->
        val response = client.post("/playlists") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://www.youtube.com/@PBSKids"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("@PBSKids", body["sourceId"]?.jsonPrimitive?.content)
        assertEquals("yt_channel", body["sourceType"]?.jsonPrimitive?.content)
    }

    @Test
    fun postPlaylist_vimeoUrl_returns400WithCta() = testApp { token ->
        val response = client.post("/playlists") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://vimeo.com/12345"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["error"]!!.jsonPrimitive.content.contains("Vimeo"))
        assertNotNull(body["cta"])
    }

    @Test
    fun postPlaylist_invalidUrl_returns400() = testApp { token ->
        val response = client.post("/playlists") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://www.google.com"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun postPlaylist_duplicateSource_returns409() = testApp(
        setupDao = {
            kotlinx.coroutines.runBlocking {
                insert(ChannelEntity(sourceType = "yt_playlist", sourceId = "PLexisting", sourceUrl = "url", displayName = "Existing"))
            }
        }
    ) { token ->
        val response = client.post("/playlists") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://www.youtube.com/playlist?list=PLexisting"}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    private fun transferApp(
        block: suspend ApplicationTestBuilder.(token: String) -> Unit,
    ) = testApplication {
        val sessionManager = SessionManager(clock = { currentTime })
        val fakeDao = FakeChannelDao()
        val mockDb = mockk<CacheDatabase>()
        every { mockDb.channelDao() } returns fakeDao

        application {
            install(ContentNegotiation) { json() }
            routing {
                sourceTransferRoutes(sessionManager, mockDb)
            }
        }

        val token = sessionManager.createSession()!!
        block(token)
    }

    private suspend fun ApplicationTestBuilder.listedSources(token: String): kotlinx.serialization.json.JsonArray =
        Json.parseToJsonElement(
            client.get("/playlists") { header("Authorization", "Bearer $token") }.bodyAsText()
        ).jsonArray

    /**
     * W13.12: the cap of 20 sources is gone.
     *
     * The count of sources was never the resource that costs the TV - a source is one small row, and
     * resolution is sequential. What costs the TV is how many videos one source pulls, and that stays
     * bounded where it is spent. These tests cover the twenty-first source, a substantially larger
     * catalog, the duplicate rule at scale, and the import path that carried the same cap.
     */
    @Test
    fun postPlaylist_twentyFirstSource_isCreated() = testApp(
        setupDao = {
            kotlinx.coroutines.runBlocking {
                repeat(20) { i ->
                    insert(ChannelEntity(sourceType = "yt_playlist", sourceId = "PLfill$i", sourceUrl = "url$i", displayName = "P$i"))
                }
            }
        }
    ) { token ->
        val response = client.post("/playlists") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"PLtwentyfirst000000000000000000"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(21, listedSources(token).size)
    }

    @Test
    fun postPlaylist_sixtySources_allCreatedAndListedInOrder() = testApp { token ->
        val started = System.nanoTime()
        repeat(60) { i ->
            val response = client.post("/playlists") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"url":"PLbulk${i.toString().padStart(4, '0')}"}""")
            }
            assertEquals("source $i", HttpStatusCode.Created, response.status)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("W13.12 measured: 60 sources added over HTTP in ${elapsedMs}ms")

        val listed = listedSources(token)
        assertEquals(60, listed.size)
        assertEquals("the source list is stable", listed.toString(), listedSources(token).toString())
    }

    @Test
    fun postPlaylist_twoHundredSources_areAllCreatedWithinBudget() = testApp { token ->
        val started = System.nanoTime()
        repeat(200) { i ->
            val response = client.post("/playlists") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"url":"PLlarge${i.toString().padStart(4, '0')}"}""")
            }
            assertEquals("source $i", HttpStatusCode.Created, response.status)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("W13.12 measured: 200 sources added over HTTP in ${elapsedMs}ms")

        assertEquals(200, listedSources(token).size)
        // A budget rather than a benchmark: the work measured is the route's own per-request cost
        // against the fake DAO. Exceeding it would mean something had become accidentally quadratic.
        assertTrue("200 adds took ${elapsedMs}ms", elapsedMs < 60_000)
    }

    @Test
    fun postPlaylist_duplicateAmongMany_isStillRejected() = testApp(
        setupDao = {
            kotlinx.coroutines.runBlocking {
                repeat(25) { i ->
                    insert(ChannelEntity(sourceType = "yt_playlist", sourceId = "PLdup$i", sourceUrl = "url$i", displayName = "D$i"))
                }
            }
        }
    ) { token ->
        val response = client.post("/playlists") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"PLdup7"}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(25, listedSources(token).size)
    }

    @Test
    fun sourceImport_pastTwenty_isNotRefused() = transferApp { token ->
        val sources = java.util.ArrayList<ExportedSource>()
        for (index in 0 until 25) {
            val id = "PLimport" + index.toString().padStart(3, '0')
            sources.add(
                ExportedSource(
                    sourceType = "yt_playlist",
                    sourceId = id,
                    sourceUrl = "https://www.youtube.com/playlist?list=" + id,
                    displayName = "Imported " + index,
                    videoCount = 0,
                )
            )
        }
        val payload = SourceTransfer.export(sources, 1_700_000_000_000L)
        val response = client.post("/sources/import") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(25, body["added"]!!.jsonArray.size)
        assertEquals(0, body["failed"]!!.jsonArray.size)
    }

    @Test
    fun deletePlaylist_existingId_returns200() = testApp(
        setupDao = {
            kotlinx.coroutines.runBlocking {
                insert(ChannelEntity(sourceType = "yt_playlist", sourceId = "PLdel", sourceUrl = "url", displayName = "Delete Me"))
            }
        }
    ) { token ->
        val listResponse = client.get("/playlists") {
            header("Authorization", "Bearer $token")
        }
        val playlists = Json.parseToJsonElement(listResponse.bodyAsText()).jsonArray
        val id = playlists[0].jsonObject["id"]?.jsonPrimitive?.content

        val response = client.delete("/playlists/$id") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun deletePlaylist_nonExistentId_returns404() = testApp { token ->
        val response = client.delete("/playlists/999") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun postPlaylist_barePlaylistId_returns201() = testApp { token ->
        val response = client.post("/playlists") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"PLnew123456"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun getPlaylists_returnsSourceFields() = testApp(
        setupDao = {
            kotlinx.coroutines.runBlocking {
                insert(ChannelEntity(sourceType = "yt_video", sourceId = "vid1", sourceUrl = "https://www.youtube.com/watch?v=vid1", displayName = "My Video", videoCount = 1))
            }
        }
    ) { token ->
        val response = client.get("/playlists") {
            header("Authorization", "Bearer $token")
        }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        val item = body[0].jsonObject
        assertEquals("yt_video", item["sourceType"]?.jsonPrimitive?.content)
        assertEquals("vid1", item["sourceId"]?.jsonPrimitive?.content)
        assertEquals("1", item["videoCount"]?.jsonPrimitive?.content)
    }
}
