package tv.safetubeforkids.app.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import tv.safetubeforkids.app.data.cache.VideoEntity

/**
 * The two ends of the seek range, explicitly.
 *
 * `SeekResumeCaptionTest` already asserts that a single seek past either end is clamped to the value it
 * should be. What it does not pin is the pair of facts a boundary really consists of, and the pair the
 * child's position depends on:
 *
 * ```text
 * A BACKWARD SEEK FROM ZERO, OR REPEATED BACKWARD SEEKS, NEVER PRODUCE A NEGATIVE POSITION
 * A FORWARD SEEK PAST THE END NEVER EXCEEDS THE DURATION
 * AND WHAT IS STORED IS THE CLAMPED POSITION, NOT THE REQUESTED ONE
 * ```
 *
 * The last of those is the one that survives to the next launch: a clamp the player applies and the
 * database does not makes the video open at a place it never reached.
 */
@RunWith(RobolectricTestRunner::class)
class SeekBoundaryTest {

    private lateinit var db: CacheDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var player: FakePlayer

    private val videoGroup = TrackGroup("video", videoFormat(360))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        player = FakePlayer()
        ServiceLocator.initForTest(
            db = db,
            pin = PinManager(store = InMemoryParentCredentialStore(), hasher = TestHasher),
            session = SessionManager(),
        )
        approveSource()
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun videoFormat(height: Int): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264).setHeight(height).setWidth(height * 16 / 9).build()

    private fun approveSource() = runBlocking {
        db.channelDao().insert(
            ChannelEntity(
                sourceType = "yt_playlist",
                sourceId = "PL-1",
                sourceUrl = "https://www.youtube.com/playlist?list=PL-1",
                displayName = "Approved",
            )
        )
        db.videoDao().insertAll(
            listOf("vid-a").mapIndexed { index, videoId ->
                VideoEntity(
                    videoId = videoId,
                    playlistId = "PL-1",
                    title = "Cached $videoId",
                    thumbnailUrl = "https://img/$videoId.jpg",
                    durationSeconds = 600,
                    position = index,
                )
            }
        )
    }

    private fun mediaFor(videoId: String) = ResolvedMedia(
        videoId = videoId,
        title = "Resolved $videoId",
        durationMs = DURATION_MS,
        dashMpdUrl = null,
        qualities = listOf(
            QualityOption(
                height = 360,
                label = "360p",
                videoUrl = "https://example.test/$videoId-360.mp4",
                audioUrl = null,
                isMerged = true,
                bitrateKbps = 343,
            )
        ),
        audioOptions = emptyList(),
        captions = emptyList(),
    )

    private fun playing(): PlaybackController {
        val controller = PlaybackController(
            context = RuntimeEnvironment.getApplication() as Context,
            db = db,
            scope = scope,
            onExit = { },
            onLocked = { },
            injectedPlayer = player.proxy,
            resolveMedia = { videoId -> mediaFor(videoId) },
        )
        controller.start("vid-a", "PL-1", 0)
        awaitCondition("the controller to be on vid-a") { controller.title == "Resolved vid-a" }
        return controller
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(15)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun savedPosition(): Long? = runBlocking { db.playbackPositionDao().get("vid-a")?.positionMs }

    // ------------------------------------------------------------------ the start

    @Test
    fun `seeking backward from the very start stays at zero`() {
        val controller = playing()
        player.positionMs = 0L

        controller.seekBy(-SEEK)

        assertEquals("never before the start", 0L, player.lastSeekMs)
        assertTrue("and never negative", player.lastSeekMs >= 0L)
        awaitCondition("zero to be the stored position") { savedPosition() == 0L }
    }

    @Test
    fun `repeated backward seeks from near the start never go negative and store zero`() {
        val controller = playing()
        player.positionMs = 5_000L

        repeat(4) {
            controller.seekBy(-SEEK)
            assertTrue("position ${player.lastSeekMs} must not be negative", player.lastSeekMs >= 0L)
        }

        assertEquals("the floor is zero", 0L, player.lastSeekMs)
        awaitCondition("the clamped position to be what is stored") {
            savedPosition() == 0L
        }
    }

    @Test
    fun `a backward seek reports the clamped position to the caller, not the requested one`() {
        val controller = playing()
        player.positionMs = 3_000L

        controller.seekBy(-SEEK)

        // The player is the authority on where it ended up: the request was -10s from 3s.
        assertEquals(0L, player.lastSeekMs)
        awaitCondition("the stored position to match the clamp") { savedPosition() == 0L }
    }

    // ------------------------------------------------------------------ the end

    @Test
    fun `seeking forward past the end stops at the duration`() {
        val controller = playing()
        player.positionMs = DURATION_MS - SEEK

        controller.seekBy(SEEK * 10)

        assertEquals("never past the end", DURATION_MS, player.lastSeekMs)
        awaitCondition("the duration to be the stored position") { savedPosition() == DURATION_MS }
    }

    @Test
    fun `repeated forward seeks keep stopping at the duration and store it`() {
        val controller = playing()
        player.positionMs = DURATION_MS / 2

        repeat(20) {
            controller.seekBy(SEEK * 2)
            assertTrue("position ${player.lastSeekMs} must not exceed the duration", player.lastSeekMs <= DURATION_MS)
        }

        assertEquals("the ceiling is the duration", DURATION_MS, player.lastSeekMs)
        awaitCondition("the clamped position to be what is stored") { savedPosition() == DURATION_MS }
    }

    @Test
    fun `an unknown duration does not invent a ceiling`() {
        val controller = playing()
        player.positionMs = 30_000L
        player.durationMs = C.TIME_UNSET

        controller.seekBy(SEEK)

        // With no duration to clamp against, the only bound the controller may apply is the floor.
        assertEquals("the seek goes where it was asked to", 40_000L, player.lastSeekMs)
    }

    // ------------------------------------------------------------------ the stand-in player

    private class FakePlayer {
        var positionMs = 0L
        var durationMs = DURATION_MS
        var playing = true
        var playWhenReady = true
        var playbackState = Player.STATE_READY
        var mediaSourceCount = 0
        var lastSeekMs = -1L
        var tracks: Tracks = Tracks(emptyList())
        var trackSelectionParameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
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
                "isPlaying" -> return playing && playWhenReady
                "getPlayWhenReady" -> return playWhenReady
                "getPlaybackState" -> return playbackState
                "play" -> { playing = true; playWhenReady = true }
                "pause" -> { playing = false; playWhenReady = false }
                "prepare" -> playbackState = Player.STATE_READY
                "seekTo" -> {
                    lastSeekMs = (args!![0] as Number).toLong()
                    positionMs = lastSeekMs
                }
                "setMediaSource", "setMediaItem" -> mediaSourceCount++
                "getCurrentTracks" -> return tracks
                "getTrackSelectionParameters" -> return trackSelectionParameters
                "setTrackSelectionParameters" -> {
                    trackSelectionParameters = args!![0] as TrackSelectionParameters
                }
            }
            return zeroOf(method.returnType)
        }

        private fun zeroOf(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Void.TYPE -> null
            else -> null
        }
    }

    private companion object {
        const val SEEK = 10_000L
        const val DURATION_MS = 600_000L
    }
}
