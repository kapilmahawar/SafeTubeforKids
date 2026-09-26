package tv.parentapproved.app.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.catalog.CATALOG_NODE_TYPE_CATEGORY
import tv.safetubeforkids.app.data.catalog.CATALOG_NODE_TYPE_SUBCATEGORY
import tv.safetubeforkids.app.data.catalog.CATALOG_NODE_TYPE_VIDEO
import tv.safetubeforkids.app.data.catalog.CATALOG_SCHEMA_VERSION
import tv.safetubeforkids.app.data.catalog.CATALOG_THUMBNAIL_MODE_AUTO
import tv.safetubeforkids.app.data.catalog.CATALOG_THUMBNAIL_MODE_VIDEO
import tv.safetubeforkids.app.data.catalog.CatalogJson
import tv.safetubeforkids.app.data.catalog.CatalogNodeDto
import tv.safetubeforkids.app.data.catalog.CatalogPutRequest
import tv.safetubeforkids.app.data.catalog.CatalogSnapshot
import tv.safetubeforkids.app.server.CatalogStore
import tv.safetubeforkids.app.server.FileCatalogStore
import tv.safetubeforkids.app.server.catalogRoutes

/**
 * The editor's writes, end to end against the real routes and a real file-backed store: **every
 * mutation a parent can make** is published as one document, and every one of them is then read back
 * both from the same server and from a *restarted* one - a brand-new store object over the same
 * directory, which is all a restarted server process is.
 *
 * The editor itself (the working copy and the tree operations) is a browser module tested in
 * `tv-app/scripts/dashboard-catalog-editor.test.js`; what belongs here is what has to hold on the
 * server no matter who produced the document: the version moves by exactly one, the catalog that was
 * committed is the catalog that is served, an unchanged or invalid write changes nothing, and a stale
 * writer cannot overwrite a newer catalog.
 */
class CatalogEditorPersistenceTest {

    private lateinit var dir: File
    private var currentTime = 1_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("catalog-editor-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A store over the editor test's directory. A second call over the same directory is a restart. */
    private fun store() = FileCatalogStore.inFilesDir(dir)

    private fun testApp(
        store: CatalogStore,
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

    // --- fixtures ----------------------------------------------------------------------------
    //
    // The documents below are what the editor sends: canonical positions (0..n-1 per parent), one
    // schema version, and nodes that carry their own timestamps.

    private val createdAt = 1_700_000_000_000L

    private fun shelf(id: String, title: String, position: Int = 0, enabled: Boolean = true) =
        CatalogNodeDto(
            id = id, parentId = null, nodeType = CATALOG_NODE_TYPE_CATEGORY, title = title,
            position = position, enabled = enabled, createdAt = createdAt, updatedAt = createdAt,
        )

    private fun container(
        id: String,
        parentId: String,
        title: String,
        position: Int = 0,
        enabled: Boolean = true,
        playlistId: String? = null,
    ) = CatalogNodeDto(
        id = id, parentId = parentId, nodeType = CATALOG_NODE_TYPE_SUBCATEGORY, title = title,
        position = position, enabled = enabled, youtubePlaylistId = playlistId,
        createdAt = createdAt, updatedAt = createdAt,
    )

    private fun video(
        id: String,
        parentId: String,
        title: String,
        position: Int = 0,
        videoId: String = "DuXwFlL8Usk",
        enabled: Boolean = true,
    ) = CatalogNodeDto(
        id = id, parentId = parentId, nodeType = CATALOG_NODE_TYPE_VIDEO, title = title,
        position = position, enabled = enabled, youtubeVideoId = videoId,
        createdAt = createdAt, updatedAt = createdAt,
    )

    private fun document(vararg nodes: CatalogNodeDto) =
        CatalogSnapshot(CATALOG_SCHEMA_VERSION, 0L, nodes.toList())

    // --- talking to the server over its routes ------------------------------------------------

    private suspend fun ApplicationTestBuilder.publish(
        token: String,
        snapshot: CatalogSnapshot,
        expectedVersion: Long?,
    ): HttpResponse = client.put("/catalog") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(
            CatalogJson.encodePutRequest(
                CatalogPutRequest(
                    schemaVersion = CATALOG_SCHEMA_VERSION,
                    expectedCatalogVersion = expectedVersion,
                    nodes = snapshot.nodes,
                )
            )
        )
    }

    private suspend fun ApplicationTestBuilder.read(token: String): CatalogSnapshot {
        val response = client.get("/catalog") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, response.status)
        return CatalogJson.decodeSnapshot(response.bodyAsText())!!
    }

    /**
     * The shape every mutation test follows: publish a catalog, publish the edited one, check both the
     * answer and what the server now serves, then do it all again on a restarted server.
     *
     * Each call gets its own directory, so one test can walk through several mutations without the
     * version of one leaking into the next.
     */
    private fun mutationIsCommittedAndSurvivesARestart(
        what: String,
        before: List<CatalogNodeDto>,
        after: List<CatalogNodeDto>,
        check: (CatalogSnapshot) -> Unit = {},
    ) = runBlocking {
        val caseDir = Files.createTempDirectory("catalog-editor-case").toFile()
        try {
            testApp(FileCatalogStore.inFilesDir(caseDir)) { _, token ->
                assertEquals("$what: the starting catalog must be stored", HttpStatusCode.OK, publish(token, document(*before.toTypedArray()), expectedVersion = 0).status)

                val response = publish(token, document(*after.toTypedArray()), expectedVersion = 1)
                assertEquals("$what must be accepted", HttpStatusCode.OK, response.status)
                assertEquals(
                    "$what must advance the version by exactly one",
                    2L,
                    CatalogJson.decodeSnapshot(response.bodyAsText())!!.catalogVersion,
                )
                assertEquals("$what must be what the server serves", after, read(token).nodes)
                check(read(token))
            }

            testApp(FileCatalogStore.inFilesDir(caseDir)) { _, token ->
                val reloaded = read(token)
                assertEquals("$what must survive a server restart", 2L, reloaded.catalogVersion)
                assertEquals("$what must be unchanged by a restart", after, reloaded.nodes)
                check(reloaded)
            }
        } finally {
            caseDir.deleteRecursively()
        }
    }

    private fun positions(snapshot: CatalogSnapshot, parentId: String?) =
        snapshot.nodes.filter { it.parentId == parentId }.sortedBy { it.position }.map { it.id to it.position }

    // --- create ------------------------------------------------------------------------------

    @Test
    fun creatingAShelfSubcategoryAndDirectVideoIsCommittedAndSurvivesARestart() {
        val before = listOf(shelf("cat-cartoon", "Cartoons", 0))

        mutationIsCommittedAndSurvivesARestart(
            "a new shelf",
            before,
            before + shelf("cat-music", "Music", 1),
        ) { snapshot ->
            assertEquals(listOf("cat-cartoon" to 0, "cat-music" to 1), positions(snapshot, null))
        }

        mutationIsCommittedAndSurvivesARestart(
            "a new subcategory",
            before,
            before + container("i-cocomelon", "cat-cartoon", "Cocomelon", 0, playlistId = "PLcocomelon"),
        ) { snapshot ->
            assertEquals(listOf("i-cocomelon" to 0), positions(snapshot, "cat-cartoon"))
        }

        mutationIsCommittedAndSurvivesARestart(
            "a new direct video beside a subcategory",
            before + container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
            before + container("i-cocomelon", "cat-cartoon", "Cocomelon", 0) +
                video("i-fresh", "cat-cartoon", "Fresh Video", 1, videoId = "vidFresh"),
        ) { snapshot ->
            assertEquals(
                listOf(CATALOG_NODE_TYPE_SUBCATEGORY, CATALOG_NODE_TYPE_VIDEO),
                snapshot.nodes.filter { it.parentId == "cat-cartoon" }.sortedBy { it.position }.map { it.nodeType },
            )
        }
    }

    // --- rename and enable/disable ------------------------------------------------------------

    @Test
    fun renamingASubcategoryKeepsItsIdentityItsChildrenAndItsProvenance() {
        val before = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0, playlistId = "PLcocomelon"),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
        )
        val after = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon Songs", 0, playlistId = "PLcocomelon"),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
        )

        mutationIsCommittedAndSurvivesARestart("a rename", before, after) { snapshot ->
            val renamed = snapshot.nodes.single { it.id == "i-cocomelon" }
            assertEquals("Cocomelon Songs", renamed.title)
            assertEquals("the import source is untouched", "PLcocomelon", renamed.youtubePlaylistId)
            assertEquals("the child is untouched", listOf("i-cocomelon#a"), snapshot.nodes.filter { it.parentId == "i-cocomelon" }.map { it.id })
        }
    }

    @Test
    fun disablingANodeIsStoredAndNeverDeletesWhatIsInsideIt() {
        val before = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
            video("i-direct", "cat-cartoon", "Direct", 1, videoId = "vidDirect"),
        )
        val after = listOf(
            shelf("cat-cartoon", "Cartoons", 0, enabled = false),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0, enabled = false),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
            video("i-direct", "cat-cartoon", "Direct", 1, videoId = "vidDirect", enabled = false),
        )

        mutationIsCommittedAndSurvivesARestart("disabling nodes", before, after) { snapshot ->
            assertTrue("a disabled shelf keeps its identifier", snapshot.nodes.any { it.id == "cat-cartoon" && !it.enabled })
            assertTrue("a disabled container keeps its children", snapshot.nodes.any { it.id == "i-cocomelon#a" })
            assertTrue("a disabled video stays in the catalog", snapshot.nodes.any { it.id == "i-direct" && !it.enabled })
            assertEquals("nothing was deleted", 4, snapshot.nodes.size)
        }
    }

    // --- delete ------------------------------------------------------------------------------

    @Test
    fun deletingAVideoAContainerOrAWholeShelfRemovesExactlyItsSubtreeAndRenumbers() {
        val full = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
            video("i-cocomelon#b", "i-cocomelon", "Video B", 1, videoId = "vidB"),
            container("i-bluey", "cat-cartoon", "Bluey", 1),
            video("i-bluey#d", "i-bluey", "Video D", 0, videoId = "vidD"),
            shelf("cat-music", "Music", 1),
        )

        // A video: only that node goes, and its siblings close up - the editor renumbers, so the
        // document it sends is already 0..n-1 (the server would refuse a sparse one, which is why the
        // "after" documents here are written out rather than filtered).
        mutationIsCommittedAndSurvivesARestart(
            "deleting a video",
            full,
            listOf(
                shelf("cat-cartoon", "Cartoons", 0),
                container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
                video("i-cocomelon#b", "i-cocomelon", "Video B", 0, videoId = "vidB"),
                container("i-bluey", "cat-cartoon", "Bluey", 1),
                video("i-bluey#d", "i-bluey", "Video D", 0, videoId = "vidD"),
                shelf("cat-music", "Music", 1),
            ),
        ) { snapshot ->
            assertEquals(listOf("i-cocomelon#b" to 0), positions(snapshot, "i-cocomelon"))
        }

        // A container: it takes the videos inside it, and the shelf closes up around it.
        mutationIsCommittedAndSurvivesARestart(
            "deleting a container",
            full,
            listOf(
                shelf("cat-cartoon", "Cartoons", 0),
                container("i-bluey", "cat-cartoon", "Bluey", 0),
                video("i-bluey#d", "i-bluey", "Video D", 0, videoId = "vidD"),
                shelf("cat-music", "Music", 1),
            ),
        ) { snapshot ->
            assertEquals("the container's videos went with it", emptyList<String>(), snapshot.nodes.filter { it.parentId == "i-cocomelon" }.map { it.id })
            assertEquals("the shelf closed up around it", listOf("i-bluey" to 0), positions(snapshot, "cat-cartoon"))
        }

        // A shelf: the whole subtree goes, and the shelves are renumbered.
        mutationIsCommittedAndSurvivesARestart(
            "deleting a shelf",
            full,
            listOf(shelf("cat-music", "Music", 0)),
        ) { snapshot ->
            assertEquals(listOf("cat-music" to 0), positions(snapshot, null))
            assertEquals(1, snapshot.nodes.size)
        }
    }

    // --- reorder -----------------------------------------------------------------------------

    @Test
    fun reorderingSiblingsKeepsEveryIdentityAndOnlyMovesPositions() {
        val before = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
            video("i-cocomelon#b", "i-cocomelon", "Video B", 1, videoId = "vidB"),
            video("i-cocomelon#c", "i-cocomelon", "Video C", 2, videoId = "vidC"),
        )
        // Move Video C up: everything else is untouched, including its own fields.
        val after = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
            video("i-cocomelon#c", "i-cocomelon", "Video C", 1, videoId = "vidC"),
            video("i-cocomelon#b", "i-cocomelon", "Video B", 2, videoId = "vidB"),
        )

        mutationIsCommittedAndSurvivesARestart("a reorder", before, after) { snapshot ->
            assertEquals(
                listOf("i-cocomelon#a" to 0, "i-cocomelon#c" to 1, "i-cocomelon#b" to 2),
                positions(snapshot, "i-cocomelon"),
            )
            assertEquals("Video C is the same node it was", "vidC", snapshot.nodes.single { it.id == "i-cocomelon#c" }.youtubeVideoId)
        }
    }

    @Test
    fun reorderingMixedSiblingsAndWholeShelvesWorksTheSameWay() {
        val before = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
            video("i-direct-a", "cat-cartoon", "Direct A", 1, videoId = "vidDirectA"),
            video("i-direct-b", "cat-cartoon", "Direct B", 2, videoId = "vidDirectB"),
            shelf("cat-music", "Music", 1),
        )
        // The direct video moves above the container; the shelves swap.
        val after = listOf(
            shelf("cat-music", "Music", 0),
            shelf("cat-cartoon", "Cartoons", 1),
            video("i-direct-b", "cat-cartoon", "Direct B", 0, videoId = "vidDirectB"),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 1),
            video("i-direct-a", "cat-cartoon", "Direct A", 2, videoId = "vidDirectA"),
        )

        mutationIsCommittedAndSurvivesARestart("a mixed reorder", before, after) { snapshot ->
            assertEquals(listOf("cat-music" to 0, "cat-cartoon" to 1), positions(snapshot, null))
            assertEquals(
                listOf("i-direct-b" to 0, "i-cocomelon" to 1, "i-direct-a" to 2),
                positions(snapshot, "cat-cartoon"),
            )
        }
    }

    // --- move between parents ------------------------------------------------------------------

    @Test
    fun movingAVideoToAnotherParentClosesTheOldGroupAndOpensTheNewOne() {
        val before = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
            video("i-cocomelon#b", "i-cocomelon", "Video B", 1, videoId = "vidB"),
            container("i-bluey", "cat-cartoon", "Bluey", 1),
            video("i-bluey#d", "i-bluey", "Video D", 0, videoId = "vidD"),
        )
        val after = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
            container("i-bluey", "cat-cartoon", "Bluey", 1),
            video("i-cocomelon#b", "i-bluey", "Video B", 0, videoId = "vidB"),
            video("i-bluey#d", "i-bluey", "Video D", 1, videoId = "vidD"),
        )

        mutationIsCommittedAndSurvivesARestart("a video move", before, after) { snapshot ->
            assertEquals("the old group closed up", listOf("i-cocomelon#a" to 0), positions(snapshot, "i-cocomelon"))
            assertEquals("the new group opened for it", listOf("i-cocomelon#b" to 0, "i-bluey#d" to 1), positions(snapshot, "i-bluey"))
            assertEquals("the moved node is the same node", "vidB", snapshot.nodes.single { it.id == "i-cocomelon#b" }.youtubeVideoId)
        }
    }

    @Test
    fun movingASubcategoryWithItsSubtreeKeepsEveryNodeInsideIt() {
        val before = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0, playlistId = "PLcocomelon"),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
            video("i-cocomelon#b", "i-cocomelon", "Video B", 1, videoId = "vidB"),
            shelf("cat-music", "Music", 1),
        )
        val after = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            shelf("cat-music", "Music", 1),
            container("i-cocomelon", "cat-music", "Cocomelon", 0, playlistId = "PLcocomelon"),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
            video("i-cocomelon#b", "i-cocomelon", "Video B", 1, videoId = "vidB"),
        )

        mutationIsCommittedAndSurvivesARestart("a container move", before, after) { snapshot ->
            assertEquals("the old shelf is empty", emptyList<Pair<String, Int>>(), positions(snapshot, "cat-cartoon"))
            assertEquals(listOf("i-cocomelon" to 0), positions(snapshot, "cat-music"))
            assertEquals("the subtree came along", listOf("i-cocomelon#a" to 0, "i-cocomelon#b" to 1), positions(snapshot, "i-cocomelon"))
        }
    }

    // --- a whole editing session in one write ---------------------------------------------------

    @Test
    fun severalEditsCanBeBatchedIntoOneAtomicDocument() {
        val before = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
        )
        // Add a shelf, add a video to it, rename the container, disable a node and reorder - once.
        val after = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon Songs", 0),
            video("i-fresh", "cat-cartoon", "Fresh", 1, videoId = "vidFresh", enabled = false),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
            shelf("cat-music", "Music", 1),
        )

        mutationIsCommittedAndSurvivesARestart("a batch of edits", before, after) { snapshot ->
            assertEquals("one write, so one version", 2L, snapshot.catalogVersion)
            assertEquals(5, snapshot.nodes.size)
            assertEquals(
                listOf("i-cocomelon" to 0, "i-fresh" to 1),
                positions(snapshot, "cat-cartoon"),
            )
        }
    }

    // --- concurrency ---------------------------------------------------------------------------

    @Test
    fun aStaleEditorCannotOverwriteANewerCatalog() = runBlocking {
        testApp(store()) { _, token ->
            assertEquals(HttpStatusCode.OK, publish(token, document(shelf("cat-cartoon", "Cartoons", 0)), expectedVersion = 0).status)

            // Client B, which read version 1, publishes first and reaches version 2.
            val theirs = publish(
                token,
                document(shelf("cat-cartoon", "Theirs", 0)),
                expectedVersion = 1,
            )
            assertEquals(HttpStatusCode.OK, theirs.status)

            // Client A still believes it is at version 1 and tries to publish its own tree.
            val stale = publish(token, document(shelf("cat-cartoon", "Mine", 0)), expectedVersion = 1)

            assertEquals("a stale editor gets a conflict", HttpStatusCode.Conflict, stale.status)
            val body = Json.parseToJsonElement(stale.bodyAsText()).jsonObject
            assertEquals("and is told which version to fetch", 2L, body["catalogVersion"]!!.jsonPrimitive.content.toLong())

            val served = read(token)
            assertEquals("the newer catalog is intact", "Theirs", served.nodes.single().title)
            assertEquals("and the version did not move", 2L, served.catalogVersion)
        }

        // The same is true after a restart: nothing was half-applied.
        testApp(store()) { _, token ->
            assertEquals("Theirs", read(token).nodes.single().title)
            assertEquals(2L, read(token).catalogVersion)
        }
    }

    @Test
    fun aConflictLeavesTheVersionAndTheCatalogExactlyAsTheyWere() = runBlocking {
        testApp(store()) { store, token ->
            publish(token, document(shelf("cat-cartoon", "Cartoons", 0)), expectedVersion = 0)
            val before = store.read()

            val stale = publish(token, document(shelf("cat-cartoon", "Renamed", 0)), expectedVersion = 7)

            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals("the stored document did not move", before, store.read())
            assertEquals(1L, store.read().catalogVersion)
        }
    }

    // --- invalid mutations ----------------------------------------------------------------------

    @Test
    fun everyInvalidMutationIsRefusedAndChangesNothing() = runBlocking {
        val stored = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
        )

        // Each entry is a document an editor could produce only by being broken: a sparse position, a
        // duplicate, an identifier used twice, a parent that is not there, a cycle, a video with no
        // identifier, a nested shelf, a container at ROOT, and a video holding a child.
        val broken = mapOf(
            "a sparse position" to listOf(shelf("cat-cartoon", "Cartoons", 0), shelf("cat-music", "Music", 10)),
            "a duplicate position" to listOf(shelf("cat-cartoon", "Cartoons", 0), shelf("cat-music", "Music", 0)),
            "a duplicate identifier" to listOf(shelf("cat-cartoon", "Cartoons", 0), shelf("cat-cartoon", "Again", 1)),
            "a parent that does not exist" to listOf(
                shelf("cat-cartoon", "Cartoons", 0),
                video("i-orphan", "cat-absent", "Orphan", 0, videoId = "vidOrphan"),
            ),
            "a cycle" to listOf(
                shelf("cat-cartoon", "Cartoons", 0),
                container("i-a", "i-b", "A", 0),
                container("i-b", "i-a", "B", 0),
            ),
            "a video with no identifier" to listOf(
                shelf("cat-cartoon", "Cartoons", 0),
                video("i-v", "cat-cartoon", "No Id", 0, videoId = "  "),
            ),
            "a shelf inside a shelf" to listOf(
                shelf("cat-cartoon", "Cartoons", 0),
                CatalogNodeDto(
                    id = "cat-inner", parentId = "cat-cartoon", nodeType = CATALOG_NODE_TYPE_CATEGORY,
                    title = "Inner", position = 0, createdAt = createdAt, updatedAt = createdAt,
                ),
            ),
            "a container at ROOT" to listOf(
                shelf("cat-cartoon", "Cartoons", 0),
                // A subcategory whose parent was cleared: a shelf is the only thing ROOT may hold.
                CatalogNodeDto(
                    id = "i-stray", parentId = null, nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
                    title = "Stray", position = 0, createdAt = createdAt, updatedAt = createdAt,
                ),
            ),
            "a video holding a child" to listOf(
                shelf("cat-cartoon", "Cartoons", 0),
                video("i-v", "cat-cartoon", "Video", 0, videoId = "vidV"),
                video("i-child", "i-v", "Child", 0, videoId = "vidChild"),
            ),
        )

        testApp(store()) { store, token ->
            assertEquals(HttpStatusCode.OK, publish(token, document(*stored.toTypedArray()), expectedVersion = 0).status)
            val committed = store.read()

            broken.forEach { (what, nodes) ->
                val response = publish(token, document(*nodes.toTypedArray()), expectedVersion = 1)

                assertEquals("$what must be refused", HttpStatusCode.BadRequest, response.status)
                assertTrue(
                    "$what should say why, got: ${response.bodyAsText()}",
                    response.bodyAsText().contains("\"details\""),
                )
                assertEquals("$what must not change the catalog", committed, store.read())
                assertEquals("$what must not change the version", 1L, store.read().catalogVersion)
            }
        }
    }

    @Test
    fun aRefusedMutationIsStillRefusedAfterARestartAndNothingWasHalfApplied() = runBlocking {
        val stored = listOf(shelf("cat-cartoon", "Cartoons", 0))

        testApp(store()) { _, token ->
            publish(token, document(*stored.toTypedArray()), expectedVersion = 0)
            val refused = publish(
                token,
                document(shelf("cat-cartoon", "Cartoons", 0), shelf("cat-music", "Music", 5)),
                expectedVersion = 1,
            )
            assertEquals(HttpStatusCode.BadRequest, refused.status)
        }

        testApp(store()) { _, token ->
            val served = read(token)
            assertEquals(stored, served.nodes)
            assertEquals(1L, served.catalogVersion)
        }
    }

    // --- the picture a container shows ----------------------------------------------------------
    //
    // A picture is stored in the same document as everything else: no separate endpoint, no upload
    // and no URL - the choice is either `AUTO` or the node id of a video inside that container.

    @Test
    fun choosingAPictureIsCommittedAndSurvivesARestart() {
        val before = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
            video("i-cocomelon#b", "i-cocomelon", "Video B", 1, videoId = "vidB"),
        )
        val after = before.map {
            if (it.id == "i-cocomelon") {
                it.copy(thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = "i-cocomelon#b")
            } else {
                it
            }
        }

        mutationIsCommittedAndSurvivesARestart("a chosen picture", before, after) { snapshot ->
            val chosen = snapshot.nodes.single { it.id == "i-cocomelon" }
            assertEquals(CATALOG_THUMBNAIL_MODE_VIDEO, chosen.thumbnailMode)
            assertEquals("i-cocomelon#b", chosen.thumbnailVideoId)
            assertEquals("and nothing else about the node moved", "Cocomelon", chosen.title)
            assertTrue("no node carries a picture URL", snapshot.nodes.all { it.thumbnailUrl == null })
        }
    }

    @Test
    fun aPictureThatCouldNotBeHonouredIsRefusedAndChangesNothing() = runBlocking {
        val stored = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
            container("i-other", "cat-cartoon", "Other", 1),
            video("i-other#a", "i-other", "Elsewhere", 0, videoId = "vidElsewhere"),
        )
        val choosing = { chosen: String? ->
            stored.map {
                if (it.id == "i-cocomelon") {
                    it.copy(thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = chosen)
                } else {
                    it
                }
            }
        }

        testApp(store()) { store, token ->
            assertEquals(HttpStatusCode.OK, publish(token, document(*stored.toTypedArray()), expectedVersion = 0).status)
            val committed = store.read()

            // A name that does not exist, a name that is not a video, and a video from another
            // container: three ways of asking for a picture the app could not draw.
            listOf("i-ghost", "i-other", "i-other#a").forEach { chosen ->
                val response = publish(token, document(*choosing(chosen).toTypedArray()), expectedVersion = 1)

                assertEquals("$chosen must be refused", HttpStatusCode.BadRequest, response.status)
                assertTrue(
                    "the refusal must be addressed to the thumbnail field, got ${response.bodyAsText()}",
                    response.bodyAsText().contains("thumbnailVideoId"),
                )
                assertEquals("$chosen must not change the catalog", committed, store.read())
                assertEquals("$chosen must not move the version", 1L, store.read().catalogVersion)
            }

            // And a choice that *can* be honoured still goes through afterwards, at the same version.
            val accepted = publish(token, document(*choosing("i-cocomelon#a").toTypedArray()), expectedVersion = 1)
            assertEquals(HttpStatusCode.OK, accepted.status)
            assertEquals("i-cocomelon#a", read(token).nodes.single { it.id == "i-cocomelon" }.thumbnailVideoId)
        }
    }

    @Test
    fun aStaleEditorCannotOverwriteANewerPicture() = runBlocking {
        val nodes = listOf(
            shelf("cat-cartoon", "Cartoons", 0),
            container("i-cocomelon", "cat-cartoon", "Cocomelon", 0),
            video("i-cocomelon#a", "i-cocomelon", "Video A", 0, videoId = "vidA"),
        )
        val pictureOf = { mode: String, chosen: String? ->
            nodes.map {
                if (it.id == "i-cocomelon") it.copy(thumbnailMode = mode, thumbnailVideoId = chosen) else it
            }
        }

        testApp(store()) { _, token ->
            publish(token, document(*nodes.toTypedArray()), expectedVersion = 0)

            // One editor chooses "Video A" and reaches version 2.
            val theirs = publish(
                token,
                document(*pictureOf(CATALOG_THUMBNAIL_MODE_VIDEO, "i-cocomelon#a").toTypedArray()),
                expectedVersion = 1,
            )
            assertEquals(HttpStatusCode.OK, theirs.status)

            // Another still believes it is at version 1 and tries to go back to automatic.
            val stale = publish(
                token,
                document(*pictureOf(CATALOG_THUMBNAIL_MODE_AUTO, null).toTypedArray()),
                expectedVersion = 1,
            )

            assertEquals("a stale editor gets a conflict", HttpStatusCode.Conflict, stale.status)
            val served = read(token)
            val container = served.nodes.single { it.id == "i-cocomelon" }
            assertEquals("the newer picture is intact", CATALOG_THUMBNAIL_MODE_VIDEO, container.thumbnailMode)
            assertEquals("i-cocomelon#a", container.thumbnailVideoId)
            assertEquals("and the version did not move", 2L, served.catalogVersion)
        }
    }

    // --- timestamps -----------------------------------------------------------------------------

    @Test
    fun theEditorDoesNotHaveToManageTimestampsAndTheServerKeepsTheOnesItIsGiven() = runBlocking {
        testApp(store()) { _, token ->
            val response = publish(
                token,
                document(
                    shelf("cat-cartoon", "Cartoons", 0),
                    // A node the editor has just created: no timestamps, so the server stamps it.
                    container("i-new", "cat-cartoon", "New", 0).copy(createdAt = 0L, updatedAt = 0L),
                ),
                expectedVersion = 0,
            )
            assertEquals(HttpStatusCode.OK, response.status)

            val served = read(token)
            assertEquals("the editor's own timestamp is kept", createdAt, served.nodes.single { it.id == "cat-cartoon" }.createdAt)
            assertTrue("a node with none is stamped by the server", served.nodes.single { it.id == "i-new" }.createdAt > 0L)
        }
    }
}
