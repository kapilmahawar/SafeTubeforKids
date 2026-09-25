package tv.safetubeforkids.app.server

import tv.safetubeforkids.app.data.catalog.CATALOG_NODE_TYPE_CATEGORY
import tv.safetubeforkids.app.data.catalog.CATALOG_NODE_TYPE_SUBCATEGORY
import tv.safetubeforkids.app.data.catalog.CATALOG_SCHEMA_VERSION
import tv.safetubeforkids.app.data.catalog.CatalogJson
import tv.safetubeforkids.app.data.catalog.CatalogNodeDto
import tv.safetubeforkids.app.data.catalog.CatalogSnapshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The server's catalog storage: contract version 2, the node tree.
 *
 * The restart tests build a *brand new store object over the same file*, which is what a restarted
 * server process does: the previous instance, its in-memory state and its lock are all gone.
 *
 * The failure tests are the other half of the contract, and the half a happy path cannot show: a
 * write that does not reach the disk must report failure and leave the previously committed catalog
 * and version exactly as they were.
 */
class CatalogStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("catalog-store-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = FileCatalogStore.inFilesDir(dir)

    private fun shelf(id: String, title: String, position: Int = 0, enabled: Boolean = true) =
        CatalogNodeDto(
            id = id,
            parentId = null,
            nodeType = CATALOG_NODE_TYPE_CATEGORY,
            title = title,
            position = position,
            enabled = enabled,
        )

    /** A shelf with one container on it: two nodes, in the only ordering the tree allows. */
    private fun shelfWithPlaylist(id: String, title: String, playlistId: String): List<CatalogNodeDto> = listOf(
        shelf(id, title),
        CatalogNodeDto(
            id = "$id-item",
            parentId = id,
            nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
            title = title,
            position = 0,
            youtubePlaylistId = playlistId,
        ),
    )

    private fun assertIsStored(result: CatalogStoreResult): CatalogSnapshot {
        assertTrue("expected a stored catalog but got $result", result is CatalogStoreResult.Stored)
        return (result as CatalogStoreResult.Stored).snapshot
    }

    // ------------------------------------------------------------------ API-02 / API-11

    @Test
    fun anUnconfiguredStoreReadsAsAnEmptyCatalogAtVersionZeroWithoutCreatingAFile() {
        val store = store()

        val snapshot = store.read()

        assertEquals(CATALOG_SCHEMA_VERSION, snapshot.schemaVersion)
        assertEquals(0L, snapshot.catalogVersion)
        assertTrue(snapshot.nodes.isEmpty())
        assertFalse("reading must not create a document", File(dir, FileCatalogStore.FILE_NAME).exists())
    }

    @Test
    fun theCatalogAndItsVersionSurviveAStoreRecreation() {
        val first = store()
        val stored = first.write(shelfWithPlaylist("cat-music", "Music", "PLnursery"))
        assertEquals(1L, (stored as CatalogStoreResult.Stored).snapshot.catalogVersion)

        // A restarted server: new object, same file, no shared state.
        val afterRestart = store().read()

        assertEquals("the version must not reset across a restart", 1L, afterRestart.catalogVersion)
        assertEquals(CATALOG_SCHEMA_VERSION, afterRestart.schemaVersion)
        assertEquals("every node survived, identity included", listOf("cat-music", "cat-music-item"), afterRestart.nodes.map { it.id })
        assertEquals("cat-music", afterRestart.nodes.single { it.id == "cat-music-item" }.parentId)
        assertEquals("PLnursery", afterRestart.nodes.single { it.id == "cat-music-item" }.youtubePlaylistId)
        assertNull(afterRestart.nodes.single { it.id == "cat-music-item" }.youtubeVideoId)
    }

    @Test
    fun theVersionKeepsClimbingAcrossRestarts() {
        store().write(listOf(shelf("c1", "One")))                          // 1
        val second = store()
        second.write(listOf(shelf("c1", "One"), shelf("c2", "Two", 1)))    // 2
        val third = store()
        third.write(listOf(shelf("c1", "One")))                            // 3

        assertEquals(3L, store().read().catalogVersion)
    }

    @Test
    fun theStoredDocumentIsTheCatalogJsonContract() {
        store().write(shelfWithPlaylist("cat-music", "Music", "PLnursery"))

        val text = File(dir, FileCatalogStore.FILE_NAME).readText()

        assertTrue(text.contains("\"schemaVersion\": $CATALOG_SCHEMA_VERSION"))
        assertTrue(text.contains("\"catalogVersion\": 1"))
        assertTrue(text.contains("\"nodes\": ["))
        assertTrue(text.contains("\"nodeType\": \"CATEGORY\""))
        assertTrue(text.contains("\"nodeType\": \"SUBCATEGORY\""))
        assertTrue(text.contains("\"title\": \"Music\""))
        assertTrue(text.contains("PLnursery"))
        assertTrue("a shelf at ROOT must say so explicitly", text.contains("\"parentId\": null"))
        assertTrue("a null video id must be explicit", text.contains("\"youtubeVideoId\": null"))
        // The document on disk can be read back by the same codec the TV uses.
        assertEquals(2, CatalogJson.decodeSnapshot(text)!!.nodes.size)
    }

    @Test
    fun aCorruptDocumentReadsAsEmptySoNoTvCanBePushedAnEmptyCatalog() {
        val file = File(dir, FileCatalogStore.FILE_NAME)
        file.writeText("{ this is not a catalog")

        val snapshot = store().read()

        // Version 0 is below any catalog a TV has already installed, and the sync client refuses to
        // move down, so corruption degrades to "the parent republishes" rather than to data loss on
        // every TV in the house.
        assertEquals(0L, snapshot.catalogVersion)
        assertTrue(snapshot.nodes.isEmpty())
    }

    @Test
    fun aCorruptDocumentCanBeOverwrittenByRepublishing() {
        File(dir, FileCatalogStore.FILE_NAME).writeText("garbage")

        val result = store().write(listOf(shelf("c1", "One")))

        assertEquals(1L, (result as CatalogStoreResult.Stored).snapshot.catalogVersion)
        assertEquals(listOf("One"), store().read().nodes.map { it.title })
    }

    // ------------------------------------------------------------------ versioning

    @Test
    fun theFirstWriteIsVersionOneAndEachWriteAfterItIncrementsByOne() {
        val store = store()

        assertEquals(1L, assertIsStored(store.write(listOf(shelf("c1", "One")))).catalogVersion)
        assertEquals(2L, assertIsStored(store.write(listOf(shelf("c1", "Two")))).catalogVersion)
        assertEquals(3L, assertIsStored(store.write(listOf(shelf("c1", "Three")))).catalogVersion)
    }

    @Test
    fun aVersionConflictWritesNothingAndDoesNotInventAVersion() {
        val store = store()
        store.write(listOf(shelf("c1", "One")))  // 1

        val result = store.write(listOf(shelf("c1", "Stale")), expectedVersion = 0)

        assertTrue(result is CatalogStoreResult.VersionConflict)
        assertEquals(1L, (result as CatalogStoreResult.VersionConflict).currentVersion)
        assertEquals(1L, store.read().catalogVersion)
        assertEquals("One", store.read().nodes[0].title)
    }

    @Test
    fun aMatchingExpectedVersionIsAccepted() {
        val store = store()
        store.write(listOf(shelf("c1", "One")))  // 1

        val result = store.write(listOf(shelf("c1", "Two")), expectedVersion = 1)

        assertEquals(2L, (result as CatalogStoreResult.Stored).snapshot.catalogVersion)
        assertEquals("Two", store.read().nodes[0].title)
    }

    @Test
    fun theStoredVersionIsAlwaysTheOneTheServerAssigned() {
        // Whatever a client sent, the number on the document is the server's own next number.
        val store = store()
        store.write(listOf(shelf("c1", "One")))                                   // 1
        val second = assertIsStored(store.write(listOf(shelf("c1", "Two")), expectedVersion = 1))

        assertEquals(2L, second.catalogVersion)
        assertEquals(2L, CatalogJson.decodeSnapshot(File(dir, FileCatalogStore.FILE_NAME).readText())!!.catalogVersion)
    }

    // ------------------------------------------------------------------ concurrency (§42)

    @Test
    fun concurrentWritesAreEachGivenADistinctVersion() {
        val store = store()
        val writers = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)
        val versions = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val pool = Executors.newFixedThreadPool(writers)

        try {
            repeat(writers) { index ->
                pool.submit {
                    start.await()
                    try {
                        val result = store.write(listOf(shelf("c$index", "Shelf $index")))
                        versions.add((result as CatalogStoreResult.Stored).snapshot.catalogVersion)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue("writers did not finish", done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(writers, versions.size)
        assertEquals(
            "two concurrent writes must not be handed the same version",
            (1L..writers.toLong()).toSet(),
            versions.toSet(),
        )
        assertEquals(writers.toLong(), store.read().catalogVersion)
    }

    @Test
    fun concurrentWritersWithAStaleExpectationCannotBothWin() {
        val store = store()
        store.write(listOf(shelf("c1", "One")))  // 1
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val stored = AtomicInteger(0)
        val conflicts = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(2)

        try {
            repeat(2) { index ->
                pool.submit {
                    start.await()
                    try {
                        when (store.write(listOf(shelf("c1", "Writer $index")), expectedVersion = 1)) {
                            is CatalogStoreResult.Stored -> stored.incrementAndGet()
                            is CatalogStoreResult.VersionConflict -> conflicts.incrementAndGet()
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals("exactly one stale writer may win", 1, stored.get())
        assertEquals(1, conflicts.get())
        assertEquals(2L, store.read().catalogVersion)
    }

    // ------------------------------------------------------------------ in-memory twin

    @Test
    fun theInMemoryStoreVersionsIdenticallySoTestsExerciseTheRealRules() {
        val store = InMemoryCatalogStore()

        assertEquals(0L, store.read().catalogVersion)
        assertEquals(1L, assertIsStored(store.write(listOf(shelf("c1", "One")))).catalogVersion)
        assertTrue(store.write(listOf(shelf("c1", "Stale")), expectedVersion = 0) is CatalogStoreResult.VersionConflict)
        assertEquals(2L, assertIsStored(store.write(listOf(shelf("c1", "Two")), expectedVersion = 1)).catalogVersion)
        assertEquals("Two", store.read().nodes[0].title)
    }

    // ------------------------------------------------- version reset / recovery (F7)
    // A TV refuses any catalog numbered below the version it already holds. When the counter lived
    // only inside the document, losing or resetting that document restarted the counter at 0, so the
    // parent's next publish was numbered below the TV's version and was refused as a regression -
    // stranding the TV until the counter climbed back past it. The counter is therefore mirrored
    // into its own file, which is what makes a reset recoverable without weakening the check.

    @Test
    fun theVersionCounterSurvivesDeletionOfTheCatalogDocument() {
        val store = store()
        repeat(3) { store.write(listOf(shelf("cat-$it", "Shelf $it"))) }
        assertEquals(3L, store.read().catalogVersion)

        // The document is lost or reinitialised - the exact situation that used to rewind the count.
        assertTrue(File(dir, FileCatalogStore.FILE_NAME).delete())
        assertEquals("a lost document reads as nothing configured", 0L, store.read().catalogVersion)

        val stored = assertIsStored(store.write(listOf(shelf("cat-new", "New Shelf"))))

        // Numbered above every version already issued, so a TV holding 3 accepts it.
        assertEquals(4L, stored.catalogVersion)
    }

    @Test
    fun theCounterKeepsClimbingAcrossRepeatedDocumentLoss() {
        val store = store()
        repeat(2) { store.write(listOf(shelf("cat-a", "A"))) }
        val document = File(dir, FileCatalogStore.FILE_NAME)

        var expected = 2L
        repeat(3) {
            assertTrue(document.delete())
            expected += 1
            assertEquals(expected, assertIsStored(store.write(listOf(shelf("cat-b", "B")))).catalogVersion)
        }
    }

    @Test
    fun anUnusableCounterFileFallsBackToTheDocumentRatherThanInventingAVersion() {
        val store = store()
        store.write(listOf(shelf("cat-a", "A")))
        assertEquals(2L, assertIsStored(store.write(listOf(shelf("cat-b", "B")))).catalogVersion)

        // A hand-mangled counter must not become a version: the floor is unusable, so the document
        // alone decides and the sequence still moves forward.
        File(dir, FileCatalogStore.VERSION_FILE_NAME).writeText("not-a-number")

        assertEquals(3L, assertIsStored(store.write(listOf(shelf("cat-c", "C")))).catalogVersion)
    }

    @Test
    fun theCounterFileIsWrittenBesideTheDocument() {
        store().write(listOf(shelf("cat-a", "A")))

        val counter = File(dir, FileCatalogStore.VERSION_FILE_NAME)
        assertTrue("the high-water mark is persisted separately", counter.exists())
        assertEquals("1", counter.readText().trim())
    }

    @Test
    fun aCompletelyFreshInstallStillStartsAtVersionOne() {
        // With BOTH files gone the server cannot be distinguished from a new install, so the counter
        // legitimately restarts. This is the documented residual case: the TV keeps its own catalog
        // and the parent must publish again - the counter is not recoverable by design here.
        assertFalse(File(dir, FileCatalogStore.FILE_NAME).exists())
        assertFalse(File(dir, FileCatalogStore.VERSION_FILE_NAME).exists())

        assertEquals(1L, assertIsStored(store().write(listOf(shelf("cat-a", "A")))).catalogVersion)
    }

    // ------------------------------------------------- node bookkeeping (the server's own clock)

    @Test
    fun nodesThatArriveWithoutTimestampsAreStampedByTheServer() {
        val stored = assertIsStored(store().write(listOf(shelf("c1", "One"))))

        val node = stored.nodes.single()
        assertTrue("a stored node must carry a creation time", node.createdAt > 0L)
        assertEquals(node.createdAt, node.updatedAt)
    }

    @Test
    fun theTimestampsADocumentCarriesAreKept() {
        val written = listOf(shelf("c1", "One").copy(createdAt = 111L, updatedAt = 222L))

        val stored = assertIsStored(store().write(written))

        assertEquals(111L, stored.nodes.single().createdAt)
        assertEquals(222L, stored.nodes.single().updatedAt)
    }

    // ------------------------------------------------- a document that predates this build

    @Test
    fun aStoredVersion1DocumentIsReadAsTheNodeTreeAndKeepsItsVersion() {
        File(dir, FileCatalogStore.FILE_NAME).writeText(
            """
            {"schemaVersion":1,"catalogVersion":6,"categories":[
              {"id":"cat-cartoon","displayName":"Cartoon","sortOrder":0,"enabled":true,"items":[
                {"id":"i-cocomelon","type":"PLAYLIST","displayName":"CoComelon","sortOrder":0,
                 "youtubePlaylistId":"PLcocomelon","youtubeVideoId":null,"enabled":true}]}]}
            """.trimIndent()
        )

        val snapshot = store().read()

        // Read as a tree, at the version it already had: throwing it away would lose the parent's
        // catalog, and resetting the version would look like a regression to every TV.
        assertEquals(CATALOG_SCHEMA_VERSION, snapshot.schemaVersion)
        assertEquals(6L, snapshot.catalogVersion)
        assertEquals(listOf("Cartoon", "CoComelon"), snapshot.nodes.map { it.title })
        assertEquals("cat-cartoon", snapshot.nodes.single { it.id == "i-cocomelon" }.parentId)
        assertEquals("PLcocomelon", snapshot.nodes.single { it.id == "i-cocomelon" }.youtubePlaylistId)

        // And the next publish stores the current contract, so the upgrade completes by itself.
        assertIsStored(store().write(snapshot.nodes))
        val text = File(dir, FileCatalogStore.FILE_NAME).readText()
        assertTrue(text.contains("\"schemaVersion\": $CATALOG_SCHEMA_VERSION"))
        assertTrue(text.contains("\"nodes\": ["))
    }

    // ------------------------------------------------- persistence failures

    @Test
    fun aFailedSaveLeavesThePreviousCatalogAndVersionInPlace() {
        val good = store()
        good.write(listOf(shelf("c1", "Committed")))  // version 1

        // A persistence failure the caller cannot mistake for success.
        val failing = object : BaseCatalogStore() {
            override fun load(): CatalogSnapshot = good.read()
            override fun save(snapshot: CatalogSnapshot) = throw IOException("disk full")
        }

        val failed = try {
            failing.write(listOf(shelf("c1", "Never Stored")))
            false
        } catch (e: IOException) {
            true
        }

        assertTrue("a write that was not persisted must not return a result", failed)
        assertEquals("the previous version must remain", 1L, good.read().catalogVersion)
        assertEquals("the previous catalog must remain", listOf("Committed"), good.read().nodes.map { it.title })
    }

    @Test
    fun aWriteThatCannotReachTheDiskFailsAndLeavesThePreviousCatalogInPlace() {
        val store = store()
        store.write(listOf(shelf("c1", "Committed")))  // version 1

        // The store writes through `<name>.tmp` and moves it into place; a directory sitting on that
        // path is a real, platform-independent way to make the write itself impossible.
        val blocked = File(dir, FileCatalogStore.FILE_NAME + ".tmp")
        assertTrue(blocked.mkdirs())

        val failed = try {
            store.write(listOf(shelf("c1", "Never Stored")))
            false
        } catch (e: Exception) {
            true
        } finally {
            blocked.delete()
        }

        assertTrue("a write that cannot reach the disk must fail loudly", failed)
        assertEquals(1L, store.read().catalogVersion)
        assertEquals(listOf("Committed"), store.read().nodes.map { it.title })
    }

    @Test
    fun aStoreWhosePathCannotBeWrittenFailsInsteadOfInventingACatalog() {
        // `blocked` is a file, so it cannot be the parent directory of the document.
        val blocked = File(dir, "blocked")
        blocked.writeText("not a directory")
        val unusable = FileCatalogStore(File(blocked, FileCatalogStore.FILE_NAME))

        val failed = try {
            unusable.write(listOf(shelf("c1", "One")))
            false
        } catch (e: Exception) {
            true
        }

        assertTrue("the write must fail rather than appear to succeed", failed)
        assertEquals("nothing was committed, so the store still reads as unconfigured", 0L, unusable.read().catalogVersion)
        assertTrue(unusable.read().nodes.isEmpty())
    }
}
