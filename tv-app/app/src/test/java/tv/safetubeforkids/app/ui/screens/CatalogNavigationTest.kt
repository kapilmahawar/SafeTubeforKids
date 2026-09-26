package tv.safetubeforkids.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeType

/**
 * Where a card press goes and where Back comes back to, with no Compose runtime, no navigation
 * controller and no device involved.
 *
 * The screen tests on the Mi Box prove the buttons work; these prove the *model* under them is right,
 * so a failure says which rule broke rather than which press was mistimed.
 *
 * ```text
 * HOME ──shelf──▶ container ──▶ video (plays)
 *   ▲                 │
 *   └────── Back ─────┘
 * ```
 */
class CatalogNavigationTest {

    private fun node(
        id: String,
        parentId: String?,
        nodeType: CatalogNodeType,
        title: String = id,
        position: Int = 0,
        enabled: Boolean = true,
        videoId: String? = null,
        playlistId: String? = null,
    ) = CatalogNodeEntity(
        id = id,
        parentId = parentId,
        nodeType = nodeType,
        title = title,
        position = position,
        enabled = enabled,
        youtubeVideoId = videoId,
        youtubePlaylistId = playlistId,
    )

    /** Cartoons ─ CoComelon ─ (Video A, Video B), plus a direct video and a second shelf. */
    private val tree: List<CatalogNodeEntity> = listOf(
        node("cat-cartoon", null, CatalogNodeType.CATEGORY, "Cartoons", position = 0),
        node("i-cocomelon", "cat-cartoon", CatalogNodeType.SUBCATEGORY, "CoComelon", position = 0),
        node("i-cocomelon#a", "i-cocomelon", CatalogNodeType.VIDEO, "Video A", position = 0, videoId = "vidA"),
        node("i-cocomelon#b", "i-cocomelon", CatalogNodeType.VIDEO, "Video B", position = 1, videoId = "vidB"),
        node("i-direct", "cat-cartoon", CatalogNodeType.VIDEO, "Direct Video", position = 1, videoId = "vidDirect"),
        node("cat-music", null, CatalogNodeType.CATEGORY, "Music", position = 1),
        node("i-nursery", "cat-music", CatalogNodeType.SUBCATEGORY, "Nursery", position = 0),
    )

    private fun containerCard(id: String, title: String = id) = CatalogCardUi(
        id = id,
        title = title,
        kind = CatalogCardKind.CONTAINER,
        thumbnailUrl = null,
        containerId = id,
    )

    private fun videoCard(id: String, videoId: String) = CatalogCardUi(
        id = id,
        title = id,
        kind = CatalogCardKind.VIDEO,
        thumbnailUrl = null,
        videoId = videoId,
    )

    // ------------------------------------------------------------- what a press does

    @Test
    fun aContainerCardOpensItsContainerAndNeverPlaysAnything() {
        val card = containerCard("i-cocomelon")

        assertEquals("i-cocomelon", CatalogNavigation.containerOpenedBy(card))
        assertNull("a container card carries no video to play", card.videoId)
    }

    @Test
    fun aVideoCardIsThePlayersBusinessAndOpensNoContainer() {
        val card = videoCard("i-cocomelon#a", "vidA")

        assertNull("pressing a video is not a navigation", CatalogNavigation.containerOpenedBy(card))
        assertEquals("vidA", card.videoId)
    }

    @Test
    fun aContinueWatchingCardIsAlwaysAVideo() {
        val card = CatalogCardUi(
            id = "shelf-continue-watching:vidA",
            title = "Video A",
            kind = CatalogCardKind.CONTINUE_WATCHING,
            thumbnailUrl = null,
            badgeText = "3 min left",
            videoId = "vidA",
        )

        assertNull(CatalogNavigation.containerOpenedBy(card))
    }

    @Test
    fun aMalformedContainerCardOpensNothingRatherThanGuessing() {
        val nameless = CatalogCardUi(
            id = "i-x",
            title = "X",
            kind = CatalogCardKind.CONTAINER,
            thumbnailUrl = null,
            containerId = "   ",
        )

        assertNull(CatalogNavigation.containerOpenedBy(nameless))
    }

    // ------------------------------------------------------------- where Back goes

    @Test
    fun backFromASubcategoryReturnsToTheShelfWhichIsTheHomeScreen() {
        assertEquals(
            "a sub-category's parent is its shelf, and a shelf is the home screen",
            null,
            CatalogNavigation.backDestination(tree, "i-cocomelon"),
        )
        assertEquals(null, CatalogNavigation.backDestination(tree, "i-nursery"))
    }

    @Test
    fun backFromANestedContainerReturnsToTheContainerThatHoldsIt() {
        val nested = tree + listOf(
            node("i-mickey", "i-cocomelon", CatalogNodeType.SUBCATEGORY, "Mickey Mouse", position = 2),
        )

        assertEquals("i-cocomelon", CatalogNavigation.backDestination(nested, "i-mickey"))
        assertEquals(
            "then one more Back leaves the outer container for the shelf",
            null,
            CatalogNavigation.backDestination(nested, "i-cocomelon"),
        )
    }

    @Test
    fun backFromSomethingThatIsNotThereIsTheHomeScreen() {
        assertEquals(null, CatalogNavigation.backDestination(tree, "i-gone"))
        assertEquals("a video has no screen of its own to go back from", null, CatalogNavigation.backDestination(tree, "i-cocomelon#a"))
        assertEquals("and a shelf is never a screen", null, CatalogNavigation.backDestination(tree, "cat-cartoon"))
        assertEquals(emptyList<CatalogNodeEntity>(), CatalogNavigation.pathFromHome(emptyList(), "i-cocomelon"))
    }

    // ------------------------------------------------------------- the path from home

    @Test
    fun thePathFromHomeNamesEveryContainerOnTheWay() {
        assertEquals(listOf("i-cocomelon"), CatalogNavigation.pathFromHome(tree, "i-cocomelon"))

        val nested = tree + listOf(
            node("i-mickey", "i-cocomelon", CatalogNodeType.SUBCATEGORY, "Mickey Mouse", position = 2),
            node("i-pluto", "i-mickey", CatalogNodeType.SUBCATEGORY, "Pluto", position = 0),
        )
        assertEquals(
            listOf("i-cocomelon", "i-mickey", "i-pluto"),
            CatalogNavigation.pathFromHome(nested, "i-pluto"),
        )
    }

    @Test
    fun thePathIsEmptyForAnythingThatIsNotAContainer() {
        assertEquals(emptyList<String>(), CatalogNavigation.pathFromHome(tree, "cat-cartoon"))
        assertEquals(emptyList<String>(), CatalogNavigation.pathFromHome(tree, "i-cocomelon#a"))
        assertEquals(emptyList<String>(), CatalogNavigation.pathFromHome(tree, "i-gone"))
    }

    // ------------------------------------------------------------- the whole flow

    @Test
    fun homeToShelfToContainerToVideosAndBackAgainIsDeterministic() {
        // Home -> the Cartoons shelf is not a destination; its CoComelon card opens the container.
        val cards = CatalogUiProjection.build(tree, emptyList(), emptyList()).shelves
            .single { it.id == "cat-cartoon" }
            .cards
        assertEquals(listOf("CoComelon", "Direct Video"), cards.map { it.title })

        val opened = CatalogNavigation.containerOpenedBy(cards[0])
        assertEquals("i-cocomelon", opened)

        // Inside it, the child gets the two videos - and no second copy of the container.
        val inside = CatalogUiProjection.container("i-cocomelon", tree, emptyList())!!
        assertEquals(listOf("Video A", "Video B"), inside.cards.map { it.title })
        assertTrue(inside.cards.all { it.kind == CatalogCardKind.VIDEO })
        assertTrue("the container is not offered inside itself", inside.cards.none { it.id == "i-cocomelon" })

        // Back goes to the shelf, which is the home screen, and the same card is still there to focus.
        assertEquals(null, CatalogNavigation.backDestination(tree, opened!!))
        assertEquals("i-cocomelon", cards[0].id)
    }

    @Test
    fun homeToShelfToADirectVideoNeverOpensAContainer() {
        val cards = CatalogUiProjection.build(tree, emptyList(), emptyList()).shelves
            .single { it.id == "cat-cartoon" }
            .cards

        val direct = cards.single { it.title == "Direct Video" }
        assertEquals(CatalogCardKind.VIDEO, direct.kind)
        assertNull("a direct video is played, not opened", CatalogNavigation.containerOpenedBy(direct))
        assertEquals("vidDirect", direct.videoId)
    }

    @Test
    fun noCardInAProjectedCatalogCanBothOpenAndPlay() {
        // The invariant behind "no accidental playback": a container card carries no video id, and a
        // video card opens no container. Whichever way a card is pressed, exactly one thing happens.
        val catalog = CatalogUiProjection.build(tree, emptyList(), emptyList())

        catalog.shelves.flatMap { it.cards }.forEach { card ->
            if (card.kind == CatalogCardKind.CONTAINER) {
                assertNull("container card ${card.id} must not name a video", card.videoId)
                assertTrue(CatalogNavigation.containerOpenedBy(card) != null)
            } else {
                assertNull("video card ${card.id} must not name a container", CatalogNavigation.containerOpenedBy(card))
            }
        }
    }
}
