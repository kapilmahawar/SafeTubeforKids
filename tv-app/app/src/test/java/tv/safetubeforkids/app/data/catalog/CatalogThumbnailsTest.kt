package tv.safetubeforkids.app.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which video's picture stands for a container, and why.
 *
 * `CatalogThumbnails` is asked the same question by three callers - the TV's grid, the parent's editor
 * and these tests - so the answer has to be a pure function of the node list. Everything here is
 * therefore expressed as a tree in, a YouTube video id out: no database, no clock, no network and no
 * dependence on the order the nodes happen to arrive in.
 *
 * Two halves, and they are deliberately different:
 *
 *  - `AUTO` is a *search*: the first video inside, in catalog order, skipping anything hidden. Its
 *    examples are pinned here so the TV and the editor cannot drift apart about which video that is.
 *  - `VIDEO` is a *choice*: the parent named a node, and the resolver either honours it or - because
 *    the name no longer holds - answers with what `AUTO` would have said. An unusable choice is never
 *    an error at render time: a shelf is not allowed to fail to draw because a video was deleted.
 */
class CatalogThumbnailsTest {

    private fun category(
        id: String = "cat-cartoon",
        title: String = "Cartoons",
        position: Int = 0,
        parentId: String? = null,
        nodeType: CatalogNodeType = CatalogNodeType.CATEGORY,
        enabled: Boolean = true,
        videoId: String? = null,
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
        youtubePlaylistId = null,
        thumbnailMode = thumbnailMode,
        thumbnailVideoId = thumbnailVideoId,
        thumbnailUrl = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun subcategory(
        id: String = "i-nursery",
        title: String = "Nursery",
        position: Int = 0,
        parentId: String? = "cat-cartoon",
        enabled: Boolean = true,
        thumbnailMode: ThumbnailMode = ThumbnailMode.AUTO,
        thumbnailVideoId: String? = null,
    ) = category(
        id = id, title = title, position = position, parentId = parentId,
        nodeType = CatalogNodeType.SUBCATEGORY, enabled = enabled,
        thumbnailMode = thumbnailMode, thumbnailVideoId = thumbnailVideoId,
    )

    private fun video(
        id: String = "i-twinkle",
        title: String = "Twinkle",
        position: Int = 0,
        parentId: String? = "i-nursery",
        videoId: String? = "DuXwFlL8Usk",
        enabled: Boolean = true,
    ) = category(
        id = id, title = title, position = position, parentId = parentId,
        nodeType = CatalogNodeType.VIDEO, enabled = enabled, videoId = videoId,
    )

    private fun resolve(nodes: List<CatalogNodeEntity>, nodeId: String) =
        CatalogThumbnails.representativeFor(nodes, nodeId)

    // ------------------------------------------------------------------ a video is itself

    @Test
    fun aVideoRepresentsItself() {
        assertEquals("DuXwFlL8Usk", resolve(listOf(category(), subcategory(), video()), "i-twinkle"))
    }

    @Test
    fun aDisabledVideoDoesNotRepresentItself() {
        val nodes = listOf(category(), subcategory(), video(enabled = false))

        assertNull(resolve(nodes, "i-twinkle"))
    }

    @Test
    fun aVideoCannotExistWithoutAnIdentifierSoThereIsNoBlankCaseToAnswer() {
        // The resolver treats a blank identifier as no picture at all. That state is unreachable
        // through the model - the entity and the payload validator both refuse it - so the check is a
        // guard for hand-edited data rather than a catalog a parent can produce.
        assertEquals(
            "a VIDEO node requires a non-blank youtubeVideoId",
            CatalogNodeValidation.problemWith(
                id = "i-twinkle", parentId = "i-nursery", nodeType = CatalogNodeType.VIDEO,
                title = "Twinkle", position = 0, youtubeVideoId = "   ", youtubePlaylistId = null,
            ),
        )
    }

    // ------------------------------------------------------------------ automatic

    @Test
    fun automaticUsesTheFirstVideoInsideTheContainer() {
        val nodes = listOf(
            category(),
            subcategory(id = "i-nursery", position = 0),
            video(id = "i-a", title = "A", position = 0, parentId = "i-nursery", videoId = "vidA"),
            video(id = "i-b", title = "B", position = 1, parentId = "i-nursery", videoId = "vidB"),
        )

        assertEquals("vidA", resolve(nodes, "cat-cartoon"))
        assertEquals("vidA", resolve(nodes, "i-nursery"))
    }

    @Test
    fun aDirectVideoBeforeASubcategoryWins() {
        val nodes = listOf(
            category(),
            video(id = "i-direct", title = "Direct", position = 0, parentId = "cat-cartoon", videoId = "vidDirect"),
            subcategory(id = "i-nursery", position = 1),
            video(id = "i-nested", title = "Nested", position = 0, parentId = "i-nursery", videoId = "vidNested"),
        )

        assertEquals("vidDirect", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun aSubcategoryBeforeADirectVideoIsDescendedIntoFirst() {
        // The documented example: X -> B is reached before Y -> C, because X comes first and B is the
        // first video inside it, even though C is a video of the same shelf.
        val nodes = listOf(
            category(),
            subcategory(id = "X", title = "X", position = 0),
            video(id = "B", title = "B", position = 0, parentId = "X", videoId = "vidB"),
            subcategory(id = "Y", title = "Y", position = 1),
            video(id = "C", title = "C", position = 0, parentId = "Y", videoId = "vidC"),
        )

        assertEquals("vidB", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun withOnlySubcategoriesTheFirstOnesFirstVideoWins() {
        val nodes = listOf(
            category(),
            subcategory(id = "X", position = 0),
            video(id = "B", title = "B", position = 1, parentId = "X", videoId = "vidB"),
            video(id = "A", title = "A", position = 0, parentId = "X", videoId = "vidA"),
            subcategory(id = "Y", position = 1),
            video(id = "C", title = "C", position = 0, parentId = "Y", videoId = "vidC"),
        )

        assertEquals("position decides inside a container too", "vidA", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun aHiddenVideoIsSkippedAndTheNextOneIsUsed() {
        val nodes = listOf(
            category(),
            video(id = "i-off", title = "Hidden", position = 0, parentId = "cat-cartoon", videoId = "vidOff", enabled = false),
            video(id = "i-on", title = "Shown", position = 1, parentId = "cat-cartoon", videoId = "vidOn"),
        )

        assertEquals("vidOn", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun aHiddenContainerTakesItsWholeSubtreeWithIt() {
        val nodes = listOf(
            category(),
            subcategory(id = "i-hidden", position = 0, enabled = false),
            video(id = "i-inside", title = "Inside", position = 0, parentId = "i-hidden", videoId = "vidInside"),
            video(id = "i-shown", title = "Shown", position = 1, parentId = "cat-cartoon", videoId = "vidShown"),
        )

        assertEquals("vidShown", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun aContainerWithNothingUsableInsideHasNoRepresentative() {
        val nodes = listOf(category(), subcategory(id = "i-empty", position = 0))

        assertNull(resolve(nodes, "i-empty"))
        assertNull("and neither has its parent", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun theAnswerNeverDependsOnTheOrderTheNodesArriveIn() {
        val tree = listOf(
            category(),
            video(id = "i-direct", title = "Direct", position = 1, parentId = "cat-cartoon", videoId = "vidDirect"),
            subcategory(id = "i-nursery", position = 0),
            video(id = "i-nested", title = "Nested", position = 0, parentId = "i-nursery", videoId = "vidNested"),
        )

        val answer = resolve(tree, "cat-cartoon")
        assertEquals("vidNested", answer)
        assertEquals(answer, resolve(tree.reversed(), "cat-cartoon"))
        assertEquals(answer, resolve(tree.shuffled(), "cat-cartoon"))
        assertEquals("and it is the same every time it is asked", answer, resolve(tree, "cat-cartoon"))
    }

    @Test
    fun equalPositionsAreBrokenByIdSoThereIsAlwaysOneAnswer() {
        val nodes = listOf(
            category(),
            video(id = "i-b", title = "B", position = 0, parentId = "cat-cartoon", videoId = "vidB"),
            video(id = "i-a", title = "A", position = 0, parentId = "cat-cartoon", videoId = "vidA"),
        )

        assertEquals("vidA", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun aShelfNeverLooksAtAnotherShelf() {
        val nodes = listOf(
            category(id = "cat-cartoon", position = 0),
            category(id = "cat-music", title = "Music", position = 1),
            video(id = "i-music", title = "Music Video", position = 0, parentId = "cat-music", videoId = "vidMusic"),
        )

        assertNull("a neighbouring shelf's videos are not this shelf's", resolve(nodes, "cat-cartoon"))
    }

    // ------------------------------------------------------------------ a chosen video

    @Test
    fun aChosenVideoIsHonoured() {
        val nodes = listOf(
            category(thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-b"),
            video(id = "i-a", title = "A", position = 0, parentId = "cat-cartoon", videoId = "vidA"),
            video(id = "i-b", title = "B", position = 1, parentId = "cat-cartoon", videoId = "vidB"),
        )

        assertEquals("the choice wins over the automatic answer", "vidB", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun aChoiceDeeperInsideTheContainerIsHonoured() {
        val nodes = listOf(
            category(thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-nested"),
            subcategory(id = "i-nursery", position = 0),
            video(id = "i-nested", title = "Nested", position = 0, parentId = "i-nursery", videoId = "vidNested"),
            video(id = "i-direct", title = "Direct", position = 1, parentId = "cat-cartoon", videoId = "vidDirect"),
        )

        assertEquals("vidNested", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun aChosenVideoIsTheNodeIdNotTheYoutubeId() {
        // Two nodes naming the same YouTube video: only the node id can say which one was meant, and
        // the answer is that video's YouTube id because that is what artwork is looked up by.
        val nodes = listOf(
            category(thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-second"),
            video(id = "i-first", title = "First", position = 0, parentId = "cat-cartoon", videoId = "vidSame"),
            video(id = "i-second", title = "Second", position = 1, parentId = "cat-cartoon", videoId = "vidSame"),
        )

        assertEquals("vidSame", resolve(nodes, "cat-cartoon"))
        assertNull("a YouTube id is not a node id", resolve(nodes, "vidSame"))
    }

    @Test
    fun aChoiceThatNoLongerHoldsFallsBackToAutomatic() {
        val chosen = listOf(
            category(thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-gone"),
            video(id = "i-here", title = "Here", position = 0, parentId = "cat-cartoon", videoId = "vidHere"),
        )

        // The video was deleted, or was never there in the first place.
        assertEquals("vidHere", resolve(chosen, "cat-cartoon"))
    }

    @Test
    fun aChoiceOfSomethingThatIsNotAVideoFallsBackToAutomatic() {
        val nodes = listOf(
            category(thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-nursery"),
            subcategory(id = "i-nursery", position = 0),
            video(id = "i-nested", title = "Nested", position = 0, parentId = "i-nursery", videoId = "vidNested"),
        )

        assertEquals("vidNested", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun aChoiceOfAVideoOutsideTheContainerFallsBackToAutomatic() {
        val nodes = listOf(
            category(id = "cat-cartoon", position = 0, thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-music"),
            video(id = "i-own", title = "Own", position = 0, parentId = "cat-cartoon", videoId = "vidOwn"),
            category(id = "cat-music", title = "Music", position = 1),
            video(id = "i-music", title = "Elsewhere", position = 0, parentId = "cat-music", videoId = "vidElsewhere"),
        )

        assertEquals("vidOwn", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun aChoiceOfAHiddenVideoFallsBackToAutomatic() {
        val nodes = listOf(
            category(thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-hidden"),
            video(id = "i-hidden", title = "Hidden", position = 0, parentId = "cat-cartoon", videoId = "vidHidden", enabled = false),
            video(id = "i-shown", title = "Shown", position = 1, parentId = "cat-cartoon", videoId = "vidShown"),
        )

        assertEquals("vidShown", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun aBlankChoiceIsTheSameAsNoChoice() {
        val nodes = listOf(
            category(thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "   "),
            video(id = "i-real", title = "Real", position = 0, parentId = "cat-cartoon", videoId = "vidReal"),
        )

        assertEquals("vidReal", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun aChoiceInsideAHiddenSubcategoryFallsBackToAutomatic() {
        val nodes = listOf(
            category(thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-nested"),
            subcategory(id = "i-hidden", position = 0, enabled = false),
            video(id = "i-nested", title = "Nested", position = 0, parentId = "i-hidden", videoId = "vidNested"),
            video(id = "i-direct", title = "Direct", position = 1, parentId = "cat-cartoon", videoId = "vidDirect"),
        )

        assertEquals(
            "a picture must not put a hidden video back on screen",
            "vidDirect", resolve(nodes, "cat-cartoon"),
        )
    }

    @Test
    fun anUnusableChoiceAndNothingToFallBackOnIsSimplyNoPicture() {
        val nodes = listOf(category(thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-gone"))

        assertNull(resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun aChosenVideoSurvivesBeingHiddenAndComesBackWhenItIsShown() {
        val hidden = listOf(
            category(thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-chosen"),
            video(id = "i-chosen", title = "Chosen", position = 0, parentId = "cat-cartoon", videoId = "vidChosen", enabled = false),
            video(id = "i-other", title = "Other", position = 1, parentId = "cat-cartoon", videoId = "vidOther"),
        )
        val shown = hidden.map { if (it.id == "i-chosen") it.copy(enabled = true) else it }

        assertEquals("while it is hidden, automatic answers", "vidOther", resolve(hidden, "cat-cartoon"))
        assertEquals("and the choice is not thrown away", "vidChosen", resolve(shown, "cat-cartoon"))
    }

    // ------------------------------------------------------------------ every container at once

    @Test
    fun everyContainerIsAnsweredOnceAndVideosAreNot() {
        val nodes = listOf(
            category(id = "cat-cartoon", position = 0),
            subcategory(id = "i-nursery", position = 0),
            video(id = "i-nested", title = "Nested", position = 0, parentId = "i-nursery", videoId = "vidNested"),
            subcategory(id = "i-empty", title = "Empty", position = 1),
            video(id = "i-direct", title = "Direct", position = 2, parentId = "cat-cartoon", videoId = "vidDirect"),
            category(id = "cat-music", title = "Music", position = 1),
        )

        val representatives = CatalogThumbnails.representatives(nodes)

        assertEquals(
            mapOf("cat-cartoon" to "vidNested", "i-nursery" to "vidNested"),
            representatives,
        )
        assertNull("a container with nothing inside is left out rather than faked", representatives["i-empty"])
        assertNull(representatives["cat-music"])
        assertNull("a video's picture is itself; it needs no representative", representatives["i-direct"])
    }

    @Test
    fun aDisabledContainerStillHasARepresentativeForWhenItIsShownAgain() {
        val nodes = listOf(
            category(id = "cat-cartoon", position = 0, enabled = false),
            video(id = "i-v", title = "V", position = 0, parentId = "cat-cartoon", videoId = "vidV"),
        )

        assertEquals("vidV", resolve(nodes, "cat-cartoon"))
    }

    @Test
    fun aDocumentThatIsNotATreeIsAnsweredRatherThanThrown() {
        // Hand-edited data is the reason this object exists at all: every shape below has to give an
        // answer. None of these trees is storable, and none of them may crash the home screen.
        assertNull(resolve(emptyList(), "cat-cartoon"))
        assertNull(resolve(listOf(category()), "nothing-like-this"))

        val orphan = listOf(video(id = "i-orphan", parentId = "cat-missing", videoId = "vidOrphan"))
        assertEquals("an orphaned video is still itself", "vidOrphan", resolve(orphan, "i-orphan"))
        assertNull("but there is no node there to represent", resolve(orphan, "cat-missing"))

        val duplicateIds = listOf(
            category(thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-v"),
            video(id = "i-v", position = 0, parentId = "cat-cartoon", videoId = "vidFirst"),
            video(id = "i-v", position = 1, parentId = "cat-cartoon", videoId = "vidSecond"),
        )
        assertTrue(
            "a duplicated id still resolves to one of the nodes that carry it",
            resolve(duplicateIds, "cat-cartoon") in setOf("vidFirst", "vidSecond"),
        )
    }

    @Test
    fun aCycleOfParentsDoesNotHang() {
        val nodes = listOf(
            subcategory(id = "i-a", position = 0, parentId = "i-b", thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-b"),
            subcategory(id = "i-b", position = 0, parentId = "i-a"),
            video(id = "i-v", position = 1, parentId = "i-a", videoId = "vidV"),
        )

        // The walk is bounded, so it returns instead of looping: the choice names a non-video, so it
        // is ignored, and the walk finds the video without following the cycle round for ever.
        assertEquals("vidV", resolve(nodes, "i-a"))

        val selfParent = listOf(subcategory(id = "i-a", parentId = "i-a"))
        assertNull(resolve(selfParent, "i-a"))
    }
}
