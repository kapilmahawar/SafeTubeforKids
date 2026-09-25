package tv.safetubeforkids.app.ui.screens

import androidx.room.Room
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import tv.safetubeforkids.app.data.catalog.CatalogCategoryDto
import tv.safetubeforkids.app.data.catalog.CatalogItemDto
import tv.safetubeforkids.app.data.catalog.CatalogRepository
import tv.safetubeforkids.app.data.catalog.CatalogWriteResult
import tv.safetubeforkids.app.data.catalog.CATALOG_TYPE_PLAYLIST
import tv.safetubeforkids.app.data.catalog.CATALOG_TYPE_VIDEO
import tv.safetubeforkids.app.data.catalog.CatalogMapper
import tv.safetubeforkids.app.data.catalog.CatalogMetadataEntity
import tv.safetubeforkids.app.playback.PlaybackApproval
import tv.safetubeforkids.app.playback.PlaybackAuthorization

/**
 * What the home screen observes, against a real Room database.
 *
 * These are the properties a pure projection test cannot reach: that the catalog is read in one
 * consistent snapshot, that a replacement reaches the UI as one new state rather than a mix, that
 * the artwork index and the Continue Watching join behave as the screen needs, and that none of it
 * can be talked into granting playback.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogHomeFlowTest {

    private lateinit var db: CacheDatabase
    private lateinit var repository: CatalogRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = CatalogRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------ fixtures

    private fun playlistItem(id: String, name: String, sortOrder: Int, playlistId: String) =
        CatalogItemDto(
            id = id,
            type = CATALOG_TYPE_PLAYLIST,
            displayName = name,
            sortOrder = sortOrder,
            youtubePlaylistId = playlistId,
        )

    private fun videoItem(id: String, name: String, sortOrder: Int, videoId: String) =
        CatalogItemDto(
            id = id,
            type = CATALOG_TYPE_VIDEO,
            displayName = name,
            sortOrder = sortOrder,
            youtubeVideoId = videoId,
        )

    private fun category(id: String, name: String, sortOrder: Int, items: List<CatalogItemDto>) =
        CatalogCategoryDto(id = id, displayName = name, sortOrder = sortOrder, items = items)

    /** Installs a catalog the way a successful sync does, through the real replacement. */
    private fun installCatalog(
        version: Long,
        categories: List<CatalogCategoryDto>,
        syncedAt: Long = 1_000L,
    ) = runBlocking {
        val mapped = CatalogMapper.toEntities(categories, syncedAt = syncedAt)
        val result = repository.replaceCatalog(
            categories = mapped.categories,
            contentItems = mapped.items,
            metadata = CatalogMetadataEntity(catalogVersion = version),
            syncedAt = syncedAt,
        )
        assertEquals(CatalogWriteResult.Written, result)
    }

    private fun approveSource(sourceId: String, displayName: String = "Approved") = runBlocking {
        db.channelDao().insert(
            ChannelEntity(
                sourceType = "yt_playlist",
                sourceId = sourceId,
                sourceUrl = "https://www.youtube.com/playlist?list=$sourceId",
                displayName = displayName,
            )
        )
    }

    private fun cacheVideos(sourceId: String, videos: List<Pair<String, Int>>) = runBlocking {
        db.videoDao().insertAll(
            videos.mapIndexed { index, (videoId, _) ->
                VideoEntity(
                    videoId = videoId,
                    playlistId = sourceId,
                    title = "Cached $videoId",
                    thumbnailUrl = "https://img/$videoId.jpg",
                    durationSeconds = 120,
                    position = index,
                )
            }
        )
    }

    private fun uiState(): CatalogUiState = runBlocking {
        CatalogUiProjection.build(
            catalog = repository.observeCatalogWithItems().first(),
            thumbnails = repository.observeVideoThumbnails().first(),
            resumable = repository.observeResumableVideos(
                CatalogUiProjection.RESUME_MIN_POSITION_MS,
                CatalogUiProjection.RESUME_MAX_PERCENT,
            ).first(),
        )
    }

    // ------------------------------------------------------------------ reading the catalog

    @Test
    fun theCatalogIsReadInTheParentsOrderThroughTheRealQuery() {
        installCatalog(
            1L,
            listOf(
                category("cat-stories", "Stories", 30, listOf(playlistItem("i-story", "Bedtime", 0, "PLstory"))),
                category("cat-music", "Music", 5, listOf(playlistItem("i-nursery", "Nursery Songs", 0, "PLnursery"))),
                category("cat-learning", "Learning", 10, listOf(playlistItem("i-numbers", "Numbers", 0, "PLnumbers"))),
            ),
        )

        assertEquals(
            listOf("Music", "Learning", "Stories"),
            uiState().shelves.map { it.title },
        )
    }

    @Test
    fun itemsAreReadWithTheirShelfAndStayMixed() {
        installCatalog(
            1L,
            listOf(
                category(
                    "cat-music", "Music", 0,
                    listOf(
                        videoItem("i-wheels", "Wheels on Bus", 30, "vidwheels"),
                        playlistItem("i-abc", "ABC Songs", 10, "PLabc"),
                        videoItem("i-twinkle", "Twinkle Twinkle", 20, "vidtwinkle"),
                        playlistItem("i-nursery", "Nursery Songs", 0, "PLnursery"),
                    ),
                )
            ),
        )

        assertEquals(
            listOf("Nursery Songs", "ABC Songs", "Twinkle Twinkle", "Wheels on Bus"),
            uiState().shelves.single().cards.map { it.title },
        )
    }

    @Test
    fun anEmptyCatalogReadsAsTheEmptyState() {
        installCatalog(4L, emptyList())

        assertTrue(uiState().isEmpty)
    }

    // ------------------------------------------------------------------ atomic replacement

    @Test
    fun aCatalogReplacementIsObservedAsOneCompleteNewCatalogNeverAMix() = runBlocking {
        val before = listOf(
            category("cat-a", "Shelf A", 0, listOf(playlistItem("i-a", "A item", 0, "PL-a"))),
            category("cat-b", "Shelf B", 1, listOf(playlistItem("i-b", "B item", 0, "PL-b"))),
        )
        val after = listOf(
            category("cat-x", "Shelf X", 0, listOf(playlistItem("i-x", "X item", 0, "PL-x"))),
            category("cat-y", "Shelf Y", 1, listOf(playlistItem("i-y", "Y item", 0, "PL-y"))),
            category("cat-z", "Shelf Z", 2, listOf(playlistItem("i-z", "Z item", 0, "PL-z"))),
        )
        installCatalog(1L, before)

        // Collect from the flow the way the UI does, and replace the catalog underneath it.
        val seen = mutableListOf<List<String>>()
        val job = launch {
            repository.observeCatalogWithItems().collect { rows ->
                seen += rows.map { row -> "${row.category.id}:${row.items.map { it.id }.sorted()}" }
            }
        }
        try {
            // Wait for the observer to have actually seen the old catalog before replacing it, so
            // the test observes the transition rather than racing it.
            withTimeout(10_000) { while (seen.isEmpty()) delay(10) }
            installCatalog(2L, after)
            withTimeout(10_000) { while (seen.size < 2) delay(10) }
        } finally {
            job.cancel()
        }

        val shapes = seen.distinct()
        assertTrue("expected to observe the replacement, saw $shapes", shapes.size >= 2)
        shapes.forEach { shape ->
            val ids = shape.map { it.substringBefore(':') }.sorted()
            val isBefore = ids == listOf("cat-a", "cat-b")
            val isAfter = ids == listOf("cat-x", "cat-y", "cat-z")
            assertTrue(
                "an emission mixed the old and new catalogs: $shape",
                isBefore || isAfter,
            )
        }
        // And every emission carried its own items, never another catalog's.
        for (shape in shapes) {
            shape.forEach { entry ->
                val categoryId = entry.substringBefore(':')
                assertTrue(
                    "shelf $categoryId was paired with foreign items: $shape",
                    entry.contains("i-${categoryId.removePrefix("cat-")}"),
                )
            }
        }
    }

    @Test
    fun aReplacementOnScreenReachesTheUiStateAsTheNewCatalog() {
        installCatalog(1L, listOf(category("cat-a", "Cartoon", 0, listOf(playlistItem("i-a", "Cocomelon", 0, "PL-a")))))
        assertEquals(listOf("Cartoon"), uiState().shelves.map { it.title })

        installCatalog(
            2L,
            listOf(
                category("cat-a", "Learning", 0, listOf(playlistItem("i-a", "Numbers", 0, "PL-a"))),
                category("cat-b", "Cartoon", 1, listOf(playlistItem("i-b", "Cocomelon", 0, "PL-b"))),
            ),
        )

        assertEquals(listOf("Learning", "Cartoon"), uiState().shelves.map { it.title })
        assertEquals(listOf("Numbers"), uiState().shelves.first().cards.map { it.title })
    }

    // ------------------------------------------------------------------ artwork

    @Test
    fun artworkComesFromTheApprovedCacheAndIsAbsentWithoutIt() {
        approveSource("PLnursery")
        cacheVideos("PLnursery", listOf("vid1" to 0, "vid2" to 1))
        installCatalog(
            1L,
            listOf(
                category(
                    "cat-music", "Music", 0,
                    listOf(
                        playlistItem("i-nursery", "Nursery Songs", 0, "PLnursery"),
                        videoItem("i-twinkle", "Twinkle Twinkle", 1, "vid2"),
                        videoItem("i-unknown", "Not Cached", 2, "vidabsent"),
                    ),
                )
            ),
        )

        val cards = uiState().shelves.single().cards
        assertEquals("https://img/vid1.jpg", cards[0].thumbnailUrl)
        assertEquals("https://img/vid2.jpg", cards[1].thumbnailUrl)
        assertEquals(null, cards[2].thumbnailUrl)
        assertEquals("Not Cached", cards[2].title)
    }

    // ------------------------------------------------------------------ continue watching

    @Test
    fun continueWatchingListsHalfWatchedApprovedVideosMostRecentFirst() {
        approveSource("PLapproved")
        cacheVideos("PLapproved", listOf("vid1" to 0, "vid2" to 1))
        // Continue Watching is curated by the catalog, so the source has to be published for the
        // cards to appear at all; this test is about their order.
        installCatalog(1L, listOf(category("cat-approved", "Approved", 0, listOf(playlistItem("i-approved", "Approved", 0, "PLapproved")))))
        runBlocking {
            db.playbackPositionDao().upsert(PlaybackPositionEntity("vid1", 60_000, 600_000, 200))
            db.playbackPositionDao().upsert(PlaybackPositionEntity("vid2", 90_000, 600_000, 300))
        }

        val shelf = uiState().shelves.first()

        assertEquals("Continue Watching", shelf.title)
        assertEquals(listOf("Cached vid2", "Cached vid1"), shelf.cards.map { it.title })
    }

    @Test
    fun aVideoWhoseApprovalIsWithdrawnDropsOutOfContinueWatching() {
        approveSource("PLapproved")
        cacheVideos("PLapproved", listOf("vid1" to 0))
        // Still published by the catalog, so the removal below can only be attributed to the
        // approval being withdrawn - not to the video being unpublished.
        installCatalog(1L, listOf(category("cat-approved", "Approved", 0, listOf(playlistItem("i-approved", "Approved", 0, "PLapproved")))))
        runBlocking { db.playbackPositionDao().upsert(PlaybackPositionEntity("vid1", 60_000, 600_000, 200)) }
        assertEquals(1, uiState().shelves.first().cards.size)

        // The parent removes the source. The playback position stays; the shelf must not.
        runBlocking { db.channelDao().deleteAll() }

        // The catalog still publishes the source, so the catalog shelf stays; what must disappear
        // is the Continue Watching card, because the video is no longer approved.
        assertTrue(
            "history must not keep an unapproved video on the shelf",
            uiState().shelves.none { it.title == "Continue Watching" },
        )
        assertNotNull(
            "the position row itself is preserved",
            runBlocking { db.playbackPositionDao().get("vid1") },
        )
    }

    @Test
    fun aBarelyStartedOrNearlyFinishedVideoIsNotOfferedForResuming() {
        approveSource("PLapproved")
        cacheVideos("PLapproved", listOf("vid-early" to 0, "vid-late" to 1, "vid-good" to 2))
        installCatalog(1L, listOf(category("cat-approved", "Approved", 0, listOf(playlistItem("i-approved", "Approved", 0, "PLapproved")))))
        runBlocking {
            db.playbackPositionDao().upsert(PlaybackPositionEntity("vid-early", 5_000, 600_000, 100))
            db.playbackPositionDao().upsert(PlaybackPositionEntity("vid-late", 594_000, 600_000, 200))
            db.playbackPositionDao().upsert(PlaybackPositionEntity("vid-good", 300_000, 600_000, 300))
        }

        val titles = uiState().shelves.first().cards.map { it.title }

        assertEquals(listOf("Cached vid-good"), titles)
    }

    // ------------------------------------------------------------------ security

    @Test
    fun aCatalogEntryWithoutApprovalCannotPlayAndOneWithApprovalCan() = runBlocking {
        val sourceId = "PLapproved"
        approveSource(sourceId)
        cacheVideos(sourceId, listOf("approvedVid" to 0))
        installCatalog(
            1L,
            listOf(
                category(
                    "cat-music", "Music", 0,
                    listOf(
                        videoItem("i-approved", "Approved Video", 0, "approvedVid"),
                        videoItem("i-rogue", "Never Approved", 1, "UNAPPROVED_VIDEO"),
                    ),
                )
            ),
        )

        // Both cards render: the catalog is what the parent configured.
        assertEquals(2, uiState().shelves.single().cards.size)

        // Only the approved one may play, and that decision is not made by the UI.
        assertTrue(PlaybackAuthorization.authorize(db, "approvedVid") is PlaybackApproval.Approved)
        assertTrue(PlaybackAuthorization.authorize(db, "UNAPPROVED_VIDEO") is PlaybackApproval.Rejected)
    }

    @Test
    fun thePlaylistStartingPointIsTheFirstApprovedVideoOrNothing() = runBlocking {
        approveSource("PLapproved")
        cacheVideos("PLapproved", listOf("first" to 0, "second" to 1))

        assertEquals("first", repository.firstApprovedVideoOf("PLapproved"))
        assertEquals("an unapproved playlist has no starting point", null, repository.firstApprovedVideoOf("PLunknown"))

        runBlocking { db.channelDao().deleteAll() }
        assertEquals("a withdrawn source has no starting point", null, repository.firstApprovedVideoOf("PLapproved"))
    }

    @Test
    fun aCatalogPlaylistCardStillCannotPlayAnUnapprovedPlaylist() = runBlocking {
        installCatalog(
            1L,
            listOf(category("cat-music", "Music", 0, listOf(playlistItem("i-p", "Nursery", 0, "PLnotApproved")))),
        )

        val card = uiState().shelves.single().cards.single()
        assertEquals(CatalogCardKind.PLAYLIST, card.kind)

        // What the view model would choose as a starting point: the playlist itself, because there
        // is no approved queue. That value then has to pass authorization like any other.
        val target = repository.firstApprovedVideoOf(card.playlistId!!) ?: card.playlistId
        assertEquals("PLnotApproved", target)
        assertTrue(PlaybackAuthorization.authorize(db, target) is PlaybackApproval.Rejected)
    }

    @Test
    fun disabledContentStaysInTheDatabaseSoAParentCanSwitchItBackOn() {
        installCatalog(
            1L,
            listOf(
                category(
                    "cat-a", "Hidden Shelf", 0,
                    listOf(playlistItem("i-a", "Hidden item", 0, "PL-a")),
                ).copy(enabled = false),
                category("cat-b", "Shown Shelf", 1, listOf(playlistItem("i-b", "Visible", 0, "PL-b"))),
            ),
        )

        assertEquals(listOf("Shown Shelf"), uiState().shelves.map { it.title })

        // The disabled shelf and its item are still on disk, ready to be switched back on.
        // Both counts are derived from the node tree, which is all the catalog is stored in.
        assertEquals(2, runBlocking { repository.getCategories().size })
        assertEquals(
            2,
            runBlocking { repository.getCategories().sumOf { repository.getItems(it.id).size } },
        )
        assertFalse(runBlocking { repository.getCategory("cat-a") }!!.enabled)
        assertEquals("Hidden item", runBlocking { repository.getItem("i-a") }!!.displayName)
    }

    @Test
    fun theHomeScreenRendersFromNodesAndTheLegacyTablesStayGone() = runBlocking {
        installCatalog(
            1L,
            listOf(
                category("cat-music", "Music", 0, listOf(playlistItem("i-p", "Nursery", 0, "PLnursery"))),
                category("cat-stories", "Stories", 1, listOf(videoItem("i-v", "Bedtime", 0, "vidbed"))),
            ),
        )

        // Rendering reads the tree, and rendering must not recreate the removed tables.
        assertEquals(listOf("Music", "Stories"), uiState().shelves.map { it.title })

        val tables = mutableListOf<String>()
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }

        assertTrue("catalog_nodes is the catalog storage: $tables", tables.contains("catalog_nodes"))
        assertFalse("categories must be gone: $tables", tables.contains("categories"))
        assertFalse("content_items must be gone: $tables", tables.contains("content_items"))
    }
}
