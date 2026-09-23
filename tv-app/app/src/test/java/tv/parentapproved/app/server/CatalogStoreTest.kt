package tv.safetubeforkids.app.server

import tv.safetubeforkids.app.data.catalog.CATALOG_SCHEMA_VERSION
import tv.safetubeforkids.app.data.catalog.CatalogCategoryDto
import tv.safetubeforkids.app.data.catalog.CatalogItemDto
import tv.safetubeforkids.app.data.catalog.CatalogSnapshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The server's catalog storage.
 *
 * The restart tests build a *brand new store object over the same file*, which is what a restarted
 * server process does: the previous instance, its in-memory state and its lock are all gone.
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

    private fun category(id: String, name: String, sortOrder: Int = 0) =
        CatalogCategoryDto(id = id, displayName = name, sortOrder = sortOrder)

    private fun withPlaylist(id: String, name: String, playlistId: String) = CatalogCategoryDto(
        id = id,
        displayName = name,
        sortOrder = 0,
        items = listOf(
            CatalogItemDto(
                id = "$id-item",
                type = "PLAYLIST",
                displayName = name,
                sortOrder = 0,
                youtubePlaylistId = playlistId,
            )
        ),
    )

    // ------------------------------------------------------------------ API-02 / API-11

    @Test
    fun anUnconfiguredStoreReadsAsAnEmptyCatalogAtVersionZeroWithoutCreatingAFile() {
        val store = store()

        val snapshot = store.read()

        assertEquals(CATALOG_SCHEMA_VERSION, snapshot.schemaVersion)
        assertEquals(0L, snapshot.catalogVersion)
        assertTrue(snapshot.categories.isEmpty())
        assertFalse("reading must not create a document", File(dir, FileCatalogStore.FILE_NAME).exists())
    }

    @Test
    fun theCatalogAndItsVersionSurviveAStoreRecreation() {
        val first = store()
        val stored = first.write(listOf(withPlaylist("cat-music", "Music", "PLnursery")))
        assertEquals(1L, (stored as CatalogStoreResult.Stored).snapshot.catalogVersion)

        // A restarted server: new object, same file, no shared state.
        val afterRestart = store().read()

        assertEquals("the version must not reset across a restart", 1L, afterRestart.catalogVersion)
        assertEquals(CATALOG_SCHEMA_VERSION, afterRestart.schemaVersion)
        assertEquals(listOf("Music"), afterRestart.categories.map { it.displayName })
        assertEquals("PLnursery", afterRestart.categories[0].items[0].youtubePlaylistId)
        assertNull(afterRestart.categories[0].items[0].youtubeVideoId)
    }

    @Test
    fun theVersionKeepsClimbingAcrossRestarts() {
        store().write(listOf(category("c1", "One")))          // 1
        val second = store()
        second.write(listOf(category("c1", "One"), category("c2", "Two")))  // 2
        val third = store()
        third.write(listOf(category("c1", "One")))            // 3

        assertEquals(3L, store().read().catalogVersion)
    }

    @Test
    fun theStoredDocumentIsTheCatalogJsonContract() {
        store().write(listOf(withPlaylist("cat-music", "Music", "PLnursery")))

        val text = File(dir, FileCatalogStore.FILE_NAME).readText()

        assertTrue(text.contains("\"schemaVersion\": $CATALOG_SCHEMA_VERSION"))
        assertTrue(text.contains("\"catalogVersion\": 1"))
        assertTrue(text.contains("\"displayName\": \"Music\""))
        assertTrue(text.contains("\"ytb\"").not())
        assertTrue(text.contains("PLnursery"))
        assertTrue("a null video id must be explicit", text.contains("\"youtubeVideoId\": null"))
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
        assertTrue(snapshot.categories.isEmpty())
    }

    @Test
    fun aCorruptDocumentCanBeOverwrittenByRepublishing() {
        File(dir, FileCatalogStore.FILE_NAME).writeText("garbage")

        val result = store().write(listOf(category("c1", "One")))

        assertEquals(1L, (result as CatalogStoreResult.Stored).snapshot.catalogVersion)
        assertEquals(listOf("One"), store().read().categories.map { it.displayName })
    }

    // ------------------------------------------------------------------ versioning

    @Test
    fun theFirstWriteIsVersionOneAndEachWriteAfterItIncrementsByOne() {
        val store = store()

        assertEquals(1L, (store.write(listOf(category("c1", "One"))) as CatalogStoreResult.Stored).snapshot.catalogVersion)
        assertEquals(2L, (store.write(listOf(category("c1", "Two"))) as CatalogStoreResult.Stored).snapshot.catalogVersion)
        assertEquals(3L, (store.write(listOf(category("c1", "Three"))) as CatalogStoreResult.Stored).snapshot.catalogVersion)
    }

    @Test
    fun aVersionConflictWritesNothingAndDoesNotInventAVersion() {
        val store = store()
        store.write(listOf(category("c1", "One")))  // 1

        val result = store.write(listOf(category("c1", "Stale")), expectedVersion = 0)

        assertTrue(result is CatalogStoreResult.VersionConflict)
        assertEquals(1L, (result as CatalogStoreResult.VersionConflict).currentVersion)
        assertEquals(1L, store.read().catalogVersion)
        assertEquals("One", store.read().categories[0].displayName)
    }

    @Test
    fun aMatchingExpectedVersionIsAccepted() {
        val store = store()
        store.write(listOf(category("c1", "One")))  // 1

        val result = store.write(listOf(category("c1", "Two")), expectedVersion = 1)

        assertEquals(2L, (result as CatalogStoreResult.Stored).snapshot.catalogVersion)
        assertEquals("Two", store.read().categories[0].displayName)
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
                        val result = store.write(listOf(category("c$index", "Shelf $index")))
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
        store.write(listOf(category("c1", "One")))  // 1
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
                        when (store.write(listOf(category("c1", "Writer $index")), expectedVersion = 1)) {
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
        assertEquals(1L, (store.write(listOf(category("c1", "One"))) as CatalogStoreResult.Stored).snapshot.catalogVersion)
        assertTrue(store.write(listOf(category("c1", "Stale")), expectedVersion = 0) is CatalogStoreResult.VersionConflict)
        assertEquals(2L, (store.write(listOf(category("c1", "Two")), expectedVersion = 1) as CatalogStoreResult.Stored).snapshot.catalogVersion)
        assertEquals("Two", store.read().categories[0].displayName)
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
        repeat(3) { store.write(listOf(category("cat-$it", "Shelf $it"))) }
        assertEquals(3L, store.read().catalogVersion)

        // The document is lost or reinitialised - the exact situation that used to rewind the count.
        assertTrue(File(dir, FileCatalogStore.FILE_NAME).delete())
        assertEquals("a lost document reads as nothing configured", 0L, store.read().catalogVersion)

        val stored = assertIsStored(store.write(listOf(category("cat-new", "New Shelf"))))

        // Numbered above every version already issued, so a TV holding 3 accepts it.
        assertEquals(4L, stored.catalogVersion)
    }

    @Test
    fun theCounterKeepsClimbingAcrossRepeatedDocumentLoss() {
        val store = store()
        repeat(2) { store.write(listOf(category("cat-a", "A"))) }
        val document = File(dir, FileCatalogStore.FILE_NAME)

        var expected = 2L
        repeat(3) {
            assertTrue(document.delete())
            expected += 1
            assertEquals(expected, assertIsStored(store.write(listOf(category("cat-b", "B")))).catalogVersion)
        }
    }

    @Test
    fun anUnusableCounterFileFallsBackToTheDocumentRatherThanInventingAVersion() {
        val store = store()
        store.write(listOf(category("cat-a", "A")))
        assertEquals(2L, assertIsStored(store.write(listOf(category("cat-b", "B")))).catalogVersion)

        // A hand-mangled counter must not become a version: the floor is unusable, so the document
        // alone decides and the sequence still moves forward.
        File(dir, FileCatalogStore.VERSION_FILE_NAME).writeText("not-a-number")

        assertEquals(3L, assertIsStored(store.write(listOf(category("cat-c", "C")))).catalogVersion)
    }

    @Test
    fun theCounterFileIsWrittenBesideTheDocument() {
        store().write(listOf(category("cat-a", "A")))

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

        assertEquals(1L, assertIsStored(store().write(listOf(category("cat-a", "A")))).catalogVersion)
    }

    private fun assertIsStored(result: CatalogStoreResult): CatalogSnapshot {
        assertTrue("expected a stored catalog but got $result", result is CatalogStoreResult.Stored)
        return (result as CatalogStoreResult.Stored).snapshot
    }
}
