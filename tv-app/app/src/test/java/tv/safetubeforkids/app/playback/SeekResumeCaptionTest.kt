package tv.safetubeforkids.app.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
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
import tv.safetubeforkids.app.data.cache.VideoEntity

/**
 * Seeking, resume persistence and captions, on their own.
 *
 * W13.5 traced three paths that the UI cannot show: a seek that was never persisted, a save that could
 * overwrite a newer one, and a caption language that pinned the *first* text track instead of the one the
 * child chose. Every assertion here is about persisted rows, the player's [TrackSelectionParameters] or the
 * calls the controller makes on the player - never about a label or a menu highlight.
 *
 * ```text
 * A SEEK IS PERSISTED WHEN IT HAPPENS
 * SAVES LAND IN THE ORDER THEY WERE CAPTURED
 * A QUEUE MOVE CANNOT WRITE ONE ITEM'S PLAYHEAD INTO ANOTHER ITEM'S ROW
 * THE CHOSEN CAPTION LANGUAGE IS THE TRACK THAT GETS PINNED
 * A LANGUAGE THE ENGINE DOES NOT HAVE IS NOT PINNED, AND NOT CLAIMED
 * ```
 */
@RunWith(RobolectricTestRunner::class)
class SeekResumeCaptionTest {

    private lateinit var db: CacheDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var player: FakePlayer

    private val videoGroup = TrackGroup("video", videoFormat(360))
    private val textGroup = TrackGroup("text", textFormat("en", "English"), textFormat("es", "Spanish"))

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

    // ------------------------------------------------------------------ fixtures

    private fun videoFormat(height: Int): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264).setHeight(height).setWidth(height * 16 / 9).build()

    private fun textFormat(language: String, label: String): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.APPLICATION_TTML).setLanguage(language).setLabel(label).build()

    private fun tracksOf(vararg groups: TrackGroup): Tracks = Tracks(
        groups.map { group ->
            Tracks.Group(group, group.length > 1, IntArray(group.length) { 0 }, BooleanArray(group.length) { true })
        }
    )

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
            listOf("vid-a", "vid-b").mapIndexed { index, videoId ->
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

    /** Progressive media with one rendition, so a quality change reopens and keeps the playhead. */
    private fun mediaFor(videoId: String, captions: List<CaptionOption> = emptyList()) = ResolvedMedia(
        videoId = videoId,
        title = "Resolved $videoId",
        durationMs = 600_000L,
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
        captions = captions,
    )

    private val englishCaption = CaptionOption("English", "en", "https://example.test/en.ttml", false)
    private val spanishCaption = CaptionOption("Spanish", "es", "https://example.test/es.ttml", false)

    /** Resolvers can be held open, which is how the queue-move window is pinned down. */
    private var holdResolve = false
    private val resolveGate = java.util.concurrent.CountDownLatch(1)

    private fun controller(captions: List<CaptionOption> = emptyList()): PlaybackController = PlaybackController(
        context = RuntimeEnvironment.getApplication() as Context,
        db = db,
        scope = scope,
        onExit = { },
        onLocked = { },
        injectedPlayer = player.proxy,
        resolveMedia = { videoId ->
            if (holdResolve) resolveGate.await()
            // Only the first item carries subtitles, so the "next video has none" path is real.
            mediaFor(videoId, if (videoId == "vid-a") captions else emptyList())
        },
    )

    private fun playing(captions: List<CaptionOption> = emptyList(), videoId: String = "vid-a"): PlaybackController {
        val controller = controller(captions)
        controller.start(videoId, "PL-1", 0)
        awaitCondition("the controller to be on $videoId") { controller.title == "Resolved $videoId" }
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

    private fun savedRow(videoId: String) = runBlocking { db.playbackPositionDao().get(videoId) }

    private fun textOverride(): TrackSelectionOverride? =
        player.trackSelectionParameters.overrides.values
            .firstOrNull { it.mediaTrackGroup.type == C.TRACK_TYPE_TEXT }

    private fun textDisabled(): Boolean =
        player.trackSelectionParameters.disabledTrackTypes.any { it == C.TRACK_TYPE_TEXT }

    // ------------------------------------------------------------------ seeking

    @Test
    fun `a seek is persisted when it happens`() {
        val controller = playing()
        player.positionMs = 30_000L

        controller.seekBy(60_000L)

        assertEquals("the player was asked for the new place", 90_000L, player.lastSeekMs)
        awaitCondition("the seek to reach the database") { savedRow("vid-a")?.positionMs == 90_000L }
    }

    @Test
    fun `repeated seeks persist the last one, not an earlier one`() {
        val controller = playing()
        player.positionMs = 10_000L

        controller.seekBy(10_000L)   // 20s
        controller.seekBy(10_000L)   // 30s
        controller.seekBy(30_000L)   // 60s

        awaitCondition("the final position to be the one stored") { savedRow("vid-a")?.positionMs == 60_000L }
        // And it must stay the last one: an out-of-order write would put an earlier capture back.
        Thread.sleep(300)
        assertEquals(60_000L, savedRow("vid-a")?.positionMs)
    }

    @Test
    fun `seeking while paused neither resumes nor loses the new place`() {
        val controller = playing()
        player.positionMs = 40_000L
        controller.togglePause()
        assertTrue("the pause must land", !player.playWhenReady)
        val playsBefore = player.playCalls

        controller.seekBy(30_000L)

        assertEquals("the player was asked for the new place", 70_000L, player.lastSeekMs)
        assertTrue("a seek must not resume playback", !player.playWhenReady)
        assertEquals("and must not call play() either", playsBefore, player.playCalls)
        awaitCondition("the paused seek to be persisted") { savedRow("vid-a")?.positionMs == 70_000L }
    }

    @Test
    fun `seeks are clamped to the video's bounds`() {
        val controller = playing()
        player.positionMs = 5_000L

        controller.seekBy(-30_000L)
        assertEquals("never before the start", 0L, player.lastSeekMs)

        player.positionMs = 590_000L
        controller.seekBy(600_000L)
        assertEquals("never past the end", 600_000L, player.lastSeekMs)
    }

    @Test
    fun `rewinding to the very start replaces the older resume row`() {
        val controller = playing()
        player.positionMs = 300_000L
        controller.seekBy(0L)
        awaitCondition("the first row") { savedRow("vid-a")?.positionMs == 300_000L }

        player.positionMs = 30_000L
        controller.seekBy(-30_000L)

        awaitCondition("the rewind to zero to be stored") { savedRow("vid-a")?.positionMs == 0L }
    }

    @Test
    fun `a save during a queue move cannot write one item's playhead into another item's row`() {
        val controller = playing()
        player.positionMs = 100_000L
        controller.seekBy(0L)
        awaitCondition("vid-a's own row") { savedRow("vid-a")?.positionMs == 100_000L }

        // Hold the resolver open: this is the window in which the controller has already recorded the new
        // video id but the player still holds the previous item.
        holdResolve = true
        controller.next()
        awaitCondition("the queue to have moved onto vid-b") { controller.queueLabel == "2 of 2" }
        Thread.sleep(150)
        // A pause during that window persists - and must persist against the *player's* item.
        controller.togglePause()
        Thread.sleep(300)

        assertEquals("vid-a keeps its own playhead", 100_000L, savedRow("vid-a")?.positionMs)
        assertNull("and vid-b must not inherit it", savedRow("vid-b"))
        holdResolve = false
        resolveGate.countDown()
    }

    // ------------------------------------------------------------------ resume

    @Test
    fun `the stored position is restored when the video is opened again`() {
        val first = playing()
        player.positionMs = 200_000L
        first.seekBy(0L)
        awaitCondition("vid-a to be saved at 200s") { savedRow("vid-a")?.positionMs == 200_000L }

        // A fresh controller and a fresh player, as after leaving the player screen and coming back.
        player = FakePlayer()
        val second = playing()
        awaitCondition("the resume offer to be raised from the stored position") {
            second.menuOptions.any { it.label.contains("3:20") }
        }

        // Choosing it must actually move the player there, which is the engine-side proof.
        second.selectMenuOption("resume")
        awaitCondition("the player to be moved to the restored position") { player.lastSeekMs == 200_000L }
    }

    @Test
    fun `a video already watched to the end is not offered as resumable`() {
        val first = playing()
        player.positionMs = 590_000L   // 98% of 600s: past the completion threshold
        first.seekBy(0L)
        awaitCondition("the finished position to be stored") { savedRow("vid-a")?.positionMs == 590_000L }

        player = FakePlayer()
        val second = playing()

        assertTrue(
            "a finished video starts over rather than offering a resume: ${second.menuOptions}",
            second.menuOptions.none { it.label.startsWith("Resume") },
        )
        awaitCondition("the finished row to be cleared") { savedRow("vid-a") == null }
    }

    // ------------------------------------------------------------------ captions

    @Test
    fun `the chosen caption language is the track that gets pinned`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing(listOf(englishCaption, spanishCaption))
        controller.openMenu(PlayerMenu.CAPTIONS)

        controller.selectMenuOption("cap:es")

        awaitCondition("the Spanish track to be pinned") { textOverride()?.trackIndices?.toList() == listOf(1) }
        assertTrue("and captions are enabled", !textDisabled())
    }

    @Test
    fun `captions can be turned off and on again`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing(listOf(englishCaption))
        controller.openMenu(PlayerMenu.CAPTIONS)
        controller.selectMenuOption("cap:en")
        awaitCondition("captions on") { textOverride() != null && !textDisabled() }

        controller.selectMenuOption("off")

        awaitCondition("the text track type to be disabled") { textDisabled() }

        controller.selectMenuOption("cap:en")
        awaitCondition("captions enabled again") { textOverride() != null && !textDisabled() }
    }

    @Test
    fun `a language the engine does not have is neither pinned nor claimed`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing(listOf(englishCaption, spanishCaption))

        // Reachable through a stale menu: a language that is in the resolver's list but not in the tracks
        // the player actually has (a subtitle that never loaded).
        controller.openMenu(PlayerMenu.CAPTIONS)
        controller.selectMenuOption("cap:fr")

        Thread.sleep(300)
        assertNull("nothing may be pinned for a track that is not there", textOverride())
    }

    @Test
    fun `a chosen caption survives a quality change`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing(listOf(englishCaption))
        controller.openMenu(PlayerMenu.CAPTIONS)
        controller.selectMenuOption("cap:en")
        awaitCondition("captions on") { textOverride() != null }

        // The progressive path reopens the stream to change quality, which installs a fresh track list.
        controller.openMenu(PlayerMenu.QUALITY)
        controller.selectMenuOption("h360")

        awaitCondition("the caption pin to be re-applied after the reopen") {
            textOverride()?.trackIndices?.toList() == listOf(0) && !textDisabled()
        }
    }

    @Test
    fun `a new item without that language falls back to off instead of claiming captions`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing(listOf(englishCaption))
        controller.openMenu(PlayerMenu.CAPTIONS)
        controller.selectMenuOption("cap:en")
        awaitCondition("captions on") { textOverride() != null }

        // The next approved video has no text tracks at all.
        player.tracks = tracksOf(videoGroup)
        controller.next()
        awaitCondition("the controller to be on vid-b") { controller.title == "Resolved vid-b" }

        awaitCondition("the text type to be disabled for a video without subtitles") { textDisabled() }
        // And the menu must offer no stale language, because this item has none to offer. (A leftover
        // override from the previous item is inert while the track type is disabled; choosing a language
        // again replaces it.)
        controller.openMenu(PlayerMenu.CAPTIONS)
        assertEquals(
            "only the honest 'no subtitles' entry may be offered",
            listOf("none"),
            controller.menuOptions.map { it.id },
        )
    }

    @Test
    fun `choosing the same caption language again leaves the same track pinned`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing(listOf(englishCaption, spanishCaption))
        controller.openMenu(PlayerMenu.CAPTIONS)
        assertEquals(
            "the menu offers off plus every language the item has, in the resolver's order",
            listOf("off", "cap:en", "cap:es"),
            controller.menuOptions.map { it.id },
        )

        controller.selectMenuOption("cap:es")
        awaitCondition("the Spanish track to be pinned") { textOverride()?.trackIndices?.toList() == listOf(1) }
        val reopensAfterFirst = player.mediaSourceCount

        // Idempotent: the same track stays pinned, captions stay on, and nothing is reopened to say so.
        controller.selectMenuOption("cap:es")

        Thread.sleep(250)
        assertEquals("the same track is still pinned", listOf(1), textOverride()?.trackIndices?.toList())
        assertTrue("captions are still enabled", !textDisabled())
        assertEquals("and re-choosing must not reopen the stream", reopensAfterFirst, player.mediaSourceCount)
    }

    // ------------------------------------------------------------------ the stand-in player

    private class FakePlayer {
        var positionMs = 0L
        var durationMs = 600_000L
        var playing = true
        var playWhenReady = true
        var playbackState = Player.STATE_READY
        var mediaSourceCount = 0
        var playCalls = 0
        var lastSeekMs = -1L
        var tracks: Tracks = Tracks(emptyList())
        var trackSelectionParameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
        private val listeners = mutableListOf<Player.Listener>()

        val proxy: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
            InvocationHandler { _, method, args -> handle(method, args) },
        ) as ExoPlayer

        fun firePlaybackState(state: Int) {
            playbackState = state
            listeners.toList().forEach { it.onPlaybackStateChanged(state) }
        }

        private fun handle(method: Method, args: Array<Any?>?): Any? {
            when (method.name) {
                "addListener" -> listeners += args!![0] as Player.Listener
                "removeListener" -> listeners -= args!![0] as Player.Listener
                "getCurrentPosition" -> return positionMs
                "getDuration" -> return durationMs
                "isPlaying" -> return playing && playWhenReady
                "getPlayWhenReady" -> return playWhenReady
                "getPlaybackState" -> return playbackState
                "play" -> { playing = true; playWhenReady = true; playCalls++ }
                "pause" -> { playing = false; playWhenReady = false }
                "prepare" -> playbackState = Player.STATE_READY
                "seekTo" -> {
                    lastSeekMs = (args!![0] as Number).toLong()
                    positionMs = lastSeekMs
                }
                "setMediaSource" -> mediaSourceCount++
                "getCurrentTracks" -> return tracks
                "getTrackSelectionParameters" -> return trackSelectionParameters
                "setTrackSelectionParameters" -> {
                    trackSelectionParameters = args!![0] as TrackSelectionParameters
                    return null
                }
                "equals" -> return args!![0] === proxy
                "hashCode" -> return System.identityHashCode(proxy)
                "toString" -> return "FakeExoPlayer"
                else -> return zeroOf(method.returnType)
            }
            return null
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
}
