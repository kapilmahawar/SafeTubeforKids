package tv.safetubeforkids.app.data.catalog

import androidx.room.Room
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.server.CatalogStore
import tv.safetubeforkids.app.server.FileCatalogStore
import tv.safetubeforkids.app.server.catalogRoutes
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The whole path with nothing stubbed: a real Netty server on a real port, the real routes, a real
 * on-disk catalog store, real HTTP requests from the real OkHttp-backed client, the real validator
 * and mapper, and a real Room database.
 *
 * This is the one test that would catch the server and the client drifting apart on the wire format,
 * because it never hands either side a hand-written payload.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogEndToEndTest {

    private lateinit var db: CacheDatabase
    private lateinit var repository: CatalogRepository
    private lateinit var filesDir: File
    private lateinit var sessionManager: SessionManager
    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val token get() = tokenValue!!

    private var tokenValue: String? = null

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("catalog-e2e").toFile()
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = CatalogRepository(db)
        sessionManager = SessionManager()
        tokenValue = sessionManager.createSession()!!
    }

    @After
    fun tearDown() {
        servers.forEach { it.stop(100, 500) }
        servers.clear()
        db.close()
        filesDir.deleteRecursively()
    }

    /** Starts the real server, over [store], and returns the port it actually bound. */
    private fun startServer(store: CatalogStore): Int {
        val server = embeddedServer(Netty, port = 0) {
            install(ContentNegotiation) { json() }
            routing { catalogRoutes(sessionManager, store) }
        }
        server.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    private fun catalogStore() = FileCatalogStore.inFilesDir(filesDir)

    private fun category(id: String, name: String, sortOrder: Int) = CatalogCategoryDto(
        id = id,
        displayName = name,
        sortOrder = sortOrder,
        items = listOf(
            CatalogItemDto(
                id = "$id-item",
                type = CATALOG_TYPE_PLAYLIST,
                displayName = name,
                sortOrder = 0,
                youtubePlaylistId = "PL$id",
            )
        ),
    )

    private fun publish(port: Int, categories: List<CatalogCategoryDto>): Pair<Int, String> {
        val body = CatalogJson.encodePutRequest(
            CatalogPutRequest(schemaVersion = CATALOG_SCHEMA_VERSION, categories = categories)
        )
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/catalog")
            .header("Authorization", "Bearer $token")
            .put(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

    private fun fetchRaw(port: Int): Pair<Int, String> {
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/catalog")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

    private fun sync(port: Int) = runBlocking {
        CatalogSyncService(
            api = HttpCatalogApi(baseUrl = "http://127.0.0.1:$port", tokenProvider = { token }),
            repository = repository,
        ).syncCatalog()
    }

    private fun localNames() = runBlocking { repository.getCategories().map { it.displayName } }

    @Test
    fun aParentPublishesOverHttpAndTheTvInstallsIt() = runBlocking {
        val store = catalogStore()
        val port = startServer(store)

        // The parent's write goes over the wire, through the route, the validator and the store.
        val (putCode, putBody) = publish(
            port,
            listOf(category("cartoon", "Cartoon", 0), category("music", "Music", 1)),
        )
        assertEquals(200, putCode)
        assertEquals("the server assigns the first version", 1L, CatalogJson.decodeSnapshot(putBody)!!.catalogVersion)

        // The TV's read goes over the wire to the same server.
        val result = sync(port)

        assertEquals(CatalogSyncResult.Updated(1L), result)
        assertEquals(listOf("Cartoon", "Music"), localNames())
        assertEquals(1L, repository.getMetadata()!!.catalogVersion)
        assertEquals(1L, repository.getMetadata()!!.serverVersion)
        assertEquals(2, db.contentItemDao().count())
    }

    @Test
    fun aReorderingByTheParentReachesTheTv() = runBlocking {
        val port = startServer(catalogStore())

        publish(
            port,
            listOf(
                category("cartoon", "Cartoon", 20),
                category("music", "Music", 5),
                category("learning", "Learning", 10),
            ),
        )
        assertEquals(CatalogSyncResult.Updated(1L), sync(port))
        assertEquals(listOf("Music", "Learning", "Cartoon"), localNames())

        publish(
            port,
            listOf(
                category("cartoon", "Cartoon", 10),
                category("music", "Music", 20),
                category("learning", "Learning", 0),
            ),
        )
        assertEquals(CatalogSyncResult.Updated(2L), sync(port))

        assertEquals(listOf("Learning", "Cartoon", "Music"), localNames())
    }

    @Test
    fun theCatalogAndItsVersionSurviveARestartOfTheServer() = runBlocking {
        val firstPort = startServer(catalogStore())
        publish(firstPort, listOf(category("music", "Music", 0)))
        publish(firstPort, listOf(category("music", "Music Renamed", 0)))
        val (beforeCode, beforeBody) = fetchRaw(firstPort)
        assertEquals(200, beforeCode)
        val before = CatalogJson.decodeSnapshot(beforeBody)!!
        assertEquals(2L, before.catalogVersion)

        // Stop the server and start a completely new one over the same storage directory: a new
        // process would do exactly this.
        servers.forEach { it.stop(100, 500) }
        servers.clear()
        val secondPort = startServer(catalogStore())

        val (afterCode, afterBody) = fetchRaw(secondPort)
        assertEquals(200, afterCode)
        val after = CatalogJson.decodeSnapshot(afterBody)!!
        assertEquals("the version must not reset", before.catalogVersion, after.catalogVersion)
        assertEquals(before, after)

        // And the TV, which never saw the restart, finds itself already current rather than being
        // sent back to version 1 or handed an empty catalog.
        assertEquals(CatalogSyncResult.Updated(2L), sync(secondPort))
        assertEquals(CatalogSyncResult.AlreadyCurrent(2L), sync(secondPort))
        assertEquals(listOf("Music Renamed"), localNames())
    }

    @Test
    fun anUnauthenticatedCallerGetsNothingFromTheRealServer() = runBlocking {
        val port = startServer(catalogStore())
        publish(port, listOf(category("music", "Music", 0)))

        val getRequest = Request.Builder().url("http://127.0.0.1:$port/catalog").get().build()
        val getCode = http.newCall(getRequest).execute().use { it.code }
        assertEquals(401, getCode)

        val putRequest = Request.Builder()
            .url("http://127.0.0.1:$port/catalog")
            .put("""{"schemaVersion":1,"categories":[]}""".toRequestBody("application/json".toMediaType()))
            .build()
        val putCode = http.newCall(putRequest).execute().use { it.code }
        assertEquals(401, putCode)

        // The parent's catalog is still there and still at version 1.
        assertEquals(1L, catalogStore().read().catalogVersion)
    }

    @Test
    fun aTvThatHasNeverSyncedTakesTheParentsCatalogAndAnOldServerCannotUndoIt() = runBlocking {
        val port = startServer(catalogStore())
        publish(port, listOf(category("cartoon", "Cartoon", 0), category("music", "Music", 1)))
        assertEquals(CatalogSyncResult.Updated(1L), sync(port))
        publish(port, listOf(category("cartoon", "Cartoon Renamed", 0), category("music", "Music", 1)))
        assertEquals(CatalogSyncResult.Updated(2L), sync(port))
        assertEquals(listOf("Cartoon Renamed", "Music"), localNames())

        // A server restored from an older backup offers version 1 again.
        val restored = catalogStore()
        val restoredFile = File(filesDir, FileCatalogStore.FILE_NAME)
        val olderSnapshot = CatalogSnapshot(CATALOG_SCHEMA_VERSION, 1L, listOf(category("cartoon", "Cartoon", 0)))
        restoredFile.writeText(CatalogJson.encode(olderSnapshot))
        assertEquals(1L, restored.read().catalogVersion)

        val result = sync(port)

        assertTrue("expected a regression refusal, got $result", result is CatalogSyncResult.VersionRegression)
        assertEquals("the newer local catalog must survive", listOf("Cartoon Renamed", "Music"), localNames())
        assertEquals(2L, repository.getMetadata()!!.catalogVersion)
    }
}
