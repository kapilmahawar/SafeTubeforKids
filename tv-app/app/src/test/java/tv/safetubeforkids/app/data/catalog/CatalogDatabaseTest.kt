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
 * The catalog DAO/entity/repository contract, against a real Room database on real SQLite (the JVM
 * suite runs Android code through Robolectric).
 *
 * These tests deliberately use a real database rather than a hand-written fake: what is under test
 * is `ORDER BY sort_order`, foreign-key cascade and the enum/column mapping - none of which a fake
 * DAO can prove.
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

    private suspend fun category(id: String, name: String, sortOrder: Int) =
        db.categoryDao().insert(CategoryEntity(id = id, displayName = name, sortOrder = sortOrder))

    private suspend fun playlist(id: String, categoryId: String, name: String, sortOrder: Int, playlistId: String) =
        db.contentItemDao().insert(
            ContentItemEntity(
                id = id,
                categoryId = categoryId,
                type = ContentItemType.PLAYLIST,
                displayName = name,
                sortOrder = sortOrder,
                youtubePlaylistId = playlistId,
            )
        )

    private suspend fun video(id: String, categoryId: String, name: String, sortOrder: Int, videoId: String) =
        db.contentItemDao().insert(
            ContentItemEntity(
                id = id,
                categoryId = categoryId,
                type = ContentItemType.VIDEO,
                displayName = name,
                sortOrder = sortOrder,
                youtubeVideoId = videoId,
            )
        )

    // ------------------------------------------------------------------ CAT-DB-01

    @Test
    fun categoryQueryReturnsParentConfiguredOrderNotInsertionOrder() = runBlocking {
        // Inserted in an order that is neither the configured order nor alphabetical.
        category("cat-learning", "Learning", 20)
        category("cat-cartoon", "Cartoon", 5)
        category("cat-music", "Music", 10)
        category("cat-stories", "Stories", 30)

        val names = db.categoryDao().getAll().map { it.displayName }

        assertEquals(listOf("Cartoon", "Music", "Learning", "Stories"), names)
    }

    @Test
    fun categorySelectorsDoNotDependOnDisplayNameOrPrimaryKeyOrder() = runBlocking {
        // Identifiers sort in exactly the opposite order to the display order, and the display
        // names are alphabetical in the opposite order too: only sort_order can produce this.
        category("a-cat", "Zebras", 0)
        category("z-cat", "Apples", 1)

        assertEquals(listOf("Zebras", "Apples"), db.categoryDao().getAll().map { it.displayName })
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

        val loaded = db.categoryDao().getById("cat-music")
        assertNotNull(loaded)
        assertEquals("Music", loaded!!.displayName)
        assertEquals(1, loaded.sortOrder)
        assertTrue(loaded.enabled)
        assertTrue("createdAt should be stamped", loaded.createdAt > 0)
        assertTrue("updatedAt should be stamped", loaded.updatedAt > 0)
        assertNull(db.categoryDao().getById("nope"))
    }

    // ------------------------------------------------------------------ CAT-DB-02

    @Test
    fun itemQueryReturnsSortOrderNotInsertionOrder() = runBlocking {
        category("cat-music", "Music", 0)
        video("i-learning", "cat-music", "Learning Song", 20, "vid-learning")
        video("i-cartoon", "cat-music", "Cartoon Song", 5, "vid-cartoon")
        video("i-music", "cat-music", "Music Song", 10, "vid-music")

        val names = db.contentItemDao().getByCategory("cat-music").map { it.displayName }

        assertEquals(listOf("Cartoon Song", "Music Song", "Learning Song"), names)
    }

    @Test
    fun itemsOfAnotherCategoryAreNotReturned() = runBlocking {
        category("cat-a", "A", 0)
        category("cat-b", "B", 1)
        video("i-a", "cat-a", "In A", 0, "vid-a")
        video("i-b", "cat-b", "In B", 0, "vid-b")

        assertEquals(listOf("In A"), db.contentItemDao().getByCategory("cat-a").map { it.displayName })
        assertEquals(1, db.contentItemDao().countByCategory("cat-a"))
        assertEquals(1, db.contentItemDao().countByCategory("cat-b"))
        assertEquals(2, db.contentItemDao().count())
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

        val stored = db.contentItemDao().getById("i-peppa")!!

        assertEquals(ContentItemType.PLAYLIST, stored.type)
        assertEquals("PLpeppa123", stored.youtubePlaylistId)
        assertNull("a playlist item must not carry a video id", stored.youtubeVideoId)
    }

    @Test
    fun playlistTypeSurvivesTheRoundTripAsText() = runBlocking {
        category("cat-cartoon", "Cartoon", 0)
        playlist("i-peppa", "cat-cartoon", "Peppa Pig", 0, "PLpeppa123")

        // Read the raw column: the enum is stored by name, not by ordinal, so reordering the enum
        // in future cannot silently reinterpret stored rows.
        db.openHelper.readableDatabase.query("SELECT type FROM content_items WHERE id = 'i-peppa'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("PLAYLIST", cursor.getString(0))
            }
    }

    // ------------------------------------------------------------------ CAT-DB-04

    @Test
    fun videoItemStoresVideoIdAndNoPlaylistId() = runBlocking {
        category("cat-music", "Music", 0)
        video("i-twinkle", "cat-music", "Twinkle Twinkle", 2, "DuXwFlL8Usk")

        val stored = db.contentItemDao().getById("i-twinkle")!!

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

        assertEquals("nothing should have been written", 0, db.contentItemDao().count())

        assertTrue(repo.upsertCategory(CategoryEntity(id = "", displayName = "X", sortOrder = 0))
            is CatalogWriteResult.Rejected)
        assertEquals(1, db.categoryDao().count())
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

        val stored = db.contentItemDao().getById("i-nursery")!!

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
        assertEquals(3, db.contentItemDao().count())

        repo.deleteCategory("cat-music")

        assertEquals(0, db.contentItemDao().countByCategory("cat-music"))
        assertEquals("the other category's items must survive", 1, db.contentItemDao().count())
        assertEquals(listOf("Bedtime Story"), db.contentItemDao().getAll().map { it.displayName })
        assertNull(db.contentItemDao().getById("i-nursery"))
    }

    @Test
    fun anItemCannotReferenceACategoryThatDoesNotExist() = runBlocking {
        val orphan = ContentItemEntity(
            id = "i-orphan", categoryId = "cat-does-not-exist", type = ContentItemType.VIDEO,
            displayName = "Orphan", sortOrder = 0, youtubeVideoId = "v",
        )

        try {
            db.contentItemDao().insert(orphan)
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

        val items = db.contentItemDao().getByCategory("cat-music")

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
        assertEquals(2, db.contentItemDao().count())
    }

    @Test
    fun theItemIndexIsActuallyUsedByTheItemQuery() {
        val plan = mutableListOf<String>()
        db.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN " +
                "SELECT * FROM content_items WHERE category_id = 'cat-music' " +
                "ORDER BY sort_order ASC, id ASC"
        ).use { c ->
            while (c.moveToNext()) plan.add(c.getString(c.getColumnIndexOrThrow("detail")))
        }

        // Without the composite index the first line would be "SCAN content_items". The second line
        // is the honest, bounded cost of the `id` tie-break: SQLite uses the index for the
        // category_id lookup and its sort_order order, then sorts only the matching category's rows
        // to break ties. The tie-break is not optional - without it the order of two items sharing a
        // sort_order would be unspecified.
        assertEquals(
            listOf(
                "SEARCH TABLE content_items USING INDEX index_content_items_category_id_sort_order (category_id=?)",
                "USE TEMP B-TREE FOR RIGHT PART OF ORDER BY",
            ),
            plan,
        )
    }

    @Test
    fun catalogTablesCarryNoApprovalFlags() {
        val columns = mutableListOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA table_info(`content_items`)").use { c ->
            while (c.moveToNext()) columns.add(c.getString(c.getColumnIndexOrThrow("name")))
        }

        assertEquals(
            listOf(
                "id", "category_id", "type", "display_name", "sort_order",
                "youtube_playlist_id", "youtube_video_id", "enabled", "created_at", "updated_at",
            ),
            columns,
        )
        assertFalse(columns.contains("approved"))
        assertFalse(columns.contains("playback_allowed"))
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
        assertEquals(2, db.contentItemDao().count())
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
        assertEquals("no half-applied rows", 1, db.contentItemDao().count())
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

        db.categoryDao().update(CategoryEntity("cat-music", "Music Time", 0))
        assertEquals("Music Time", repo.getCategory("cat-music")!!.displayName)

        repo.deleteItem("i-abc")
        assertEquals(listOf("Nursery"), repo.getItems("cat-music").map { it.displayName })

        repo.deleteItemsInCategory("cat-music")
        assertEquals(emptyList<ContentItemEntity>(), repo.getItems("cat-music"))

        repo.upsertItem(
            ContentItemEntity("i-again", "cat-music", ContentItemType.VIDEO, "Again", 0, youtubeVideoId = "v")
        )
        assertEquals(1, db.contentItemDao().count())
        repo.deleteCategory("cat-music")
        assertEquals(0, db.contentItemDao().count())
    }
}
