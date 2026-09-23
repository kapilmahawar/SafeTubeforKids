package tv.safetubeforkids.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.safetubeforkids.app.data.cache.ResumableVideoRow
import tv.safetubeforkids.app.data.cache.VideoThumbnailRow
import tv.safetubeforkids.app.data.catalog.CategoryEntity
import tv.safetubeforkids.app.data.catalog.CategoryWithItems
import tv.safetubeforkids.app.data.catalog.ContentItemEntity
import tv.safetubeforkids.app.data.catalog.ContentItemType

/**
 * What the child sees, derived from rows alone.
 *
 * These are the rendering rules: the parent's order, hidden-but-not-deleted disabled content, empty
 * shelves, parent names, artwork lookup and the Continue Watching shelf. No database and no device
 * are involved, so a failure here is unambiguous about which rule broke.
 */
class CatalogUiProjectionTest {

    private fun category(
        id: String,
        name: String,
        sortOrder: Int,
        enabled: Boolean = true,
        items: List<ContentItemEntity> = emptyList(),
    ) = CategoryWithItems(
        category = CategoryEntity(
            id = id,
            displayName = name,
            sortOrder = sortOrder,
            enabled = enabled,
        ),
        items = items,
    )

    private fun playlist(
        id: String,
        name: String,
        sortOrder: Int,
        playlistId: String,
        enabled: Boolean = true,
    ) = ContentItemEntity(
        id = id,
        categoryId = "ignored",
        type = ContentItemType.PLAYLIST,
        displayName = name,
        sortOrder = sortOrder,
        youtubePlaylistId = playlistId,
        enabled = enabled,
    )

    private fun video(
        id: String,
        name: String,
        sortOrder: Int,
        videoId: String,
        enabled: Boolean = true,
    ) = ContentItemEntity(
        id = id,
        categoryId = "ignored",
        type = ContentItemType.VIDEO,
        displayName = name,
        sortOrder = sortOrder,
        youtubeVideoId = videoId,
        enabled = enabled,
    )

    private fun build(
        catalog: List<CategoryWithItems> = emptyList(),
        thumbnails: List<VideoThumbnailRow> = emptyList(),
        resumable: List<ResumableVideoRow> = emptyList(),
    ) = CatalogUiProjection.build(catalog, thumbnails, resumable)

    // ------------------------------------------------------------------ order

    @Test
    fun categoriesAreRenderedInTheParentsOrderNotTheirInsertionOrder() {
        // Learning=20, Cartoon=5, Music=10, Stories=30, arriving in that deliberately wrong order.
        val state = build(
            listOf(
                category("cat-learning", "Learning", 20),
                category("cat-cartoon", "Cartoon", 5),
                category("cat-music", "Music", 10),
                category("cat-stories", "Stories", 30),
            ).map { withOneItem(it) },
        )

        assertEquals(
            listOf("Cartoon", "Music", "Learning", "Stories"),
            state.shelves.map { it.title },
        )
    }

    @Test
    fun itemsAreRenderedInTheParentsOrderNotTheirInsertionOrder() {
        val state = build(
            listOf(
                category(
                    "cat-music", "Music", 0,
                    items = listOf(
                        video("i-wheels", "Wheels on Bus", 30, "vidwheels"),
                        playlist("i-abc", "ABC Songs", 10, "PLabc"),
                        video("i-twinkle", "Twinkle Twinkle", 20, "vidtwinkle"),
                        playlist("i-nursery", "Nursery Songs", 0, "PLnursery"),
                    ),
                )
            )
        )

        assertEquals(
            listOf("Nursery Songs", "ABC Songs", "Twinkle Twinkle", "Wheels on Bus"),
            state.shelves.single().cards.map { it.title },
        )
    }

    @Test
    fun equalSortOrdersBreakOnIdSoTheOrderIsNeverUnspecified() {
        val state = build(
            listOf(
                category(
                    "cat-music", "Music", 0,
                    items = listOf(
                        video("i-b", "B", 0, "vidb"),
                        video("i-a", "A", 0, "vida"),
                    ),
                )
            )
        )

        assertEquals(listOf("A", "B"), state.shelves.single().cards.map { it.title })
    }

    @Test
    fun categoriesAreNotSortedAlphabetically() {
        // Alphabetical would be Apples, Zebras; the parent asked for the other way round.
        val state = build(
            listOf(
                withOneItem(category("cat-z", "Zebras", 0)),
                withOneItem(category("cat-a", "Apples", 1)),
            )
        )

        assertEquals(listOf("Zebras", "Apples"), state.shelves.map { it.title })
    }

    // ------------------------------------------------------------------ mixed content

    @Test
    fun oneShelfCanMixPlaylistsAndVideosWithoutBeingSplit() {
        val state = build(
            listOf(
                category(
                    "cat-music", "Music", 0,
                    items = listOf(
                        playlist("i-nursery", "Nursery Songs", 0, "PLnursery"),
                        playlist("i-abc", "ABC Songs", 1, "PLabc"),
                        video("i-twinkle", "Twinkle Twinkle", 2, "DuXwFlL8Usk"),
                        video("i-wheels", "Wheels on Bus", 3, "vidwheels"),
                    ),
                )
            )
        )

        val cards = state.shelves.single().cards
        assertEquals(4, cards.size)
        assertEquals(
            listOf(
                CatalogCardKind.PLAYLIST, CatalogCardKind.PLAYLIST,
                CatalogCardKind.VIDEO, CatalogCardKind.VIDEO,
            ),
            cards.map { it.kind },
        )
        assertEquals("PLnursery", cards[0].playlistId)
        assertEquals("DuXwFlL8Usk", cards[2].videoId)
    }

    // ------------------------------------------------------------------ names

    @Test
    fun theParentDisplayNameIsWhatIsRenderedNeverTheIdentifier() {
        val state = build(
            listOf(
                category(
                    "cat-music", "Music", 0,
                    items = listOf(
                        playlist("i-nursery", "Bedtime Songs", 0, "PLsuperFunEducationalSongs2026Official"),
                        video("i-twinkle", "Twinkle Before Bed", 1, "MR5XSOdjKMA"),
                    ),
                )
            )
        )

        val cards = state.shelves.single().cards
        assertEquals(listOf("Bedtime Songs", "Twinkle Before Bed"), cards.map { it.title })
        assertFalse(cards[0].title.contains(cards[0].playlistId!!))
        assertFalse(cards[1].title.contains(cards[1].videoId!!))
        assertEquals("Music", state.shelves.single().title)
    }

    @Test
    fun shelfTitlesAreTheCategoryDisplayNamesNotIds() {
        val state = build(listOf(withOneItem(category("cat-bedtime", "Bedtime Stories", 0))))

        assertEquals("Bedtime Stories", state.shelves.single().title)
        assertEquals("cat-bedtime", state.shelves.single().id)
    }

    // ------------------------------------------------------------------ enabled

    @Test
    fun aDisabledCategoryIsNotRenderedAtAll() {
        val state = build(
            listOf(
                withOneItem(category("cat-a", "Shown", 0)),
                withOneItem(category("cat-b", "Hidden", 1, enabled = false)),
            )
        )

        assertEquals(listOf("Shown"), state.shelves.map { it.title })
    }

    @Test
    fun aDisabledItemIsNotRenderedButItsSiblingsAre() {
        val state = build(
            listOf(
                category(
                    "cat-music", "Music", 0,
                    items = listOf(
                        playlist("i-one", "Visible", 0, "PLone"),
                        playlist("i-two", "Hidden", 1, "PLtwo", enabled = false),
                        playlist("i-three", "Also Visible", 2, "PLthree"),
                    ),
                )
            )
        )

        assertEquals(listOf("Visible", "Also Visible"), state.shelves.single().cards.map { it.title })
    }

    @Test
    fun aCategoryWhoseOnlyItemsAreDisabledIsDroppedRatherThanShownEmpty() {
        val state = build(
            listOf(
                category(
                    "cat-music", "Music", 0,
                    items = listOf(playlist("i-one", "Hidden", 0, "PLone", enabled = false)),
                )
            )
        )

        assertTrue(state.isEmpty)
    }

    // ------------------------------------------------------------------ empty

    @Test
    fun anEmptyCatalogProducesTheEmptyState() {
        val state = build()

        assertTrue(state.isEmpty)
        assertTrue(state.shelves.isEmpty())
    }

    @Test
    fun aCategoryWithNoItemsProducesNoShelf() {
        val state = build(listOf(category("cat-stories", "Stories", 3)))

        assertTrue("an empty shelf would be a heading over dead space", state.isEmpty)
    }

    @Test
    fun oneEmptyCategoryAmongPopulatedOnesOnlyDropsThatShelf() {
        val state = build(
            listOf(
                withOneItem(category("cat-cartoon", "Cartoon", 0)),
                category("cat-stories", "Stories", 1),
            )
        )

        assertEquals(listOf("Cartoon"), state.shelves.map { it.title })
    }

    // ------------------------------------------------------------------ artwork

    @Test
    fun aVideoCardTakesItsArtworkFromTheApprovedCache() {
        val state = build(
            catalog = listOf(
                category("cat-music", "Music", 0, items = listOf(video("i-v", "Twinkle", 0, "vid1"))),
            ),
            thumbnails = listOf(VideoThumbnailRow("vid1", "PLone", "https://img/vid1.jpg")),
        )

        val card = state.shelves.single().cards.single()
        assertEquals("https://img/vid1.jpg", card.thumbnailUrl)
        assertEquals("PLone", card.playlistId)
    }

    @Test
    fun aPlaylistCardTakesItsArtworkFromThePlaylistsOpeningVideo() {
        val state = build(
            catalog = listOf(
                category("cat-music", "Music", 0, items = listOf(playlist("i-p", "Nursery", 0, "PLn"))),
            ),
            thumbnails = listOf(
                VideoThumbnailRow("vid1", "PLn", "https://img/first.jpg"),
                VideoThumbnailRow("vid2", "PLn", "https://img/second.jpg"),
                VideoThumbnailRow("vid9", "PLother", "https://img/other.jpg"),
            ),
        )

        assertEquals("https://img/first.jpg", state.shelves.single().cards.single().thumbnailUrl)
    }

    @Test
    fun aCardWithNoCachedArtworkStillRendersWithNoUrl() {
        val state = build(
            listOf(category("cat-music", "Music", 0, items = listOf(video("i-v", "Twinkle", 0, "vid1")))),
        )

        val card = state.shelves.single().cards.single()
        assertNull(card.thumbnailUrl)
        assertEquals("Twinkle", card.title)
    }

    // ------------------------------------------------------------------ continue watching

    @Test
    fun continueWatchingComesFirstAndKeepsItsMostRecentFirstOrder() {
        val state = build(
            catalog = listOf(withOneItem(category("cat-cartoon", "Cartoon", 0))),
            resumable = listOf(
                resumable("vid-new", "Newest", updatedAt = 300),
                resumable("vid-mid", "Middle", updatedAt = 200),
                resumable("vid-old", "Oldest", updatedAt = 100),
            ),
        )

        assertEquals("Continue Watching", state.shelves.first().title)
        assertEquals(
            listOf("Newest", "Middle", "Oldest"),
            state.shelves.first().cards.map { it.title },
        )
        assertEquals(listOf("Continue Watching", "Cartoon"), state.shelves.map { it.title })
    }

    @Test
    fun aContinueWatchingCardCarriesTheVideoAndItsSource() {
        val state = build(resumable = listOf(resumable("vid1", "Twinkle", playlistId = "PLapproved")))

        val card = state.shelves.single().cards.single()
        assertEquals(CatalogCardKind.CONTINUE_WATCHING, card.kind)
        assertEquals("vid1", card.videoId)
        assertEquals("PLapproved", card.playlistId)
        assertEquals("vid1", card.id.substringAfterLast(':'))
    }

    @Test
    fun aContinueWatchingCardShowsHowMuchIsLeft() {
        val state = build(
            resumable = listOf(
                resumable("vid1", "Twinkle", positionMs = 60_000, durationMs = 660_000),
            )
        )

        assertEquals("10 min left", state.shelves.single().cards.single().badgeText)
    }

    @Test
    fun aContinueWatchingCardFallsBackToWatchedWhenTheDurationIsUnknown() {
        val state = build(resumable = listOf(resumable("vid1", "Twinkle", positionMs = 30_000, durationMs = 0)))

        assertEquals("Watched", state.shelves.single().cards.single().badgeText)
    }

    @Test
    fun noContinueWatchingShelfAppearsWhenNothingIsResumable() {
        val state = build(catalog = listOf(withOneItem(category("cat-cartoon", "Cartoon", 0))))

        assertEquals(listOf("Cartoon"), state.shelves.map { it.title })
    }

    @Test
    fun catalogCardsHaveNoBadgeBecauseTheCatalogCarriesNoDurations() {
        val state = build(
            listOf(category("cat-music", "Music", 0, items = listOf(video("i-v", "Twinkle", 0, "vid1")))),
        )

        assertNull(state.shelves.single().cards.single().badgeText)
    }

    // ------------------------------------------------------------------ scale

    @Test
    fun manyCategoriesAndItemsAreAllRendered() {
        val catalog = (1..20).map { c ->
            category(
                "cat-$c", "Shelf $c", c,
                items = (1..30).map { i ->
                    if (i % 2 == 0) playlist("i-$c-$i", "Playlist $c-$i", i, "PL$c$i")
                    else video("i-$c-$i", "Video $c-$i", i, "vid$c$i")
                },
            )
        }

        val state = build(catalog)

        assertEquals(20, state.shelves.size)
        assertEquals(30, state.shelves.first().cards.size)
        assertEquals("Shelf 1", state.shelves.first().title)
        assertEquals("Shelf 20", state.shelves.last().title)
        // 20 shelves x 30 items is a real catalog; the first shelf must still be in the parent's
        // order, alternating kinds exactly where the parent put them.
        assertEquals("Video 1-1", state.shelves.first().cards.first().title)
        assertEquals("Playlist 1-30", state.shelves.first().cards.last().title)
        assertEquals(
            List(15) { CatalogCardKind.VIDEO } .zip(List(15) { CatalogCardKind.PLAYLIST })
                .flatMap { listOf(it.first, it.second) },
            state.shelves.first().cards.map { it.kind },
        )
    }

    private fun withOneItem(category: CategoryWithItems) = category.copy(
        items = listOf(
            playlist("item-${category.category.id}", category.category.displayName, 0, "PL${category.category.id}"),
        ),
    )

    private fun resumable(
        videoId: String,
        title: String,
        playlistId: String = "PLapproved",
        positionMs: Long = 60_000,
        durationMs: Long = 600_000,
        updatedAt: Long = 1,
    ) = ResumableVideoRow(
        videoId = videoId,
        playlistId = playlistId,
        title = title,
        thumbnailUrl = "https://img/$videoId.jpg",
        durationSeconds = durationMs / 1000,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
    )
}
