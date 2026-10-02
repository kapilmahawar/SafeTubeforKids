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
import androidx.media3.common.VideoSize
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
 * Manual video-quality selection, on its own: what the menu offers, what choosing an option does to the
 * player's track selection, and what the chip claims afterwards.
 *
 * W13.4 found the quality menu and the selection behind it disagreeing in several separate ways, and every
 * one of them is a *selection* fact rather than a label fact, so these tests assert the player's
 * [TrackSelectionParameters] and the controller's own state rather than what a menu happens to highlight:
 *
 * ```text
 * THE MENU OFFERS THE HEIGHTS THIS ITEM HAS, AND NOTHING ELSE
 * CHOOSING ONE PINS THAT RENDITION'S TRACK AND IS REPORTED AS PINNED
 * AUTOMATIC RELEASES THE VIDEO PIN AND NOTHING ELSE (NOT THE SUBTITLES)
 * A QUALITY THE STREAM CANNOT HONOUR IS NOT PINNED AND NOT CLAIMED
 * THE CHIP TELLS THE REQUEST APART FROM WHAT IS BEING RENDERED
 * ```
 *
 * The player is a stand-in whose track list, position and reported video size this suite controls;
 * everything else - the controller, the resolver seam, the approved cache, the authorization boundary -
 * is the real thing.
 */
@RunWith(RobolectricTestRunner::class)
class QualitySelectionTest {

    private lateinit var db: CacheDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var player: FakePlayer

    /** Three renditions in one group, which is what a multi-track (DASH) stream exposes. */
    private val videoGroup = TrackGroup("video", videoFormat(1080), videoFormat(720), videoFormat(360))

    /** One rendition, which is what a progressive stream exposes - no track switching is possible. */
    private val singleVideoGroup = TrackGroup("video", videoFormat(360))
    private val textGroup = TrackGroup("text", textFormat())

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
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setHeight(height)
        .setWidth(height * 16 / 9)
        .build()

    private fun textFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.APPLICATION_TTML)
        .setLanguage("en")
        .setLabel("English")
        .build()

    private fun tracksOf(vararg groups: TrackGroup): Tracks = Tracks(
        groups.map { group ->
            Tracks.Group(
                group,
                /* adaptiveSupported = */ group.length > 1,
                IntArray(group.length) { 0 },
                BooleanArray(group.length) { true },
            )
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
                    durationSeconds = 300,
                    position = index,
                )
            }
        )
    }

    /** Three progressive renditions, which is what the resolver hands over for a kids' video. */
    private fun mediaFor(videoId: String) = ResolvedMedia(
        videoId = videoId,
        title = "Resolved $videoId",
        durationMs = 600_000L,
        dashMpdUrl = "https://example.test/$videoId.mpd",
        qualities = listOf(1080, 720, 360).map { height ->
            QualityOption(
                height = height,
                label = "${height}p",
                videoUrl = "https://example.test/$videoId-$height.mp4",
                audioUrl = "https://example.test/$videoId-audio.mp4",
                isMerged = false,
                bitrateKbps = height,
            )
        },
        audioOptions = emptyList(),
        captions = emptyList(),
    )

    private fun controller(): PlaybackController = PlaybackController(
        context = RuntimeEnvironment.getApplication() as Context,
        db = db,
        scope = scope,
        onExit = { },
        onLocked = { },
        injectedPlayer = player.proxy,
        resolveMedia = { videoId -> mediaFor(videoId) },
    )

    /** Starts a video and waits until the controller is on it, so `resolved` and the tracks are set. */
    private fun playing(): PlaybackController {
        val controller = controller()
        controller.start("vid-a", "PL-1", 0)
        awaitCondition("the controller to be playing vid-a") { controller.title == "Resolved vid-a" }
        return controller
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(15)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun optionIds(controller: PlaybackController) = controller.menuOptions.map { it.id }

    private fun videoOverride(): TrackSelectionOverride? =
        player.trackSelectionParameters.overrides.values
            .firstOrNull { it.mediaTrackGroup.type == C.TRACK_TYPE_VIDEO }

    private fun hasTextOverride(): Boolean =
        player.trackSelectionParameters.overrides.values
            .any { it.mediaTrackGroup.type == C.TRACK_TYPE_TEXT }

    // ------------------------------------------------------------------ the menu

    @Test
    fun `the menu offers exactly the heights the current item has`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing()

        controller.openMenu(PlayerMenu.QUALITY)

        assertEquals(listOf("auto", "h1080", "h720", "h360"), optionIds(controller))
        assertEquals("Video quality", controller.menuTitle)
        assertTrue("Auto is what is playing", controller.menuOptions.first().selected)
    }

    @Test
    fun `the menu follows the tracks when they change under it`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing()
        controller.openMenu(PlayerMenu.QUALITY)

        // A track refresh that drops the 1080p rendition: reopening the menu must not offer it.
        player.tracks = tracksOf(TrackGroup("video", videoFormat(720), videoFormat(360)), textGroup)
        controller.closeMenu()
        controller.openMenu(PlayerMenu.QUALITY)

        assertEquals(listOf("auto", "h720", "h360"), optionIds(controller))
    }

    // ------------------------------------------------------------------ selection

    @Test
    fun `choosing a quality pins that rendition's track`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing()
        controller.openMenu(PlayerMenu.QUALITY)

        controller.selectMenuOption("h720")

        val override = videoOverride()
        assertTrue("a video track must be pinned, not just a label changed", override != null)
        assertEquals(videoGroup, override!!.mediaTrackGroup)
        assertEquals("the 720p rendition is track 1 of the group", listOf(1), override.trackIndices.toList())
        assertEquals("720p", controller.qualityLabel)
        // And the menu says so from the same state the player holds.
        assertEquals(true, controller.menuOptions.firstOrNull { it.id == "h720" }?.selected)
        assertEquals(false, controller.menuOptions.firstOrNull { it.id == "auto" }?.selected)
    }

    @Test
    fun `automatic releases the video pin and keeps the subtitle pin`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing()

        // A child with a pinned subtitle language, which lives in the same override map as a video pin.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(textGroup, 0))
            .build()

        controller.openMenu(PlayerMenu.QUALITY)
        controller.selectMenuOption("h720")
        assertTrue("the manual choice is pinned", videoOverride() != null)
        assertTrue("pinning a quality must not unset the subtitle", hasTextOverride())

        controller.selectMenuOption("auto")

        assertNull("Automatic must release the video pin", videoOverride())
        assertTrue("Automatic must not take the subtitles with it", hasTextOverride())
        assertEquals("Auto", controller.qualityLabel)
    }

    @Test
    fun `automatic reads as selected when only a subtitle is pinned`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(textGroup, 0))
            .build()

        controller.openMenu(PlayerMenu.QUALITY)

        val auto = controller.menuOptions.first { it.id == "auto" }
        assertTrue("no video pin is set, so Automatic is what is playing: $auto", auto.selected)
    }

    @Test
    fun `a quality the stream cannot honour is not pinned and not claimed`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing()
        awaitCondition("the chip to settle on Automatic") { controller.qualityLabel == "Auto" }
        controller.openMenu(PlayerMenu.QUALITY)

        // A track refresh that removes the 1080p rendition *while the menu is still open*: the option the
        // child is looking at now names a track the stream no longer has. Multi-track to multi-track, so
        // the choice is still the override path - the progressive path offers the resolver's renditions
        // instead, and a rendition that is genuinely in `media.qualities` may be pinned there.
        player.tracks = tracksOf(TrackGroup("video", videoFormat(720), videoFormat(360)), textGroup)
        controller.selectMenuOption("h1080")

        assertNull("nothing may be pinned for a rendition that is not there", videoOverride())
        assertEquals("and the chip must not claim it was applied", "Auto", controller.qualityLabel)
    }

    // ------------------------------------------------------------------ request vs rendered

    @Test
    fun `the chip tells the request apart from what is being rendered`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing()
        controller.openMenu(PlayerMenu.QUALITY)
        controller.selectMenuOption("h720")
        assertEquals("720p", controller.qualityDisplayLabel)

        // The decoder reports what is on screen. A stream that cannot honour the pin has to be visible as
        // such rather than repeated back as if it were a fact.
        player.fireVideoSize(height = 540)
        assertEquals("720p (showing 540p)", controller.qualityDisplayLabel)
    }

    @Test
    fun `on automatic the chip names what is actually rendering`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing()
        player.fireVideoSize(height = 480)

        controller.openMenu(PlayerMenu.QUALITY)
        // The label itself is written by prepare() on another dispatcher, so the expectation is awaited
        // rather than read the instant the title appears.
        awaitCondition("the chip to name the rendered rendition") {
            controller.qualityLabel == "Auto" && controller.qualityDisplayLabel == "Auto (480p)"
        }
    }

    // ------------------------------------------------------------------ playback continuity

    @Test
    fun `changing quality in a multi-track stream does not reopen anything or move the playhead`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        player.positionMs = 45_000L
        val controller = playing()
        val sourcesBefore = player.mediaSourceCount
        controller.openMenu(PlayerMenu.QUALITY)

        controller.selectMenuOption("h360")

        assertEquals("Track-selection switching must not reopen the stream", sourcesBefore, player.mediaSourceCount)
        assertEquals("and must not move the playhead", -1L, player.lastSeekMs)
        assertTrue("playback continues", player.playing)
    }

    @Test
    fun `a progressive stream reopens at the same position instead of restarting`() {
        // One video track, so the rendition is baked into the stream and only a reopen can change it.
        player.tracks = tracksOf(singleVideoGroup, textGroup)
        player.positionMs = 45_000L
        val controller = playing()
        assertNull("a single-track stream has no video override to set", videoOverride())
        controller.openMenu(PlayerMenu.QUALITY)
        assertEquals(listOf("auto", "h1080", "h720", "h360"), optionIds(controller))

        controller.selectMenuOption("h720")
        awaitCondition("the reopen to be requested") { player.lastSeekMs == 45_000L }

        assertEquals("the child must not lose their place", 45_000L, player.lastSeekMs)
        assertEquals("720p", controller.qualityLabel)
        assertTrue("playback continues after the reopen", player.playing)
    }

    // ------------------------------------------------------------------ a queue move

    @Test
    fun `moving to the next video does not leave the old menu open`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing()
        controller.openMenu(PlayerMenu.QUALITY)
        assertTrue(optionIds(controller).contains("h1080"))

        controller.next()
        awaitCondition("the controller to be on vid-b") { controller.title == "Resolved vid-b" }

        assertTrue(
            "a menu describes the item it was opened on: ${controller.activeMenu}",
            controller.activeMenu != PlayerMenu.QUALITY,
        )
    }

    // ------------------------------------------------------------------ the stand-in player

    private class FakePlayer {
        var positionMs = 0L
        var durationMs = 600_000L
        var playing = true
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

        /** What a real player reports when the decoder starts rendering a rendition. */
        fun fireVideoSize(height: Int) {
            listeners.toList().forEach { it.onVideoSizeChanged(VideoSize(height * 16 / 9, height)) }
        }

        private fun handle(method: Method, args: Array<Any?>?): Any? {
            when (method.name) {
                "addListener" -> listeners += args!![0] as Player.Listener
                "removeListener" -> listeners -= args!![0] as Player.Listener
                "getCurrentPosition" -> return positionMs
                "getDuration" -> return durationMs
                "getPlayWhenReady", "isPlaying" -> return playing
                "getPlaybackState" -> return playbackState
                "play" -> playing = true
                "pause" -> playing = false
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
