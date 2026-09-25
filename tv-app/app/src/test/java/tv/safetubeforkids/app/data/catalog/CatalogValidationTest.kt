package tv.safetubeforkids.app.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playlist/video identity rule on its own, with no database involved.
 *
 * This is the check a future sync layer runs against an untrusted payload *before* it builds
 * entities, which is why it returns a reason instead of throwing.
 */
class CatalogValidationTest {

    @Test
    fun aPlaylistItemNeedsAPlaylistIdAndNoVideoId() {
        assertNull(CatalogValidation.contentIdentityProblem(ContentItemType.PLAYLIST, "PLabc", null))
        assertTrue(CatalogValidation.isValidContentIdentity(ContentItemType.PLAYLIST, "PLabc", null))
    }

    @Test
    fun aVideoItemNeedsAVideoIdAndNoPlaylistId() {
        assertNull(CatalogValidation.contentIdentityProblem(ContentItemType.VIDEO, null, "DuXwFlL8Usk"))
        assertTrue(CatalogValidation.isValidContentIdentity(ContentItemType.VIDEO, null, "DuXwFlL8Usk"))
    }

    @Test
    fun everyMalformedCombinationNamesItsProblem() {
        assertNotNull(
            "PLAYLIST without a playlist id",
            CatalogValidation.contentIdentityProblem(ContentItemType.PLAYLIST, null, null),
        )
        assertNotNull(
            "PLAYLIST with a blank playlist id",
            CatalogValidation.contentIdentityProblem(ContentItemType.PLAYLIST, "  ", null),
        )
        assertNotNull(
            "PLAYLIST carrying a video id",
            CatalogValidation.contentIdentityProblem(ContentItemType.PLAYLIST, "PLabc", "vid"),
        )
        assertNotNull(
            "VIDEO without a video id",
            CatalogValidation.contentIdentityProblem(ContentItemType.VIDEO, null, null),
        )
        assertNotNull(
            "VIDEO with a blank video id",
            CatalogValidation.contentIdentityProblem(ContentItemType.VIDEO, null, ""),
        )
        assertNotNull(
            "VIDEO carrying a playlist id",
            CatalogValidation.contentIdentityProblem(ContentItemType.VIDEO, "PLabc", "vid"),
        )
    }

    @Test
    fun theReasonSaysWhichFieldIsWrong() {
        assertEquals(
            "a PLAYLIST item must not carry a youtubeVideoId (got 'vid')",
            CatalogValidation.contentIdentityProblem(ContentItemType.PLAYLIST, "PLabc", "vid"),
        )
        assertEquals(
            "a VIDEO item must not carry a youtubePlaylistId (got 'PLabc')",
            CatalogValidation.contentIdentityProblem(ContentItemType.VIDEO, "PLabc", "vid"),
        )
    }

    @Test
    fun requireThrowsForMalformedInputAndPassesValidInput() {
        CatalogValidation.requireValidContentIdentity(ContentItemType.VIDEO, null, "vid")

        try {
            CatalogValidation.requireValidContentIdentity(ContentItemType.VIDEO, "PLabc", "vid")
            throw AssertionError("should have thrown")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Malformed catalog content item"))
        }
    }

    @Test
    fun theEntityItselfRefusesMalformedInput() {
        val good = ContentItemEntity(
            id = "i1", categoryId = "c", type = ContentItemType.PLAYLIST,
            displayName = "Nursery Songs", sortOrder = 0, youtubePlaylistId = "PLnursery",
        )
        assertEquals("PLnursery", good.youtubePlaylistId)

        try {
            ContentItemEntity(
                id = "i2", categoryId = "c", type = ContentItemType.PLAYLIST,
                displayName = "Bad", sortOrder = 0, youtubePlaylistId = "PLnursery",
                youtubeVideoId = "vid",
            )
            throw AssertionError("should have thrown")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Malformed catalog content item"))
        }
    }
}
