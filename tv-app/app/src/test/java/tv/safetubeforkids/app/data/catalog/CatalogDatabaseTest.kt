package tv.safetubeforkids.app.data.catalog

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import tv.safetubeforkids.app.data.cache.VideoEntity
import tv.safetubeforkids.app.playback.PlaybackApproval
import tv.safetubeforkids.app.playback.PlaybackAuthorization

/**
 * The catalog repository contract, against a real Room database on real SQLite (the JVM suite runs
 * Android code through Robolectric).
 *
 * These tests deliberately use a real database rather than a hand-written fake: what is under test
 * is the `position` ordering rule, the foreign-key cascade and the enum/column mapping - none of
 * which a fake can prove.
 *
 * **The storage of record is `catalog_nodes`, and only `catalog_nodes`.** Everything here seeds and
 * reads through [CatalogRepository], whose reads are derived from the node tree, so a shelf is a ROOT
 * `CATEGORY` node and an entry is one of its children. The old `categories` / `content_items` tables
 * and their DAOs are gone - that is why the seeding no longer says `insert` into a table, and
 * `theLegacyCatalogTablesAreGone` proves the tables cannot come back.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogDatabaseTest {

    private lateinit var db: CacheDatabase
    private lateinit var repo: CatalogRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = CatalogRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun category(id: String, name: String, sortOrder: Int) {
        repo.upsertCategory(CategoryEntity(id = id, displayName = name, sortOrder = sortOrder))
    }

    private suspend fun playlist(id: String, categoryId: String, name: String, sortOrder: Int, playlistId: String) {
        repo.upsertItem(
            ContentItemEntity(
                id = id,
                categoryId = categoryId,
                type = ContentItemType.PLAYLIST,
                displayName = name,
                sortOrder = sortOrder,
                youtubePlaylistId = playlistId,
            )
        )
    }

    private suspend fun video(id: String, categoryId: String, name: String, sortOrder: Int, videoId: String) {
        repo.upsertItem(
            ContentItemEntity(
                id = id,
                categoryId = categoryId,
                type = ContentItemType.VIDEO,
                displayName = name,
                sortOrder = sortOrder,
                youtubeVideoId = videoId,
            )
        )
    }

    /** The shelves in configured order. A derived view of the ROOT CATEGORY nodes. */
    private suspend fun categories(): List<CategoryEntity> = repo.getCategories()

    /** One shelf's entries in configured order. A derived view of that node's children. */
    private suspend fun itemsIn(categoryId: String): List<ContentItemEntity> = repo.getItems(categoryId)

    /** Every entry of every shelf, in the flat `category_id, sort_order, id` order the old table used. */
    private suspend fun allItems(): List<ContentItemEntity> =
        repo.getCategories().flatMap { repo.getItems(it.id) }
            .sortedWith(compareBy({ it.categoryId }, { it.sortOrder }, { it.id }))

    /**
     * How many entries the catalog holds. Counts each configured child of a shelf once, which is
     * exactly the row count the removed `content_items` table used to report - a playlist is one
     * entry, not one entry per episode it later imported.
     */
    private suspend fun itemCount(): Int =
        repo.getCategories().sumOf { repo.getItems(it.id).size }

    private suspend fun categoryCount(): Int = repo.getCategories().size

    /** Table names as SQLite itself reports them, which is where a removed table must be absent. */
    private fun tableNames(): List<String> {
        val names = mutableListOf<String>()
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
            .use { c -> while (c.moveToNext()) names.add(c.getString(0)) }
        return names
    }

    // ------------------------------------------------------------------ CAT-DB-01

    @Test
    fun categoryQueryReturnsParentConfiguredOrderNotInsertionOrder() = runBlocking {
        // Inserted in an order that is neither the configured order nor alphabetical.
        category("cat-learning", "Learning", 20)
        category("cat-cartoon", "Cartoon", 5)
        category("cat-music", "Music", 10)
        category("cat-stories", "Stories", 30)

        val names = categories().map { it.displayName }

        assertEquals(listOf("Cartoon", "Music", "Learning", "Stories"), names)
    }

    @Test
    fun categorySelectorsDoNotDependOnDisplayNameOrPrimaryKeyOrder() = runBlocking {
        // Identifiers sort in exactly the opposite order to the display order, and the display
        // names are alphabetical in the opposite order too: only position can produce this.
        category("a-cat", "Zebras", 0)
        category("z-cat", "Apples", 1)

        assertEquals(listOf("Zebras", "Apples"), categories().map { it.displayName })
    }

    @Test
    fun observeCategoriesEmitsInSortOrder() = runBlocking {
        category("c3", "Music", 2)
        category("c1", "Cartoon", 0)
        category("c2", "Learning", 1)

        assertEquals(
            listOf("Cartoon", "Learning", "Music"),
            repo.observeCategories().first().map { it.displayName },
        )
    }

    @Test
    fun getCategoryByIdAndMetadataFields() = runBlocking {
        category("cat-music", "Music", 1)

        val loaded = repo.getCategory("cat-music")
        assertNotNull(loaded)
        assertEquals("Music", loaded!!.displayName)
        assertEquals(1, loaded.sortOrder)
        assertTrue(loaded.enabled)
        assertTrue("createdAt should be stamped", loaded.createdAt > 0)
        assertTrue("updatedAt should be stamped", loaded.updatedAt > 0)
        assertNull(repo.getCategory("nope"))
    }

    // ------------------------------------------------------------------ CAT-DB-02

    @Test
    fun itemQueryReturnsSortOrderNotInsertionOrder() = runBlocking {
        category("cat-music", "Music", 0)
        video("i-learning", "cat-music", "Learning Song", 20, "vid-learning")
        video("i-cartoon", "cat-music", "Cartoon Song", 5, "vid-cartoon")
        video("i-music", "cat-music", "Music Song", 10, "vid-music")

        val names = itemsIn("cat-music").map { it.displayName }

        assertEquals(listOf("Cartoon Song", "Music Song", "Learning Song"), names)
    }

    @Test
    fun itemsOfAnotherCategoryAreNotReturned() = runBlocking {
        category("cat-a", "A", 0)
        category("cat-b", "B", 1)
        video("i-a", "cat-a", "In A", 0, "vid-a")
        video("i-b", "cat-b", "In B", 0, "vid-b")

        assertEquals(listOf("In A"), itemsIn("cat-a").map { it.displayName })
        assertEquals(1, itemsIn("cat-a").size)
        assertEquals(1, itemsIn("cat-b").size)
        assertEquals(2, itemCount())
    }

    @Test
    fun getItemById() = runBlocking {
        category("cat-music", "Music", 0)
        playlist("i-nursery", "cat-music", "Nursery Songs", 0, "PLnursery")

        val loaded = repo.getItem("i-nursery")
        assertNotNull(loaded)
        assertEquals("PLnursery", loaded!!.youtubePlaylistId)
        assertNull(repo.getItem("absent"))
    }

    // ------------------------------------------------------------------ CAT-DB-03

    @Test
    fun playlistItemStoresPlaylistIdAndNoVideoId() = runBlocking {
        category("cat-cartoon", "Cartoon", 0)
        playlist("i-peppa", "cat-cartoon", "Peppa Pig", 0, "PLpeppa123")

        val stored = repo.getItem("i-peppa")!!

        assertEquals(ContentItemType.PLAYLIST, stored.type)
        assertEquals("PLpeppa123", stored.youtubePlaylistId)
        assertNull("a playlist item must not carry a video id", stored.youtubeVideoId)
    }

    @Test
    fun nodeTypeSurvivesTheRoundTripAsText() = runBlocking {
        category("cat-cartoon", "Cartoon", 0)
        playlist("i-peppa", "cat-cartoon", "Peppa Pig", 0, "PLpeppa123")
        video("i-twinkle", "cat-cartoon", "Twinkle Twinkle", 1, "DuXwFlL8Usk")

        // Read the raw column: the node type is stored by name, not by ordinal, so reordering the
        // enum in future cannot silently reinterpret stored rows. The expected values are written
        // as literals for the same reason - `CatalogNodeType.SUBCATEGORY.name` would follow a rename
        // and prove nothing.
        val stored = mutableListOf<String>()
        db.openHelper.readableDatabase
            .query("SELECT node_type FROM catalog_nodes WHERE id IN ('i-peppa', 'i-twinkle') ORDER BY id")
            .use { c -> while (c.moveToNext()) stored.add(c.getString(0)) }

        assertEquals(listOf("SUBCATEGORY", "VIDEO"), stored)
    }

    // ------------------------------------------------------------------ CAT-DB-04

    @Test
    fun videoItemStoresVideoIdAndNoPlaylistId() = runBlocking {
        category("cat-music", "Music", 0)
        video("i-twinkle", "cat-music", "Twinkle Twinkle", 2, "DuXwFlL8Usk")

        val stored = repo.getItem("i-twinkle")!!

        assertEquals(ContentItemType.VIDEO, stored.type)
        assertEquals("DuXwFlL8Usk", stored.youtubeVideoId)
        assertNull("a video item must not carry a playlist id", stored.youtubePlaylistId)
    }

    // ------------------------------------------------------------------ CAT-DB-05

    @Test
    fun malformedItemsAreRejected() {
        val rejected = listOf(
            "PLAYLIST with no playlist id" to {
                ContentItemEntity(
                    id = "i1", categoryId = "c", type = ContentItemType.PLAYLIST,
                    displayName = "x", sortOrder = 0, youtubePlaylistId = null,
                )
            },
            "PLAYLIST carrying a video id too" to {
                ContentItemEntity(
                    id = "i2", categoryId = "c", type = ContentItemType.PLAYLIST,
                    displayName = "x", sortOrder = 0,
                    youtubePlaylistId = "PLx", youtubeVideoId = "vidx",
                )
            },
            "PLAYLIST with a blank playlist id" to {
                ContentItemEntity(
                    id = "i3", categoryId = "c", type = ContentItemType.PLAYLIST,
                    displayName = "x", sortOrder = 0, youtubePlaylistId = "   ",
                )
            },
            "VIDEO with no video id" to {
                ContentItemEntity(
                    id = "i4", categoryId = "c", type = ContentItemType.VIDEO,
                    displayName = "x", sortOrder = 0, youtubeVideoId = null,
                )
            },
            "VIDEO carrying a playlist id too" to {
                ContentItemEntity(
                    id = "i5", categoryId = "c", type = ContentItemType.VIDEO,
                    displayName = "x", sortOrder = 0,
                    youtubeVideoId = "vidx", youtubePlaylistId = "PLx",
                )
            },
            "VIDEO with a blank video id" to {
                ContentItemEntity(
                    id = "i6", categoryId = "c", type = ContentItemType.VIDEO,
                    displayName = "x", sortOrder = 0, youtubeVideoId = "",
                )
            },
        )

        rejected.forEach { (what, build) ->
            try {
                build()
                throw AssertionError("$what should have been rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "$what should name the problem, got: ${expected.message}",
                    expected.message!!.contains("Malformed catalog content item"),
                )
            }
        }
    }

    @Test
    fun repositoryRejectsItemsWithBlankStructuralFields() = runBlocking {
        category("cat-music", "Music", 0)

        val blankId = ContentItemEntity(
            id = "  ", categoryId = "cat-music", type = ContentItemType.VIDEO,
            displayName = "x", sortOrder = 0, youtubeVideoId = "v",
        )
        assertEquals(
            CatalogWriteResult.Rejected("a content item requires a non-blank id"),
            repo.upsertItem(blankId),
        )

        val blankCategory = ContentItemEntity(
            id = "i1", categoryId = "", type = ContentItemType.VIDEO,
            displayName = "x", sortOrder = 0, youtubeVideoId = "v",
        )
        assertTrue(repo.upsertItem(blankCategory) is CatalogWriteResult.Rejected)

        val blankName = ContentItemEntity(
            id = "i2", categoryId = "cat-music", type = ContentItemType.VIDEO,
            displayName = "", sortOrder = 0, youtubeVideoId = "v",
        )
        assertTrue(repo.upsertItem(blankName) is CatalogWriteResult.Rejected)

        assertEquals("nothing should have been written", 0, itemCount())

        assertTrue(repo.upsertCategory(CategoryEntity(id = "", displayName = "X", sortOrder = 0))
            is CatalogWriteResult.Rejected)
        assertEquals(1, categoryCount())
    }

    // ------------------------------------------------------------------ CAT-DB-06

    @Test
    fun displayNameIsIndependentOfTheYoutubeIdentifier() = runBlocking {
        category("cat-music", "Music", 0)
        playlist(
            id = "i-nursery",
            categoryId = "cat-music",
            name = "Nursery Songs",
            sortOrder = 0,
            playlistId = "PLsuperFunEducationalSongs2026Official",
        )

        val stored = repo.getItem("i-nursery")!!

        assertEquals("Nursery Songs", stored.displayName)
        assertEquals("PLsuperFunEducationalSongs2026Official", stored.youtubePlaylistId)
        assertFalse(
            "the child-facing name must not be derived from the YouTube id",
            stored.displayName.contains(stored.youtubePlaylistId!!),
        )
    }

    // ------------------------------------------------------------------ CAT-DB-07

    @Test
    fun deletingACategoryLeavesNoOrphanedItems() = runBlocking {
        category("cat-music", "Music", 0)
        category("cat-stories", "Stories", 1)
        playlist("i-nursery", "cat-music", "Nursery Songs", 0, "PLnursery")
        video("i-twinkle", "cat-music", "Twinkle Twinkle", 1, "vidtwinkle")
        video("i-story", "cat-stories", "Bedtime Story", 0, "vidstory")
        assertEquals(3, itemCount())

        repo.deleteCategory("cat-music")

        assertEquals(0, itemsIn("cat-music").size)
        assertEquals("the other category's items must survive", 1, itemCount())
        assertEquals(listOf("Bedtime Story"), allItems().map { it.displayName })
        assertNull(repo.getItem("i-nursery"))
    }

    @Test
    fun anItemCannotReferenceACategoryThatDoesNotExist() = runBlocking {
        val orphan = ContentItemEntity(
            id = "i-orphan", categoryId = "cat-does-not-exist", type = ContentItemType.VIDEO,
            displayName = "Orphan", sortOrder = 0, youtubeVideoId = "v",
        )

        try {
            repo.upsertItem(orphan)
            throw AssertionError("the foreign key should have refused an unknown category")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            assertTrue(
                "expected a FOREIGN KEY failure, got: ${expected.message}",
                expected.message!!.contains("FOREIGN KEY"),
            )
        }
    }

    // ------------------------------------------------------------------ CAT-DB-08

    @Test
    fun catalogMetadataStoresVersionAndSuccessfulSyncTime() = runBlocking {
        assertNull("nothing is known before the first sync", repo.getMetadata())

        repo.markSyncSucceeded(catalogVersion = 42L, syncedAt = 1_700_000_000_000L)

        val stored = repo.getMetadata()!!
        assertEquals(CatalogMetadataEntity.SINGLETON_ID, stored.id)
        assertEquals(42L, stored.catalogVersion)
        assertEquals(1_700_000_000_000L, stored.lastSuccessfulSyncAt)
    }

    @Test
    fun aFailedAttemptDoesNotMoveTheSuccessfulSyncMarker() = runBlocking {
        repo.markSyncSucceeded(catalogVersion = 7L, syncedAt = 1_000L)
        repo.markSyncAttempt(attemptedAt = 2_000L)

        val stored = repo.getMetadata()!!
        assertEquals("a newer attempt must not look like a success", 7L, stored.catalogVersion)
        assertEquals(1_000L, stored.lastSuccessfulSyncAt)
        assertEquals(2_000L, stored.lastAttemptAt)
    }

    @Test
    fun serverVersionIsTrackedSeparatelyFromTheStoredVersion() = runBlocking {
        repo.markSyncSucceeded(catalogVersion = 7L, syncedAt = 1_000L)
        repo.markServerVersion(9L)

        val stored = repo.getMetadata()!!
        assertEquals(7L, stored.catalogVersion)
        assertEquals(9L, stored.serverVersion)
    }

    @Test
    fun metadataIsASingleRow() = runBlocking {
        repo.upsertMetadata(CatalogMetadataEntity(catalogVersion = 1L))
        repo.upsertMetadata(CatalogMetadataEntity(catalogVersion = 2L))

        val ids = mutableListOf<Int>()
        db.openHelper.readableDatabase.query("SELECT id FROM catalog_metadata").use { c ->
            while (c.moveToNext()) ids.add(c.getInt(0))
        }
        assertEquals(listOf(CatalogMetadataEntity.SINGLETON_ID), ids)
        assertEquals(2L, repo.getMetadata()!!.catalogVersion)
    }

    // ------------------------------------------------------- mixed playlist/video

    @Test
    fun oneCategoryCanHoldPlaylistsAndVideosInOneConfiguredOrder() = runBlocking {
        category("cat-music", "Music", 0)
        // Inserted deliberately out of order.
        video("i-wheels", "cat-music", "Wheels on Bus", 3, "vidwheels")
        playlist("i-abc", "cat-music", "ABC Songs", 1, "PLabc")
        video("i-twinkle", "cat-music", "Twinkle Twinkle", 2, "vidtwinkle")
        playlist("i-nursery", "cat-music", "Nursery Songs", 0, "PLnursery")

        val items = itemsIn("cat-music")

        assertEquals(
            listOf("Nursery Songs", "ABC Songs", "Twinkle Twinkle", "Wheels on Bus"),
            items.map { it.displayName },
        )
        assertEquals(
            listOf(
                ContentItemType.PLAYLIST, ContentItemType.PLAYLIST,
                ContentItemType.VIDEO, ContentItemType.VIDEO,
            ),
            items.map { it.type },
        )
        assertEquals(listOf(0, 1, 2, 3), items.map { it.sortOrder })
    }

    @Test
    fun fullHierarchyFromTheTaskShapeCanBeStored() = runBlocking {
        repo.upsertCategories(
            listOf(
                CategoryEntity("cat-cartoon", "Cartoon", 0),
                CategoryEntity("cat-music", "Music", 1),
                CategoryEntity("cat-learning", "Learning", 2),
                CategoryEntity("cat-stories", "Stories", 3),
            )
        ).let { assertEquals(CatalogWriteResult.Written, it) }

        repo.upsertItems(
            listOf(
                ContentItemEntity("i-cocomelon", "cat-cartoon", ContentItemType.PLAYLIST, "Cocomelon", 0, youtubePlaylistId = "PLcocomelon"),
                ContentItemEntity("i-bluey", "cat-cartoon", ContentItemType.PLAYLIST, "Bluey", 1, youtubePlaylistId = "PLbluey"),
                ContentItemEntity("i-peppa", "cat-cartoon", ContentItemType.PLAYLIST, "Peppa Pig", 2, youtubePlaylistId = "PLpeppa"),
                ContentItemEntity("i-nursery", "cat-music", ContentItemType.PLAYLIST, "Nursery Songs", 0, youtubePlaylistId = "PLnursery"),
                ContentItemEntity("i-abc", "cat-music", ContentItemType.PLAYLIST, "ABC Songs", 1, youtubePlaylistId = "PLabc"),
                ContentItemEntity("i-twinkle", "cat-music", ContentItemType.VIDEO, "Twinkle Twinkle", 2, youtubeVideoId = "DuXwFlL8Usk"),
                ContentItemEntity("i-wheels", "cat-music", ContentItemType.VIDEO, "Wheels on the Bus", 3, youtubeVideoId = "vidwheels"),
                ContentItemEntity("i-numbers", "cat-learning", ContentItemType.PLAYLIST, "Numbers", 0, youtubePlaylistId = "PLnumbers"),
                ContentItemEntity("i-alphabets", "cat-learning", ContentItemType.PLAYLIST, "Alphabets", 1, youtubePlaylistId = "PLalphabets"),
                ContentItemEntity("i-colors", "cat-learning", ContentItemType.VIDEO, "Colors", 2, youtubeVideoId = "vidcolors"),
            )
        ).let { assertEquals(CatalogWriteResult.Written, it) }

        assertEquals(
            listOf("Cartoon", "Music", "Learning", "Stories"),
            repo.getCategories().map { it.displayName },
        )
        assertEquals(
            listOf("Cocomelon", "Bluey", "Peppa Pig"),
            repo.getItems("cat-cartoon").map { it.displayName },
        )
        assertEquals(
            listOf("Nursery Songs", "ABC Songs", "Twinkle Twinkle", "Wheels on the Bus"),
            repo.getItems("cat-music").map { it.displayName },
        )
        assertEquals(
            listOf("Numbers", "Alphabets", "Colors"),
            repo.getItems("cat-learning").map { it.displayName },
        )
        assertEquals("Stories is empty, not absent", emptyList<String>(), repo.getItems("cat-stories").map { it.displayName })
    }

    // ------------------------------------------- the catalog is not an authorization mechanism

    @Test
    fun aCatalogEntryDoesNotGrantPlaybackPermission() = runBlocking {
        // An approved source and one approved video - the existing security model.
        db.channelDao().insert(
            ChannelEntity(
                sourceType = "yt_playlist",
                sourceId = "PLapproved",
                sourceUrl = "https://www.youtube.com/playlist?list=PLapproved",
                displayName = "Approved Cartoons",
            )
        )
        db.videoDao().insertAll(
            listOf(VideoEntity("approvedVid", "PLapproved", "Approved Video", "thumb", 60, 0))
        )

        // A parent configures a catalog shelf that also points at a video nobody approved.
        category("cat-music", "Music", 0)
        video("i-rogue", "cat-music", "Looks Innocent", 0, "unapprovedVid")
        video("i-approved", "cat-music", "Approved Video", 1, "approvedVid")

        // The catalog row exists...
        assertNotNull(repo.getItem("i-rogue"))

        // ...and it still does not play.
        assertTrue(
            "an unapproved video must not be playable just because the catalog lists it",
            PlaybackAuthorization.authorize(db, "unapprovedVid") is PlaybackApproval.Rejected,
        )
        assertTrue(
            PlaybackAuthorization.authorize(db, "approvedVid") is PlaybackApproval.Approved,
        )

        // Withdraw the approval while the catalog keeps pointing at the video: still refused.
        db.channelDao().deleteAll()
        assertNotNull("the catalog row is untouched by an approval change", repo.getItem("i-approved"))
        assertTrue(PlaybackAuthorization.authorize(db, "approvedVid") is PlaybackApproval.Rejected)

        // The catalogue held no authorization data of its own to fall back on.
        assertEquals(2, itemCount())
    }

    @Test
    fun theSiblingIndexIsActuallyUsedByTheChildQuery() {
        val plan = mutableListOf<String>()
        db.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN " +
                "SELECT * FROM catalog_nodes WHERE parent_id = 'cat-music' " +
                "ORDER BY position ASC, id ASC"
        ).use { c ->
            while (c.moveToNext()) plan.add(c.getString(c.getColumnIndexOrThrow("detail")))
        }

        // Without the composite index the first line would be "SCAN catalog_nodes". The second line
        // is the honest, bounded cost of the `id` tie-break: SQLite uses the index for the parent
        // lookup and its position order, then sorts only that parent's children to break ties. The
        // tie-break is not optional - without it the order of two children sharing a position would
        // be unspecified.
        assertEquals(
            listOf(
                "SEARCH TABLE catalog_nodes USING INDEX index_catalog_nodes_parent_id_position (parent_id=?)",
                "USE TEMP B-TREE FOR RIGHT PART OF ORDER BY",
            ),
            plan,
        )
    }

    @Test
    fun catalogTablesCarryNoApprovalFlags() {
        val columns = mutableListOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA table_info(`catalog_nodes`)").use { c ->
            while (c.moveToNext()) columns.add(c.getString(c.getColumnIndexOrThrow("name")))
        }

        assertEquals(
            listOf(
                "id", "parent_id", "node_type", "title", "position", "enabled",
                "youtube_video_id", "youtube_playlist_id",
                "thumbnail_mode", "thumbnail_video_id", "thumbnail_url",
                "created_at", "updated_at",
            ),
            columns,
        )
        assertFalse(columns.contains("approved"))
        assertFalse(columns.contains("playback_allowed"))
    }

    // -------------------------------------------------- the legacy tables are gone

    /**
     * The swap's acceptance condition, stated where SQLite can answer it: `catalog_nodes` is the only
     * catalog table, and `categories` / `content_items` do not exist - not at rest, not after a write
     * and not after a replacement.
     *
     * The compile-time half of this guard is the deletion of `CategoryDao` / `ContentItemDao`: any
     * source reference to them, or to a `categoryDao()` / `contentItemDao()` accessor, is now a build
     * error rather than a runtime surprise. The reflection check below asserts the types are really
     * absent from the compiled artifact rather than merely unreferenced.
     */
    @Test
    fun theLegacyCatalogTablesAreGone() = runBlocking {
        assertFalse("categories must not exist in a fresh database", tableNames().contains("categories"))
        assertFalse("content_items must not exist in a fresh database", tableNames().contains("content_items"))
        assertTrue("catalog_nodes is the catalog storage", tableNames().contains("catalog_nodes"))

        // A write through every mutation path must not recreate them either.
        category("cat-music", "Music", 0)
        playlist("i-nursery", "cat-music", "Nursery Songs", 0, "PLnursery")
        video("i-twinkle", "cat-music", "Twinkle Twinkle", 1, "vidtwinkle")
        repo.deleteItem("i-twinkle")
        repo.replaceCatalog(
            categories = listOf(CategoryEntity("cat-music", "Music", 0)),
            contentItems = listOf(
                ContentItemEntity("i-nursery", "cat-music", ContentItemType.PLAYLIST, "Nursery Songs", 0, youtubePlaylistId = "PLnursery"),
            ),
            metadata = CatalogMetadataEntity(catalogVersion = 1L),
            syncedAt = 100L,
        )

        val tables = tableNames()
        assertTrue("the catalog must still be readable through the tree", tables.contains("catalog_nodes"))
        assertFalse("categories came back: $tables", tables.contains("categories"))
        assertFalse("content_items came back: $tables", tables.contains("content_items"))
        assertEquals(listOf("Music"), categories().map { it.displayName })
        assertEquals(listOf("Nursery Songs"), itemsIn("cat-music").map { it.displayName })

        listOf(
            "tv.safetubeforkids.app.data.catalog.CategoryDao",
            "tv.safetubeforkids.app.data.catalog.ContentItemDao",
        ).forEach { type ->
            try {
                Class.forName(type)
                throw AssertionError("$type still exists on the classpath")
            } catch (expected: ClassNotFoundException) {
                // expected: the legacy DAO type is not in the build at all
            }
        }
    }

    // ------------------------------------------------------------- atomic replacement

    @Test
    fun replaceCatalogSwapsEverythingInOneTransaction() = runBlocking {
        repo.replaceCatalog(
            categories = listOf(CategoryEntity("old", "Old Shelf", 0)),
            contentItems = listOf(
                ContentItemEntity("old-item", "old", ContentItemType.VIDEO, "Old Video", 0, youtubeVideoId = "v1"),
            ),
            metadata = CatalogMetadataEntity(),
            syncedAt = 500L,
        )

        val result = repo.replaceCatalog(
            categories = listOf(
                CategoryEntity("cat-cartoon", "Cartoon", 0),
                CategoryEntity("cat-music", "Music", 1),
            ),
            contentItems = listOf(
                ContentItemEntity("i-peppa", "cat-cartoon", ContentItemType.PLAYLIST, "Peppa Pig", 0, youtubePlaylistId = "PLpeppa"),
                ContentItemEntity("i-twinkle", "cat-music", ContentItemType.VIDEO, "Twinkle Twinkle", 0, youtubeVideoId = "vidtwinkle"),
            ),
            metadata = CatalogMetadataEntity(catalogVersion = 12L),
            syncedAt = 900L,
        )

        assertEquals(CatalogWriteResult.Written, result)
        assertEquals(listOf("Cartoon", "Music"), repo.getCategories().map { it.displayName })
        assertEquals(2, itemCount())
        assertNull("the replaced shelf must be gone", repo.getItem("old-item"))
        assertEquals(12L, repo.getMetadata()!!.catalogVersion)
        assertEquals(900L, repo.getMetadata()!!.lastSuccessfulSyncAt)
    }

    @Test
    fun aReplacementThatFailsHalfwayLeavesTheLastKnownGoodCatalogIntact() = runBlocking {
        repo.replaceCatalog(
            categories = listOf(CategoryEntity("cat-good", "Good Shelf", 0)),
            contentItems = listOf(
                ContentItemEntity("i-good", "cat-good", ContentItemType.VIDEO, "Good Video", 0, youtubeVideoId = "v1"),
            ),
            metadata = CatalogMetadataEntity(catalogVersion = 1L),
            syncedAt = 100L,
        )

        // Passes the structural checks, then fails on the foreign key inside the transaction.
        val poisoned = listOf(
            ContentItemEntity("i-new", "cat-good", ContentItemType.VIDEO, "New Video", 0, youtubeVideoId = "v2"),
            ContentItemEntity("i-orphan", "cat-absent", ContentItemType.VIDEO, "Orphan", 1, youtubeVideoId = "v3"),
        )

        try {
            repo.replaceCatalog(
                categories = listOf(CategoryEntity("cat-good", "Renamed Shelf", 0)),
                contentItems = poisoned,
                metadata = CatalogMetadataEntity(catalogVersion = 2L),
                syncedAt = 200L,
            )
            throw AssertionError("the replacement should have failed on the foreign key")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            // expected - and everything below proves the old catalog survived the rollback
        }

        assertEquals(listOf("Good Shelf"), repo.getCategories().map { it.displayName })
        assertEquals(listOf("Good Video"), repo.getItems("cat-good").map { it.displayName })
        assertEquals("no half-applied rows", 1, itemCount())
        assertEquals("the failed replacement must not claim success", 1L, repo.getMetadata()!!.catalogVersion)
        assertEquals(100L, repo.getMetadata()!!.lastSuccessfulSyncAt)
    }

    @Test
    fun replaceCatalogRefusesABlankCategoryBeforeWritingAnything() = runBlocking {
        repo.replaceCatalog(
            categories = listOf(CategoryEntity("cat-good", "Good Shelf", 0)),
            contentItems = emptyList(),
            metadata = CatalogMetadataEntity(catalogVersion = 1L),
        )

        val result = repo.replaceCatalog(
            categories = listOf(CategoryEntity("cat-new", "   ", 0)),
            contentItems = emptyList(),
            metadata = CatalogMetadataEntity(catalogVersion = 2L),
        )

        assertTrue(result is CatalogWriteResult.Rejected)
        assertEquals(listOf("Good Shelf"), repo.getCategories().map { it.displayName })
        assertEquals(1L, repo.getMetadata()!!.catalogVersion)
    }

    // ------------------------------------------------------------- mutation surface

    @Test
    fun updateAndDeleteOperationsBehave() = runBlocking {
        repo.upsertCategory(CategoryEntity("cat-music", "Music", 0))
        repo.upsertItem(
            ContentItemEntity("i-nursery", "cat-music", ContentItemType.PLAYLIST, "Nursery", 0, youtubePlaylistId = "PLn")
        )
        repo.upsertItem(
            ContentItemEntity("i-abc", "cat-music", ContentItemType.PLAYLIST, "ABC", 1, youtubePlaylistId = "PLabc")
        )

        // Renaming a shelf must not take its entries with it: an existing node is updated in place,
        // never re-inserted, because SQLite's REPLACE would delete the row first and the foreign
        // key's ON DELETE CASCADE would then remove every child.
        repo.upsertCategory(CategoryEntity("cat-music", "Music Time", 0))
        assertEquals("Music Time", repo.getCategory("cat-music")!!.displayName)

        repo.deleteItem("i-abc")
        assertEquals(listOf("Nursery"), repo.getItems("cat-music").map { it.displayName })

        repo.deleteItemsInCategory("cat-music")
        assertEquals(emptyList<ContentItemEntity>(), repo.getItems("cat-music"))

        repo.upsertItem(
            ContentItemEntity("i-again", "cat-music", ContentItemType.VIDEO, "Again", 0, youtubeVideoId = "v")
        )
        assertEquals(1, itemCount())
        repo.deleteCategory("cat-music")
        assertEquals(0, itemCount())
    }
}
