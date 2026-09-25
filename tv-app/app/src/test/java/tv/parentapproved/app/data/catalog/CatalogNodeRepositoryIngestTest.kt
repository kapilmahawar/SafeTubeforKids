package tv.parentapproved.app.data.catalog

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.VideoEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeRepository
import tv.safetubeforkids.app.data.catalog.CatalogNodeType
import tv.safetubeforkids.app.data.catalog.CategoryEntity
import tv.safetubeforkids.app.data.catalog.ContentItemEntity
import tv.safetubeforkids.app.data.catalog.ContentItemType

/**
 * The version-1 payload -> node tree translation, which is what makes the storage swap mechanical: a
 * migrated installation and a freshly synced one must produce identical trees, with derived ids so a
 * re-sync cannot churn identity, and unchanged nodes left alone.
 *
 * Real Room database, real SQLite: idempotence, ordering and transactional rollback are database
 * behaviours, not in-memory ones.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogNodeRepositoryIngestTest {

    private lateinit var db: CacheDatabase
    private lateinit var repo: CatalogNodeRepository

    private val playlist = "PLcocomelon"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = CatalogNodeRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedCategory(id: String, name: String, order: Int, enabled: Boolean = true) {
        db.categoryDao().insert(
            CategoryEntity(id = id, displayName = name, sortOrder = order, enabled = enabled)
        )
    }

    private suspend fun seedItem(
        id: String,
        categoryId: String,
        type: ContentItemType,
        name: String,
        order: Int,
        playlistId: String? = null,
        videoId: String? = null,
        enabled: Boolean = true,
    ) {
        db.contentItemDao().insert(
            ContentItemEntity(
                id = id, categoryId = categoryId, type = type, displayName = name, sortOrder = order,
                youtubePlaylistId = playlistId, youtubeVideoId = videoId, enabled = enabled,
            )
        )
    }

    /** The approved cache: videos the resolved playlist already brought in, in cache order. */
    private suspend fun seedCachedVideos(vararg videoIds: String) {
        db.videoDao().insertAll(
            videoIds.mapIndexed { index, videoId ->
                VideoEntity(
                    videoId = videoId, playlistId = playlist, title = "Episode $videoId",
                    thumbnailUrl = "thumb-$videoId", durationSeconds = 60, position = index,
                )
            }
        )
    }

    private suspend fun payload(): List<CategoryEntity> = db.categoryDao().getAll()

    // --- the tree the payload describes ----------------------------------------------------------

    @Test
    fun anEmptyCatalogProducesNoNodes() = runBlocking {
        assertEquals(0, repo.ingest(emptyList()))
        assertEquals(0, repo.count())
    }

    @Test
    fun categoriesBecomeRootNodesInConfiguredOrderNotInsertionOrder() = runBlocking {
        seedCategory("c-music", "Music", 1)
        seedCategory("c-learning", "Learning", 2)
        seedCategory("c-cartoon", "Cartoon", 0)

        repo.ingest(payload())

        assertEquals(listOf("Cartoon", "Music", "Learning"), repo.childrenOf(null).map { it.title })
        assertEquals(listOf(0, 1, 2), repo.childrenOf(null).map { it.position })
        assertTrue(repo.childrenOf(null).all { it.nodeType == CatalogNodeType.CATEGORY })
        assertTrue(repo.childrenOf(null).all { it.parentId == null })
    }

    @Test
    fun aCategorysMixedChildrenKeepThePayloadOrder() = runBlocking {
        seedCategory("cat-cartoon", "Cartoon", 0)
        seedItem("i-cocomelon", "cat-cartoon", ContentItemType.PLAYLIST, "Cocomelon", 0, playlistId = playlist)
        seedItem("i-halloween", "cat-cartoon", ContentItemType.VIDEO, "Halloween Special", 1, videoId = "vid-halloween")
        seedItem("i-bluey", "cat-cartoon", ContentItemType.PLAYLIST, "Bluey", 2, playlistId = "PLbluey")

        repo.ingest(payload())

        val children = repo.childrenOf("cat-cartoon")
        assertEquals(listOf("Cocomelon", "Halloween Special", "Bluey"), children.map { it.title })
        assertEquals(listOf(0, 1, 2), children.map { it.position })
        assertEquals(
            listOf(CatalogNodeType.SUBCATEGORY, CatalogNodeType.VIDEO, CatalogNodeType.SUBCATEGORY),
            children.map { it.nodeType },
        )
    }

    @Test
    fun aPlaylistItemBecomesAContainerHoldingItsCachedVideosInCacheOrder() = runBlocking {
        seedCategory("cat-cartoon", "Cartoon", 0)
        seedItem("i-cocomelon", "cat-cartoon", ContentItemType.PLAYLIST, "Cocomelon", 0, playlistId = playlist)
        // Cache order is deliberately not alphabetical, so using it is observable.
        seedCachedVideos("vidB", "vidA", "vidC")

        repo.ingest(payload())

        val container = repo.node("i-cocomelon")!!
        assertEquals(CatalogNodeType.SUBCATEGORY, container.nodeType)
        assertEquals("Cocomelon", container.title)
        assertEquals(playlist, container.youtubePlaylistId)

        val episodes = repo.childrenOf("i-cocomelon")
        assertEquals(listOf("vidB", "vidA", "vidC"), episodes.map { it.youtubeVideoId })
        assertEquals(listOf(0, 1, 2), episodes.map { it.position })
        assertEquals(listOf("Episode vidB", "Episode vidA", "Episode vidC"), episodes.map { it.title })
        assertTrue(episodes.all { it.nodeType == CatalogNodeType.VIDEO })
        assertTrue("provenance is kept on every imported video", episodes.all { it.youtubePlaylistId == playlist })
    }

    @Test
    fun aPlaylistWithNothingCachedBecomesAnEmptyContainer() = runBlocking {
        seedCategory("cat-music", "Music", 0)
        seedItem("i-nursery", "cat-music", ContentItemType.PLAYLIST, "Nursery Songs", 0, playlistId = "PLnursery")

        repo.ingest(payload())

        assertEquals(CatalogNodeType.SUBCATEGORY, repo.node("i-nursery")!!.nodeType)
        assertEquals("PLnursery", repo.node("i-nursery")!!.youtubePlaylistId)
        assertEquals(0, repo.childrenOf("i-nursery").size)
    }

    @Test
    fun directVideosKeepTheirYoutubeIdAndVisibility() = runBlocking {
        seedCategory("cat-cartoon", "Cartoon", 0)
        seedItem("i-halloween", "cat-cartoon", ContentItemType.VIDEO, "Halloween Special", 0, videoId = "vid-halloween")
        seedItem("i-hidden", "cat-cartoon", ContentItemType.VIDEO, "Hidden Episode", 1, videoId = "vid-hidden", enabled = false)

        repo.ingest(payload())

        assertEquals("vid-halloween", repo.node("i-halloween")!!.youtubeVideoId)
        assertTrue(repo.node("i-halloween")!!.enabled)
        assertEquals(false, repo.node("i-hidden")!!.enabled)
    }

    @Test
    fun disabledCategoriesSurviveTheTranslation() = runBlocking {
        seedCategory("cat-hidden", "Hidden", 0, enabled = false)

        repo.ingest(payload())

        assertEquals(false, repo.node("cat-hidden")!!.enabled)
    }

    @Test
    fun everySiblingListIsContiguous() = runBlocking {
        seedCategory("cat-cartoon", "Cartoon", 0)
        seedCategory("cat-music", "Music", 1)
        seedItem("i-a", "cat-cartoon", ContentItemType.VIDEO, "A", 0, videoId = "vid-a")
        seedItem("i-b", "cat-cartoon", ContentItemType.VIDEO, "B", 1, videoId = "vid-b")
        seedItem("i-cocomelon", "cat-cartoon", ContentItemType.PLAYLIST, "Cocomelon", 2, playlistId = playlist)
        seedCachedVideos("v1", "v2")

        repo.ingest(payload())

        listOf(null, "cat-cartoon", "cat-music", "i-cocomelon").forEach { parentId ->
            val children = repo.childrenOf(parentId)
            assertEquals("positions under $parentId", children.indices.toList(), children.map { it.position })
        }
    }

    // --- identity and idempotence ----------------------------------------------------------------

    @Test
    fun idsAreDerivedSoASecondIdenticalIngestChangesNothingAndKeepsTimestamps() = runBlocking {
        seedCategory("cat-cartoon", "Cartoon", 0)
        seedItem("i-cocomelon", "cat-cartoon", ContentItemType.PLAYLIST, "Cocomelon", 0, playlistId = playlist)
        seedCachedVideos("v1", "v2")

        val first = repo.ingest(payload())
        assertTrue("the first ingest must write the tree", first > 0)
        val before = repo.tree().associate { it.id to (it.createdAt to it.updatedAt) }
        assertEquals(
            listOf("cat-cartoon", "i-cocomelon", "i-cocomelon#v1", "i-cocomelon#v2"),
            repo.tree().map { it.id }.sorted(),
        )

        val second = repo.ingest(payload())

        assertEquals("an unchanged payload must not rewrite anything", 0, second)
        assertEquals(before, repo.tree().associate { it.id to (it.createdAt to it.updatedAt) })
    }

    @Test
    fun aReorderedPayloadUpdatesPositionsWithoutRecreatingNodes() = runBlocking {
        seedCategory("cat-cartoon", "Cartoon", 0)
        seedItem("i-a", "cat-cartoon", ContentItemType.VIDEO, "A", 0, videoId = "vid-a")
        seedItem("i-b", "cat-cartoon", ContentItemType.VIDEO, "B", 1, videoId = "vid-b")
        repo.ingest(payload())
        val createdAt = repo.node("i-a")!!.createdAt

        // The parent puts B first on the server.
        db.contentItemDao().update(
            ContentItemEntity(
                id = "i-b", categoryId = "cat-cartoon", type = ContentItemType.VIDEO,
                displayName = "B", sortOrder = 0, youtubeVideoId = "vid-b",
            )
        )
        db.contentItemDao().update(
            ContentItemEntity(
                id = "i-a", categoryId = "cat-cartoon", type = ContentItemType.VIDEO,
                displayName = "A", sortOrder = 1, youtubeVideoId = "vid-a",
            )
        )

        repo.ingest(payload())

        assertEquals(listOf("B", "A"), repo.childrenOf("cat-cartoon").map { it.title })
        assertEquals(listOf(0, 1), repo.childrenOf("cat-cartoon").map { it.position })
        assertEquals("identity survives a reorder", createdAt, repo.node("i-a")!!.createdAt)
    }

    @Test
    fun contentRemovedFromThePayloadLeavesTheTree() = runBlocking {
        seedCategory("cat-cartoon", "Cartoon", 0)
        seedItem("i-a", "cat-cartoon", ContentItemType.VIDEO, "A", 0, videoId = "vid-a")
        seedItem("i-b", "cat-cartoon", ContentItemType.VIDEO, "B", 1, videoId = "vid-b")
        seedItem("i-gone", "cat-cartoon", ContentItemType.VIDEO, "Gone", 2, videoId = "vid-gone")
        repo.ingest(payload())
        assertEquals(4, repo.count())

        db.contentItemDao().deleteById("i-gone")
        repo.ingest(payload())

        assertEquals(3, repo.count())
        assertEquals(listOf("A", "B"), repo.childrenOf("cat-cartoon").map { it.title })
    }

    @Test
    fun aDeletedCategoryTakesItsSubtreeWithIt() = runBlocking {
        seedCategory("cat-cartoon", "Cartoon", 0)
        seedItem("i-cocomelon", "cat-cartoon", ContentItemType.PLAYLIST, "Cocomelon", 0, playlistId = playlist)
        seedCachedVideos("v1", "v2")
        repo.ingest(payload())
        assertEquals(4, repo.count())

        // The category is gone on the server, so the whole subtree goes - container and episodes.
        db.contentItemDao().deleteByCategory("cat-cartoon")
        db.categoryDao().deleteById("cat-cartoon")
        repo.ingest(payload())

        assertEquals(0, repo.count())
    }

    @Test
    fun anIngestThatFailsHalfWayLeavesThePreviousTreeIntact() = runBlocking {
        seedCategory("cat-cartoon", "Cartoon", 0)
        seedItem("i-a", "cat-cartoon", ContentItemType.VIDEO, "A", 0, videoId = "vid-a")
        repo.ingest(payload())
        val before = repo.tree().map { it.id to it.position }

        seedCategory("cat-new", "New", 1)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_ingest BEFORE INSERT ON catalog_nodes " +
                "BEGIN SELECT RAISE(ABORT, 'forced ingest failure'); END"
        )
        val failure = runCatching { repo.ingest(payload()) }
        assertTrue("the forced SQLite failure must reach the caller", failure.isFailure)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_ingest")

        assertEquals("no partial catalog may be left behind", before, repo.tree().map { it.id to it.position })
        assertEquals(0, repo.childrenOf(null).count { it.id == "cat-new" })
    }

    // --- the derived legacy views the projection still consumes ------------------------------------

    @Test
    fun theDerivedViewsReadBackThePayloadInTheSameShape() = runBlocking {
        seedCategory("cat-cartoon", "Cartoon", 0)
        seedCategory("cat-music", "Music", 1)
        seedItem("i-cocomelon", "cat-cartoon", ContentItemType.PLAYLIST, "Cocomelon", 0, playlistId = playlist)
        seedItem("i-halloween", "cat-cartoon", ContentItemType.VIDEO, "Halloween Special", 1, videoId = "vid-halloween")

        repo.ingest(payload())

        val categories = repo.categories()
        assertEquals(listOf("Cartoon", "Music"), categories.map { it.displayName })
        assertEquals(listOf(0, 1), categories.map { it.sortOrder })

        val items = repo.items("cat-cartoon")
        assertEquals(listOf("Cocomelon", "Halloween Special"), items.map { it.displayName })
        assertEquals(listOf(0, 1), items.map { it.sortOrder })
        // A container reads as the playlist item it used to be; a video as a video.
        assertEquals(ContentItemType.PLAYLIST, items[0].type)
        assertEquals(playlist, items[0].youtubePlaylistId)
        assertEquals(ContentItemType.VIDEO, items[1].type)
        assertEquals("vid-halloween", items[1].youtubeVideoId)
    }

    // --- node writes go through the ordering service ------------------------------------------------

    @Test
    fun aNodeAddedLaterJoinsTheEndOfItsParentAndCanBeMoved() = runBlocking {
        seedCategory("cat-cartoon", "Cartoon", 0)
        seedItem("i-a", "cat-cartoon", ContentItemType.VIDEO, "A", 0, videoId = "vid-a")
        seedItem("i-b", "cat-cartoon", ContentItemType.VIDEO, "B", 1, videoId = "vid-b")
        repo.ingest(payload())

        repo.add(
            CatalogNodeEntity(
                id = "i-new", parentId = "cat-cartoon", nodeType = CatalogNodeType.VIDEO,
                title = "New", position = 0, youtubeVideoId = "vid-new",
            )
        )
        assertEquals(listOf("A", "B", "New"), repo.childrenOf("cat-cartoon").map { it.title })

        assertTrue(repo.move("i-new", "cat-cartoon", 0))
        assertEquals(listOf("New", "A", "B"), repo.childrenOf("cat-cartoon").map { it.title })

        repo.rename("i-new", "Renamed")
        assertEquals("Renamed", repo.node("i-new")!!.title)
        repo.setEnabled("i-new", false)
        assertFalse(repo.node("i-new")!!.enabled)
        repo.delete("i-new")
        assertNotNull(repo.node("i-a"))
        assertEquals(3, repo.count())
    }
}
