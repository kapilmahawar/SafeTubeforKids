package tv.parentapproved.app.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.safetubeforkids.app.data.catalog.CatalogNodeConverters
import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeType
import tv.safetubeforkids.app.data.catalog.CatalogNodeValidation
import tv.safetubeforkids.app.data.catalog.ThumbnailMode

/**
 * The tree's shape rules: one parent each, categories only at ROOT, videos only where a container can
 * hold them, and no identifier in a node that does not belong there.
 *
 * The malformed cases are asked about as *values* (as the sync layer will), because a malformed node
 * cannot be constructed at all - the entity refuses - which is asserted separately below.
 */
class CatalogNodeValidationTest {

    private fun problem(
        id: String = "n1",
        parentId: String? = null,
        type: CatalogNodeType = CatalogNodeType.CATEGORY,
        title: String = "Cartoons",
        position: Int = 0,
        videoId: String? = null,
        playlistId: String? = null,
    ) = CatalogNodeValidation.problemWith(
        id = id,
        parentId = parentId,
        nodeType = type,
        title = title,
        position = position,
        youtubeVideoId = videoId,
        youtubePlaylistId = playlistId,
    )

    @Test
    fun aCategorySitsAtRootAndCarriesNoYoutubeIdentity() {
        assertNull(problem())

        assertTrue(problem(parentId = "some-category")!!.contains("ROOT"))
        assertTrue(problem(videoId = "abc")!!.contains("youtubeVideoId"))
        assertTrue(problem(playlistId = "PLabc")!!.contains("youtubePlaylistId"))
    }

    @Test
    fun aSubcategoryNeedsAContainerAboveItAndMayKeepItsImportSource() {
        assertNull(problem(id = "s1", parentId = "cat", type = CatalogNodeType.SUBCATEGORY, title = "Cocomelon"))
        // The playlist it was imported from is provenance, and allowed.
        assertNull(
            problem(
                id = "s1", parentId = "cat", type = CatalogNodeType.SUBCATEGORY,
                title = "Cocomelon", playlistId = "PLcocomelon",
            )
        )
        assertTrue(
            problem(id = "s1", parentId = null, type = CatalogNodeType.SUBCATEGORY, title = "Cocomelon")!!
                .contains("needs a parent")
        )
        assertTrue(
            problem(
                id = "s1", parentId = "cat", type = CatalogNodeType.SUBCATEGORY,
                title = "Cocomelon", videoId = "abc",
            )!!.contains("youtubeVideoId")
        )
    }

    @Test
    fun aVideoNeedsAParentAndAVideoId() {
        assertNull(
            problem(id = "v1", parentId = "cat", type = CatalogNodeType.VIDEO, title = "Episode 1", videoId = "abc")
        )
        // Inside a sub-category is just as valid as inside a category.
        assertNull(
            problem(id = "v1", parentId = "sub", type = CatalogNodeType.VIDEO, title = "Episode 1", videoId = "abc")
        )
        assertTrue(
            problem(id = "v1", parentId = null, type = CatalogNodeType.VIDEO, title = "Episode 1", videoId = "abc")!!
                .contains("needs a parent")
        )
        assertTrue(
            problem(id = "v1", parentId = "cat", type = CatalogNodeType.VIDEO, title = "Episode 1")!!
                .contains("youtubeVideoId")
        )
        assertTrue(
            problem(id = "v1", parentId = "cat", type = CatalogNodeType.VIDEO, title = "Episode 1", videoId = " ")!!
                .contains("youtubeVideoId")
        )
    }

    @Test
    fun everyNodeNeedsAnIdATitleAndANonNegativePosition() {
        assertTrue(problem(id = " ")!!.contains("id"))
        assertTrue(problem(title = "  ")!!.contains("title"))
        assertTrue(problem(position = -1)!!.contains("position"))
    }

    @Test
    fun theEntityItselfRefusesMalformedNodes() {
        // A malformed node cannot reach the database through any path, including a direct DAO call.
        var threw = false
        try {
            CatalogNodeEntity(
                id = "v1",
                parentId = "cat",
                nodeType = CatalogNodeType.VIDEO,
                title = "No video id",
                position = 0,
            )
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("Malformed catalog node"))
        }
        assertTrue("constructing a VIDEO node without a video id must throw", threw)

        // and a valid one is constructed without complaint
        assertEquals(
            "Episode 1",
            CatalogNodeEntity(
                id = "v1", parentId = "cat", nodeType = CatalogNodeType.VIDEO,
                title = "Episode 1", position = 0, youtubeVideoId = "abc",
            ).title,
        )
    }

    @Test
    fun onlyACategoryMaySitAtRootAndOnlyVideosInsideASubcategory() {
        assertEquals(setOf(CatalogNodeType.CATEGORY), CatalogNodeValidation.childTypeAllowedIn(null))
        assertEquals(
            setOf(CatalogNodeType.SUBCATEGORY, CatalogNodeType.VIDEO),
            CatalogNodeValidation.childTypeAllowedIn(CatalogNodeType.CATEGORY),
        )
        assertEquals(
            setOf(CatalogNodeType.VIDEO),
            CatalogNodeValidation.childTypeAllowedIn(CatalogNodeType.SUBCATEGORY),
        )
        assertTrue(CatalogNodeValidation.childTypeAllowedIn(CatalogNodeType.VIDEO).isEmpty())
    }

    @Test
    fun customThumbnailsAreReservedRatherThanHalfImplemented() {
        // AUTO and VIDEO are the implemented modes; CUSTOM exists so the schema does not have to change
        // later, and nothing may reach it yet.
        assertEquals(listOf("AUTO", "VIDEO", "CUSTOM"), ThumbnailMode.entries.map { it.name })
        assertFalse(
            "the first implementation must not claim custom images",
            ThumbnailMode.entries.take(2).contains(ThumbnailMode.CUSTOM),
        )
    }

    // ------------------------------------- the stored representation is a name, never an ordinal

    /**
     * `catalog_nodes` is the only catalog storage, so [CatalogNodeConverters] is what decides what the
     * database actually holds. These two tests are the storage-representation guard the removed
     * `content_items` converter used to carry, moved onto the converter that is still in the build.
     */
    @Test
    fun theStoredTypeAndThumbnailModeAreNamesRatherThanOrdinals() {
        val converters = CatalogNodeConverters()

        // Guard against a stored-value change: the database holds these five strings, written as
        // literals so a rename of the enum cannot follow them silently.
        assertEquals("CATEGORY", converters.nodeTypeToString(CatalogNodeType.CATEGORY))
        assertEquals("SUBCATEGORY", converters.nodeTypeToString(CatalogNodeType.SUBCATEGORY))
        assertEquals("VIDEO", converters.nodeTypeToString(CatalogNodeType.VIDEO))
        assertEquals("AUTO", converters.thumbnailModeToString(ThumbnailMode.AUTO))
        assertEquals("CUSTOM", converters.thumbnailModeToString(ThumbnailMode.CUSTOM))

        // and the same five come back as the same values
        assertEquals(CatalogNodeType.VIDEO, converters.stringToNodeType("VIDEO"))
        assertEquals(CatalogNodeType.CATEGORY, converters.stringToNodeType("CATEGORY"))
        assertEquals(ThumbnailMode.VIDEO, converters.stringToThumbnailMode("VIDEO"))
        assertEquals(ThumbnailMode.CUSTOM, converters.stringToThumbnailMode("CUSTOM"))
    }

    @Test
    fun anUnknownStoredValueIsNotSilentlyAccepted() {
        val converters = CatalogNodeConverters()

        // A row written by a newer build, or by hand, must fail loudly rather than decode to the
        // first enum constant and be rendered as something the parent never configured.
        try {
            converters.stringToNodeType("CHANNEL")
            throw AssertionError("an unknown node type should not be silently accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "the failure should name the offending value, got: ${expected.message}",
                expected.message!!.contains("CHANNEL"),
            )
        }
        try {
            converters.stringToThumbnailMode("PROVIDED")
            throw AssertionError("an unknown thumbnail mode should not be silently accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "the failure should name the offending value, got: ${expected.message}",
                expected.message!!.contains("PROVIDED"),
            )
        }
    }
}
