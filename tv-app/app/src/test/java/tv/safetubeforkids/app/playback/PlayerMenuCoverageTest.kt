package tv.safetubeforkids.app.playback

import android.content.Context
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
import org.junit.Assert.assertNotNull
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
 * The three player menus the W14 audit found implemented but uncovered: audio tracks, playback speed,
 * and retry after a failure.
 *
 * ```text
 * THE AUDIO MENU OFFERS THIS ITEM'S TRACKS AND NAMES THE ONE IN USE
 * CHOOSING AN AUDIO TRACK REOPENS THE STREAM WITH IT - AND TOUCHES NOTHING ELSE
 * AN AUDIO TRACK THIS ITEM DOES NOT OFFER IS IGNORED, NOT GUESSED AT
 * THE SPEED MENU STARTS AT 1x AND APPLIES WHAT IS CHOSEN TO THE PLAYER
 * A RE-PREPARE DOES NOT SILENTLY RETURN THE SPEED TO 1x
 * RETRY RE-RESOLVES THE SAME APPROVED VIDEO, AND CANNOT REACH AN UNAPPROVED ONE
 * ```
 *
 * Everything asserted here is a fact about the player's track-selection parameters, the calls the
 * controller makes, or the controller's own state - never about a label or a highlight. The player is a
 * stand-in whose track list this suite controls; the controller, the resolver seam, the approved cache and
 * the authorization boundary are the real thing.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerMenuCoverageTest {

    private lateinit var db: CacheDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var player: FakePlayer
    private var resolvedIds = mutableListOf<String>()

    /** Which resolve attempts should fail, so retry has something to recover from. */
    private var failFirstResolve = false
    private var resolveAttempts = 0

    private val videoGroup = TrackGroup("video", videoFormat(360))
    private val textGroup = TrackGroup("text", textFormat("en", "English"))

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

    private val englishAudio = AudioOption(
        label = "English", url = "https://example.test/en.m4a", bitrateKbps = 128,
        trackId = "aud-en", languageTag = "en",
    )
    private val hindiAutoDubbed = AudioOption(
        label = "Hindi", url = "https://example.test/hi.m4a", bitrateKbps = 128,
        trackId = "aud-hi", languageTag = "hi", isAutoDubbed = true,
    )
    private val englishCaption = CaptionOption("English", "en", "https://example.test/en.ttml", false)

    private fun mediaFor(
        videoId: String,
        audio: List<AudioOption> = emptyList(),
        captions: List<CaptionOption> = emptyList(),
    ) = ResolvedMedia(
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
        audioOptions = audio,
        captions = captions,
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

    private fun controller(
        audio: List<AudioOption> = emptyList(),
        captions: List<CaptionOption> = emptyList(),
    ): PlaybackController = PlaybackController(
        context = RuntimeEnvironment.getApplication() as Context,
        db = db,
        scope = scope,
        onExit = { },
        onLocked = { },
        injectedPlayer = player.proxy,
        resolveMedia = { videoId ->
            resolvedIds += videoId
            resolveAttempts++
            if (failFirstResolve && resolveAttempts == 1) null else mediaFor(videoId, audio, captions)
        },
    )

    private fun playing(
        audio: List<AudioOption> = emptyList(),
        captions: List<CaptionOption> = emptyList(),
        videoId: String = "vid-a",
    ): PlaybackController {
        val controller = controller(audio, captions)
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

    private fun selectedId(menu: PlayerMenu): String? {
        val options = controller().let {
            it.openMenu(menu)
            it.menuOptions
        }
        return options.firstOrNull { it.selected }?.id
    }

    private fun videoOverrideCount(): Int =
        player.trackSelectionParameters.overrides.values.count { it.mediaTrackGroup.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }

    private fun textOverride(): TrackSelectionOverride? =
        player.trackSelectionParameters.overrides.values
            .firstOrNull { it.mediaTrackGroup.type == androidx.media3.common.C.TRACK_TYPE_TEXT }

    // ------------------------------------------------------------------ audio tracks

    @Test
    fun `the audio menu offers this item's tracks and marks the one in use`() {
        val controller = playing(audio = listOf(englishAudio, hindiAutoDubbed))

        controller.openMenu(PlayerMenu.AUDIO)

        assertEquals(
            "one entry per track the resolver offered, in its order",
            listOf("at:aud-en", "at:aud-hi"),
            controller.menuOptions.map { it.id },
        )
        assertEquals("the default track is the one marked", "at:aud-en", controller.menuOptions.first { it.selected }.id)
        assertTrue(
            "an auto-dubbed track says so",
            controller.menuOptions.first { it.id == "at:aud-hi" }.label.contains("auto-dubbed"),
        )
    }

    @Test
    fun `choosing an audio track reopens the stream and is then the one in use`() {
        val controller = playing(audio = listOf(englishAudio, hindiAutoDubbed))
        val reopensBefore = player.mediaSourceCount

        controller.openMenu(PlayerMenu.AUDIO)
        controller.selectMenuOption("at:aud-hi")

        awaitCondition("the stream to be reopened for the new audio track") {
            player.mediaSourceCount > reopensBefore
        }
        controller.openMenu(PlayerMenu.AUDIO)
        assertEquals(
            "the chosen track is the one marked afterwards",
            "at:aud-hi",
            controller.menuOptions.first { it.selected }.id,
        )
        assertEquals("and the same video is still the item", "Resolved vid-a", controller.title)
    }

    @Test
    fun `choosing an audio track does not touch the captions or the video rendition`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing(audio = listOf(englishAudio, hindiAutoDubbed), captions = listOf(englishCaption))
        controller.openMenu(PlayerMenu.CAPTIONS)
        controller.selectMenuOption("cap:en")
        awaitCondition("captions on") { textOverride() != null }
        val videoPinsBefore = videoOverrideCount()

        controller.openMenu(PlayerMenu.AUDIO)
        controller.selectMenuOption("at:aud-hi")
        awaitCondition("the audio change to land") { player.mediaSourceCount > 0 }

        assertNotNull("the caption pin must survive an audio change", textOverride())
        assertEquals("and no video rendition may be pinned by it", videoPinsBefore, videoOverrideCount())
    }

    @Test
    fun `an audio track this item does not offer is ignored rather than guessed at`() {
        val controller = playing(audio = listOf(englishAudio))
        val reopensBefore = player.mediaSourceCount

        // Reachable from a stale menu: a track id the resolver did not offer for this item.
        controller.openMenu(PlayerMenu.AUDIO)
        controller.selectMenuOption("at:aud-zz")

        Thread.sleep(250)
        assertEquals("nothing is reopened for a track that does not exist", reopensBefore, player.mediaSourceCount)
        controller.openMenu(PlayerMenu.AUDIO)
        assertEquals("and the default stays in use", "at:aud-en", controller.menuOptions.first { it.selected }.id)
    }

    @Test
    fun `closing the audio menu leaves no menu open`() {
        val controller = playing(audio = listOf(englishAudio, hindiAutoDubbed))

        controller.openMenu(PlayerMenu.AUDIO)
        assertNotNull(controller.activeMenu)
        controller.closeMenu()

        assertNull("BACK closes the menu before it leaves the player", controller.activeMenu)
    }

    // ------------------------------------------------------------------ playback speed

    @Test
    fun `the speed menu offers the six choices and starts at 1x`() {
        val controller = playing()

        controller.openMenu(PlayerMenu.SPEED)

        assertEquals(
            "the six choices, in order",
            listOf("0.5", "0.75", "1.0", "1.25", "1.5", "2.0"),
            controller.menuOptions.map { it.label.removeSuffix("x") },
        )
        assertEquals("and normal speed is where it starts", 1f, controller.speed, 0f)
    }

    @Test
    fun `choosing a speed applies it to the player`() {
        val controller = playing()
        controller.openMenu(PlayerMenu.SPEED)

        controller.selectMenuOption(speedOptionId(controller, "1.5"))

        assertEquals("the controller holds the choice", 1.5f, controller.speed, 0.001f)
        assertEquals("and the player was told", listOf(1.5f), player.speedsSet)
    }

    @Test
    fun `a re-prepare does not silently return the speed to 1x`() {
        val controller = playing()
        controller.openMenu(PlayerMenu.SPEED)
        controller.selectMenuOption(speedOptionId(controller, "2.0"))
        assertEquals(2f, controller.speed, 0.001f)
        val reopensBefore = player.mediaSourceCount

        // The progressive path reopens the stream to change quality - a real re-prepare of the media.
        controller.openMenu(PlayerMenu.QUALITY)
        controller.selectMenuOption("h360")
        awaitCondition("the stream to be reopened for the quality change") {
            player.mediaSourceCount > reopensBefore
        }

        assertEquals("the chosen speed is still the chosen speed", 2f, controller.speed, 0.001f)
        assertEquals(
            "and the controller never asks the player to go back to normal speed",
            listOf(2f),
            player.speedsSet,
        )
    }

    @Test
    fun `the chosen speed carries to the next item in the approved queue`() {
        val controller = playing()
        controller.openMenu(PlayerMenu.SPEED)
        controller.selectMenuOption(speedOptionId(controller, "1.25"))

        controller.next()
        awaitCondition("the controller to be on vid-b") { controller.title == "Resolved vid-b" }

        assertEquals("speed is a viewing choice, not a property of one item", 1.25f, controller.speed, 0.001f)
        assertEquals("and the only speed the player was told is the chosen one", listOf(1.25f), player.speedsSet)
    }

    /** The menu builds its own ids; read the one for [label] rather than hard-coding the format. */
    private fun speedOptionId(controller: PlaybackController, label: String): String =
        controller.menuOptions.first { it.label.removeSuffix("x") == label }.id

    // ------------------------------------------------------------------ retry

    @Test
    fun `retry re-resolves the same approved video after a failure`() {
        failFirstResolve = true
        val controller = controller()
        val reopensBefore = player.mediaSourceCount

        controller.start("vid-a", "PL-1", 0)
        awaitCondition("the failure to be reported") { controller.errorMessage != null }
        assertEquals("nothing was handed to the player", reopensBefore, player.mediaSourceCount)

        controller.retry()
        awaitCondition("the retry to resolve and play") { controller.title == "Resolved vid-a" }

        assertNull("there is no error left to show", controller.errorMessage)
        assertTrue("and media reached the player", player.mediaSourceCount > reopensBefore)
    }

    @Test
    fun `retry cannot play a video the parent has withdrawn since it started`() {
        val controller = playing()
        assertEquals("the item is playing to begin with", "Resolved vid-a", controller.title)
        val resolvedBefore = resolvedIds.size
        val reopensBefore = player.mediaSourceCount

        // The parent stops allowing the source while the child is on the player. The cached video row is
        // still there - the cache is not permission - so the only thing that can refuse this is the
        // authorization boundary being asked again.
        withdrawSource()

        controller.retry()
        Thread.sleep(400)

        assertEquals(
            "a retry is a fresh attempt to play, so it must ask authorization again",
            resolvedBefore,
            resolvedIds.size,
        )
        assertEquals(
            "and nothing may be handed to the player for a withdrawn item",
            reopensBefore,
            player.mediaSourceCount,
        )
    }

    private fun withdrawSource() = runBlocking {
        db.channelDao().getBySourceId("PL-1")?.let { db.channelDao().deleteById(it.id) }
    }

    // ------------------------------------------------------------------ the stand-in player

    private class FakePlayer {
        var playing = true
        var playWhenReady = true
        var playbackState = Player.STATE_READY
        var positionMs = 0L
        var durationMs = 600_000L
        var mediaSourceCount = 0
        val speedsSet = mutableListOf<Float>()
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
                "seekTo" -> positionMs = (args!![0] as Number).toLong()
                "setMediaSource", "setMediaItem" -> mediaSourceCount++
                "setPlaybackSpeed" -> speedsSet += (args!![0] as Number).toFloat()
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
}
