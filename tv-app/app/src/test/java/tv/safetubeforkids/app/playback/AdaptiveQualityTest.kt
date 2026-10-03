package tv.safetubeforkids.app.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import tv.safetubeforkids.app.util.BandwidthOverride

/**
 * Automatic quality's *use* of the policy in [AutoQuality], which is where the phase's questions live:
 * whether the chooser runs on the path that is actually playing, whether a measurement decides the
 * rendition, whether a manual choice survives everything adaptive, and what a paused or stalled player does.
 *
 * [AutoQualityTest] pins the policy itself. This file pins the controller: the rendition the app asks the
 * engine to play (read off the media source it hands to the player), the number of stream reopens, and the
 * state it reports. The bandwidth measurement is injected through the app's own debug seam, which exists
 * for exactly this - a real stall cannot be produced on demand.
 *
 * The two tests that wait are waiting on the app's own timers (a 5s step-up check, an 8s stall watch), not
 * on a network: they are deterministic, they are just not instant.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class AdaptiveQualityTest {

    private lateinit var db: CacheDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var player: FakePlayer

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
        BandwidthOverride.set(null)
        ServiceLocator.initForTest(
            db = db,
            pin = PinManager(store = InMemoryParentCredentialStore(), hasher = TestHasher),
            session = SessionManager(),
        )
        approveSource()
    }

    @After
    fun tearDown() {
        BandwidthOverride.set(null)
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

    /** Four progressive renditions, which is the path this app actually plays on. */
    private fun mediaFor(videoId: String) = ResolvedMedia(
        videoId = videoId,
        title = "Resolved $videoId",
        durationMs = 600_000L,
        dashMpdUrl = null,
        qualities = listOf(
            rendition(1080, 5000),
            rendition(720, 2500),
            rendition(480, 1200),
            rendition(360, 600),
        ).map { it.copy(videoUrl = "$($it.videoUrl)-$videoId") },
        audioOptions = listOf(
            AudioOption(label = "Original", url = "https://example.test/$videoId-a0.m4a", bitrateKbps = 128, trackId = "a0", languageTag = "en", isAutoDubbed = false),
            AudioOption(label = "Spanish", url = "https://example.test/$videoId-a1.m4a", bitrateKbps = 128, trackId = "a1", languageTag = "es", isAutoDubbed = true),
        ),
        captions = listOf(CaptionOption("English", "en", "https://example.test/$videoId-en.ttml", false)),
    )

    private fun rendition(height: Int, bitrateKbps: Int) = QualityOption(
        height = height,
        label = "${height}p",
        videoUrl = "https://example.test/r$height",
        audioUrl = null,
        isMerged = false,
        bitrateKbps = bitrateKbps,
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

    private fun playing(videoId: String = "vid-a"): PlaybackController {
        val controller = controller()
        controller.start(videoId, "PL-1", 0)
        awaitCondition("the controller to be on $videoId") { controller.title == "Resolved $videoId" }
        // prepare() sets the title before it hands the source to the player, so waiting on the title alone
        // reads quality state before the load has happened. Every test here waits for the load.
        awaitCondition("the stream to be handed to the player") { player.mediaSourceCount >= 1 }
        return controller
    }

    /**
     * Waits for the loaded rendition to be the one the chip reports, and - where a reopen is expected - for
     * that stream to have reached the player.
     *
     * Both halves matter. `prepare()` publishes the quality label *before* it hands the source to the
     * player, so waiting on the label alone can capture a reopen count one revision too early; that race made
     * the stall test fail in the release variant, where the load is slower.
     */
    private fun awaitLabel(controller: PlaybackController, expected: String, reopensBefore: Int? = null) {
        awaitCondition("the quality chip to read '$expected' (it reads '${controller.qualityLabel}')") {
            controller.qualityLabel == expected
        }
        if (reopensBefore != null) {
            awaitCondition("the stream for '$expected' to reach the player") {
                player.mediaSourceCount > reopensBefore
            }
        }
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(15)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** One step-up check plus a margin: the loop runs every 5s. */
    private fun oneStepUpWindow() = Thread.sleep(6_500)

    /** The stall watch waits 8s before it calls a buffering player stalled. */
    private fun oneStallWindow() = Thread.sleep(9_500)

    // ------------------------------------------------------------------ the chooser runs, on this path

    @Test
    fun `automatic plays a rendition the item actually has, chosen from the measurement`() {
        BandwidthOverride.set(900)
        val controller = playing()
        awaitLabel(controller, "Auto (360p)")

        // 900 kbps may spend 630: 360p (600) fits, 480p (1200) does not - and the stream handed to the
        // engine is that rendition, not merely a menu highlight.
        assertTrue(
            "the engine must be given the 360p stream: ${player.lastRenditionUri}",
            player.lastRenditionUri?.contains("r360") == true,
        )
    }

    @Test
    fun `a better measurement is given a better rendition of the same item`() {
        BandwidthOverride.set(900)
        playing()
        assertTrue("the first item opened at 360p: ${player.lastRenditionUri}", player.lastRenditionUri?.contains("r360") == true)

        // The decision is made per item, from what is measured now - an earlier item's choice is not reused.
        BandwidthOverride.set(20_000)
        val second = playing("vid-b")
        awaitLabel(second, "Auto (1080p)")

        assertTrue(
            "the engine must be given the 1080p stream: ${player.lastRenditionUri}",
            player.lastRenditionUri?.contains("r1080") == true,
        )
    }

    // ------------------------------------------------------------------ manual is authoritative

    @Test
    fun `a manual rendition is not replaced by the adaptive chooser`() {
        val controller = playing()
        controller.openMenu(PlayerMenu.QUALITY)
        val reopensBeforePin = player.mediaSourceCount
        controller.selectMenuOption("h720")
        awaitLabel(controller, "720p", reopensBeforePin)
        val reopensAfterPin = player.mediaSourceCount

        // A connection that could carry much more: Auto would climb, a pin must not.
        BandwidthOverride.set(50_000)
        oneStepUpWindow()

        assertEquals("the pin must hold", "720p", controller.qualityLabel)
        assertEquals("and nothing may be reopened", reopensAfterPin, player.mediaSourceCount)
    }

    @Test
    fun `a stall does not override a manual rendition`() {
        val controller = playing()
        controller.openMenu(PlayerMenu.QUALITY)
        val reopensBeforePin = player.mediaSourceCount
        controller.selectMenuOption("h720")
        awaitLabel(controller, "720p", reopensBeforePin)
        val reopensAfterPin = player.mediaSourceCount

        // Eight seconds of buffering is what Auto treats as a stall worth downgrading for.
        player.firePlaybackState(Player.STATE_BUFFERING)
        oneStallWindow()

        assertEquals("a pinned rendition is the viewer's choice", "720p", controller.qualityLabel)
        assertEquals("so the stall must not reopen the stream", reopensAfterPin, player.mediaSourceCount)
    }

    // ------------------------------------------------------------------ paused playback

    @Test
    fun `a paused player is never stepped up`() {
        val controller = playing()
        controller.togglePause()
        val reopensWhilePaused = player.mediaSourceCount

        BandwidthOverride.set(50_000)
        oneStepUpWindow()

        assertTrue("a quality change must not resume playback", !player.playWhenReady)
        assertEquals("and must not reopen the stream either", reopensWhilePaused, player.mediaSourceCount)
    }

    // ------------------------------------------------------------------ no evidence, no change

    @Test
    fun `nothing measured means no upgrade`() {
        val controller = playing()
        val reopensAtStart = player.mediaSourceCount

        BandwidthOverride.set(null)
        oneStepUpWindow()

        assertEquals("Auto must not climb on a guess", reopensAtStart, player.mediaSourceCount)
        assertTrue("still playing", player.playWhenReady)
    }

    // ------------------------------------------------------------------ across items

    @Test
    fun `the rendered size of one item does not leak into the next item's chip`() {
        val controller = playing()
        controller.openMenu(PlayerMenu.QUALITY)
        controller.selectMenuOption("h720")
        awaitLabel(controller, "720p")
        player.fireVideoSize(height = 540)
        awaitCondition("the chip to admit the mismatch") { controller.qualityDisplayLabel == "720p (showing 540p)" }

        controller.next()
        awaitCondition("the controller to be on vid-b") { controller.title == "Resolved vid-b" }

        assertEquals(
            "the new item has rendered nothing yet, so it must not inherit the old picture's size",
            "720p",
            controller.qualityDisplayLabel,
        )
    }

    // ------------------------------------------------------------------ preservation

    @Test
    fun `a quality change keeps the audio track, the captions and the playhead`() {
        player.tracks = tracksOf(videoGroup, textGroup)
        val controller = playing()
        player.positionMs = 120_000L

        controller.openMenu(PlayerMenu.AUDIO)
        controller.selectMenuOption("at:a1")
        awaitCondition("the second audio track to be chosen") { controller.audioLabel == "Spanish" }
        controller.openMenu(PlayerMenu.CAPTIONS)
        controller.selectMenuOption("cap:en")
        awaitCondition("captions on") {
            player.trackSelectionParameters.overrides.values.any { it.mediaTrackGroup.type == C.TRACK_TYPE_TEXT }
        }

        controller.openMenu(PlayerMenu.QUALITY)
        controller.selectMenuOption("h360")
        awaitCondition("the reopen") { controller.qualityLabel == "360p" }

        assertEquals("the chosen audio track must survive a quality change", "Spanish", controller.audioLabel)
        assertTrue(
            "and so must the caption pin",
            player.trackSelectionParameters.overrides.values.any { it.mediaTrackGroup.type == C.TRACK_TYPE_TEXT },
        )
        assertEquals("the playhead must not move", 120_000L, player.positionMs)
    }

    // ------------------------------------------------------------------ the stand-in player

    private class FakePlayer {
        var positionMs = 0L
        var durationMs = 600_000L
        var playWhenReady = true
        var playbackState = Player.STATE_READY
        var mediaSourceCount = 0
        var lastRenditionUri: String? = null
        var tracks: Tracks = Tracks(listOf<Tracks.Group>())
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

        fun fireVideoSize(height: Int) {
            listeners.toList().forEach { it.onVideoSizeChanged(VideoSize(height * 16 / 9, height)) }
        }

        private fun handle(method: Method, args: Array<Any?>?): Any? {
            when (method.name) {
                "addListener" -> listeners += args!![0] as Player.Listener
                "removeListener" -> listeners -= args!![0] as Player.Listener
                "getCurrentPosition" -> return positionMs
                "getDuration" -> return durationMs
                "getPlayWhenReady", "isPlaying" -> return playWhenReady
                "getPlaybackState" -> return playbackState
                "play" -> playWhenReady = true
                "pause" -> playWhenReady = false
                "prepare" -> playbackState = Player.STATE_READY
                "seekTo" -> {
                    positionMs = (args!![0] as Number).toLong()
                    return null
                }
                "setMediaSource" -> {
                    mediaSourceCount++
                    // The rendition the app actually asked for, read off the source it handed over.
                    lastRenditionUri = args!![0]
                        ?.let { source -> runCatching { (source as MediaSource).mediaItem.localConfiguration?.uri?.toString() }.getOrNull() }
                    return null
                }
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
