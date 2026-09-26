package tv.safetubeforkids.app.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the server applies before storing a catalog and the TV applies again before installing
 * one. Both sides run this same object, so a tree the server accepts cannot be one the TV then
 * refuses for a different reason.
 *
 * What is under test here is the *tree*: version 1 could only describe a shelf and its entries, so
 * the shapes that matter now - a container with children, a cycle, a parent that does not exist, a
 * gap in the sibling numbering - are exactly the ones it could not express and the ones the server
 * has to refuse rather than repair.
 */
class CatalogPayloadValidatorTest {

    private fun category(
        id: String = "cat-music",
        title: String = "Music",
        position: Int = 0,
        enabled: Boolean = true,
        nodeType: String = CATALOG_NODE_TYPE_CATEGORY,
        parentId: String? = null,
        videoId: String? = null,
        playlistId: String? = null,
        thumbnailMode: String = CATALOG_THUMBNAIL_MODE_AUTO,
        thumbnailVideoId: String? = null,
        thumbnailUrl: String? = null,
    ) = CatalogNodeDto(
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
        thumbnailUrl = thumbnailUrl,
    )

    private fun container(
        id: String = "i-nursery",
        title: String = "Nursery Songs",
        position: Int = 0,
        parentId: String? = "cat-music",
        playlistId: String? = "PLnursery",
        enabled: Boolean = true,
        videoId: String? = null,
        thumbnailMode: String = CATALOG_THUMBNAIL_MODE_AUTO,
        thumbnailVideoId: String? = null,
        thumbnailUrl: String? = null,
    ) = category(
        id = id,
        title = title,
        position = position,
        nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
        parentId = parentId,
        playlistId = playlistId,
        videoId = videoId,
        enabled = enabled,
        thumbnailMode = thumbnailMode,
        thumbnailVideoId = thumbnailVideoId,
        thumbnailUrl = thumbnailUrl,
    )

    private fun video(
        id: String = "i-twinkle",
        title: String = "Twinkle Twinkle",
        position: Int = 0,
        parentId: String? = "cat-music",
        videoId: String? = "DuXwFlL8Usk",
        playlistId: String? = null,
        enabled: Boolean = true,
        thumbnailMode: String = CATALOG_THUMBNAIL_MODE_AUTO,
        thumbnailVideoId: String? = null,
        thumbnailUrl: String? = null,
    ) = category(
        id = id,
        title = title,
        position = position,
        nodeType = CATALOG_NODE_TYPE_VIDEO,
        parentId = parentId,
        videoId = videoId,
        playlistId = playlistId,
        enabled = enabled,
        thumbnailMode = thumbnailMode,
        thumbnailVideoId = thumbnailVideoId,
        thumbnailUrl = thumbnailUrl,
    )

    private fun reasons(nodes: List<CatalogNodeDto>): List<String> {
        val outcome = CatalogPayloadValidator.validate(nodes)
        assertTrue("expected the payload to be refused", outcome is CatalogPayloadValidator.Outcome.Invalid)
        return (outcome as CatalogPayloadValidator.Outcome.Invalid).problems.map { it.toString() }
    }

    private fun assertValid(nodes: List<CatalogNodeDto>) {
        val outcome = CatalogPayloadValidator.validate(nodes)
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
    fun aShelfWithNothingOnItIsValid() {
        assertValid(listOf(category(id = "cat-stories", title = "Stories", position = 0)))
    }

    @Test
    fun aMixedShelfIsValid() {
        assertValid(
            listOf(
                category(position = 0),
                container(id = "i-nursery", position = 0),
                video(id = "i-twinkle", position = 1),
            )
        )
    }

    @Test
    fun aRecursiveTreeOfThreeLevelsIsValid() {
        assertValid(
            listOf(
                category(id = "cat-cartoon", title = "Cartoon", position = 0),
                container(id = "i-cocomelon", parentId = "cat-cartoon", title = "Cocomelon", position = 0, playlistId = "PLcocomelon"),
                video(id = "i-cocomelon#vidA", parentId = "i-cocomelon", title = "Episode A", position = 0, videoId = "vidA", playlistId = "PLcocomelon"),
                video(id = "i-cocomelon#vidB", parentId = "i-cocomelon", title = "Episode B", position = 1, videoId = "vidB", playlistId = "PLcocomelon"),
                video(id = "i-halloween", parentId = "cat-cartoon", title = "Halloween", position = 1, videoId = "vidhalloween"),
            )
        )
    }

    @Test
    fun theOrderNodesAreListedInDoesNotMatter() {
        // A document describes a tree through parentId, so listing a child before its parent is the
        // same tree - the write reorders them, and the validator must not refuse a document merely
        // because of the order the JSON happened to be assembled in.
        assertValid(
            listOf(
                video(id = "i-cocomelon#vidA", parentId = "i-cocomelon", title = "Episode A", position = 0, videoId = "vidA"),
                container(id = "i-cocomelon", parentId = "cat-cartoon", title = "Cocomelon", position = 0),
                category(id = "cat-cartoon", title = "Cartoon", position = 0),
            )
        )
    }

    @Test
    fun aContainerWithNoImportSourceIsValid() {
        // A hand-built container holds whatever the parent put in it; it does not need a playlist.
        assertValid(
            listOf(
                category(position = 0),
                container(id = "i-hand", title = "Favourites", position = 0, playlistId = null),
                video(id = "i1", parentId = "i-hand", title = "One", position = 0, videoId = "vid1"),
            )
        )
    }

    @Test
    fun aContainerWhosePlaylistHasBroughtNothingInYetIsValid() {
        assertValid(
            listOf(
                category(position = 0),
                container(id = "i-nursery", position = 0, playlistId = "PLnursery"),
            )
        )
    }

    @Test
    fun aVideoIdIsNotRequiredToLookLikeElevenCharacters() {
        assertValid(
            listOf(category(position = 0), video(position = 0, videoId = "short"))
        )
    }

    @Test
    fun aPlaylistIdWithThePlPrefixIsAccepted() {
        assertValid(
            listOf(category(position = 0), container(position = 0, playlistId = "PLabc123"))
        )
    }

    @Test
    fun everySiblingListIsNumberedFromZero() {
        // The canonical numbering, including on a container's own children.
        assertValid(
            listOf(
                category(id = "c1", position = 0),
                category(id = "c2", title = "Two", position = 1),
                container(id = "i1", parentId = "c1", position = 0),
                video(id = "v1", parentId = "i1", position = 0, videoId = "vidA"),
                video(id = "v2", parentId = "i1", position = 1, videoId = "vidB"),
            )
        )
    }

    // ------------------------------------------------------------------ identity and titles

    @Test
    fun aBlankNodeIdIsRefused() {
        assertTrue(reasons(listOf(category(id = ""))).any { it.contains("non-blank id") })
        assertTrue(reasons(listOf(category(id = "  "))).any { it.contains("non-blank id") })
    }

    @Test
    fun aBlankTitleIsRefused() {
        assertTrue(reasons(listOf(category(title = ""))).any { it.contains("non-blank title") })
        assertTrue(reasons(listOf(category(title = "   "))).any { it.contains("non-blank title") })
    }

    @Test
    fun aDuplicateNodeIdIsRefused() {
        val problems = reasons(
            listOf(category(id = "cat-music", position = 0), category(id = "cat-music", title = "Again", position = 1))
        )

        assertTrue(problems.any { it.contains("nodes[1].id") && it.contains("duplicate node id") })
    }

    @Test
    fun anUnsupportedNodeTypeIsRefusedWithTheTypeNamed() {
        val problems = reasons(listOf(category(nodeType = "CHANNEL")))

        assertTrue(problems.any { it.contains("unsupported node type 'CHANNEL'") })
    }

    // ------------------------------------------------------------------ the tree's shape

    @Test
    fun aNodeWhoseParentDoesNotExistIsRefused() {
        val problems = reasons(listOf(video(id = "v1", parentId = "cat-absent", position = 0)))

        assertTrue(problems.any { it.contains("nodes[0].parentId") && it.contains("does not exist") })
    }

    @Test
    fun aNodeThatIsItsOwnParentIsRefused() {
        val problems = reasons(
            listOf(
                category(id = "cat-music", position = 0),
                container(id = "i-self", parentId = "i-self", position = 0),
            )
        )

        assertTrue(problems.any { it.contains("cycle") })
    }

    @Test
    fun aCycleOfParentsIsRefused() {
        // Two containers naming each other: neither is reachable from ROOT, and rendering the tree
        // would never finish.
        val problems = reasons(
            listOf(
                category(id = "cat-music", position = 0),
                container(id = "i-a", parentId = "i-b", position = 0),
                container(id = "i-b", parentId = "i-a", position = 0),
            )
        )

        assertTrue(problems.any { it.contains("cycle") })
    }

    @Test
    fun onlyAShelfMaySitAtRoot() {
        val containerAtRoot = reasons(listOf(container(id = "i1", parentId = null, position = 0)))
        assertTrue(containerAtRoot.any { it.contains("only a CATEGORY node may sit at ROOT") })

        val videoAtRoot = reasons(listOf(video(id = "v1", parentId = null, position = 0)))
        assertTrue(videoAtRoot.any { it.contains("only a CATEGORY node may sit at ROOT") })
    }

    @Test
    fun aShelfMayNotSitInsideAnotherShelf() {
        val problems = reasons(
            listOf(
                category(id = "c1", position = 0),
                category(id = "c2", title = "Inner", parentId = "c1", position = 0),
            )
        )

        // A CATEGORY node with a parent is already refused by the node rules themselves.
        assertTrue(problems.any { it.contains("must sit at ROOT") })
    }

    @Test
    fun aContainerMayNotSitInsideAContainer() {
        val problems = reasons(
            listOf(
                category(id = "c1", position = 0),
                container(id = "i-outer", parentId = "c1", position = 0),
                container(id = "i-inner", parentId = "i-outer", position = 0),
            )
        )

        assertTrue(problems.any { it.contains("cannot hold a SUBCATEGORY node") })
    }

    @Test
    fun aVideoMayNotHoldAnything() {
        val problems = reasons(
            listOf(
                category(id = "c1", position = 0),
                video(id = "v1", parentId = "c1", position = 0),
                video(id = "v2", parentId = "v1", position = 0, videoId = "vidB"),
            )
        )

        assertTrue(problems.any { it.contains("cannot hold a VIDEO node") })
    }

    // ------------------------------------------------------------------ positions

    @Test
    fun aNegativePositionIsRefused() {
        assertTrue(reasons(listOf(category(position = -1))).any { it.contains("must not be negative") })
    }

    @Test
    fun nonContiguousPositionsAreRefused() {
        // The version-1 policy allowed 0/10/20 and broke ties on id. The tree has one numbering rule,
        // and a sparse list means the same configured order could be written two different ways.
        val problems = reasons(
            listOf(
                category(id = "c1", position = 0),
                category(id = "c2", title = "Two", position = 10),
                category(id = "c3", title = "Three", position = 20),
            )
        )

        assertTrue(problems.any { it.contains("positions must be 0..2, found 0,10,20") })
    }

    @Test
    fun duplicatePositionsAreRefused() {
        // Two children at the same position would leave the rendered order to whatever the reader did
        // with the tie, which is exactly what the position rule exists to prevent.
        val problems = reasons(
            listOf(
                category(id = "c1", position = 0),
                category(id = "c2", title = "Two", position = 0),
            )
        )

        assertTrue(problems.any { it.contains("positions must be 0..1, found 0,0") })
    }

    @Test
    fun aGapInAContainersChildrenIsRefused() {
        val problems = reasons(
            listOf(
                category(position = 0),
                container(id = "i1", position = 0),
                video(id = "v1", parentId = "i1", position = 1, videoId = "vidA"),
            )
        )

        assertTrue(problems.any { it.contains("children of 'i1'") && it.contains("positions must be 0..0, found 1") })
    }

    @Test
    fun siblingListsAreCheckedIndependentlyOfEachOther() {
        // The shelves are canonical and so are the container's children, but the container's own
        // position among the shelves is not.
        val problems = reasons(
            listOf(
                category(id = "c1", position = 0),
                container(id = "i1", parentId = "c1", position = 5),
            )
        )

        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("children of 'c1'"))
    }

    // ------------------------------------------------------------------ YouTube identity

    @Test
    fun aVideoWithoutAVideoIdIsRefused() {
        assertTrue(
            reasons(listOf(category(position = 0), video(position = 0, videoId = null)))
                .any { it.contains("requires a non-blank youtubeVideoId") }
        )
        assertTrue(
            reasons(listOf(category(position = 0), video(position = 0, videoId = "")))
                .any { it.contains("requires a non-blank youtubeVideoId") }
        )
    }

    @Test
    fun aContainerCarryingAVideoIdIsRefused() {
        assertTrue(
            reasons(listOf(category(position = 0), container(position = 0, videoId = "vid1")))
                .any { it.contains("SUBCATEGORY node must not carry a youtubeVideoId") }
        )
    }

    @Test
    fun aContainerWithABlankPlaylistIdIsRefused() {
        assertTrue(
            reasons(listOf(category(position = 0), container(position = 0, playlistId = "  ")))
                .any { it.contains("youtubePlaylistId must not be blank") }
        )
    }

    @Test
    fun aVideoMayKeepThePlaylistItWasImportedFrom() {
        // Unlike a version-1 item, a video node may carry a playlist id: that is provenance for an
        // episode the TV materialized from an import, not a second content type.
        assertValid(
            listOf(
                category(id = "cat-cartoon", position = 0),
                container(id = "i-cocomelon", parentId = "cat-cartoon", position = 0, playlistId = "PLcocomelon"),
                video(id = "i-cocomelon#vidA", parentId = "i-cocomelon", position = 0, videoId = "vidA", playlistId = "PLcocomelon"),
            )
        )
    }

    @Test
    fun aPlaylistIdTheDashboardItselfWouldRefuseIsRefused() {
        listOf("RDmix", "UUuploads", "LLliked", "WLlater").forEach { bad ->
            val problems = reasons(listOf(category(position = 0), container(position = 0, playlistId = bad)))
            assertTrue(
                "auto-generated list '$bad' should be refused, got $problems",
                problems.any { it.contains("Auto-generated playlists") },
            )
        }
    }

    @Test
    fun aPlaylistIdWithIllegalCharactersIsRefused() {
        listOf("@handle", "has space", "PL&amp", "PL?x").forEach { bad ->
            val problems = reasons(listOf(category(position = 0), container(position = 0, playlistId = bad)))
            assertTrue("'$bad' should be refused, got $problems", problems.isNotEmpty())
        }
    }

    @Test
    fun aVideoIdWithIllegalCharactersIsRefused() {
        listOf("@PBSKids", "has space", "vid&x", "/shorts/abc").forEach { bad ->
            val problems = reasons(listOf(category(position = 0), video(position = 0, videoId = bad)))
            assertTrue("'$bad' should be refused, got $problems", problems.isNotEmpty())
        }
    }

    @Test
    fun aShelfMayNotCarryEitherYouTubeIdentifier() {
        val withVideo = reasons(listOf(category(position = 0, videoId = "vid1")))
        assertTrue(withVideo.any { it.contains("CATEGORY node must not carry a youtubeVideoId") })

        val withPlaylist = reasons(listOf(category(position = 0, playlistId = "PLabc")))
        assertTrue(withPlaylist.any { it.contains("CATEGORY node must not carry a youtubePlaylistId") })
    }

    // ------------------------------------------------------------------ thumbnails
    //
    // A container's picture is either chosen by the app (`AUTO`) or named by the parent (`VIDEO`), and
    // the name is the **node id** of a video inside that container - not a YouTube id, and not a URL.
    // Everything about the choice that depends on the tree around it is checked here: the node exists,
    // it is a video, it sits below the container, and it has a picture to give.

    @Test
    fun anAutoThumbnailIsTheDefaultAndNeedsNothing() {
        assertValid(listOf(category(position = 0), container(id = "i1", position = 0)))
    }

    /** A shelf holding a subcategory, which holds one video: the shape every rule below is asked of. */
    private fun shelfWithOneVideo(
        thumbnailMode: String = CATALOG_THUMBNAIL_MODE_AUTO,
        thumbnailVideoId: String? = null,
        videoId: String? = "vidA",
        videoEnabled: Boolean = true,
        containerEnabled: Boolean = true,
    ) = listOf(
        category(id = "cat-music", position = 0),
        container(id = "i1", position = 0, enabled = containerEnabled, thumbnailMode = thumbnailMode, thumbnailVideoId = thumbnailVideoId),
        video(id = "v1", parentId = "i1", title = "Twinkle", position = 0, videoId = videoId, enabled = videoEnabled),
    )

    @Test
    fun aVideoThumbnailNamesTheVideoInsideTheContainer() {
        assertValid(shelfWithOneVideo(thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = "v1"))
    }

    @Test
    fun aVideoThumbnailNestedDeeperInsideIsStillInside() {
        assertValid(
            listOf(
                category(
                    id = "cat-music", position = 0,
                    thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = "v1",
                ),
                container(id = "i1", parentId = "cat-music", position = 0),
                video(id = "v1", parentId = "i1", title = "Twinkle", position = 0, videoId = "vidA"),
            )
        )
    }

    @Test
    fun aVideoThumbnailWithoutAChoiceIsRefused() {
        assertTrue(
            reasons(listOf(category(position = 0), container(id = "i1", position = 0, thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO)))
                .any { it.contains("requires the node id of a video inside this container") }
        )
    }

    @Test
    fun aVideoThumbnailNamingSomethingThatIsNotThereIsRefused() {
        assertTrue(
            reasons(shelfWithOneVideo(thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = "ghost"))
                .any { it.contains("thumbnail video 'ghost' does not exist in this catalog") }
        )
    }

    @Test
    fun aVideoThumbnailNamingSomethingThatIsNotAVideoIsRefused() {
        assertTrue(
            reasons(shelfWithOneVideo(thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = "i1"))
                .any { it.contains("thumbnail video 'i1' is a SUBCATEGORY, not a VIDEO") }
        )

        // A shelf may not name another shelf either.
        assertTrue(
            reasons(
                listOf(
                    category(id = "cat-music", position = 0),
                    category(id = "cat-other", title = "Other", position = 1),
                    container(
                        id = "i1", position = 0,
                        thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = "cat-other",
                    ),
                )
            ).any { it.contains("thumbnail video 'cat-other' is a CATEGORY, not a VIDEO") }
        )
    }

    @Test
    fun aVideoThumbnailNamingAVideoOutsideTheContainerIsRefused() {
        val problems = reasons(
            listOf(
                category(id = "cat-music", position = 0),
                container(
                    id = "i1", position = 0,
                    thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = "v2",
                ),
                video(id = "v1", parentId = "i1", title = "Inside", position = 0, videoId = "vidA"),
                container(id = "i2", title = "Other", position = 1),
                video(id = "v2", parentId = "i2", title = "Elsewhere", position = 0, videoId = "vidB"),
            )
        )

        assertTrue(problems.any { it.contains("thumbnail video 'v2' is not inside 'i1'") })
    }

    @Test
    fun aVideoThumbnailNamingAVideoWithNoYoutubeIdIsRefused() {
        assertTrue(
            reasons(
                shelfWithOneVideo(
                    thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO,
                    thumbnailVideoId = "v1",
                    videoId = null,
                )
            ).any { it.contains("has no YouTube video id to take a picture from") }
        )
    }

    @Test
    fun aVideoThumbnailNamingAHiddenVideoIsAcceptedBecauseHidingIsReversible() {
        // Hiding a video must never make a catalog unpublishable: the choice is the parent's, the TV
        // falls back to AUTO while the video is hidden, and unhiding it brings the picture back.
        assertValid(
            shelfWithOneVideo(
                thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO,
                thumbnailVideoId = "v1",
                videoEnabled = false,
            )
        )
    }

    @Test
    fun aVideoIsItsOwnThumbnailSoItChoosesNothing() {
        assertValid(shelfWithOneVideo())

        assertTrue(
            reasons(
                listOf(
                    category(id = "cat-music", position = 0),
                    video(id = "v1", parentId = "cat-music", position = 0, videoId = "vidA", thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO),
                )
            ).any { it.contains("a VIDEO node uses its own YouTube thumbnail") }
        )

        assertTrue(
            reasons(
                listOf(
                    category(id = "cat-music", position = 0),
                    video(
                        id = "v1", parentId = "cat-music", position = 0, videoId = "vidA",
                        thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = "v1",
                    ),
                )
            ).any { it.contains("a VIDEO node must not name a thumbnail video") }
        )
    }

    @Test
    fun aShelfChoosesItsPictureFromInsideItselfToo() {
        assertValid(
            listOf(
                category(
                    id = "cat-music", position = 0,
                    thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = "v1",
                ),
                video(id = "v1", parentId = "cat-music", position = 0, videoId = "vidA"),
            )
        )

        assertTrue(
            reasons(
                listOf(
                    category(
                        id = "cat-music", position = 0,
                        thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = "v2",
                    ),
                    video(id = "v1", parentId = "cat-music", position = 0, videoId = "vidA"),
                )
            ).any { it.contains("thumbnail video 'v2' does not exist in this catalog") }
        )
    }

    @Test
    fun anAutoThumbnailMustNotNameAVideo() {
        assertTrue(
            reasons(listOf(category(position = 0), container(id = "i1", position = 0, thumbnailVideoId = "vidA")))
                .any { it.contains("it must not name one") }
        )
    }

    @Test
    fun anUnknownThumbnailModeIsRefused() {
        assertTrue(
            reasons(listOf(category(position = 0), container(id = "i1", position = 0, thumbnailMode = "PROVIDED")))
                .any { it.contains("unsupported thumbnail mode 'PROVIDED'") }
        )
    }

    @Test
    fun customThumbnailsAreRefusedBecauseTheyAreNotImplemented() {
        assertTrue(
            reasons(
                listOf(
                    category(position = 0),
                    container(id = "i1", position = 0, thumbnailMode = CATALOG_THUMBNAIL_MODE_CUSTOM),
                )
            ).any { it.contains("custom thumbnails are not implemented yet") }
        )
    }

    @Test
    fun aThumbnailUrlIsRefused() {
        assertTrue(
            reasons(
                listOf(
                    category(position = 0),
                    container(id = "i1", position = 0, thumbnailUrl = "https://example.test/a.png"),
                )
            ).any { it.contains("thumbnail URLs are not supported yet") }
        )
    }

    @Test
    fun aNegativeTimestampIsRefused() {
        val node = category(position = 0).copy(createdAt = -1L)
        assertTrue(reasons(listOf(node)).any { it.contains("createdAt must not be negative") })

        val other = category(position = 0).copy(updatedAt = -5L)
        assertTrue(reasons(listOf(other)).any { it.contains("updatedAt must not be negative") })
    }

    // ------------------------------------------------------------------ reporting

    @Test
    fun everyProblemIsReportedNotJustTheFirst() {
        val problems = reasons(
            listOf(
                category(id = "cat-shelf", position = 0),
                video(id = "v0", parentId = "cat-shelf", title = "Fine", position = 0, videoId = "vidFine"),
                container(id = "i1", parentId = "cat-shelf", title = "Nursery", position = 1, playlistId = "  "),
                video(id = "", parentId = "cat-shelf", title = "No Id", position = 2, videoId = "vidNoId"),
                video(id = "v1", parentId = "i1", title = "One", position = 0, videoId = "@bad", thumbnailMode = "PROVIDED"),
            )
        )

        // Every offending node and every offending field is named, and the reasons are addressed to
        // the field that caused them - so a parent can act on the whole list in one go.
        assertEquals(
            listOf(
                "nodes[2].youtubePlaylistId",
                "nodes[3]",
                "nodes[4].youtubeVideoId",
                "nodes[4].thumbnailMode",
            ),
            problems.map { it.substringBefore(": ") },
        )
    }

    @Test
    fun problemsAreAddressedToTheExactField() {
        val problems = reasons(
            listOf(
                category(id = "cat-music", position = 0),
                container(id = "i1", position = 0, playlistId = "  "),
            )
        )

        assertEquals(
            "nodes[1].youtubePlaylistId: a youtubePlaylistId must not be blank; " +
                "leave it out for a container with no import",
            problems[0],
        )
    }

    @Test
    fun describeSummarisesWithoutDumpingEverything() {
        val problems = (1..9).map { CatalogPayloadValidator.Problem("nodes[$it]", "bad") }

        val text = CatalogPayloadValidator.describe(problems, limit = 3)

        assertTrue(text.startsWith("nodes[1]: bad; nodes[2]: bad; nodes[3]: bad"))
        assertTrue(text.endsWith("(+6 more)"))
        assertFalse(text.contains("nodes[9]"))
    }

    @Test
    fun theValidatorDoesNotRewriteThePayloadItApproves() {
        val nodes = listOf(
            category(id = "cat-music", position = 0),
            container(id = "i1", position = 0),
            video(id = "i2", position = 1),
        )

        val outcome = CatalogPayloadValidator.validate(nodes) as CatalogPayloadValidator.Outcome.Valid

        assertEquals(nodes, outcome.nodes)
    }

    // ------------------------------------------------------------------ the mapper

    @Test
    fun theMapperKeepsTheParentNameBesideTheYoutubeIdentifier() {
        val nodes = listOf(
            category(id = "cat-music", title = "Music", position = 0),
            container(
                id = "i-nursery",
                title = "Nursery Songs",
                position = 0,
                playlistId = "PLsuperFunEducationalSongs2026OfficialPlaylist",
            ),
        )

        val mapped = CatalogMapper.toNodes(nodes, syncedAt = 1_700_000_000_000L)

        val container = mapped.single { it.id == "i-nursery" }
        assertEquals("Nursery Songs", container.title)
        assertEquals("PLsuperFunEducationalSongs2026OfficialPlaylist", container.youtubePlaylistId)
        assertFalse(container.title.contains(container.youtubePlaylistId!!))
        assertEquals(CatalogNodeType.SUBCATEGORY, container.nodeType)
        assertEquals("cat-music", container.parentId)
        assertEquals(1_700_000_000_000L, container.createdAt)
    }

    @Test
    fun theMapperCarriesEveryFieldOfTheTreeThrough() {
        val mapped = CatalogMapper.toNodes(
            listOf(
                container(
                    id = "i1", position = 0, enabled = false,
                    thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO, thumbnailVideoId = "vidA",
                ),
            ),
            syncedAt = 0L,
        )

        val node = mapped.single()
        assertEquals("i1", node.id)
        assertEquals("cat-music", node.parentId)
        assertEquals(0, node.position)
        assertEquals(false, node.enabled)
        assertEquals(ThumbnailMode.VIDEO, node.thumbnailMode)
        assertEquals("vidA", node.thumbnailVideoId)
        assertNull(node.thumbnailUrl)
    }

    @Test
    fun theMapperUsesTheServersTimestampsWhenItSendsThem() {
        val mapped = CatalogMapper.toNodes(
            listOf(category(position = 0).copy(createdAt = 111L, updatedAt = 222L)),
            syncedAt = 999L,
        )

        assertEquals(111L, mapped.single().createdAt)
        assertEquals(222L, mapped.single().updatedAt)
    }

    @Test
    fun theMapperCarriesNoApprovalInformationBecauseThereIsNoneToCarry() {
        val mapped = CatalogMapper.toNodes(
            listOf(video(id = "i1", position = 0, videoId = "unapprovedVid")),
            syncedAt = 0L,
        )

        // The only things a mapped node can say are what the parent configured.
        val node = mapped.single()
        assertEquals("unapprovedVid", node.youtubeVideoId)
        assertEquals("i1", node.id)
        assertEquals("cat-music", node.parentId)
        assertEquals(CatalogNodeType.VIDEO, node.nodeType)
    }

    @Test
    fun theMapperRejectsANodeTypeNobodyValidated() {
        val nodes = listOf(category(position = 0, nodeType = "CHANNEL"))

        try {
            CatalogMapper.toNodes(nodes, syncedAt = 0L)
            throw AssertionError("an unmapped node type should be an invariant failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unsupported node type 'CHANNEL'"))
        }
    }

    @Test
    fun theMapperRejectsAThumbnailModeNobodyValidated() {
        val nodes = listOf(container(id = "i1", position = 0, thumbnailMode = "PROVIDED"))

        try {
            CatalogMapper.toNodes(nodes, syncedAt = 0L)
            throw AssertionError("an unmapped thumbnail mode should be an invariant failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unsupported thumbnail mode 'PROVIDED'"))
        }
    }
}
