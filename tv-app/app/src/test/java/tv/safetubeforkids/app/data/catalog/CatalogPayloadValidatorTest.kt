package tv.safetubeforkids.app.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the server applies before storing a catalog and the TV applies again before installing
 * one. Both sides run this same object, so a payload the server accepts cannot be one the TV then
 * refuses for a different reason.
 */
class CatalogPayloadValidatorTest {

    private fun playlist(
        id: String = "i-nursery",
        name: String = "Nursery Songs",
        sortOrder: Int = 0,
        playlistId: String? = "PLnursery",
        videoId: String? = null,
    ) = CatalogItemDto(
        id = id,
        type = CATALOG_TYPE_PLAYLIST,
        displayName = name,
        sortOrder = sortOrder,
        youtubePlaylistId = playlistId,
        youtubeVideoId = videoId,
    )

    private fun video(
        id: String = "i-twinkle",
        name: String = "Twinkle Twinkle",
        sortOrder: Int = 0,
        videoId: String? = "DuXwFlL8Usk",
        playlistId: String? = null,
    ) = CatalogItemDto(
        id = id,
        type = CATALOG_TYPE_VIDEO,
        displayName = name,
        sortOrder = sortOrder,
        youtubePlaylistId = playlistId,
        youtubeVideoId = videoId,
    )

    private fun category(
        id: String = "cat-music",
        name: String = "Music",
        sortOrder: Int = 0,
        items: List<CatalogItemDto> = emptyList(),
    ) = CatalogCategoryDto(id = id, displayName = name, sortOrder = sortOrder, items = items)

    private fun reasons(categories: List<CatalogCategoryDto>): List<String> {
        val outcome = CatalogPayloadValidator.validate(categories)
        assertTrue("expected the payload to be refused", outcome is CatalogPayloadValidator.Outcome.Invalid)
        return (outcome as CatalogPayloadValidator.Outcome.Invalid).problems.map { it.toString() }
    }

    private fun assertValid(categories: List<CatalogCategoryDto>) {
        val outcome = CatalogPayloadValidator.validate(categories)
        assertTrue(
            "expected the payload to be accepted, got $outcome",
            outcome is CatalogPayloadValidator.Outcome.Valid,
        )
    }

    // ------------------------------------------------------------------ accepted

    @Test
    fun anEmptyCatalogIsValid() {
        assertValid(emptyList())
    }

    @Test
    fun aCategoryWithNoItemsIsValid() {
        assertValid(listOf(category(id = "cat-stories", name = "Stories", sortOrder = 3)))
    }

    @Test
    fun aMixedShelfIsValid() {
        assertValid(
            listOf(
                category(
                    items = listOf(
                        playlist(id = "i-nursery", sortOrder = 0),
                        playlist(id = "i-abc", name = "ABC Songs", sortOrder = 1, playlistId = "PLabc"),
                        video(id = "i-twinkle", sortOrder = 2),
                        video(id = "i-wheels", name = "Wheels on Bus", sortOrder = 3, videoId = "vidwheels"),
                    )
                )
            )
        )
    }

    @Test
    fun duplicateAndNegativeSortOrdersAreValid() {
        // Phase 2's deliberate decision, which the server must not reverse: the local order is made
        // deterministic with `sortOrder ASC, id ASC` instead.
        assertValid(
            listOf(
                category(id = "cat-a", name = "A", sortOrder = 0),
                category(id = "cat-b", name = "B", sortOrder = 0),
                category(id = "cat-c", name = "C", sortOrder = -10),
            )
        )
        assertValid(
            listOf(
                category(
                    items = listOf(
                        playlist(id = "i1", sortOrder = 5),
                        playlist(id = "i2", sortOrder = 5),
                    )
                )
            )
        )
    }

    @Test
    fun aVideoIdIsNotRequiredToLookLikeElevenCharacters() {
        // The existing dashboard parser accepts any id made of YouTube's id characters, and this
        // validator deliberately does not add a length rule that could refuse a valid id.
        assertValid(listOf(category(items = listOf(video(id = "i1", videoId = "abc")))))
        assertValid(listOf(category(items = listOf(video(id = "i1", videoId = "a_b-c_D-123")))))
    }

    @Test
    fun aPlaylistIdWithThePlPrefixIsAccepted() {
        assertValid(listOf(category(items = listOf(playlist(id = "i1", playlistId = "PLabc-123_XY")))))
    }

    // ------------------------------------------------------------------ categories

    @Test
    fun aBlankCategoryIdIsRefused() {
        assertTrue(reasons(listOf(category(id = "   "))).any { it.contains("categories[0].id") && it.contains("blank") })
    }

    @Test
    fun aBlankCategoryDisplayNameIsRefused() {
        assertTrue(reasons(listOf(category(name = ""))).any { it.contains("display name must not be blank") })
    }

    @Test
    fun aDuplicateCategoryIdIsRefused() {
        val problems = reasons(listOf(category(id = "cat-music"), category(id = "cat-music", name = "Again")))

        assertTrue(problems.any { it.contains("duplicate category id 'cat-music'") })
    }

    // ------------------------------------------------------------------ items

    @Test
    fun aBlankItemIdIsRefused() {
        assertTrue(
            reasons(listOf(category(items = listOf(playlist(id = " "))))).any { it.contains("item id must not be blank") }
        )
    }

    @Test
    fun aBlankItemDisplayNameIsRefused() {
        assertTrue(
            reasons(listOf(category(items = listOf(playlist(name = " "))))).any { it.contains("display name must not be blank") }
        )
    }

    @Test
    fun anUnsupportedItemTypeIsRefusedWithTheTypeNamed() {
        val problems = reasons(
            listOf(category(items = listOf(playlist().copy(type = "CHANNEL"))))
        )

        assertTrue(problems.any { it.contains("unsupported item type 'CHANNEL'") })
    }

    @Test
    fun aDuplicateItemIdWithinOneCategoryIsRefused() {
        val problems = reasons(
            listOf(category(items = listOf(playlist(id = "i-same"), playlist(id = "i-same", name = "Other"))))
        )

        assertTrue(problems.any { it.contains("duplicate item id 'i-same'") })
    }

    @Test
    fun aDuplicateItemIdAcrossTwoCategoriesIsRefused() {
        // The item id is the primary key locally, so two shelves claiming it would collide in Room.
        val problems = reasons(
            listOf(
                category(id = "cat-a", name = "A", items = listOf(playlist(id = "i-same"))),
                category(id = "cat-b", name = "B", items = listOf(playlist(id = "i-same", name = "Other"))),
            )
        )

        assertTrue(problems.any { it.contains("categories[1].items[0].id") && it.contains("duplicate item id") })
    }

    // ------------------------------------------------------------------ playlists

    @Test
    fun aPlaylistWithoutAPlaylistIdIsRefused() {
        assertTrue(
            reasons(listOf(category(items = listOf(playlist(playlistId = null)))))
                .any { it.contains("requires a youtubePlaylistId") }
        )
        assertTrue(
            reasons(listOf(category(items = listOf(playlist(playlistId = "  ")))))
                .any { it.contains("requires a youtubePlaylistId") }
        )
    }

    @Test
    fun aPlaylistCarryingAVideoIdIsRefused() {
        assertTrue(
            reasons(listOf(category(items = listOf(playlist(videoId = "vid1")))))
                .any { it.contains("PLAYLIST item must not carry a youtubeVideoId") }
        )
    }

    @Test
    fun aPlaylistIdTheDashboardItselfWouldRefuseIsRefused() {
        listOf("RDmix", "UUuploads", "LLliked", "WLlater").forEach { bad ->
            val problems = reasons(listOf(category(items = listOf(playlist(playlistId = bad)))))
            assertTrue(
                "auto-generated list '$bad' should be refused, got $problems",
                problems.any { it.contains("Auto-generated playlists") },
            )
        }
    }

    @Test
    fun aPlaylistIdWithIllegalCharactersIsRefused() {
        listOf("@handle", "has space", "PL&amp", "PL?x").forEach { bad ->
            val problems = reasons(listOf(category(items = listOf(playlist(playlistId = bad)))))
            assertTrue("'$bad' should be refused, got $problems", problems.isNotEmpty())
        }
    }

    // ------------------------------------------------------------------ videos

    @Test
    fun aVideoWithoutAVideoIdIsRefused() {
        assertTrue(
            reasons(listOf(category(items = listOf(video(videoId = null)))))
                .any { it.contains("requires a youtubeVideoId") }
        )
        assertTrue(
            reasons(listOf(category(items = listOf(video(videoId = "")))))
                .any { it.contains("requires a youtubeVideoId") }
        )
    }

    @Test
    fun aVideoCarryingAPlaylistIdIsRefused() {
        assertTrue(
            reasons(listOf(category(items = listOf(video(playlistId = "PLabc")))))
                .any { it.contains("VIDEO item must not carry a youtubePlaylistId") }
        )
    }

    @Test
    fun aVideoIdWithIllegalCharactersIsRefused() {
        listOf("@PBSKids", "has space", "vid&x", "/shorts/abc").forEach { bad ->
            val problems = reasons(listOf(category(items = listOf(video(videoId = bad)))))
            assertTrue("'$bad' should be refused, got $problems", problems.isNotEmpty())
        }
    }

    // ------------------------------------------------------------------ reporting

    @Test
    fun everyProblemIsReportedNotJustTheFirst() {
        val problems = reasons(
            listOf(
                category(id = "", name = "", items = listOf(playlist(id = "", playlistId = null))),
            )
        )

        assertEquals(4, problems.size)
    }

    @Test
    fun problemsAreAddressedToTheExactField() {
        val problems = reasons(
            listOf(
                category(id = "cat-music", items = listOf(playlist(id = "i1", playlistId = null))),
            )
        )

        assertEquals(
            "categories[0].items[0].youtubePlaylistId: a PLAYLIST item requires a youtubePlaylistId",
            problems[0],
        )
    }

    @Test
    fun describeSummarisesWithoutDumpingEverything() {
        val problems = (1..9).map { CatalogPayloadValidator.Problem("categories[$it]", "bad") }

        val text = CatalogPayloadValidator.describe(problems, limit = 3)

        assertTrue(text.startsWith("categories[1]: bad; categories[2]: bad; categories[3]: bad"))
        assertTrue(text.endsWith("(+6 more)"))
        assertFalse(text.contains("categories[9]"))
    }

    @Test
    fun theValidatorDoesNotRewriteThePayloadItApproves() {
        val categories = listOf(
            category(id = "cat-music", items = listOf(playlist(id = "i1"), video(id = "i2", sortOrder = 1))),
        )

        val outcome = CatalogPayloadValidator.validate(categories) as CatalogPayloadValidator.Outcome.Valid

        assertEquals(categories, outcome.categories)
    }

    // ------------------------------------------------------------------ mapper

    @Test
    fun theMapperKeepsTheParentNameBesideTheYoutubeIdentifier() {
        val categories = listOf(
            category(
                id = "cat-music",
                name = "Music",
                items = listOf(
                    playlist(
                        id = "i-nursery",
                        name = "Nursery Songs",
                        playlistId = "PLsuperFunEducationalSongs2026OfficialPlaylist",
                    ),
                ),
            )
        )

        val mapped = CatalogMapper.toEntities(categories, syncedAt = 1_700_000_000_000L)

        val item = mapped.items.single()
        assertEquals("Nursery Songs", item.displayName)
        assertEquals("PLsuperFunEducationalSongs2026OfficialPlaylist", item.youtubePlaylistId)
        assertFalse(item.displayName.contains(item.youtubePlaylistId!!))
        assertEquals(ContentItemType.PLAYLIST, item.type)
        assertEquals("cat-music", item.categoryId)
        assertEquals(1_700_000_000_000L, item.createdAt)
    }

    @Test
    fun theMapperDropsTheIdentifierThatDoesNotBelongToTheItemType() {
        val categories = listOf(
            category(
                items = listOf(
                    playlist(id = "i1"),
                    video(id = "i2", sortOrder = 1),
                ),
            )
        )

        val mapped = CatalogMapper.toEntities(categories, syncedAt = 0L)

        assertNull(mapped.items[0].youtubeVideoId)
        assertNull(mapped.items[1].youtubePlaylistId)
        assertEquals(ContentItemType.VIDEO, mapped.items[1].type)
    }

    @Test
    fun theMapperCarriesNoApprovalInformationBecauseThereIsNoneToCarry() {
        val mapped = CatalogMapper.toEntities(
            listOf(category(items = listOf(video(id = "i1", videoId = "unapprovedVid")))),
            syncedAt = 0L,
        )

        // The only things a mapped row can say are what the parent configured.
        val item = mapped.items.single()
        assertEquals("unapprovedVid", item.youtubeVideoId)
        assertEquals("i1", item.id)
        assertEquals("cat-music", item.categoryId)
    }

    @Test
    fun theMapperRejectsAnItemTypeNobodyValidated() {
        val categories = listOf(category(items = listOf(playlist().copy(type = "PODCAST"))))

        try {
            CatalogMapper.toEntities(categories, syncedAt = 0L)
            throw AssertionError("an unmapped type should be an invariant failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unsupported type 'PODCAST'"))
        }
    }
}
