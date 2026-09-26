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
import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeType
import tv.safetubeforkids.app.data.catalog.ContentItemEntity
import tv.safetubeforkids.app.data.catalog.ContentItemType
import tv.safetubeforkids.app.data.catalog.ThumbnailMode

/**
 * What the child sees, derived from the tree alone.
 *
 * These are the rendering rules: the parent's order, hidden-but-not-deleted disabled content, empty
 * shelves, the container/category distinction, artwork lookup and the Continue Watching shelf. No
 * database and no device are involved, so a failure here is unambiguous about which rule broke.
 *
 * The fixtures are the *tree*, because that is what the catalog is: a category node with its children
 * parented to it and numbered in the order the test configured. A helper therefore returns a shelf
 * **and** its children, which is why the catalog is a list of lists.
 */
class CatalogUiProjectionTest {

    /** A shelf: the category node, plus its children re-parented to it, keeping their own positions. */
    private fun category(
        id: String,
        name: String,
        sortOrder: Int,
        enabled: Boolean = true,
        items: List<CatalogNodeEntity> = emptyList(),
    ): List<CatalogNodeEntity> =
        listOf(node(id, null, CatalogNodeType.CATEGORY, name, sortOrder, enabled)) +
            items.map { child -> child.copy(parentId = id) }

    /** A sub-category: one container card on its shelf, whether or not it imports a playlist. */
    private fun container(
        id: String,
        name: String,
        sortOrder: Int,
        playlistId: String? = null,
        enabled: Boolean = true,
    ) = node(
        id = id,
        // A sub-category is never at ROOT; the shelf that holds it re-parents it.
        parentId = "unplaced",
        nodeType = CatalogNodeType.SUBCATEGORY,
        title = name,
        position = sortOrder,
        enabled = enabled,
        playlistId = playlistId,
    )

    /** A container that imports a playlist. */
    private fun playlist(
        id: String,
        name: String,
        sortOrder: Int,
        playlistId: String,
        enabled: Boolean = true,
    ) = container(id, name, sortOrder, playlistId, enabled)

    private fun video(
        id: String,
        name: String,
        sortOrder: Int,
        videoId: String,
        enabled: Boolean = true,
    ) = node(
        id = id,
        parentId = "unplaced",
        nodeType = CatalogNodeType.VIDEO,
        title = name,
        position = sortOrder,
        enabled = enabled,
        videoId = videoId,
    )

    private fun build(
        catalog: List<List<CatalogNodeEntity>> = emptyList(),
        thumbnails: List<VideoThumbnailRow> = emptyList(),
        resumable: List<ResumableVideoRow> = emptyList(),
    ) = CatalogUiProjection.build(catalog.flatten(), thumbnails, resumable)

    /** A catalog node of the tree that decides a shelf's picture. */
    private fun node(
        id: String,
        parentId: String?,
        nodeType: CatalogNodeType,
        title: String = id,
        position: Int = 0,
        enabled: Boolean = true,
        videoId: String? = null,
        playlistId: String? = null,
        thumbnailMode: ThumbnailMode = ThumbnailMode.AUTO,
        thumbnailVideoId: String? = null,
    ) = CatalogNodeEntity(
        id = id,
        parentId = parentId,
        nodeType = nodeType,
        title = title,
        position = position,
        enabled = enabled,
        youtubeVideoId = videoId,
        youtubePlaylistId = playlistId,
        thumbnailMode = thumbnailMode,
        thumbnailVideoId = thumbnailVideoId,
    )

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
                CatalogCardKind.CONTAINER, CatalogCardKind.CONTAINER,
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

    // -------------------------------------------------- the hierarchy the child navigates (W6)
    //
    // A category is a shelf *title*: it is never a card, never focusable and never a destination. A
    // sub-category is a card that opens the sub-category, and it is never replaced by its first video -
    // that flattening is exactly what W6 removed.

    @Test
    fun aCategoryBecomesAShelfTitleAndNoCardOfItsOwn() {
        val state = build(
            catalog = listOf(
                category(
                    "cat-cartoon", "Cartoons", 0,
                    items = listOf(
                        container("i-cocomelon", "CoComelon", 0),
                        video("i-direct", "Direct Video", 1, "vidDirect"),
                    ),
                ),
            ),
        )

        val shelf = state.shelves.single()
        assertEquals("Cartoons", shelf.title)
        assertEquals(
            "the category's own id is a shelf, not a card",
            listOf("i-cocomelon", "i-direct"),
            shelf.cards.map { it.id },
        )
        assertTrue(
            "no card may stand for the category itself",
            shelf.cards.none { it.id == "cat-cartoon" },
        )
    }

    @Test
    fun aHandBuiltSubcategoryIsAContainerCardAndNeverItsFirstVideo() {
        // The W6 acceptance criterion, in miniature: the shelf shows the container, not the first thing
        // inside it. This container has no import source at all - the parent built it by hand, which is
        // exactly the case that used to be flattened into "Video A".
        val shelf = listOf(
            node("cat-cartoon", null, CatalogNodeType.CATEGORY, "Cartoons", position = 0),
            node("i-cocomelon", "cat-cartoon", CatalogNodeType.SUBCATEGORY, "CoComelon", position = 0),
            node("i-cocomelon#a", "i-cocomelon", CatalogNodeType.VIDEO, "Video A", position = 0, videoId = "vidA"),
            node("i-cocomelon#b", "i-cocomelon", CatalogNodeType.VIDEO, "Video B", position = 1, videoId = "vidB"),
            node("i-direct", "cat-cartoon", CatalogNodeType.VIDEO, "Direct Video", position = 1, videoId = "vidDirect"),
        )

        val cards = build(catalog = listOf(shelf)).shelves.single().cards

        assertEquals(listOf("CoComelon", "Direct Video"), cards.map { it.title })
        assertEquals(
            listOf(CatalogCardKind.CONTAINER, CatalogCardKind.VIDEO),
            cards.map { it.kind },
        )

        val container = cards.first()
        assertEquals("i-cocomelon", container.containerId)
        assertNull("a container card is never a video", container.videoId)
        assertNull("and it names no playlist it does not import", container.playlistId)

        // Its own screen is where the videos are.
        val opened = CatalogUiProjection.container("i-cocomelon", shelf, emptyList())
        assertEquals(listOf("Video A", "Video B"), opened!!.cards.map { it.title })
    }

    @Test
    fun mixedChildrenKeepTheParentsExactOrder() {
        val state = build(
            catalog = listOf(
                category(
                    "cat-cartoon", "Cartoons", 0,
                    items = listOf(
                        container("i-cocomelon", "CoComelon", 0),
                        video("i-a", "Direct Video A", 1, "vidA"),
                        container("i-peppa", "Peppa Pig", 2),
                        video("i-b", "Direct Video B", 3, "vidB"),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf("CoComelon", "Direct Video A", "Peppa Pig", "Direct Video B"),
            state.shelves.single().cards.map { it.title },
        )
        assertEquals(
            listOf(
                CatalogCardKind.CONTAINER, CatalogCardKind.VIDEO,
                CatalogCardKind.CONTAINER, CatalogCardKind.VIDEO,
            ),
            state.shelves.single().cards.map { it.kind },
        )
    }

    @Test
    fun equalPositionsAmongMixedChildrenBreakOnId() {
        val state = build(
            catalog = listOf(
                category(
                    "cat-cartoon", "Cartoons", 0,
                    items = listOf(
                        video("i-b", "B", 0, "vidB"),
                        container("i-a", "A", 0),
                    ),
                ),
            ),
        )

        assertEquals(listOf("i-a", "i-b"), state.shelves.single().cards.map { it.id })
    }

    @Test
    fun aDisabledContainerIsNotACardAndCannotBeOpened() {
        val tree = listOf(
            node("cat-cartoon", null, CatalogNodeType.CATEGORY, "Cartoons", position = 0),
            node("i-shown", "cat-cartoon", CatalogNodeType.SUBCATEGORY, "Shown", position = 0),
            node("i-hidden", "cat-cartoon", CatalogNodeType.SUBCATEGORY, "Hidden", position = 1, enabled = false),
            node("i-hidden#v", "i-hidden", CatalogNodeType.VIDEO, "Hidden Video", position = 0, videoId = "vidHidden"),
        )

        val state = CatalogUiProjection.build(tree, emptyList(), emptyList())

        assertEquals(listOf("Shown"), state.shelves.single().cards.map { it.title })
        assertFalse(
            "a hidden container is not projected at all",
            state.shelves.single().cards.any { it.containerId == "i-hidden" },
        )
    }

    @Test
    fun openingAContainerShowsItsOwnEnabledChildrenInOrder() {
        val tree = listOf(
            node("cat-cartoon", null, CatalogNodeType.CATEGORY, "Cartoons", position = 0),
            node("i-cocomelon", "cat-cartoon", CatalogNodeType.SUBCATEGORY, "CoComelon", position = 0),
            node("i-cocomelon#b", "i-cocomelon", CatalogNodeType.VIDEO, "Video B", position = 1, videoId = "vidB"),
            node("i-cocomelon#a", "i-cocomelon", CatalogNodeType.VIDEO, "Video A", position = 0, videoId = "vidA"),
            node("i-cocomelon#off", "i-cocomelon", CatalogNodeType.VIDEO, "Hidden", position = 2, enabled = false, videoId = "vidOff"),
        )

        val opened = CatalogUiProjection.container("i-cocomelon", tree, emptyList())!!

        assertEquals("CoComelon", opened.title)
        assertEquals(
            "position decides, and a disabled child is not offered",
            listOf("Video A", "Video B"),
            opened.cards.map { it.title },
        )
        assertEquals(listOf("vidA", "vidB"), opened.cards.map { it.videoId })
        assertFalse(opened.isEmpty)
    }

    @Test
    fun anEmptyContainerOpensWithNoCardsAndNoCrash() {
        val tree = listOf(
            node("cat-cartoon", null, CatalogNodeType.CATEGORY, "Cartoons", position = 0),
            node("i-empty", "cat-cartoon", CatalogNodeType.SUBCATEGORY, "Empty", position = 0),
        )

        val opened = CatalogUiProjection.container("i-empty", tree, emptyList())!!

        assertEquals("Empty", opened.title)
        assertTrue(opened.isEmpty)
        assertEquals(emptyList<CatalogCardUi>(), opened.cards)
        assertEquals(emptyList<CatalogCardUi>(), opened.asShelf().cards)
    }

    @Test
    fun aNestedContainerOpensItselfRatherThanTheVideosBelowIt() {
        // The schema keeps a sub-category to videos only, so this shape cannot be produced by the
        // editor today. If a catalog ever holds one - a hand-edited document, a future schema - the
        // projection still shows the inner container as a card instead of flattening it.
        val tree = listOf(
            node("cat-cartoon", null, CatalogNodeType.CATEGORY, "Cartoons", position = 0),
            node("i-disney", "cat-cartoon", CatalogNodeType.SUBCATEGORY, "Disney", position = 0),
            node("i-mickey", "i-disney", CatalogNodeType.SUBCATEGORY, "Mickey Mouse", position = 0),
            node("i-mickey#v", "i-mickey", CatalogNodeType.VIDEO, "Video 1", position = 0, videoId = "vid1"),
        )

        val outer = CatalogUiProjection.container("i-disney", tree, emptyList())!!
        assertEquals(listOf("Mickey Mouse"), outer.cards.map { it.title })
        assertEquals(CatalogCardKind.CONTAINER, outer.cards.single().kind)
        assertEquals("i-mickey", outer.cards.single().containerId)

        val inner = CatalogUiProjection.container("i-mickey", tree, emptyList())!!
        assertEquals(listOf("Video 1"), inner.cards.map { it.title })
    }

    @Test
    fun aCategoryIsNotADestinationAndNeitherIsAnUnknownId() {
        val tree = listOf(
            node("cat-cartoon", null, CatalogNodeType.CATEGORY, "Cartoons", position = 0),
            node("i-cocomelon", "cat-cartoon", CatalogNodeType.SUBCATEGORY, "CoComelon", position = 0),
            node("i-cocomelon#a", "i-cocomelon", CatalogNodeType.VIDEO, "Video A", position = 0, videoId = "vidA"),
        )

        assertNull("a category is a heading, not a screen", CatalogUiProjection.container("cat-cartoon", tree, emptyList()))
        assertNull("a video is not a container", CatalogUiProjection.container("i-cocomelon#a", tree, emptyList()))
        assertNull("an id that is not in the catalog opens nothing", CatalogUiProjection.container("i-gone", tree, emptyList()))
    }

    @Test
    fun continueWatchingContainsVideosOnlyNeverAContainer() {
        val state = build(
            catalog = catalogPublishingApproved(),
            thumbnails = listOf(VideoThumbnailRow("vid1", "PLapproved", "https://img/vid1.jpg")),
            resumable = listOf(resumable("vid1", "Half Watched")),
        )

        val continueWatching = state.shelves.first { it.id == CatalogUiProjection.CONTINUE_WATCHING_ID }
        assertTrue(
            "a container must never appear in Continue Watching",
            continueWatching.cards.none { it.kind == CatalogCardKind.CONTAINER },
        )
        assertEquals(
            listOf(CatalogCardKind.CONTINUE_WATCHING),
            continueWatching.cards.map { it.kind },
        )
        assertEquals(listOf("vid1"), continueWatching.cards.map { it.videoId })
        assertNull(continueWatching.cards.single().containerId)
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

    // ------------------------------------------------------- the picture a container shows
    // A shelf or a subcategory is a group, so one video inside it stands for it: the one the parent
    // chose, or the first one the app finds. The lookup is by the container's own node id, and the
    // artwork still comes from the approved cache and nowhere else.

    private fun treeOfTheShelfItem() = listOf(
        node("cat-music", null, CatalogNodeType.CATEGORY, "Music"),
        node("i-p", "cat-music", CatalogNodeType.SUBCATEGORY, "Nursery", position = 0, playlistId = "PLn"),
        node("i-p#first", "i-p", CatalogNodeType.VIDEO, "First", position = 0, videoId = "vid1"),
        node("i-p#second", "i-p", CatalogNodeType.VIDEO, "Second", position = 1, videoId = "vid2"),
    )

    @Test
    fun aShelfShowsThePictureOfAVideoInsideIt() {
        val state = build(
            catalog = listOf(treeOfTheShelfItem()),
            thumbnails = listOf(
                VideoThumbnailRow("vid1", "PLn", "https://img/first.jpg"),
                VideoThumbnailRow("vid2", "PLn", "https://img/second.jpg"),
            ),
        )

        assertEquals("https://img/first.jpg", state.shelves.single().cards.single().thumbnailUrl)
    }

    @Test
    fun aShelfShowsThePictureOfTheVideoTheParentChose() {
        val tree = treeOfTheShelfItem().map {
            if (it.id == "i-p") {
                it.copy(thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-p#second")
            } else {
                it
            }
        }

        val state = build(
            catalog = listOf(tree),
            thumbnails = listOf(
                VideoThumbnailRow("vid1", "PLn", "https://img/first.jpg"),
                VideoThumbnailRow("vid2", "PLn", "https://img/second.jpg"),
            ),
        )

        assertEquals("https://img/second.jpg", state.shelves.single().cards.single().thumbnailUrl)
    }

    @Test
    fun aShelfFallsBackToItsPlaylistArtworkWhenTheChosenVideoIsNotCached() {
        val tree = treeOfTheShelfItem().map {
            if (it.id == "i-p") {
                it.copy(thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-p#second")
            } else {
                it
            }
        }

        val state = build(
            catalog = listOf(tree),
            // The approved cache knows the playlist's opening video but not the chosen one: a video
            // whose source is not approved has no artwork, and none may be invented for it.
            thumbnails = listOf(VideoThumbnailRow("vid1", "PLn", "https://img/first.jpg")),
        )

        assertEquals("https://img/first.jpg", state.shelves.single().cards.single().thumbnailUrl)
    }

    @Test
    fun theShelfHeadingCarriesTheSamePictureAsTheShelf() {
        val state = build(
            catalog = listOf(treeOfTheShelfItem()),
            thumbnails = listOf(VideoThumbnailRow("vid1", "PLn", "https://img/first.jpg")),
        )

        assertEquals("https://img/first.jpg", state.shelves.single().thumbnailUrl)
        assertEquals(state.shelves.single().cards.single().thumbnailUrl, state.shelves.single().thumbnailUrl)
    }

    @Test
    fun aShelfWithNothingToTakeAPictureFromHasNoShelfPicture() {
        val state = build(
            catalog = listOf(
                listOf(
                    node("cat-music", null, CatalogNodeType.CATEGORY, "Music"),
                    node("i-p", "cat-music", CatalogNodeType.SUBCATEGORY, "Nursery", position = 0, playlistId = "PLn"),
                    node(
                        "i-p#off", "i-p", CatalogNodeType.VIDEO, "Hidden",
                        position = 0, enabled = false, videoId = "vid1",
                    ),
                ),
            ),
            thumbnails = listOf(VideoThumbnailRow("vid1", "PLn", "https://img/first.jpg")),
        )

        assertNull("a hidden video may not stand for the shelf", state.shelves.single().thumbnailUrl)
        assertEquals(
            "and the container card keeps the playlist artwork it had before thumbnails existed",
            "https://img/first.jpg", state.shelves.single().cards.single().thumbnailUrl,
        )
    }

    @Test
    fun aCatalogOfPlainVideosProjectsExactlyAsItAlwaysDid() {
        val state = build(
            catalog = listOf(
                category("cat-music", "Music", 0, items = listOf(video("i-v", "Twinkle", 0, "vid1"))),
            ),
            thumbnails = listOf(VideoThumbnailRow("vid1", "PLone", "https://img/vid1.jpg")),
        )

        assertEquals("https://img/vid1.jpg", state.shelves.single().cards.single().thumbnailUrl)
        assertEquals(CatalogCardKind.VIDEO, state.shelves.single().cards.single().kind)
        assertEquals(
            "and the shelf header shows the video the app picked inside it",
            "https://img/vid1.jpg", state.shelves.single().thumbnailUrl,
        )
    }

    @Test
    fun theContinueWatchingShelfHasNoHeaderPictureBecauseItIsNotAContainer() {
        // Continue Watching is a shelf the app builds, not a category a parent configured, so there is
        // no node for a picture to come from and its header stays exactly what it has always been.
        val state = build(
            catalog = catalogPublishingApproved(),
            thumbnails = listOf(VideoThumbnailRow("vid1", "PLapproved", "https://img/vid1.jpg")),
            resumable = listOf(resumable("vid1", "Half Watched")),
        )

        val continueWatching = state.shelves.first { it.id.startsWith("shelf-continue-watching") }
        assertNull("a built shelf has no category to take a picture from", continueWatching.thumbnailUrl)
        assertEquals(1, continueWatching.cards.size)
        assertEquals(
            "the card keeps its own artwork",
            "https://img/vid1.jpg", continueWatching.cards.single().thumbnailUrl,
        )
    }

    @Test
    fun aVideoCardIsUnaffectedByWhosePictureTheShelfIs() {
        val state = build(
            catalog = listOf(
                listOf(
                    node("cat-music", null, CatalogNodeType.CATEGORY, "Music"),
                    node("i-p", "cat-music", CatalogNodeType.SUBCATEGORY, "Nursery", position = 0, playlistId = "PLn"),
                    node("i-p#first", "i-p", CatalogNodeType.VIDEO, "First", position = 0, videoId = "vid1"),
                    node("i-v", "cat-music", CatalogNodeType.VIDEO, "Twinkle", position = 1, videoId = "vid2"),
                ),
            ),
            thumbnails = listOf(
                VideoThumbnailRow("vid1", "PLn", "https://img/first.jpg"),
                VideoThumbnailRow("vid2", "PLn", "https://img/second.jpg"),
            ),
        )

        val cards = state.shelves.single().cards
        assertEquals("the container card uses its inside", "https://img/first.jpg", cards[0].thumbnailUrl)
        assertEquals("a video card is still itself", "https://img/second.jpg", cards[1].thumbnailUrl)
    }

    // ------------------------------------------------------------------ continue watching

    @Test
    fun continueWatchingComesFirstAndKeepsItsMostRecentFirstOrder() {
        val state = build(
            // The cards also have to be published by the catalog now, so this asserts the order of
            // the videos the parent still offers rather than of everything ever half-watched.
            catalog = listOf(
                category(
                    "cat-cartoon", "Cartoon", 0,
                    items = listOf(playlist("i-approved", "Approved", 0, "PLapproved")),
                ),
            ),
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
        val state = build(
            catalog = catalogPublishingApproved(),
            resumable = listOf(resumable("vid1", "Twinkle", playlistId = "PLapproved")),
        )

        // Continue Watching is first; the category that publishes the video adds a shelf behind it.
        val card = state.shelves.first { it.id == CatalogUiProjection.CONTINUE_WATCHING_ID }.cards.single()
        assertEquals(CatalogCardKind.CONTINUE_WATCHING, card.kind)
        assertEquals("vid1", card.videoId)
        assertEquals("PLapproved", card.playlistId)
        assertEquals("vid1", card.id.substringAfterLast(':'))
    }

    @Test
    fun aContinueWatchingCardShowsHowMuchIsLeft() {
        val state = build(
            catalog = catalogPublishingApproved(),
            resumable = listOf(
                resumable("vid1", "Twinkle", positionMs = 60_000, durationMs = 660_000),
            ),
        )

        val shelf = state.shelves.first { it.id == CatalogUiProjection.CONTINUE_WATCHING_ID }
        assertEquals("10 min left", shelf.cards.single().badgeText)
    }

    @Test
    fun aContinueWatchingCardFallsBackToWatchedWhenTheDurationIsUnknown() {
        val state = build(
            catalog = catalogPublishingApproved(),
            resumable = listOf(resumable("vid1", "Twinkle", positionMs = 30_000, durationMs = 0)),
        )

        val shelf = state.shelves.first { it.id == CatalogUiProjection.CONTINUE_WATCHING_ID }
        assertEquals("Watched", shelf.cards.single().badgeText)
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
            List(15) { CatalogCardKind.VIDEO }.zip(List(15) { CatalogCardKind.CONTAINER })
                .flatMap { listOf(it.first, it.second) },
            state.shelves.first().cards.map { it.kind },
        )
    }

    /** The same shelf with one imported container under it. */
    private fun withOneItem(shelf: List<CatalogNodeEntity>): List<CatalogNodeEntity> {
        val category = shelf.first()
        return shelf + container(
            id = "item-${category.id}",
            name = category.title,
            sortOrder = 0,
            playlistId = "PL${category.id}",
        ).copy(parentId = category.id)
    }

    /**
     * A catalog that publishes the playlist the resumable fixtures below belong to.
     *
     * Continue Watching is curated by the catalog, so a card only appears when the parent still
     * publishes the video - either by naming it or by naming the playlist it sits in. Tests about a
     * card's own properties (badge, order, source) therefore have to publish it first; that is a
     * deliberate precondition of the rule, not a workaround.
     */
    private fun catalogPublishingApproved(playlistId: String = "PLapproved") = listOf(
        category(
            "cat-approved", "Approved", 0,
            items = listOf(playlist("i-approved", "Approved", 0, playlistId)),
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

    // ---------------------------------------------- Continue Watching is curated by the catalog
    // F6. The catalog is what a parent curates, so a video that is no longer published must not
    // stay reachable from Continue Watching. These are visibility rules only: no approval record
    // and no saved position is touched, and `PlaybackAuthorization` is unchanged.

    @Test
    fun aPublishedResumableVideoIsStillOfferedInContinueWatching() {
        val state = build(
            catalog = listOf(
                category("cat-music", "Music", 0, items = listOf(video("i-1", "Twinkle", 0, "vidA"))),
            ),
            resumable = listOf(resumable("vidA", "Twinkle")),
        )

        val shelf = state.shelves.first { it.id == CatalogUiProjection.CONTINUE_WATCHING_ID }
        assertEquals(listOf("vidA"), shelf.cards.map { it.videoId })
        assertEquals(1, shelf.cards.size)
    }

    @Test
    fun aVideoRemovedFromTheCatalogDisappearsFromContinueWatching() {
        val state = build(
            catalog = listOf(
                category("cat-music", "Music", 0, items = listOf(video("i-1", "Something Else", 0, "vidOTHER"))),
            ),
            resumable = listOf(resumable("vidA", "Removed")),
        )

        assertTrue(state.shelves.none { it.id == CatalogUiProjection.CONTINUE_WATCHING_ID })
    }

    @Test
    fun anEmptyCatalogShowsTheEmptyStateEvenWithAHalfWatchedVideo() {
        // The reported symptom: an empty catalog still rendered a Continue Watching shelf, which
        // suppressed the empty state entirely.
        val state = build(
            catalog = emptyList(),
            resumable = listOf(resumable("vidA", "Half watched")),
        )

        assertTrue(state.isEmpty)
        assertTrue(state.shelves.isEmpty())
    }

    @Test
    fun aVideoInADisabledCategoryIsNotOfferedInContinueWatching() {
        val state = build(
            catalog = listOf(
                category(
                    "cat-off", "Hidden", 0, enabled = false,
                    items = listOf(video("i-1", "Twinkle", 0, "vidA")),
                ),
            ),
            resumable = listOf(resumable("vidA", "Twinkle")),
        )

        assertTrue(state.shelves.none { it.id == CatalogUiProjection.CONTINUE_WATCHING_ID })
    }

    @Test
    fun aDisabledCatalogItemIsNotOfferedInContinueWatching() {
        val state = build(
            catalog = listOf(
                category(
                    "cat-music", "Music", 0,
                    items = listOf(video("i-1", "Twinkle", 0, "vidA", enabled = false)),
                ),
            ),
            resumable = listOf(resumable("vidA", "Twinkle")),
        )

        assertTrue(state.shelves.none { it.id == CatalogUiProjection.CONTINUE_WATCHING_ID })
    }

    @Test
    fun aVideoInsideAPublishedPlaylistIsOfferedWithoutItsOwnItem() {
        val state = build(
            catalog = listOf(
                category("cat-music", "Music", 0, items = listOf(playlist("i-p", "Nursery", 0, "PLapproved"))),
            ),
            resumable = listOf(resumable("vidA", "Twinkle", playlistId = "PLapproved")),
        )

        val shelf = state.shelves.first { it.id == CatalogUiProjection.CONTINUE_WATCHING_ID }
        assertEquals(listOf("vidA"), shelf.cards.map { it.videoId })
    }

    @Test
    fun aDisabledPlaylistItemHidesItsVideosFromContinueWatching() {
        val state = build(
            catalog = listOf(
                category(
                    "cat-music", "Music", 0,
                    items = listOf(playlist("i-p", "Nursery", 0, "PLapproved", enabled = false)),
                ),
            ),
            resumable = listOf(resumable("vidA", "Twinkle", playlistId = "PLapproved")),
        )

        assertTrue(state.shelves.none { it.id == CatalogUiProjection.CONTINUE_WATCHING_ID })
    }

    @Test
    fun rePublishingTheVideoBringsItBackToContinueWatching() {
        // Nothing is deleted for being unpublished, so switching it back on restores the card.
        val hidden = build(
            catalog = emptyList(),
            resumable = listOf(resumable("vidA", "Twinkle")),
        )
        assertTrue(hidden.shelves.none { it.id == CatalogUiProjection.CONTINUE_WATCHING_ID })

        val shown = build(
            catalog = listOf(
                category("cat-music", "Music", 0, items = listOf(video("i-1", "Twinkle", 0, "vidA"))),
            ),
            resumable = listOf(resumable("vidA", "Twinkle")),
        )

        val shelf = shown.shelves.first { it.id == CatalogUiProjection.CONTINUE_WATCHING_ID }
        assertEquals(listOf("vidA"), shelf.cards.map { it.videoId })
    }

    @Test
    fun onlyUnpublishedVideosAreDroppedWhilePublishedOnesKeepTheirOrder() {
        val state = build(
            catalog = listOf(
                category("cat-music", "Music", 0, items = listOf(video("i-1", "Kept", 0, "vidKEEP"))),
            ),
            resumable = listOf(
                resumable("vidKEEP", "Kept", updatedAt = 2),
                resumable("vidGONE", "Removed", updatedAt = 1),
            ),
        )

        val shelf = state.shelves.first { it.id == CatalogUiProjection.CONTINUE_WATCHING_ID }
        assertEquals(listOf("vidKEEP"), shelf.cards.map { it.videoId })
    }
}
