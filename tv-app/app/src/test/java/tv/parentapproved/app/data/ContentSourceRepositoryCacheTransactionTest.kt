package tv.parentapproved.app.data

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
import tv.safetubeforkids.app.data.ContentSourceRepository
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.ChannelEntity
import tv.safetubeforkids.app.data.cache.VideoEntity
import tv.safetubeforkids.app.data.models.VideoItem
import tv.safetubeforkids.app.playback.PlaybackApproval
import tv.safetubeforkids.app.playback.PlaybackAuthorization

/**
 * The atomicity of one approved-source refresh, against a real Room database on real SQLite (the JVM
 * suite runs Android code through Robolectric, as the catalog database tests do).
 *
 * Why this exists: `cacheVideos` replaces a source's cached videos by deleting the old rows and
 * inserting the newly resolved ones. Done as two independent statements, a failure in the second one
 * left the source with **no** cached videos - and `PlaybackAuthorization` approves a video only while
 * it is in that cache, so a failed refresh revoked playback for the whole source until a later refresh
 * happened to succeed. The failure is induced at the SQLite level with a trigger, so the rollback under
 * test is Room's own transaction rollback on a real database, not a simulated one.
 *
 * The tests are written so they cannot pass vacuously: if the delete had not run, or the insert had
 * partially committed, the asserted row set would be different.
 */
@RunWith(RobolectricTestRunner::class)
class ContentSourceRepositoryCacheTransactionTest {

    private lateinit var db: CacheDatabase

    private val sourceId = "PLcachetest"

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

    // --- fixtures -------------------------------------------------------------------------------

    private suspend fun seedApprovedSourceWithCachedVideos() {
        // An approved source (channels row) plus the two videos already in the approved cache.
        db.channelDao().insert(
            ChannelEntity(
                sourceType = "yt_playlist",
                sourceId = sourceId,
                sourceUrl = "https://www.youtube.com/playlist?list=$sourceId",
                displayName = "Cached Cartoons",
                videoCount = 2,
            )
        )
        db.videoDao().insertAll(listOf(approvedVideo("vid1", 0), approvedVideo("vid2", 1)))
    }

    private fun approvedVideo(id: String, position: Int) = VideoEntity(
        videoId = id,
        playlistId = sourceId,
        title = "Cached title $id",
        thumbnailUrl = "https://example.invalid/$id.jpg",
        durationSeconds = 60,
        position = position,
    )

    private fun resolvedVideo(id: String, position: Int) = VideoItem(
        videoId = id,
        title = "Resolved title $id",
        thumbnailUrl = "https://example.invalid/$id.jpg",
        durationSeconds = 61,
        playlistId = sourceId,
        position = position,
    )

    /** Fails every INSERT into `videos` while installed, at the SQLite level. */
    private fun failEveryVideoInsert() {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_video_insert BEFORE INSERT ON videos " +
                "BEGIN SELECT RAISE(ABORT, 'forced insert failure'); END"
        )
    }

    /** Fails only the INSERT of one specific video id, so a batch is interrupted mid-way. */
    private fun failVideoInsertFor(videoId: String) {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_one_video_insert BEFORE INSERT ON videos " +
                "WHEN NEW.videoId = '$videoId' " +
                "BEGIN SELECT RAISE(ABORT, 'forced insert failure'); END"
        )
    }

    private fun dropInsertTriggers() {
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_video_insert")
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_one_video_insert")
    }

    private suspend fun cachedVideoIds(): List<String> =
        db.videoDao().getByPlaylist(sourceId).map { it.videoId }

    private suspend fun authorized(videoId: String): Boolean =
        PlaybackAuthorization.authorize(db, videoId) is PlaybackApproval.Approved

    // --- tests ----------------------------------------------------------------------------------

    /**
     * The failure the audit found: the insert fails after the delete has already run. The previously
     * cached rows must still be there, the new ones must not be, and the video must still be playable
     * under the existing authorization gate.
     */
    @Test
    fun aFailedRefreshLeavesThePreviousCacheAndItsAuthorizationIntact() = runBlocking {
        seedApprovedSourceWithCachedVideos()
        assertEquals(listOf("vid1", "vid2"), cachedVideoIds())
        assertTrue("precondition: the seeded video is approved", authorized("vid1"))

        failEveryVideoInsert()
        val failure = runCatching {
            ContentSourceRepository.cacheVideos(db, sourceId, listOf(resolvedVideo("new1", 0)))
        }
        assertTrue(
            "the forced SQLite failure must reach the caller, not be swallowed",
            failure.isFailure,
        )

        assertEquals("the previous rows must survive a failed refresh", listOf("vid1", "vid2"), cachedVideoIds())
        assertEquals("no partially inserted row may survive", 0, db.videoDao().countByVideoId("new1"))
        assertEquals("the cache holds exactly the previous rows", 2, db.videoDao().count())

        // The rows must be the originals, not re-created ones.
        val rows = db.videoDao().getByPlaylist(sourceId)
        assertEquals("Cached title vid1", rows[0].title)
        assertEquals(0, rows[0].position)

        assertTrue(
            "authorization must still approve a video from the untouched cache",
            authorized("vid1"),
        )
        assertTrue("and the other cached video too", authorized("vid2"))
    }

    /**
     * The stronger case: the batch is interrupted after some rows have already been inserted. Without
     * the transaction the cache would contain the partial batch; with it, the exact previous set is
     * restored.
     */
    @Test
    fun aRefreshInterruptedMidBatchRestoresTheExactPreviousSet() = runBlocking {
        seedApprovedSourceWithCachedVideos()

        failVideoInsertFor("boom")
        val failure = runCatching {
            ContentSourceRepository.cacheVideos(
                db,
                sourceId,
                listOf(resolvedVideo("new1", 0), resolvedVideo("boom", 1), resolvedVideo("new2", 2)),
            )
        }
        assertTrue("the forced SQLite failure must reach the caller", failure.isFailure)

        assertEquals(
            "a mid-batch failure must leave exactly the previous rows, never a partial batch",
            listOf("vid1", "vid2"),
            cachedVideoIds(),
        )
        assertEquals("the row inserted before the failure must be rolled back", 0, db.videoDao().countByVideoId("new1"))
        assertTrue("authorization for the surviving video is unchanged", authorized("vid1"))
    }

    /**
     * The replacement still has to work: a good refresh swaps the rows, and the old ones stop being
     * approved. This is the guard that the transaction did not turn the refresh into a no-op.
     */
    @Test
    fun aSuccessfulRefreshReplacesTheRowsAndWhatIsApproved() = runBlocking {
        seedApprovedSourceWithCachedVideos()

        ContentSourceRepository.cacheVideos(
            db,
            sourceId,
            listOf(resolvedVideo("new1", 0), resolvedVideo("new2", 1)),
        )

        assertEquals(listOf("new1", "new2"), cachedVideoIds())
        assertEquals("a replaced video leaves the approved cache", 0, db.videoDao().countByVideoId("vid1"))
        assertTrue("the replacement is what is approved now", authorized("new1"))

        val rows = db.videoDao().getByPlaylist(sourceId)
        assertEquals("Resolved title new1", rows[0].title)
        assertTrue("the replaced identifier is no longer authorized", !authorized("vid1"))
    }

    /**
     * Unchanged semantics, deliberately pinned: a refresh that legitimately resolves nothing clears the
     * cache. Making the replacement atomic must not turn "the parent emptied the source" into "keep the
     * stale rows forever".
     */
    @Test
    fun aSuccessfulEmptyRefreshStillClearsTheCache() = runBlocking {
        seedApprovedSourceWithCachedVideos()

        ContentSourceRepository.cacheVideos(db, sourceId, emptyList())

        assertEquals(emptyList<String>(), cachedVideoIds())
        assertTrue("nothing is authorized once the source resolves nothing", !authorized("vid1"))
    }

    /** The trigger is the failure being tested: without it the same refresh succeeds. */
    @Test
    fun theSameRefreshSucceedsOnceTheFailureIsRemoved() = runBlocking {
        seedApprovedSourceWithCachedVideos()

        failEveryVideoInsert()
        failVideoInsertFor("boom")
        dropInsertTriggers()

        ContentSourceRepository.cacheVideos(db, sourceId, listOf(resolvedVideo("new1", 0)))

        assertEquals(listOf("new1"), cachedVideoIds())
    }
}
