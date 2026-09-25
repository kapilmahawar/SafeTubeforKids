package tv.parentapproved.app.data.catalog

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeType
import tv.safetubeforkids.app.data.catalog.CatalogOrderingService

/**
 * Ordering is the contract the whole catalog turns on: one `parent_id + position` rule for containers
 * and videos alike, contiguous from 0, and every multi-row mutation atomic. These tests run against a
 * real Room database on real SQLite, as the catalog database tests do, because contiguity and rollback
 * are SQLite behaviours, not in-memory ones.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogOrderingServiceTest {

    private lateinit var db: CacheDatabase
    private lateinit var ordering: CatalogOrderingService

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ordering = CatalogOrderingService(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun category(id: String, title: String) =
        CatalogNodeEntity(id = id, nodeType = CatalogNodeType.CATEGORY, title = title, position = 0)

    private fun subcategory(id: String, parentId: String, title: String) =
        CatalogNodeEntity(
            id = id, parentId = parentId, nodeType = CatalogNodeType.SUBCATEGORY, title = title, position = 0,
        )

    private fun video(id: String, parentId: String, title: String, youtubeId: String = "yt-$id") =
        CatalogNodeEntity(
            id = id, parentId = parentId, nodeType = CatalogNodeType.VIDEO, title = title,
            position = 0, youtubeVideoId = youtubeId,
        )

    private suspend fun titlesOf(parentId: String?) = ordering.childrenOf(parentId).map { it.title }

    private suspend fun positionsOf(parentId: String?) = ordering.childrenOf(parentId).map { it.position }

    // --- the mixed sibling list the spec's example describes ------------------------------------

    @Test
    fun aCategoryHoldsContainersAndVideosInOneOrderedList() = runBlocking {
        ordering.append(category("cartoons", "Cartoons"))
        ordering.append(subcategory("cocomelon", "cartoons", "Cocomelon"))
        ordering.append(video("v-halloween", "cartoons", "Halloween Special"))
        ordering.append(subcategory("bluey", "cartoons", "Bluey"))
        ordering.append(video("v-christmas", "cartoons", "Christmas Special"))
        ordering.append(subcategory("peppa", "cartoons", "Peppa Pig"))

        assertEquals(
            listOf("Cocomelon", "Halloween Special", "Bluey", "Christmas Special", "Peppa Pig"),
            titlesOf("cartoons"),
        )
        assertEquals(listOf(0, 1, 2, 3, 4), positionsOf("cartoons"))
    }

    @Test
    fun categoriesAtRootAreOrderedTheSameWay() = runBlocking {
        ordering.append(category("c-cartoons", "Cartoons"))
        ordering.append(category("c-music", "Music"))
        ordering.append(category("c-learning", "Learning"))

        assertEquals(listOf("Cartoons", "Music", "Learning"), titlesOf(null))
        assertEquals(listOf(0, 1, 2), positionsOf(null))
    }

    @Test
    fun siblingsComeBackInPositionOrderNotInsertionOrder() = runBlocking {
        // Inserted last, but configured first.
        ordering.append(category("c-music", "Music"))
        ordering.append(category("c-cartoons", "Cartoons"))
        ordering.move("c-cartoons", null, 0)

        assertEquals(listOf("Cartoons", "Music"), titlesOf(null))
    }

    // --- moving ---------------------------------------------------------------------------------

    @Test
    fun movingAnItemWithinItsParentRenumbersTheWholeSiblingList() = runBlocking {
        ordering.append(category("cat", "Cat"))
        listOf("A", "B", "C", "D").forEach { ordering.append(video("v$it", "cat", it)) }
        assertEquals(listOf("A", "B", "C", "D"), titlesOf("cat"))

        // Move D before B: A D B C.
        assertTrue(ordering.move("vD", "cat", 1))

        assertEquals(listOf("A", "D", "B", "C"), titlesOf("cat"))
        assertEquals(listOf(0, 1, 2, 3), positionsOf("cat"))
    }

    @Test
    fun anOutOfRangePositionIsClampedInsteadOfCorruptingTheList() = runBlocking {
        ordering.append(category("cat", "Cat"))
        listOf("A", "B").forEach { ordering.append(video("v$it", "cat", it)) }

        ordering.move("vA", "cat", 99)
        assertEquals(listOf("B", "A"), titlesOf("cat"))

        ordering.move("vA", "cat", -5)
        assertEquals(listOf("A", "B"), titlesOf("cat"))
        assertEquals(listOf(0, 1), positionsOf("cat"))
    }

    @Test
    fun moveUpAndMoveDownWalkTheSiblingList() = runBlocking {
        ordering.append(category("cat", "Cat"))
        listOf("A", "B", "C").forEach { ordering.append(video("v$it", "cat", it)) }

        ordering.moveUp("vC")
        assertEquals(listOf("A", "C", "B"), titlesOf("cat"))

        ordering.moveDown("vA")
        assertEquals(listOf("C", "A", "B"), titlesOf("cat"))

        // The ends are no-ops, not wrap-arounds.
        ordering.moveUp("vC")
        ordering.moveDown("vB")
        assertEquals(listOf("C", "A", "B"), titlesOf("cat"))
    }

    @Test
    fun movingBetweenParentsClosesTheOldGapAndInsertsAtTheRequestedPlace() = runBlocking {
        ordering.append(category("cartoons", "Cartoons"))
        ordering.append(category("music", "Music"))
        ordering.append(subcategory("cocomelon", "cartoons", "Cocomelon"))
        ordering.append(subcategory("bluey", "cartoons", "Bluey"))
        ordering.append(subcategory("kids-songs", "music", "Kids Songs"))

        // Move Bluey to Music, before Kids Songs.
        assertTrue(ordering.move("bluey", "music", 0))

        assertEquals(listOf("Cocomelon"), titlesOf("cartoons"))
        assertEquals(listOf(0), positionsOf("cartoons"))
        assertEquals(listOf("Bluey", "Kids Songs"), titlesOf("music"))
        assertEquals(listOf(0, 1), positionsOf("music"))
    }

    @Test
    fun aVideoMovesBetweenContainersThroughTheSameCodeAsAContainer() = runBlocking {
        ordering.append(category("cartoons", "Cartoons"))
        ordering.append(subcategory("cocomelon", "cartoons", "Cocomelon"))
        ordering.append(video("v1", "cartoons", "Direct Video"))

        // The same move method serves a video: nothing here is video-specific.
        assertTrue(ordering.move("v1", "cocomelon", 0))

        assertEquals(listOf("Cocomelon"), titlesOf("cartoons"))
        assertEquals(listOf("Direct Video"), titlesOf("cocomelon"))
        assertEquals("cocomelon", ordering.node("v1")!!.parentId)
    }

    @Test
    fun movingSomethingThatDoesNotExistChangesNothing() = runBlocking {
        ordering.append(category("cartoons", "Cartoons"))

        assertFalse(ordering.move("nope", "cartoons", 0))
        assertEquals(listOf("Cartoons"), titlesOf(null))
    }

    // --- atomicity ------------------------------------------------------------------------------

    @Test
    fun aMoveThatFailsHalfWayLeavesThePreviousOrderExactlyAsItWas() = runBlocking {
        ordering.append(category("cat", "Cat"))
        listOf("A", "B", "C").forEach { ordering.append(video("v$it", "cat", it)) }
        val before = ordering.childrenOf("cat").map { it.id to it.position }

        // Fail any write that would land a node at position 2, so the renumbering cannot complete.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_position_two BEFORE UPDATE ON catalog_nodes " +
                "WHEN NEW.position = 2 BEGIN SELECT RAISE(ABORT, 'forced reorder failure'); END"
        )

        val failure = runCatching { ordering.move("vC", "cat", 0) }
        assertTrue("the forced SQLite failure must reach the caller", failure.isFailure)

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_position_two")

        // Every sibling is exactly where it was: no half-reordered list was ever committed.
        assertEquals(before, ordering.childrenOf("cat").map { it.id to it.position })
    }

    @Test
    fun aMoveThatFailsAcrossParentsLeavesBothListsUntouched() = runBlocking {
        ordering.append(category("a", "A"))
        ordering.append(category("b", "B"))
        ordering.append(video("v1", "a", "One"))
        ordering.append(video("v2", "a", "Two"))
        ordering.append(video("v3", "b", "Three"))
        val listA = ordering.childrenOf("a").map { it.id to it.position }
        val listB = ordering.childrenOf("b").map { it.id to it.position }

        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_any_update BEFORE UPDATE ON catalog_nodes " +
                "BEGIN SELECT RAISE(ABORT, 'forced move failure'); END"
        )
        val failure = runCatching { ordering.move("v2", "b", 0) }
        assertTrue(failure.isFailure)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_any_update")

        assertEquals(listA, ordering.childrenOf("a").map { it.id to it.position })
        assertEquals(listB, ordering.childrenOf("b").map { it.id to it.position })
        assertEquals("a", ordering.node("v2")!!.parentId)
    }

    @Test
    fun normaliseClosesAnyGapLeftByOlderData() = runBlocking {
        ordering.append(category("cartoons", "Cartoons"))
        ordering.append(video("vA", "cartoons", "A", "yt-a"))
        ordering.append(video("vB", "cartoons", "B", "yt-b"))
        ordering.append(video("vC", "cartoons", "C", "yt-c"))

        // Simulate a legacy row that arrived with a sparse position.
        db.catalogNodeDao().updatePlacement("vC", "cartoons", 7, System.currentTimeMillis())
        assertEquals(listOf(0, 1, 7), positionsOf("cartoons"))

        val changed = ordering.normalise("cartoons")

        assertEquals(1, changed)
        assertEquals(listOf(0, 1, 2), positionsOf("cartoons"))
        assertEquals(listOf("A", "B", "C"), titlesOf("cartoons"))
    }

    @Test
    fun deletingAContainerTakesItsChildrenWithIt() = runBlocking {
        ordering.append(category("cartoons", "Cartoons"))
        ordering.append(subcategory("cocomelon", "cartoons", "Cocomelon"))
        ordering.append(video("v1", "cocomelon", "Episode 1"))

        db.catalogNodeDao().deleteById("cocomelon")

        assertEquals(0, db.catalogNodeDao().countChildrenOf("cocomelon"))
        assertEquals(listOf("Cartoons"), titlesOf(null))
    }
}
