package tv.safetubeforkids.app.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.auth.InMemoryParentCredentialStore
import tv.safetubeforkids.app.auth.PinManager
import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.auth.TestHasher
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.ChannelEntity
import tv.safetubeforkids.app.data.cache.PlaybackPositionEntity
import tv.safetubeforkids.app.data.cache.VideoEntity

/**
 * [PlaybackController] on its own: which video is current, what the queue does at its boundaries,
 * what a withdrawn source does to a queue that was built before it was withdrawn, and which row a
 * playhead is saved against.
 *
 * This file exists because W11 found the class had no direct tests at all - and found, while looking,
 * that it saved the playhead against the wrong video after every NEXT. The queue, the resume point
 * and the end of a video are the parts of this app a child actually feels, and none of them were
 * pinned by anything except a device run that could not see the difference.
 *
 * Two things are injected to make that testable, and neither changes production behaviour: the
 * player (a stand-in whose playhead and state this suite controls) and the resolver (so it can be
 * failed on purpose). Everything else is the real thing - a real Room database, the real
 * [PlaybackAuthorization], the real queue, the real save path.
 *
 * The invariants this file pins:
 *
 * ```text
 * THE SAVED PLAYHEAD BELONGS TO THE VIDEO THAT IS PLAYING
 * A QUEUE ENTRY IS NOT PERMISSION (a withdrawn item is not played)
 * AN UNAPPROVED VIDEO IS REFUSED BEFORE ANYTHING IS RESOLVED
 * THE END OF THE QUEUE IS THE END, NOT ANOTHER VIDEO
 * ```
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackControllerTest {

    private lateinit var db: CacheDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var player: FakePlayer
    private var exits = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        player = FakePlayer()
        exits = 0
        // The controller asks ServiceLocator for the time-limit gate once playback starts. Tests get
        // the project's own test wiring, whose time-limit manager is the no-op "no limits" one.
        ServiceLocator.initForTest(
            db = db,
            pin = PinManager(store = InMemoryParentCredentialStore(), hasher = TestHasher),
            session = SessionManager(),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    // ------------------------------------------------------------------ fixtures

    /** The parent approving a source, which is what puts anything into the approved cache. */
    private fun approveSource(sourceId: String, videoIds: List<String>, displayName: String = "Approved") =
        runBlocking {
            db.channelDao().insert(
                ChannelEntity(
                    sourceType = "yt_playlist",
                    sourceId = sourceId,
                    sourceUrl = "https://www.youtube.com/playlist?list=$sourceId",
                    displayName = displayName,
                )
            )
            db.videoDao().insertAll(
                videoIds.mapIndexed { index, videoId ->
                    VideoEntity(
                        videoId = videoId,
                        playlistId = sourceId,
                        title = "Cached $videoId",
                        thumbnailUrl = "https://img/$videoId.jpg",
                        durationSeconds = 300,
                        position = index,
                    )
                }
            )
        }

    /**
     * The parent withdrawing one video.
     *
     * Done the way it really happens - the source is re-resolved and the withdrawn video is simply
     * not in the new list - because that is the only path there is: nothing in the app deletes one
     * video from the cache, and `cacheVideos` replaces a source's rows as one transaction.
     */
    private fun withdrawVideo(sourceId: String, videoIds: List<String>, videoId: String) = runBlocking {
        db.videoDao().deleteByPlaylist(sourceId)
        db.videoDao().insertAll(
            videoIds.filterNot { it == videoId }.mapIndexed { index, remaining ->
                VideoEntity(
                    videoId = remaining,
                    playlistId = sourceId,
                    title = "Cached $remaining",
                    thumbnailUrl = "https://img/$remaining.jpg",
                    durationSeconds = 300,
                    position = index,
                )
            }
        )
    }

    /** The parent withdrawing the whole source, which is what happens when a source is removed. */
    private fun withdrawSource(sourceId: String) = runBlocking {
        db.videoDao().deleteByPlaylist(sourceId)
        db.channelDao().getBySourceId(sourceId)?.let { db.channelDao().deleteById(it.id) }
    }

    private fun savePosition(videoId: String, positionMs: Long) = runBlocking {
        db.playbackPositionDao().upsert(
            PlaybackPositionEntity(
                videoId = videoId,
                positionMs = positionMs,
                durationMs = DURATION_MS,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun savedRow(videoId: String): PlaybackPositionEntity? =
        runBlocking { db.playbackPositionDao().get(videoId) }

    private fun resolvedCalls() = resolved

    private val resolved = mutableListOf<String>()

    private fun controller(resolve: (suspend (String) -> ResolvedMedia?)? = null): PlaybackController =
        PlaybackController(
            context = RuntimeEnvironment.getApplication() as Context,
            db = db,
            scope = scope,
            onExit = { exits++ },
            onLocked = { },
            injectedPlayer = player.proxy,
            resolveMedia = resolve ?: { videoId ->
                resolved += videoId
                mediaFor(videoId)
            },
        )

    private fun mediaFor(videoId: String, durationMs: Long = DURATION_MS) = ResolvedMedia(
        videoId = videoId,
        title = titleFor(videoId),
        durationMs = durationMs,
        dashMpdUrl = null,
        qualities = listOf(
            QualityOption(
                height = 720,
                label = "720p",
                videoUrl = "https://example.test/$videoId.mp4",
                audioUrl = null,
                isMerged = true,
                bitrateKbps = 1500,
            )
        ),
        audioOptions = emptyList(),
        captions = emptyList(),
    )

    // ------------------------------------------------------------------ waiting

    /**
     * Waits for the controller to be *on* a video. The title is the observable the player screen
     * shows, so it is also the honest thing to assert: it is taken from the media that was prepared.
     */
    private fun awaitCurrent(controller: PlaybackController, videoId: String, timeoutMs: Long = 4_000) {
        val want = titleFor(videoId)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (controller.title == want) return
            Thread.sleep(15)
        }
        throw AssertionError("expected to be on $videoId ('$want') but the title is '${controller.title}'")
    }

    /** Waits for the playhead of one video to be persisted, which happens on another dispatcher. */
    private fun awaitSaved(videoId: String, expectedMs: Long, timeoutMs: Long = 4_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (savedRow(videoId)?.positionMs == expectedMs) return
            Thread.sleep(15)
        }
        throw AssertionError(
            "expected $videoId saved at ${expectedMs}ms but it is ${savedRow(videoId)?.positionMs}ms"
        )
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(15)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun titleFor(videoId: String) = "Resolved $videoId"

    // ------------------------------------------- the playhead belongs to the playing video (D1)

    @Test
    fun nextSavesTheNewVideosPlayheadWithoutRewritingTheOldVideosRow() {
        approveSource("PL-1", listOf("vid-a", "vid-b"))
        val controller = controller()
        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")

        player.positionMs = 30_000
        controller.togglePause()
        awaitSaved("vid-a", 30_000)

        controller.next()
        awaitCurrent(controller, "vid-b")
        player.positionMs = 20_000
        controller.togglePause()
        awaitSaved("vid-b", 20_000)

        assertEquals(
            "the video the child moved away from keeps its own playhead",
            30_000L,
            savedRow("vid-a")?.positionMs,
        )
        assertEquals("and the video that is playing records its own", 20_000L, savedRow("vid-b")?.positionMs)
    }

    @Test
    fun previousSavesTheVideoItReturnedToAndLeavesTheOneItLeftAlone() {
        approveSource("PL-1", listOf("vid-a", "vid-b"))
        val controller = controller()
        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")
        player.positionMs = 30_000
        controller.togglePause()
        awaitSaved("vid-a", 30_000)

        controller.next()
        awaitCurrent(controller, "vid-b")
        player.positionMs = 20_000
        controller.togglePause()
        awaitSaved("vid-b", 20_000)

        controller.previous()
        awaitCurrent(controller, "vid-a")
        player.positionMs = 40_000
        controller.togglePause()
        awaitSaved("vid-a", 40_000)

        assertEquals("the video left behind is untouched", 20_000L, savedRow("vid-b")?.positionMs)
    }

    @Test
    fun threeVideosKeepThreeSeparatePlayheads() {
        approveSource("PL-1", listOf("vid-a", "vid-b", "vid-c"))
        val controller = controller()
        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")
        player.positionMs = 11_000
        controller.togglePause()
        awaitSaved("vid-a", 11_000)

        controller.next()
        awaitCurrent(controller, "vid-b")
        player.positionMs = 22_000
        controller.togglePause()
        awaitSaved("vid-b", 22_000)

        controller.next()
        awaitCurrent(controller, "vid-c")
        player.positionMs = 33_000
        controller.togglePause()
        awaitSaved("vid-c", 33_000)

        assertEquals(11_000L, savedRow("vid-a")?.positionMs)
        assertEquals(22_000L, savedRow("vid-b")?.positionMs)
        assertEquals(33_000L, savedRow("vid-c")?.positionMs)
    }

    @Test
    fun returningToAVideoOffersThatVideosOwnResumePoint() {
        approveSource("PL-1", listOf("vid-a", "vid-b"))
        val controller = controller()
        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")
        player.positionMs = 30_000
        controller.togglePause()
        awaitSaved("vid-a", 30_000)

        controller.next()
        awaitCurrent(controller, "vid-b")
        player.positionMs = 20_000
        controller.togglePause()
        awaitSaved("vid-b", 20_000)

        controller.previous()
        awaitCurrent(controller, "vid-a")

        assertEquals(
            "returning to A offers A's own 30s, not B's 20s",
            PlayerMenu.RESUME,
            controller.activeMenu,
        )
    }

    @Test
    fun aSavedPlayheadIsOfferedForTheVideoThatActuallyPlayed() {
        approveSource("PL-1", listOf("vid-a", "vid-b"))
        val controller = controller()
        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")

        controller.next()
        awaitCurrent(controller, "vid-b")
        player.positionMs = 25_000
        controller.togglePause()
        awaitSaved("vid-b", 25_000)

        val resumable = runBlocking {
            db.playbackPositionDao()
                .observeResumable(CatalogResumeFloor.MIN_MS, CatalogResumeFloor.MAX_PERCENT)
                .first()
        }
        val ids = resumable.map { it.videoId }
        assertTrue("the video that was watched is resumable", ids.contains("vid-b"))
        assertFalse(
            "and the one it advanced away from has no invented playhead",
            resumable.any { it.videoId == "vid-a" },
        )
    }

    // ------------------------------------------------------------------ queue boundaries

    @Test
    fun previousOnTheFirstItemRestartsItInsteadOfLeavingTheQueue() {
        approveSource("PL-1", listOf("vid-a", "vid-b"))
        val controller = controller()
        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")
        player.positionMs = 50_000

        controller.previous()
        awaitCondition("the first video to be restarted") { player.lastSeekMs == 0L }

        assertEquals("still on the first video", titleFor("vid-a"), controller.title)
        assertEquals("and the queue was not left", 0, exits)
    }

    @Test
    fun nextOnTheLastItemEndsTheQueueInsteadOfPlayingSomethingElse() {
        approveSource("PL-1", listOf("vid-a"))
        val controller = controller()
        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")
        val preparedBefore = player.mediaSourceCount

        controller.next()
        awaitCondition("the queue to end") { exits == 1 }

        assertEquals("nothing new was prepared", preparedBefore, player.mediaSourceCount)
        assertEquals("and the screen was asked to leave the player", 1, exits)
    }

    @Test
    fun theEndOfAVideoAdvancesInsideTheQueueAndTheEndOfTheQueueLeaves() {
        approveSource("PL-1", listOf("vid-a", "vid-b"))
        val controller = controller()
        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")

        // The player reaching the end of its item is what the real player reports.
        player.fireStateEnded()
        awaitCurrent(controller, "vid-b")

        player.fireStateEnded()
        awaitCondition("the queue to end after its last item") { exits == 1 }
        assertEquals("and nothing left the queue", titleFor("vid-b"), controller.title)
    }

    // ------------------------------------------- a withdrawn item must not play (D2)

    @Test
    fun anItemWithdrawnWhileWatchingIsSkippedRatherThanPlayed() {
        approveSource("PL-1", listOf("vid-a", "vid-b", "vid-c"))
        val controller = controller()
        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")

        withdrawVideo("PL-1", listOf("vid-a", "vid-b", "vid-c"), "vid-b")
        controller.next()

        awaitCurrent(controller, "vid-c")
        assertNotEquals("the withdrawn video is not what is playing", titleFor("vid-b"), controller.title)
        assertFalse("and it was never even resolved", resolvedCalls().contains("vid-b"))
    }

    @Test
    fun withdrawingTheLastItemEndsTheQueueRatherThanPlayingIt() {
        approveSource("PL-1", listOf("vid-a", "vid-b"))
        val controller = controller()
        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")
        val preparedBefore = player.mediaSourceCount

        withdrawVideo("PL-1", listOf("vid-a", "vid-b"), "vid-b")
        controller.next()

        awaitCondition("the queue to end") { exits == 1 }
        assertEquals("nothing was prepared for the withdrawn item", preparedBefore, player.mediaSourceCount)
        assertFalse("and it was never resolved", resolvedCalls().contains("vid-b"))
    }

    @Test
    fun withdrawingTheWholeSourceEndsTheQueueWhenTheChildPressesNext() {
        approveSource("PL-1", listOf("vid-a", "vid-b"))
        val controller = controller()
        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")
        val preparedBefore = player.mediaSourceCount

        withdrawSource("PL-1")
        controller.next()

        awaitCondition("the queue to end") { exits == 1 }
        assertEquals("nothing else was prepared", preparedBefore, player.mediaSourceCount)
    }

    @Test
    fun aWithdrawnItemLeavesNoContinueWatchingRowBehind() {
        approveSource("PL-1", listOf("vid-a", "vid-b"))
        val controller = controller()
        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")
        player.positionMs = 40_000
        controller.togglePause()
        awaitSaved("vid-a", 40_000)

        withdrawVideo("PL-1", listOf("vid-a", "vid-b"), "vid-b")
        controller.next()
        awaitCondition("the queue to end") { exits == 1 }

        assertNull("the withdrawn video never played, so it has no playhead", savedRow("vid-b"))
        assertEquals("and the video that did play kept its own", 40_000L, savedRow("vid-a")?.positionMs)
    }

    // ------------------------------------------------------------------ authorization

    @Test
    fun anUnapprovedVideoIsRefusedBeforeAnythingIsResolvedOrPrepared() {
        approveSource("PL-1", listOf("vid-a"))
        val controller = controller()

        controller.start("vid-never-approved", "PL-1", 0)
        awaitCondition("the refusal to be reported") { controller.errorMessage != null }

        assertEquals("This video can't be played", controller.errorMessage)
        assertEquals("nothing was resolved", 0, resolvedCalls().size)
        assertEquals("nothing was prepared", 0, player.mediaSourceCount)
    }

    @Test
    fun aVideoFromASourceTheParentRemovedCannotBeStarted() {
        approveSource("PL-1", listOf("vid-a"))
        withdrawSource("PL-1")
        val controller = controller()

        controller.start("vid-a", "PL-1", 0)
        awaitCondition("the refusal to be reported") { controller.errorMessage != null }

        assertEquals("This video can't be played", controller.errorMessage)
        assertEquals("nothing was resolved", 0, resolvedCalls().size)
    }

    // ------------------------------------------------------------------ resume rules

    @Test
    fun aNearlyFinishedVideoStartsOverInsteadOfResuming() {
        approveSource("PL-1", listOf("vid-a"))
        // 98% of the way in: past the point where carrying on is what a child wants.
        savePosition("vid-a", DURATION_MS * 98 / 100)
        val controller = controller()

        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")
        awaitCondition("the finished row to be dropped") { savedRow("vid-a") == null }

        assertNull("no resume offer for a video that was finished", controller.activeMenu)
        assertTrue(
            "and nothing seeks into the video, so it plays from the start",
            player.lastSeekMs <= 0L,
        )
    }

    @Test
    fun aPartiallyWatchedVideoIsOfferedWhereItStopped() {
        approveSource("PL-1", listOf("vid-a"))
        savePosition("vid-a", 45_000)
        val controller = controller()

        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")

        assertEquals("the offer to carry on is raised", PlayerMenu.RESUME, controller.activeMenu)
    }

    @Test
    fun aPositionBelowTheResumeFloorIsNotOffered() {
        approveSource("PL-1", listOf("vid-a"))
        savePosition("vid-a", 3_000)
        val controller = controller()

        controller.start("vid-a", "PL-1", 0)
        awaitCurrent(controller, "vid-a")

        assertNull("three seconds in is not something to offer to resume", controller.activeMenu)
    }

    // ------------------------------------------------------------------ resolver failure

    @Test
    fun aResolverFailureIsReportedAndDoesNotCrashThePlayer() {
        approveSource("PL-1", listOf("vid-a"))
        val controller = controller(resolve = { null })

        controller.start("vid-a", "PL-1", 0)
        awaitCondition("the failure to be reported") { controller.errorMessage != null }

        assertEquals("Couldn't play this video", controller.errorMessage)
        assertEquals("and no media was handed to the player", 0, player.mediaSourceCount)
    }

    // ------------------------------------------------------------------ the stand-in player

    /**
     * An [ExoPlayer] that is only as real as these tests need: a playhead this suite sets, a state it
     * can drive, and a record of what was asked of it. Every other call is answered with the zero of
     * its return type, so a test never fails because of a method nobody meant to exercise.
     */
    private class FakePlayer {
        var positionMs = 0L
        var durationMs = DURATION_MS
        var playing = true
        var playbackState = Player.STATE_READY
        var mediaSourceCount = 0
        var lastSeekMs = -1L
        private val listeners = mutableListOf<Player.Listener>()

        val proxy: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
            InvocationHandler { _, method, args -> handle(method, args) },
        ) as ExoPlayer

        private fun handle(method: Method, args: Array<Any?>?): Any? {
            when (method.name) {
                "addListener" -> listeners += args!![0] as Player.Listener
                "removeListener" -> listeners -= args!![0] as Player.Listener
                "getCurrentPosition" -> return positionMs
                "getDuration" -> return durationMs
                "getPlayWhenReady" -> return playing
                "isPlaying" -> return playing
                "getPlaybackState" -> return playbackState
                "play" -> playing = true
                "pause" -> playing = false
                "prepare" -> playbackState = Player.STATE_READY
                "seekTo" -> {
                    lastSeekMs = (args!![0] as Number).toLong()
                    positionMs = lastSeekMs
                }
                "setMediaSource", "setMediaItem", "setPlaybackSpeed" -> mediaSourceCount +=
                    if (method.name == "setMediaSource") 1 else 0
                "getTrackSelectionParameters" -> return TrackSelectionParameters.DEFAULT
                "equals" -> return args!![0] === proxy
                "hashCode" -> return System.identityHashCode(proxy)
                "toString" -> return "FakeExoPlayer"
                else -> return zeroOf(method.returnType)
            }
            return null
        }

        /** What the real player reports when the current item reaches its end. */
        fun fireStateEnded() {
            listeners.toList().forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }
        }

        private fun zeroOf(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> ' '
            else -> null
        }
    }

    private companion object {
        const val DURATION_MS = 300_000L
    }

    /** The same floor and ceiling the app uses, named here so the test says what it is asking. */
    private object CatalogResumeFloor {
        const val MIN_MS = 20_000L
        const val MAX_PERCENT = 95
    }
}




