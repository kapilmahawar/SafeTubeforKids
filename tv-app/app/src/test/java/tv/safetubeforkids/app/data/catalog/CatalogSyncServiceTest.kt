package tv.safetubeforkids.app.data.catalog

import androidx.room.Room
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.ChannelEntity
import tv.safetubeforkids.app.data.cache.PlaybackPositionEntity
import tv.safetubeforkids.app.data.cache.VideoEntity
import tv.safetubeforkids.app.playback.PlaybackApproval
import tv.safetubeforkids.app.playback.PlaybackAuthorization
import tv.safetubeforkids.app.ui.screens.CatalogCardKind
import tv.safetubeforkids.app.ui.screens.CatalogUiProjection
import java.util.concurrent.atomic.AtomicInteger

/**
 * The catalog sync, end to end: a real HTTP request through OkHttp to a real socket, the catalog JSON
 * the server actually emits, the real validator and mapper, and a real Room database.
 *
 * Only the server's *content* is stubbed (MockWebServer serves the queued body); nothing in the
 * client, the parser, the validator, the mapper, the repository or the transaction is.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogSyncServiceTest {

    private lateinit var db: CacheDatabase
    private lateinit var repository: CatalogRepository
    private lateinit var server: MockWebServer
    private var now = 1_700_000_000_000L

    private val token = "parent-session-token"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = CatalogRepository(db)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    // ------------------------------------------------------------------ fixtures
    //
    // The fixtures build a version-2 document: a flat list of nodes, each naming its parent. A shelf
    // is a CATEGORY node, a playlist entry a SUBCATEGORY container and an individual video a VIDEO
    // node. `category(...)` returns a shelf *and* its entries, so a call site still reads as one shelf
    // with its contents.

    private fun playlistItem(
        id: String,
        name: String,
        sortOrder: Int,
        playlistId: String,
        enabled: Boolean = true,
    ) = CatalogNodeDto(
        id = id,
        parentId = null,
        nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
        title = name,
        position = sortOrder,
        enabled = enabled,
        youtubePlaylistId = playlistId,
    )

    private fun videoItem(
        id: String,
        name: String,
        sortOrder: Int,
        videoId: String,
        enabled: Boolean = true,
    ) = CatalogNodeDto(
        id = id,
        parentId = null,
        nodeType = CATALOG_NODE_TYPE_VIDEO,
        title = name,
        position = sortOrder,
        enabled = enabled,
        youtubeVideoId = videoId,
    )

    private fun category(
        id: String,
        name: String,
        sortOrder: Int,
        items: List<CatalogNodeDto> = emptyList(),
        enabled: Boolean = true,
    ): List<CatalogNodeDto> = listOf(
        CatalogNodeDto(
            id = id,
            parentId = null,
            nodeType = CATALOG_NODE_TYPE_CATEGORY,
            title = name,
            position = sortOrder,
            enabled = enabled,
        )
    ) + items.map { it.copy(parentId = id) }

    /**
     * A shelf's entries, parented and numbered `0..n-1` in the order the fixture configured them.
     *
     * A version-2 document has to be canonically numbered, so the *numbers* a fixture passes in decide
     * the order and are then normalised away - which is what the server does when it stores one.
     */
    private fun canonicalNodes(shelves: List<List<CatalogNodeDto>>): List<CatalogNodeDto> {
        val flat = shelves.flatten()
        val roots = flat.filter { it.parentId == null }
            .sortedWith(compareBy({ it.position }, { it.id }))
            .mapIndexed { index, node -> node.copy(position = index) }
        val children = flat.filter { it.parentId != null }
            .groupBy { it.parentId }
            .flatMap { (parentId, nodes) ->
                nodes.sortedWith(compareBy({ it.position }, { it.id }))
                    .mapIndexed { index, node -> node.copy(parentId = parentId, position = index) }
            }
        return roots + children
    }

    private fun catalog(
        version: Long,
        shelves: List<List<CatalogNodeDto>>,
        schemaVersion: Int = CATALOG_SCHEMA_VERSION,
    ) = CatalogSnapshot(schemaVersion, version, canonicalNodes(shelves))

    private fun enqueueCatalog(snapshot: CatalogSnapshot, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(CatalogJson.encode(snapshot))
        )
    }

    private fun enqueueBody(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }

    private fun service(
        baseUrl: String = server.url("/").toString().removeSuffix("/"),
        sessionToken: String? = token,
        api: CatalogApi? = null,
    ) = CatalogSyncService(
        api = api ?: HttpCatalogApi(
            baseUrl = baseUrl,
            tokenProvider = { sessionToken },
        ),
        repository = repository,
        clock = { now },
    )

    /** Installs a local catalog the way a previous successful sync would have. */
    private fun installLocal(
        version: Long,
        shelves: List<List<CatalogNodeDto>>,
        syncedAt: Long = 1_600_000_000_000L,
    ) = runBlocking {
        val result = repository.replaceTree(
            serverNodes = CatalogMapper.toNodes(canonicalNodes(shelves), syncedAt = syncedAt),
            metadata = CatalogMetadataEntity(catalogVersion = version),
            syncedAt = syncedAt,
        )
        assertEquals(CatalogWriteResult.Written, result)
    }

    private fun localCategoryNames(): List<String> = runBlocking {
        repository.getCategories().map { it.displayName }
    }

    private fun localItems(categoryId: String): List<ContentItemEntity> = runBlocking {
        repository.getItems(categoryId)
    }

    /** Every entry of every shelf. Derived from the node tree, which is the only catalog storage. */
    private fun localAllItems(): List<ContentItemEntity> = runBlocking {
        repository.getCategories().flatMap { repository.getItems(it.id) }
    }

    /** How many entries the local catalog holds - one per configured child of a shelf. */
    private fun localItemCount(): Int = runBlocking {
        repository.getCategories().sumOf { repository.getItems(it.id).size }
    }

    // ------------------------------------------------------------------ SYNC-01

    @Test
    fun sync01_aFreshTvAdoptsTheServerCatalog() = runBlocking {
        enqueueCatalog(
            catalog(
                1L,
                listOf(
                    category("cat-cartoon", "Cartoon", 0, listOf(playlistItem("i-cocomelon", "Cocomelon", 0, "PLcocomelon"))),
                    category("cat-music", "Music", 1, listOf(playlistItem("i-nursery", "Nursery Songs", 0, "PLnursery"))),
                ),
            )
        )

        assertNull("nothing is known before the first sync", repository.getMetadata())
        val result = service().syncCatalog()

        assertEquals(CatalogSyncResult.Updated(1L), result)
        assertEquals(listOf("Cartoon", "Music"), localCategoryNames())
        assertEquals(listOf("Cocomelon"), localItems("cat-cartoon").map { it.displayName })

        val metadata = repository.getMetadata()!!
        assertEquals(1L, metadata.catalogVersion)
        assertEquals(1L, metadata.serverVersion)
        assertEquals(now, metadata.lastSuccessfulSyncAt)
        assertEquals(now, metadata.lastAttemptAt)
    }

    // ------------------------------------------------------------------ SYNC-02

    @Test
    fun sync02_anOlderLocalCatalogIsReplacedByTheNewerServerOne() = runBlocking {
        installLocal(1L, listOf(category("cat-old", "Old Shelf", 0)))
        enqueueCatalog(catalog(2L, listOf(category("cat-new", "New Shelf", 0))))

        val result = service().syncCatalog()

        assertEquals(CatalogSyncResult.Updated(2L), result)
        assertEquals(listOf("New Shelf"), localCategoryNames())
        assertNull("the replaced shelf must be gone", repository.getCategory("cat-old"))
        assertEquals(2L, repository.getMetadata()!!.catalogVersion)
        assertEquals(now, repository.getMetadata()!!.lastSuccessfulSyncAt)
    }

    // ------------------------------------------------------------------ SYNC-03

    @Test
    fun sync03_aServerErrorLeavesTheLocalCatalogUntouched() = runBlocking {
        installLocal(2L, listOf(category("cat-cartoon", "Cartoon", 0)))
        enqueueBody("""{"error":"boom"}""", code = 500)

        val result = service().syncCatalog()

        assertEquals(CatalogSyncResult.ServerUnavailable("HTTP 500"), result)
        assertEquals(listOf("Cartoon"), localCategoryNames())
        val metadata = repository.getMetadata()!!
        assertEquals("the installed version must not move", 2L, metadata.catalogVersion)
        assertEquals(1_600_000_000_000L, metadata.lastSuccessfulSyncAt)
        assertEquals("the attempt is still recorded", now, metadata.lastAttemptAt)
    }

    @Test
    fun sync03_anUnreachableServerLeavesTheLocalCatalogUntouched() = runBlocking {
        installLocal(2L, listOf(category("cat-cartoon", "Cartoon", 0)))

        // Port 1 has nothing listening on it: a real connection failure, not a stubbed one.
        val result = service(baseUrl = "http://127.0.0.1:1").syncCatalog()

        assertTrue("expected ServerUnavailable, got $result", result is CatalogSyncResult.ServerUnavailable)
        assertEquals(listOf("Cartoon"), localCategoryNames())
        assertEquals(2L, repository.getMetadata()!!.catalogVersion)
    }

    @Test
    fun sync03_aRefusedSessionIsReportedAndChangesNothing() = runBlocking {
        installLocal(2L, listOf(category("cat-cartoon", "Cartoon", 0)))
        enqueueBody("""{"error":"Unauthorized"}""", code = 401)

        val result = service().syncCatalog()

        assertEquals(CatalogSyncResult.Unauthorized, result)
        assertEquals(listOf("Cartoon"), localCategoryNames())
        assertEquals(2L, repository.getMetadata()!!.catalogVersion)
    }

    @Test
    fun sync03_aTvWithNoSessionDoesNotEvenAsk() = runBlocking {
        installLocal(2L, listOf(category("cat-cartoon", "Cartoon", 0)))

        val result = service(sessionToken = null).syncCatalog()

        assertEquals(CatalogSyncResult.Unauthorized, result)
        assertEquals(0, server.requestCount)
        assertEquals(listOf("Cartoon"), localCategoryNames())
    }

    // ------------------------------------------------------------------ SYNC-04 / SYNC-09

    @Test
    fun sync04_anUnreadableResponseLeavesTheLocalCatalogUntouched() = runBlocking {
        installLocal(2L, listOf(category("cat-cartoon", "Cartoon", 0)))
        enqueueBody("<html><body>502 Bad Gateway</body></html>")

        val result = service().syncCatalog()

        assertTrue("expected InvalidResponse, got $result", result is CatalogSyncResult.InvalidResponse)
        assertEquals(listOf("Cartoon"), localCategoryNames())
        assertEquals(2L, repository.getMetadata()!!.catalogVersion)
    }

    @Test
    fun sync04_anEmptyBodyLeavesTheLocalCatalogUntouched() = runBlocking {
        installLocal(2L, listOf(category("cat-cartoon", "Cartoon", 0)))
        enqueueBody("")

        val result = service().syncCatalog()

        assertTrue(result is CatalogSyncResult.InvalidResponse)
        assertEquals(listOf("Cartoon"), localCategoryNames())
    }

    @Test
    fun sync09_anEmptyJsonObjectIsNotReadAsAnEmptyCatalog() = runBlocking {
        installLocal(5L, listOf(category("cat-cartoon", "Cartoon", 0)))
        enqueueBody("{}")

        val result = service().syncCatalog()

        assertTrue("{} must be malformed, not empty", result is CatalogSyncResult.InvalidResponse)
        assertEquals("the local catalog must survive a malformed empty response", listOf("Cartoon"), localCategoryNames())
        assertEquals(5L, repository.getMetadata()!!.catalogVersion)
    }

    @Test
    fun sync09_aJsonArrayOrNullBodyIsNotReadAsACatalog() = runBlocking {
        installLocal(5L, listOf(category("cat-cartoon", "Cartoon", 0)))

        listOf("[]", "null", "42").forEach { body ->
            enqueueBody(body)
            val result = service().syncCatalog()
            assertTrue("'$body' should be InvalidResponse, got $result", result is CatalogSyncResult.InvalidResponse)
        }

        assertEquals(listOf("Cartoon"), localCategoryNames())
        assertEquals(5L, repository.getMetadata()!!.catalogVersion)
    }

    // ------------------------------------------------------------------ SYNC-05

    @Test
    fun sync05_oneInvalidItemStopsTheWholePayloadFromBeingApplied() = runBlocking {
        installLocal(
            2L,
            listOf(
                category("cat-cartoon", "Cartoon", 0, listOf(playlistItem("i-cocomelon", "Cocomelon", 0, "PLcocomelon"))),
                category("cat-music", "Music", 1),
            ),
        )

        // Version 3 is mostly valid - and one item is not. Nothing from it may appear locally.
        enqueueCatalog(
            catalog(
                3L,
                listOf(
                    category("cat-learning", "Learning", 0, listOf(playlistItem("i-numbers", "Numbers", 0, "PLnumbers"))),
                    category("cat-music", "Music", 1, listOf(playlistItem("i-broken", "Broken", 0, "RDautoMix"))),
                ),
            )
        )

        val result = service().syncCatalog()

        assertTrue("expected InvalidCatalog, got $result", result is CatalogSyncResult.InvalidCatalog)
        val problems = (result as CatalogSyncResult.InvalidCatalog).problems
        assertTrue(
            "the refusal should name the field, got $problems",
            problems.any { it.location == "nodes[3].youtubePlaylistId" },
        )

        assertEquals("no part of version 3 may appear", listOf("Cartoon", "Music"), localCategoryNames())
        assertNull("the new shelf from the rejected payload must not exist", repository.getCategory("cat-learning"))
        assertNull("the rejected item must not exist", repository.getItem("i-broken"))
        assertEquals(1, localItems("cat-cartoon").size)

        val metadata = repository.getMetadata()!!
        assertEquals("the installed version must not move", 2L, metadata.catalogVersion)
        assertEquals("but the server version we saw is recorded", 3L, metadata.serverVersion)
        assertEquals(1_600_000_000_000L, metadata.lastSuccessfulSyncAt)
        assertEquals(now, metadata.lastAttemptAt)
    }

    // ------------------------------------------------------------------ SYNC-06

    @Test
    fun sync06_anOlderServerCatalogIsRefusedRatherThanDowngraded() = runBlocking {
        installLocal(5L, listOf(category("cat-cartoon", "Cartoon", 0)))
        enqueueCatalog(catalog(4L, listOf(category("cat-old", "Old Shelf", 0))))

        val result = service().syncCatalog()

        assertEquals(CatalogSyncResult.VersionRegression(serverVersion = 4L, localVersion = 5L), result)
        assertEquals("the newer local catalog must win", listOf("Cartoon"), localCategoryNames())
        assertEquals(5L, repository.getMetadata()!!.catalogVersion)
        assertEquals(1_600_000_000_000L, repository.getMetadata()!!.lastSuccessfulSyncAt)
    }

    // ------------------------------------------------------------------ SYNC-07

    @Test
    fun sync07_theSameVersionIsNotRewrittenEvenWhenTheServerContentDiffers() = runBlocking {
        installLocal(5L, listOf(category("cat-cartoon", "Cartoon", 0)), syncedAt = 1_600_000_000_000L)
        val before = repository.getCategories().single()

        // Same version, different content: the version is what decides, so the local rows must stand
        // exactly as they are - including their timestamps, which a rewrite would restamp.
        enqueueCatalog(catalog(5L, listOf(category("cat-different", "Different", 0))))

        val result = service().syncCatalog()

        assertEquals(CatalogSyncResult.AlreadyCurrent(5L), result)
        assertEquals(listOf("Cartoon"), localCategoryNames())
        val after = repository.getCategories().single()
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(before.updatedAt, after.updatedAt)

        val metadata = repository.getMetadata()!!
        assertEquals(5L, metadata.catalogVersion)
        assertEquals(5L, metadata.serverVersion)
        assertEquals("no replacement happened, so the success marker must not move", 1_600_000_000_000L, metadata.lastSuccessfulSyncAt)
        assertEquals(now, metadata.lastAttemptAt)
    }

    // ------------------------------------------------------------------ SYNC-08

    @Test
    fun sync08_anExplicitlyEmptyServerCatalogEmptiesTheLocalOne() = runBlocking {
        installLocal(
            5L,
            listOf(
                category("cat-cartoon", "Cartoon", 0, listOf(playlistItem("i-cocomelon", "Cocomelon", 0, "PLcocomelon"))),
            ),
        )
        enqueueCatalog(catalog(6L, emptyList()))

        val result = service().syncCatalog()

        assertEquals(CatalogSyncResult.Updated(6L), result)
        assertTrue(localCategoryNames().isEmpty())
        assertEquals(0, localItemCount())
        assertEquals(6L, repository.getMetadata()!!.catalogVersion)
    }

    // ------------------------------------------------------------------ SYNC-10

    @Test
    fun sync10_aFailureInsideTheLocalTransactionLeavesTheLastKnownGoodCatalog() = runBlocking {
        installLocal(
            2L,
            listOf(
                category("cat-cartoon", "Cartoon", 0, listOf(playlistItem("i-cocomelon", "Cocomelon", 0, "PLcocomelon"))),
            ),
        )

        // Make the database itself refuse one row of the incoming payload, halfway through the
        // replacement: everything before it has already been deleted inside the transaction.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER sync_test_failure BEFORE INSERT ON catalog_nodes " +
                "WHEN NEW.id = 'cat-boom' BEGIN SELECT RAISE(ABORT, 'simulated mid-replacement failure'); END"
        )

        enqueueCatalog(
            catalog(
                3L,
                listOf(
                    category("cat-learning", "Learning", 0),
                    category("cat-boom", "Boom", 1),
                ),
            )
        )

        val result = service().syncCatalog()

        assertTrue("expected LocalWriteFailed, got $result", result is CatalogSyncResult.LocalWriteFailed)
        assertTrue(
            (result as CatalogSyncResult.LocalWriteFailed).reason.contains("simulated mid-replacement failure"),
        )

        // The transaction rolled back: the version 2 catalog is exactly as it was, and none of the
        // version 3 rows - not even the ones inserted before the failure - survived.
        assertEquals(listOf("Cartoon"), localCategoryNames())
        assertEquals(1, localItems("cat-cartoon").size)
        assertNull(repository.getCategory("cat-learning"))
        assertEquals("the installed version must not move on a failed write", 2L, repository.getMetadata()!!.catalogVersion)
        assertEquals(1_600_000_000_000L, repository.getMetadata()!!.lastSuccessfulSyncAt)
    }

    // ------------------------------------------------------------------ §31 reordering

    @Test
    fun categoryOrderFromTheServerIsWhatTheTvShowsAndCanBeChangedByReordering() = runBlocking {
        enqueueCatalog(
            catalog(
                1L,
                listOf(
                    category("cat-cartoon", "Cartoon", 20),
                    category("cat-music", "Music", 5),
                    category("cat-learning", "Learning", 10),
                ),
            )
        )
        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())
        assertEquals(listOf("Music", "Learning", "Cartoon"), localCategoryNames())

        // The parent reorders: order is configuration data, not insertion order.
        enqueueCatalog(
            catalog(
                2L,
                listOf(
                    category("cat-cartoon", "Cartoon", 10),
                    category("cat-music", "Music", 20),
                    category("cat-learning", "Learning", 0),
                ),
            )
        )
        assertEquals(CatalogSyncResult.Updated(2L), service().syncCatalog())

        assertEquals(listOf("Learning", "Cartoon", "Music"), localCategoryNames())
        // The order the server configured is what the TV shows. The node tree stores that order as
        // contiguous positions 0..n-1 rather than the server's arbitrary 0/10/20 values, because
        // `parent_id + position` with no gaps is the only ordering mechanism the tree has - the
        // relative order is what the parent configured, and it is preserved exactly.
        assertEquals(listOf(0, 1, 2), repository.getCategories().map { it.sortOrder })
    }

    @Test
    fun duplicateSortOrdersStayDeterministicAfterSync() = runBlocking {
        enqueueCatalog(
            catalog(
                1L,
                listOf(
                    category("cat-b", "B", 0),
                    category("cat-a", "A", 0),
                ),
            )
        )

        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        // Both at 0, so the id tie-break decides - the same rule Phase 2 chose.
        assertEquals(listOf("A", "B"), localCategoryNames())
    }

    // ------------------------------------------------------------------ §32 mixed content

    @Test
    fun mixedPlaylistAndVideoContentSurvivesSyncInTheConfiguredOrder() = runBlocking {
        enqueueCatalog(
            catalog(
                1L,
                listOf(
                    category(
                        "cat-music",
                        "Music",
                        0,
                        listOf(
                            playlistItem("i-nursery", "Nursery Songs", 0, "PLnursery"),
                            playlistItem("i-abc", "ABC Songs", 1, "PLabc"),
                            videoItem("i-twinkle", "Twinkle Twinkle", 2, "DuXwFlL8Usk"),
                            videoItem("i-wheels", "Wheels on Bus", 3, "vidwheels"),
                        ),
                    ),
                ),
            )
        )

        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        // Read back through the real Room query, not through the in-memory payload.
        val rows = localItems("cat-music")
        assertEquals(
            listOf("Nursery Songs", "ABC Songs", "Twinkle Twinkle", "Wheels on Bus"),
            rows.map { it.displayName },
        )
        assertEquals(
            listOf(
                ContentItemType.PLAYLIST, ContentItemType.PLAYLIST,
                ContentItemType.VIDEO, ContentItemType.VIDEO,
            ),
            rows.map { it.type },
        )
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.sortOrder })
        assertEquals("PLnursery", rows[0].youtubePlaylistId)
        assertNull(rows[0].youtubeVideoId)
        assertEquals("DuXwFlL8Usk", rows[2].youtubeVideoId)
        assertNull(rows[2].youtubePlaylistId)
    }

    @Test
    fun itemOrderFromTheServerIsWhatTheTvShowsEvenWhenTheNodesArriveOutOfOrder() = runBlocking {
        // The nodes array is deliberately scrambled, and the positions are canonical: what decides the
        // order the TV renders is the position the parent configured, never the order the document
        // happened to list its nodes in.
        enqueueCatalog(
            CatalogSnapshot(
                schemaVersion = CATALOG_SCHEMA_VERSION,
                catalogVersion = 1L,
                nodes = listOf(
                    CatalogNodeDto(
                        id = "cat-music", parentId = null, nodeType = CATALOG_NODE_TYPE_CATEGORY,
                        title = "Music", position = 0,
                    ),
                ) + listOf(
                    videoItem("i-wheels", "Wheels on Bus", 3, "vidwheels"),
                    playlistItem("i-abc", "ABC Songs", 1, "PLabc"),
                    videoItem("i-twinkle", "Twinkle Twinkle", 2, "DuXwFlL8Usk"),
                    playlistItem("i-nursery", "Nursery Songs", 0, "PLnursery"),
                ).map { it.copy(parentId = "cat-music") },
            )
        )

        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        assertEquals(
            listOf("Nursery Songs", "ABC Songs", "Twinkle Twinkle", "Wheels on Bus"),
            localItems("cat-music").map { it.displayName },
        )
        assertEquals(listOf(0, 1, 2, 3), localItems("cat-music").map { it.sortOrder })
    }

    @Test
    fun disabledFlagsAndCustomIdsSurviveSync() = runBlocking {
        enqueueCatalog(
            catalog(
                1L,
                listOf(
                    category(
                        "cat-music",
                        "Music",
                        0,
                        listOf(playlistItem("i-nursery", "Nursery Songs", 0, "PLnursery", enabled = false)),
                        enabled = false,
                    ),
                ),
            )
        )

        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        assertFalse(repository.getCategory("cat-music")!!.enabled)
        assertFalse(repository.getItem("i-nursery")!!.enabled)
        assertEquals("cat-music", repository.getItem("i-nursery")!!.categoryId)
    }

    // ------------------------------------------------------------------ §33 display names

    @Test
    fun theParentDisplayNameIsNotDerivedFromTheYoutubeTitleOrId() = runBlocking {
        enqueueCatalog(
            catalog(
                1L,
                listOf(
                    category(
                        "cat-music",
                        "Music",
                        0,
                        listOf(
                            playlistItem("i-nursery", "Nursery Songs", 0, "PLsuperlongactualtitle"),
                        ),
                    ),
                ),
            )
        )

        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        val item = repository.getItem("i-nursery")!!
        assertEquals("Nursery Songs", item.displayName)
        assertEquals("PLsuperlongactualtitle", item.youtubePlaylistId)
        assertFalse(item.displayName.contains(item.youtubePlaylistId!!))
    }

    // ---------------------------------------------------- §23 the picture a container shows
    //
    // A picture is configuration: it travels as part of the same document, is stored in the same rows
    // as everything else, and decides nothing about playback. These tests sync documents and then read
    // the *database* back, because the point is that the choice is stored rather than remembered.

    /** A shelf holding a subcategory, which holds two videos: the shape a picture is chosen in. */
    private fun shelfWithTwoVideos(
        thumbnailMode: String = CATALOG_THUMBNAIL_MODE_AUTO,
        thumbnailVideoId: String? = null,
        secondEnabled: Boolean = true,
    ) = listOf(
        CatalogNodeDto(
            id = "cat-cartoon", parentId = null, nodeType = CATALOG_NODE_TYPE_CATEGORY,
            title = "Cartoons", position = 0,
        ),
        CatalogNodeDto(
            id = "i-nursery", parentId = "cat-cartoon", nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
            title = "Nursery", position = 0,
            thumbnailMode = thumbnailMode, thumbnailVideoId = thumbnailVideoId,
        ),
        CatalogNodeDto(
            id = "i-first", parentId = "i-nursery", nodeType = CATALOG_NODE_TYPE_VIDEO,
            title = "First", position = 0, youtubeVideoId = "vidFirst",
        ),
        CatalogNodeDto(
            id = "i-second", parentId = "i-nursery", nodeType = CATALOG_NODE_TYPE_VIDEO,
            title = "Second", position = 1, youtubeVideoId = "vidSecond", enabled = secondEnabled,
        ),
    )

    /** The stored tree, read the way the home screen reads it. */
    private fun storedTree(): List<CatalogNodeEntity> = runBlocking { CatalogNodeRepository(db).tree() }

    private fun enqueueShelf(version: Long, nodes: List<CatalogNodeDto>) {
        enqueueCatalog(CatalogSnapshot(CATALOG_SCHEMA_VERSION, version, canonicalNodes(listOf(nodes))))
    }

    @Test
    fun aPictureChosenByTheParentArrivesWithTheCatalogAndIsStoredWithIt() = runBlocking {
        enqueueShelf(1L, shelfWithTwoVideos(CATALOG_THUMBNAIL_MODE_VIDEO, "i-second"))

        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        val stored = CatalogNodeRepository(db).node("i-nursery")!!
        assertEquals(ThumbnailMode.VIDEO, stored.thumbnailMode)
        assertEquals("i-second", stored.thumbnailVideoId)
        assertNull("no URL is ever stored for a picture", stored.thumbnailUrl)

        val rows = storedTree()
        assertEquals("vidSecond", CatalogThumbnails.representativeFor(rows, "i-nursery"))
        assertEquals(
            "the shelf above it is a container in its own right, so it is still automatic for itself",
            "vidFirst", CatalogThumbnails.representativeFor(rows, "cat-cartoon"),
        )
    }

    @Test
    fun theStoredPictureIsReadBackFromTheDatabaseAfterARestartRatherThanRemembered() = runBlocking {
        enqueueShelf(1L, shelfWithTwoVideos(CATALOG_THUMBNAIL_MODE_VIDEO, "i-second"))
        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        // A restart: nothing of the sync is held in memory, so everything below comes from the rows.
        val afterRestart = CatalogNodeRepository(db).tree()
        val representatives = CatalogThumbnails.representatives(afterRestart)

        assertEquals("vidSecond", representatives["i-nursery"])
        assertNull(
            "and the category is not answered at all: a category is a title with no picture (W6.1)",
            representatives["cat-cartoon"],
        )
    }

    @Test
    fun aPictureEditIsAppliedInPlaceAndChangesNothingElseAboutTheNode() = runBlocking {
        enqueueShelf(1L, shelfWithTwoVideos())
        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        val before = CatalogNodeRepository(db).node("i-nursery")!!
        enqueueShelf(2L, shelfWithTwoVideos(CATALOG_THUMBNAIL_MODE_VIDEO, "i-second"))

        assertEquals(CatalogSyncResult.Updated(2L), service().syncCatalog())

        val after = CatalogNodeRepository(db).node("i-nursery")!!
        // Same row, same identity, same content - only the picture columns moved.
        assertEquals(before.id, after.id)
        assertEquals(before.parentId, after.parentId)
        assertEquals(before.title, after.title)
        assertEquals(before.position, after.position)
        assertEquals(before.enabled, after.enabled)
        assertEquals(before.youtubePlaylistId, after.youtubePlaylistId)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(ThumbnailMode.VIDEO, after.thumbnailMode)
        assertEquals("i-second", after.thumbnailVideoId)
    }

    @Test
    fun hidingTheChosenVideoKeepsTheChoiceAndFallsBackToTheNextPicture() = runBlocking {
        enqueueShelf(1L, shelfWithTwoVideos(CATALOG_THUMBNAIL_MODE_VIDEO, "i-second"))
        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        enqueueShelf(2L, shelfWithTwoVideos(CATALOG_THUMBNAIL_MODE_VIDEO, "i-second", secondEnabled = false))
        assertEquals(CatalogSyncResult.Updated(2L), service().syncCatalog())

        val stored = CatalogNodeRepository(db).node("i-nursery")!!
        assertEquals("hiding is reversible, so the choice is kept", "i-second", stored.thumbnailVideoId)
        assertEquals(
            "and while it is hidden the first video still shown stands in",
            "vidFirst", CatalogThumbnails.representativeFor(storedTree(), "i-nursery"),
        )
    }

    @Test
    fun aPictureNamingNothingIsRefusedWholesaleAndTheLocalCatalogIsUntouched() = runBlocking {
        installLocal(1L, listOf(category("cat-cartoon", "Cartoon", 0)))
        enqueueShelf(2L, shelfWithTwoVideos(CATALOG_THUMBNAIL_MODE_VIDEO, "i-ghost"))

        val result = service().syncCatalog()

        assertTrue("expected InvalidCatalog, got $result", result is CatalogSyncResult.InvalidCatalog)
        assertTrue(
            (result as CatalogSyncResult.InvalidCatalog).problems.any { it.location == "nodes[1].thumbnailVideoId" },
        )
        assertEquals("no part of version 2 may appear", listOf("Cartoon"), localCategoryNames())
        assertNull(CatalogNodeRepository(db).node("i-nursery"))
        assertEquals(1L, repository.getMetadata()!!.catalogVersion)
    }

    @Test
    fun aPictureNamingAVideoOutsideItsContainerIsRefusedAsWell() = runBlocking {
        installLocal(1L, listOf(category("cat-cartoon", "Cartoon", 0)))

        enqueueShelf(
            2L,
            listOf(
                CatalogNodeDto(
                    id = "cat-cartoon", parentId = null, nodeType = CATALOG_NODE_TYPE_CATEGORY,
                    title = "Cartoons", position = 0,
                ),
                CatalogNodeDto(
                    id = "i-nursery", parentId = "cat-cartoon", nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
                    title = "Nursery", position = 0,
                    thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = "i-elsewhere",
                ),
                CatalogNodeDto(
                    id = "i-elsewhere", parentId = "cat-cartoon", nodeType = CATALOG_NODE_TYPE_VIDEO,
                    title = "Elsewhere", position = 1, youtubeVideoId = "vidElsewhere",
                ),
            ),
        )

        assertTrue(service().syncCatalog() is CatalogSyncResult.InvalidCatalog)
        assertNull("the container must not exist after a refusal", CatalogNodeRepository(db).node("i-nursery"))
    }

    @Test
    fun changingAPictureDisturbsNoPlaylistMaterialisationAndNoItem() = runBlocking {
        val curated = listOf(
            CatalogNodeDto(
                id = "i-curated", parentId = "cat-cartoon", nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
                title = "Curated", position = 1,
            ),
            CatalogNodeDto(
                id = "i-a", parentId = "i-curated", nodeType = CATALOG_NODE_TYPE_VIDEO,
                title = "A", position = 0, youtubeVideoId = "vidA",
            ),
            CatalogNodeDto(
                id = "i-b", parentId = "i-curated", nodeType = CATALOG_NODE_TYPE_VIDEO,
                title = "B", position = 1, youtubeVideoId = "vidB",
            ),
        )
        val imported = { category("cat-cartoon", "Cartoon", 0, listOf(playlistItem("i-cocomelon", "Cocomelon", 0, "PLcocomelon"))) }

        enqueueShelf(1L, imported() + curated)
        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        val before = storedTree().map { Triple(it.id, it.title, it.nodeType) }
        val beforeCount = localItemCount()

        // Version 2 changes one thing: which video stands for the curated subcategory.
        enqueueShelf(
            2L,
            imported() + curated.map {
                if (it.id == "i-curated") {
                    it.copy(thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = "i-b")
                } else {
                    it
                }
            },
        )
        assertEquals(CatalogSyncResult.Updated(2L), service().syncCatalog())

        assertEquals("the tree the child browses is exactly the same", before, storedTree().map { Triple(it.id, it.title, it.nodeType) })
        assertEquals(beforeCount, localItemCount())
        assertEquals("vidB", CatalogThumbnails.representativeFor(storedTree(), "i-curated"))
    }

    // ------------------------------------------------------------------ §34 last known good

    @Test
    fun lastKnownGood_theLastGoodCatalogSurvivesAFailedSyncAndIsReplacedByTheNextGoodOne() = runBlocking {
        installLocal(
            10L,
            listOf(
                category("cat-cartoon", "Cartoon", 0),
                category("cat-music", "Music", 1),
            ),
        )

        // The server moves to 11, and 11 is partly broken.
        enqueueCatalog(
            catalog(
                11L,
                listOf(
                    category("cat-learning", "Learning", 0),
                    category("cat-music", "Music", 1),
                    category("cat-music", "Music Again", 2, listOf(playlistItem("i-bad", "Bad", 0, "RDautoMix"))),
                ),
            )
        )

        val failed = service().syncCatalog()
        assertTrue("expected InvalidCatalog, got $failed", failed is CatalogSyncResult.InvalidCatalog)

        assertEquals("local version must stay 10", 10L, repository.getMetadata()!!.catalogVersion)
        assertEquals(listOf("Cartoon", "Music"), localCategoryNames())
        assertNull("nothing from version 11 may partially appear", repository.getCategory("cat-learning"))
        assertEquals("server version 11 is recorded even though we did not take it", 11L, repository.getMetadata()!!.serverVersion)

        // The parent fixes it and publishes 12.
        enqueueCatalog(
            catalog(
                12L,
                listOf(
                    category("cat-learning", "Learning", 0),
                    category("cat-music", "Music", 1),
                    category("cat-cartoon", "Cartoon", 2),
                ),
            )
        )

        val recovered = service().syncCatalog()

        assertEquals(CatalogSyncResult.Updated(12L), recovered)
        assertEquals(12L, repository.getMetadata()!!.catalogVersion)
        assertEquals(listOf("Learning", "Music", "Cartoon"), localCategoryNames())
        assertEquals(now, repository.getMetadata()!!.lastSuccessfulSyncAt)
    }

    // ------------------------------------------------------------------ §21 schema version

    @Test
    fun anUnsupportedSchemaVersionIsRefusedWithoutTouchingTheLocalCatalog() = runBlocking {
        installLocal(2L, listOf(category("cat-cartoon", "Cartoon", 0)))
        enqueueCatalog(catalog(3L, listOf(category("cat-new", "New", 0)), schemaVersion = 999))

        val result = service().syncCatalog()

        assertEquals(
            CatalogSyncResult.UnsupportedSchema(serverSchemaVersion = 999, supportedSchemaVersion = 2),
            result,
        )
        assertEquals(listOf("Cartoon"), localCategoryNames())
        assertEquals(2L, repository.getMetadata()!!.catalogVersion)
        assertEquals("an unreadable contract cannot even be counted as a seen version", null, repository.getMetadata()!!.serverVersion)
    }

    // ------------------------------------------------------------------ the network boundary

    @Test
    fun theFetchUsesTheExistingBearerSessionAgainstTheCatalogPath() = runBlocking {
        enqueueCatalog(catalog(1L, emptyList()))

        service().syncCatalog()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/catalog", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun theSyncTimestampsComeFromTheClock() = runBlocking {
        enqueueCatalog(catalog(1L, emptyList()))
        now = 1_800_000_000_000L

        service().syncCatalog()

        val metadata = repository.getMetadata()!!
        assertEquals(1_800_000_000_000L, metadata.lastSuccessfulSyncAt)
        assertEquals(1_800_000_000_000L, metadata.lastAttemptAt)
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    fun twoOverlappingSyncsNeverRunAtTheSameTime() = runBlocking {
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        // The fake suspends, which is exactly what lets a second call get in: without the service's
        // mutex the second one enters while the first is still waiting on the server.
        val api = object : CatalogApi {
            override val catalogPath = "/catalog"
            override suspend fun fetch(): CatalogFetch {
                concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, concurrent.get()) }
                try {
                    delay(150)
                } finally {
                    concurrent.decrementAndGet()
                }
                return CatalogFetch.Ok(catalog(1L, listOf(category("cat-a", "A", 0))))
            }
        }
        val shared = service(api = api)

        val results = listOf(
            async { shared.syncCatalog() },
            async { shared.syncCatalog() },
        ).awaitAll()

        assertEquals("syncs must not overlap", 1, maxConcurrent.get())
        assertTrue("both calls should complete successfully, got $results", results.all { it.isSuccess })
        assertTrue(
            "the second call must have seen the first one's result, not raced it",
            results.any { it == CatalogSyncResult.Updated(1L) },
        )
        assertEquals(listOf("A"), localCategoryNames())
        assertEquals(1L, repository.getMetadata()!!.catalogVersion)
    }

    // ------------------------------------------------------------------ referential integrity

    @Test
    fun everySyncedNodeBelongsToANodeThatWasStoredWithIt() = runBlocking {
        enqueueCatalog(
            catalog(
                1L,
                listOf(
                    category("cat-a", "A", 0, listOf(playlistItem("i1", "One", 0, "PLone"))),
                    category("cat-b", "B", 1, listOf(videoItem("i2", "Two", 0, "vidtwo"))),
                ),
            )
        )

        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        val tree = repository.getTree()
        val ids = tree.map { it.id }.toSet()
        tree.filter { it.parentId != null }.forEach { node ->
            assertTrue(
                "'${node.id}' points at parent '${node.parentId}', which was not stored",
                ids.contains(node.parentId),
            )
        }

        // Referential integrity through the derived views too: every entry resolves to a stored shelf.
        val categoryIds = repository.getCategories().map { it.id }.toSet()
        val itemCategoryIds = localAllItems().map { it.categoryId }.toSet()
        assertEquals(2, itemCategoryIds.size)
        assertTrue(
            "every stored item must point at a stored category",
            categoryIds.containsAll(itemCategoryIds),
        )
    }

    @Test
    fun aDocumentBuiltByTheEditorInstallsAsTheSameHierarchyTheServerHolds() = runBlocking {
        // What the dashboard editor publishes: a shelf holding a container and a direct video, and two
        // videos inside the container - the shape a two-level catalog could not describe.
        enqueueCatalog(
            CatalogSnapshot(
                schemaVersion = CATALOG_SCHEMA_VERSION,
                catalogVersion = 1L,
                nodes = listOf(
                    CatalogNodeDto(
                        id = "cat-cartoon", parentId = null, nodeType = CATALOG_NODE_TYPE_CATEGORY,
                        title = "Cartoons", position = 0,
                    ),
                    CatalogNodeDto(
                        id = "i-cocomelon", parentId = "cat-cartoon", nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
                        title = "Cocomelon", position = 0,
                    ),
                    videoItem("i-cocomelon#a", "Video A", 0, "vidA").copy(parentId = "i-cocomelon"),
                    videoItem("i-cocomelon#b", "Video B", 1, "vidB").copy(parentId = "i-cocomelon"),
                    videoItem("i-direct", "Direct Video", 1, "vidDirect").copy(parentId = "cat-cartoon"),
                ),
            )
        )

        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        // The TV holds the same tree the server described: the shelf, its two children - a container
        // and a direct video, in the configured order - and the container's own videos.
        val tree = repository.getTree()
        assertEquals(
            listOf("i-cocomelon" to 0, "i-direct" to 1),
            tree.filter { it.parentId == "cat-cartoon" }.sortedBy { it.position }.map { it.id to it.position },
        )
        assertEquals(
            listOf("i-cocomelon#a" to 0, "i-cocomelon#b" to 1),
            tree.filter { it.parentId == "i-cocomelon" }.sortedBy { it.position }.map { it.id to it.position },
        )
        assertEquals(listOf("Cartoons"), localCategoryNames())
        assertEquals("the direct video is a card of its own", 2, localItems("cat-cartoon").size)

        // And the child sees exactly that hierarchy: the shelf holds a *container* card called
        // Cocomelon and a video card called Direct Video - never "Video A" in the container's place.
        val home = CatalogUiProjection.build(
            tree = tree,
            thumbnails = repository.observeVideoThumbnails().first(),
            resumable = emptyList(),
        )
        val shelf = home.shelves.single()
        assertEquals("Cartoons", shelf.title)
        assertEquals(listOf("Cocomelon", "Direct Video"), shelf.cards.map { it.title })
        assertEquals(
            listOf(CatalogCardKind.CONTAINER, CatalogCardKind.VIDEO),
            shelf.cards.map { it.kind },
        )
        assertEquals("i-cocomelon", shelf.cards[0].containerId)
        assertNull("a container card names no video to play", shelf.cards[0].videoId)
        assertEquals("vidDirect", shelf.cards[1].videoId)

        val opened = CatalogUiProjection.container(
            containerId = "i-cocomelon",
            tree = tree,
            thumbnails = repository.observeVideoThumbnails().first(),
        )!!
        assertEquals("Cocomelon", opened.title)
        assertEquals(listOf("Video A", "Video B"), opened.cards.map { it.title })
    }

    @Test
    fun anImportedPlaylistArrivesAsOrdinaryVideosAndGrantsNothing() = runBlocking {
        // What a W4 playlist import publishes: videos appended under a shelf, one of them hidden
        // because its source playlist dropped it. No playlist node, no container invented for it.
        enqueueCatalog(
            CatalogSnapshot(
                schemaVersion = CATALOG_SCHEMA_VERSION,
                catalogVersion = 1L,
                nodes = listOf(
                    CatalogNodeDto(
                        id = "cat-cartoon", parentId = null, nodeType = CATALOG_NODE_TYPE_CATEGORY,
                        title = "Cartoons", position = 0,
                    ),
                    videoItem("i-first", "First", 0, "vidFirst").copy(
                        parentId = "cat-cartoon", youtubePlaylistId = "PLimported",
                    ),
                    videoItem("i-second", "Second", 1, "vidSecond").copy(
                        parentId = "cat-cartoon", youtubePlaylistId = "PLimported", enabled = false,
                    ),
                ),
            )
        )

        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        // The videos are ordinary catalog videos under the shelf - and the playlist is nowhere.
        val tree = repository.getTree()
        assertEquals(listOf("i-first", "i-second"), tree.filter { it.parentId == "cat-cartoon" }.map { it.id })
        assertEquals(
            listOf(CatalogNodeType.VIDEO, CatalogNodeType.VIDEO),
            tree.filter { it.parentId == "cat-cartoon" }.map { it.nodeType },
        )
        assertTrue("no node was created for the playlist", tree.none { it.id == "PLimported" })

        // The disabled one is stored but not shown to the child.
        assertEquals(false, tree.single { it.id == "i-second" }.enabled)
        assertEquals(1, CatalogUiProjection.build(
            tree = tree,
            thumbnails = repository.observeVideoThumbnails().first(),
            resumable = repository.observeResumableVideos(20_000, 95).first(),
        ).shelves.single().cards.size)

        // And being in the catalog is not permission: neither imported video can play, because
        // neither is in the approved cache. A playlist import approves nothing.
        assertTrue(PlaybackAuthorization.authorize(db, "vidFirst") is PlaybackApproval.Rejected)
        assertTrue(PlaybackAuthorization.authorize(db, "vidSecond") is PlaybackApproval.Rejected)
    }

    @Test
    fun importingMoreVideosDoesNotDisturbWhatIsAlreadyWatched() = runBlocking {
        // An approved source with one approved video, half watched.
        db.channelDao().insert(
            ChannelEntity(
                sourceType = "yt_playlist",
                sourceId = "PLapproved",
                sourceUrl = "https://www.youtube.com/playlist?list=PLapproved",
                displayName = "Approved",
            )
        )
        db.videoDao().insertAll(listOf(VideoEntity("vidKept", "PLapproved", "Kept", "thumb", 60, 0)))
        db.playbackPositionDao().upsert(PlaybackPositionEntity("vidKept", 30_000, 60_000, 100))
        db.playEventDao().insert(
            tv.safetubeforkids.app.data.events.PlayEventEntity(
                videoId = "vidKept", playlistId = "PLapproved", startedAt = 100,
            )
        )
        installLocal(1L, listOf(category("cat-cartoon", "Cartoons", 0, listOf(videoItem("i-kept", "Kept", 0, "vidKept")))))
        val keptNode = repository.getTree().single { it.id == "i-kept" }

        // The parent imports a playlist into the same shelf: two more videos, in playlist order.
        enqueueCatalog(
            CatalogSnapshot(
                schemaVersion = CATALOG_SCHEMA_VERSION,
                catalogVersion = 2L,
                nodes = listOf(
                    CatalogNodeDto(
                        id = "cat-cartoon", parentId = null, nodeType = CATALOG_NODE_TYPE_CATEGORY,
                        title = "Cartoons", position = 0,
                    ),
                    CatalogNodeDto(
                        id = "i-kept", parentId = "cat-cartoon", nodeType = CATALOG_NODE_TYPE_VIDEO,
                        title = "Kept", position = 0, youtubeVideoId = "vidKept",
                    ),
                    videoItem("i-new-a", "New A", 1, "vidNewA").copy(parentId = "cat-cartoon"),
                    videoItem("i-new-b", "New B", 2, "vidNewB").copy(parentId = "cat-cartoon"),
                ),
            )
        )

        assertEquals(CatalogSyncResult.Updated(2L), service().syncCatalog())

        // The video that was already there is the same node it was: same id, same position, and its
        // resume point untouched. An import is not a re-import of history.
        val afterKept = repository.getTree().single { it.id == "i-kept" }
        assertEquals(keptNode.id, afterKept.id)
        assertEquals(keptNode.position, afterKept.position)
        assertEquals(keptNode.createdAt, afterKept.createdAt)
        assertEquals(30_000L, db.playbackPositionDao().get("vidKept")!!.positionMs)
        assertEquals(1, db.playEventDao().count())

        // And the two new videos are appended after it, with nothing else disturbed.
        assertEquals(
            listOf("i-kept", "i-new-a", "i-new-b"),
            repository.getTree().filter { it.parentId == "cat-cartoon" }.sortedBy { it.position }.map { it.id },
        )
        assertEquals("no fake watch history appeared", 1, db.playEventDao().count())
    }

    @Test
    fun aVersion1ResponseIsRefusedRatherThanReadAsTheNodeTree() = runBlocking {        installLocal(2L, listOf(category("cat-cartoon", "Cartoon", 0)))

        // A server that still speaks contract version 1: its document has no `nodes`, so it is not a
        // version-2 catalog. Reading it as one would install a tree the parent never configured.
        enqueueBody(
            """
            {"schemaVersion":1,"catalogVersion":9,"categories":[
              {"id":"cat-new","displayName":"New","sortOrder":0,"enabled":true,"items":[]}]}
            """.trimIndent()
        )

        val result = service().syncCatalog()

        assertTrue("expected an unreadable response, got $result", result is CatalogSyncResult.InvalidResponse)
        assertEquals(listOf("Cartoon"), localCategoryNames())
        assertEquals(2L, repository.getMetadata()!!.catalogVersion)
    }

    // ------------------------------------------------------------------ §28 security boundary

    @Test
    fun aSyncedCatalogEntryDoesNotGrantPlaybackPermission() = runBlocking {
        val unapproved = "UNAPPROVED_VIDEO"

        // The parent configures a shelf pointing at a video nobody has approved.
        enqueueCatalog(
            catalog(
                1L,
                listOf(
                    category(
                        "cat-music",
                        "Music",
                        0,
                        listOf(videoItem("i-rogue", "Looks Innocent", 0, unapproved)),
                    ),
                ),
            )
        )
        assertEquals(CatalogSyncResult.Updated(1L), service().syncCatalog())

        // It is in the local catalog...
        assertEquals(unapproved, repository.getItem("i-rogue")!!.youtubeVideoId)

        // ...and it still does not play.
        assertTrue(
            "a catalog entry must not be able to grant playback",
            PlaybackAuthorization.authorize(db, unapproved) is PlaybackApproval.Rejected,
        )

        // Approve it the way a parent does - through the existing approval model.
        db.channelDao().insert(
            ChannelEntity(
                sourceType = "yt_playlist",
                sourceId = "PLapproved",
                sourceUrl = "https://www.youtube.com/playlist?list=PLapproved",
                displayName = "Approved",
            )
        )
        db.videoDao().insertAll(
            listOf(VideoEntity(unapproved, "PLapproved", "Approved Video", "thumb", 60, 0)),
        )

        val approved = PlaybackAuthorization.authorize(db, unapproved)
        assertTrue("approval must still work", approved is PlaybackApproval.Approved)
        assertEquals("PLapproved", (approved as PlaybackApproval.Approved).sourceId)

        // Take the approval away again, leaving the catalog entry exactly where it was.
        db.channelDao().deleteAll()

        assertNotNull("the catalog entry is untouched by an approval change", repository.getItem("i-rogue"))
        assertTrue(
            "removing approval must revoke playback even though the catalog still lists the video",
            PlaybackAuthorization.authorize(db, unapproved) is PlaybackApproval.Rejected,
        )
    }

    // ------------------------------------------------------------------ no server needed

    @Test
    fun theLocalCatalogStaysReadableWithTheServerGone() = runBlocking {
        enqueueCatalog(
            catalog(1L, listOf(category("cat-music", "Music", 0, listOf(playlistItem("i1", "Nursery", 0, "PLn"))))),
        )
        service().syncCatalog()

        server.shutdown()

        // A later sync fails, and reading is unaffected: the TV runs from Room.
        assertTrue(service().syncCatalog() is CatalogSyncResult.ServerUnavailable)
        assertEquals(listOf("Music"), repository.observeCategories().first().map { it.displayName })
        assertEquals(listOf("Nursery"), repository.getItems("cat-music").map { it.displayName })
    }
}
