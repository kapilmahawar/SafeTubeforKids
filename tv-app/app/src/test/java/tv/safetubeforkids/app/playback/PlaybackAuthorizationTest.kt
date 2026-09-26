package tv.safetubeforkids.app.playback

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.ChannelEntity
import tv.safetubeforkids.app.data.cache.VideoEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeRepository
import tv.safetubeforkids.app.data.catalog.CatalogNodeType
import tv.safetubeforkids.app.data.catalog.CatalogThumbnails
import tv.safetubeforkids.app.data.catalog.ThumbnailMode

/**
 * The playback gate, on its own.
 *
 * [PlaybackAuthorization] is the single most security-critical class in the app: Phase 5 moved
 * playback onto Media3, and the architectural invariant that makes that safe is that **Media3 is not
 * an authorization mechanism**. A player that can resolve a URL is not thereby permitted to play it,
 * and a catalog entry is not a permission either.
 *
 * These tests exercise the gate directly rather than through the UI, so a failure here points at the
 * gate itself and not at a screen. The end-to-end proof that no route reaches the player without
 * passing through it stays in the harness (`unapproved-video-blocked`, `unapproved-video-not-playing`,
 * `deeplink-plays-nothing`, `extras-cannot-start-playback`), which is the right layer for it.
 *
 * The invariants this file pins:
 *
 * ```text
 * CATALOG ≠ AUTHORIZATION
 * AUTHORIZATION → REQUIRED FOR PLAYBACK
 * PLAYABLE URL ≠ AUTHORIZATION
 * PLAYER QUEUE ≠ YOUTUBE
 * ```
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackAuthorizationTest {

    private lateinit var db: CacheDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CacheDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------ fixtures

    /** The parent approving a source, which is what puts anything into the approved cache. */
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

    /** The approved cache holding these videos, in the parent-defined order. */
    private fun cacheVideos(sourceId: String, videoIds: List<String>) = runBlocking {
        db.videoDao().insertAll(
            videoIds.mapIndexed { index, videoId ->
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

    private fun authorize(videoId: String): PlaybackApproval =
        runBlocking { PlaybackAuthorization.authorize(db, videoId) }

    // ------------------------------------------------- authorization is required

    @Test
    fun anApprovedVideoWhoseSourceIsStillPresentIsAllowed() {
        approveSource("PLapproved")
        cacheVideos("PLapproved", listOf("vid1"))

        val approval = authorize("vid1")

        assertTrue("an approved, cached video must be playable: $approval", approval is PlaybackApproval.Approved)
        val approved = approval as PlaybackApproval.Approved
        assertEquals("vid1", approved.video.videoId)
        // The source travels with the approval so the player can name the queue it may walk.
        assertEquals("PLapproved", approved.sourceId)
    }

    @Test
    fun aVideoThatWasNeverApprovedIsDenied() {
        // Nothing approved at all: the strongest form of "not authorized".
        assertTrue(authorize("vid1") is PlaybackApproval.Rejected)
    }

    @Test
    fun aVideoFromASourceTheParentRemovedIsDeniedEvenThoughItIsStillCached() {
        approveSource("PLapproved")
        cacheVideos("PLapproved", listOf("vid1"))
        assertTrue("precondition: playable while the source exists", authorize("vid1") is PlaybackApproval.Approved)

        // The parent withdraws the source. The video row deliberately stays in the cache, so it is
        // still fully resolvable - exactly the situation in which "a playable URL exists" must not
        // be mistaken for permission to play.
        runBlocking { db.channelDao().deleteAll() }

        assertTrue(
            "a resolvable video whose source was removed must still be denied",
            authorize("vid1") is PlaybackApproval.Rejected,
        )
    }

    @Test
    fun aVideoCachedForADifferentSourceIsDeniedForThisOne() {
        approveSource("PLapproved")
        cacheVideos("PLapproved", listOf("vid1"))
        // A second, unapproved source's video must not become playable by association.
        cacheVideos("PLother", listOf("vid2"))

        assertTrue(authorize("vid2") is PlaybackApproval.Rejected)
    }

    // --------------------------------------------- the queue is SafeTube's, not YouTube's

    @Test
    fun theApprovedQueueIsExactlyTheCachedVideosInTheParentsOrder() {
        approveSource("PLapproved")
        cacheVideos("PLapproved", listOf("vidFirst", "vidSecond", "vidThird"))

        val queue = runBlocking { PlaybackAuthorization.approvedQueue(db, "PLapproved") }

        // Exactly the approved rows, in the parent's order - never a superset. If the player ever
        // walked a queue containing something that is not here, that item would be unauthorized.
        assertEquals(listOf("vidFirst", "vidSecond", "vidThird"), queue.map { it.videoId })
    }

    @Test
    fun theApprovedQueueIsEmptyForASourceThatIsNotApproved() {
        cacheVideos("PLnotApproved", listOf("vid1"))

        val queue = runBlocking { PlaybackAuthorization.approvedQueue(db, "PLnotApproved") }

        // An empty queue is what stops the player wandering: there is no queue to advance into and
        // therefore nothing for it to discover on its own.
        assertTrue("an unapproved source must yield no queue at all", queue.isEmpty())
    }

    @Test
    fun theQueueShrinksWhenTheSourceIsWithdrawn() {
        approveSource("PLapproved")
        cacheVideos("PLapproved", listOf("vid1", "vid2"))
        assertEquals(2, runBlocking { PlaybackAuthorization.approvedQueue(db, "PLapproved") }.size)

        runBlocking { db.channelDao().deleteAll() }

        assertTrue(
            "withdrawing the source must empty the queue, not leave a stale one to advance through",
            runBlocking { PlaybackAuthorization.approvedQueue(db, "PLapproved") }.isEmpty(),
        )
    }

    @Test
    fun everyQueueEntryIsItselfIndividuallyAuthorized() {
        approveSource("PLapproved")
        cacheVideos("PLapproved", listOf("vid1", "vid2"))

        // The queue is a convenience, not a permission: each item it yields must independently pass
        // the same gate a direct request would. This is what keeps "next item" from becoming a way
        // to reach something authorize() would have refused.
        val queue = runBlocking { PlaybackAuthorization.approvedQueue(db, "PLapproved") }
        assertTrue(queue.isNotEmpty())
        queue.forEach { item ->
            assertTrue(
                "queued item ${item.videoId} must be independently authorized",
                authorize(item.videoId) is PlaybackApproval.Approved,
            )
        }
    }

    // --------------------------------------------- a picture is not a permission either

    @Test
    fun aVideoThatStandsForAShelfIsStillNotPlayable() {
        // The catalog tree can say which video represents a shelf. That is a *picture*, and nothing
        // else: the gate is asked about the very video the shelf points at, and it is still the only
        // thing that decides whether anything plays.
        val nodes = CatalogNodeRepository(db)
        runBlocking {
            nodes.add(
                CatalogNodeEntity(
                    id = "cat-cartoon", parentId = null, nodeType = CatalogNodeType.CATEGORY,
                    title = "Cartoons", position = 0,
                    thumbnailMode = ThumbnailMode.VIDEO, thumbnailVideoId = "i-unapproved",
                )
            )
            nodes.add(
                CatalogNodeEntity(
                    id = "i-unapproved", parentId = "cat-cartoon", nodeType = CatalogNodeType.VIDEO,
                    title = "Unapproved", position = 0, youtubeVideoId = "vidUnapproved",
                )
            )
        }

        assertEquals(
            "the shelf does take its picture from that video",
            "vidUnapproved", CatalogThumbnails.representativeFor(runBlocking { nodes.tree() }, "cat-cartoon"),
        )
        assertTrue(
            "and it still may not play",
            authorize("vidUnapproved") is PlaybackApproval.Rejected,
        )

        // Approving the source is what makes it playable - never the catalog pointing at it.
        approveSource("PLapproved")
        cacheVideos("PLapproved", listOf("vidUnapproved"))
        assertTrue(authorize("vidUnapproved") is PlaybackApproval.Approved)
    }
}
