package tv.parentapproved.app.playback

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.parentapproved.app.ServiceLocator
import tv.parentapproved.app.data.cache.CacheDatabase
import tv.parentapproved.app.data.events.PlayEventRecorder
import tv.parentapproved.app.data.models.VideoItem
import tv.parentapproved.app.timelimits.TimeLimitStatus
import tv.parentapproved.app.util.AppLogger

/**
 * Maps Android TV remote keys onto player actions.
 *
 * NEXT and PREVIOUS deliberately mean "next/previous item in the parent-approved queue";
 * they never consult YouTube for recommendations.
 */
object PlaybackKeys {
    enum class Action {
        TogglePlayPause,
        SeekBackward,
        SeekForward,
        NextApproved,
        PreviousApproved,
        RevealControls,
    }

    fun actionOf(keyCode: Int): Action? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        -> Action.TogglePlayPause

        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        -> Action.SeekBackward

        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        -> Action.SeekForward

        KeyEvent.KEYCODE_MEDIA_NEXT -> Action.NextApproved
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Action.PreviousApproved

        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_INFO,
        -> Action.RevealControls

        else -> null
    }
}

/**
 * Playback pipeline: Kids UI -> PlaybackController -> PlaybackAuthorization -> VideoResolver
 * -> Media3. The UI holds no media state of its own and cannot start playback without the
 * authorization step inside [start].
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackController(
    context: Context,
    private val db: CacheDatabase,
    private val scope: CoroutineScope,
    private val onExit: () -> Unit,
    private val onLocked: (String) -> Unit,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply { playWhenReady = true }

    var title by mutableStateOf("")
        private set
    var queueLabel by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var warningText by mutableStateOf<String?>(null)
        private set
    var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        private set

    private var queue: List<VideoItem> = emptyList()
    private var index = 0
    private var sourceId = ""
    private var playlistTitle = ""
    private var playStartTime = 0L
    private var useDash = true
    private var resolved: ResolvedMedia? = null
    private var currentVideoId = ""
    private var startIndex = 0
    private var timeLimitWatchStarted = false

    init {
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    // End of an approved video: continue only inside the approved queue.
                    next()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                AppLogger.error("Player error (${error.errorCodeName}): ${error.message}")
                handlePlaybackFailure(error.errorCodeName)
            }

            override fun onIsPlayingChanged(nowPlaying: Boolean) {
                AppLogger.log("Player isPlaying=$nowPlaying")
            }
        })

        // Publish the playhead so remote behaviour is observable from the status API.
        scope.launch {
            while (true) {
                PlayEventRecorder.currentPositionMs = player.currentPosition.coerceAtLeast(0L)
                delay(500)
            }
        }
    }

    fun start(videoId: String, fallbackSourceId: String, requestedIndex: Int) {
        currentVideoId = videoId
        startIndex = requestedIndex
        errorMessage = null
        scope.launch { boot(videoId, fallbackSourceId, requestedIndex) }
    }

    private suspend fun boot(videoId: String, fallbackSourceId: String, requestedIndex: Int) {
        when (val approval = PlaybackAuthorization.authorize(db, videoId)) {
            is PlaybackApproval.Rejected -> {
                // Security boundary: never resolve or prepare media for anything unapproved.
                errorMessage = "This video can't be played"
                AppLogger.warn("Playback rejected: ${approval.reason}")
                return
            }
            is PlaybackApproval.Approved -> {
                sourceId = approval.sourceId
                queue = PlaybackAuthorization.approvedQueue(db, sourceId)
                playlistTitle = withContext(Dispatchers.IO) {
                    db.channelDao().getBySourceId(sourceId)?.displayName ?: sourceId
                }
                index = queue.indexOfFirst { it.videoId == videoId }
                    .takeIf { it >= 0 }
                    ?: requestedIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
                updateQueueLabel()
                prepare(videoId)
                startTimeLimitWatch()
            }
        }
    }

    private suspend fun prepare(videoId: String) {
        errorMessage = null
        val media = VideoResolver.resolve(videoId)
        if (media == null) {
            errorMessage = "Couldn't play this video"
            return
        }
        resolved = media

        val queueItem = queue.getOrNull(index)
        title = media.title.ifBlank { queueItem?.title ?: videoId }
        playStartTime = System.currentTimeMillis()

        PlayEventRecorder.startEvent(
            videoId = videoId,
            playlistId = sourceId,
            title = title,
            playlistTitle = playlistTitle,
            durationMs = media.durationMs,
        )

        try {
            val source = PlayerMedia.mediaSource(
                media = media,
                quality = media.defaultQuality,
                audio = media.defaultAudio,
                preferDash = useDash,
            )
            player.setMediaSource(source)
            player.prepare()
            player.play()
            AppLogger.success(
                "Playing $videoId (${if (useDash && media.isAdaptive) "dash" else "progressive"}, " +
                    "${media.qualities.size} renditions, ${media.captions.size} caption tracks)"
            )
        } catch (e: Exception) {
            AppLogger.error("Preparing playback failed: ${e.message}")
            errorMessage = "Couldn't play this video"
        }
    }

    /** Retry the current video on the same path; degradation is handled on failure. */
    fun retry() {
        scope.launch {
            if (currentVideoId.isNotBlank()) prepare(currentVideoId)
        }
    }

    fun next() {
        scope.launch {
            endCurrentEvent(100)
            val nextIndex = index + 1
            if (nextIndex < queue.size) {
                index = nextIndex
                updateQueueLabel()
                prepare(queue[nextIndex].videoId)
            } else {
                AppLogger.log("Approved queue finished")
                onExit()
            }
        }
    }

    fun previous() {
        scope.launch {
            endCurrentEvent(0)
            val previousIndex = index - 1
            if (previousIndex >= 0) {
                index = previousIndex
                updateQueueLabel()
                prepare(queue[previousIndex].videoId)
            } else {
                // Already at the first approved video: restart it.
                player.seekTo(0)
                player.play()
            }
        }
    }

    fun togglePause() {
        // playWhenReady carries the intent, so a pause press works even while the video is
        // still buffering - isPlaying would be false there and the press would be lost.
        AppLogger.log(
            "TogglePause: playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} " +
                "state=${player.playbackState}"
        )
        if (player.playWhenReady) {
            player.pause()
            PlayEventRecorder.onPause()
        } else {
            player.play()
            PlayEventRecorder.onResume()
        }
    }

    /** Seeking is owned here so the playhead, logging and clamping stay in one place. */
    fun seekBy(deltaMs: Long) {
        val duration = player.duration
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        val clamped = if (duration != C.TIME_UNSET && duration > 0) {
            target.coerceAtMost(duration)
        } else {
            target
        }
        player.seekTo(clamped)
        AppLogger.log("Seek ${deltaMs / 1000}s -> ${clamped / 1000}s")
    }

    fun cycleAspectRatio() {
        resizeMode = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        AppLogger.log("Aspect ratio mode: $resizeMode")
    }

    fun stop() {
        // The event is closed by release() when the screen goes away.
        onExit()
    }

    fun release() {
        if (playStartTime > 0) {
            val elapsed = elapsedSeconds()
            if (elapsed > 0) PlayEventRecorder.endEvent(elapsed, currentPercent())
        }
        player.release()
    }

    private fun handlePlaybackFailure(codeName: String) {
        // A failed DASH manifest is recoverable: fall back to the progressive renditions and
        // keep the child watching instead of surfacing an error we can work around.
        if (useDash && resolved?.isAdaptive == true) {
            useDash = false
            AppLogger.warn("DASH playback failed ($codeName) - retrying progressively")
            scope.launch { prepare(currentVideoId) }
            return
        }
        errorMessage = "Couldn't play this video"
    }

    private fun startTimeLimitWatch() {
        if (timeLimitWatchStarted) return
        timeLimitWatchStarted = true
        scope.launch {
            val initial = ServiceLocator.timeLimitManager.canPlay()
            if (initial is TimeLimitStatus.Blocked) {
                onLocked(initial.reason.name.lowercase())
                return@launch
            }
            while (true) {
                delay(30_000)
                when (val status = ServiceLocator.timeLimitManager.canPlay()) {
                    is TimeLimitStatus.Blocked -> {
                        player.pause()
                        warningText = null
                        onLocked(status.reason.name.lowercase())
                        return@launch
                    }
                    is TimeLimitStatus.Warning -> warningText = "${status.minutesLeft} minutes left!"
                    is TimeLimitStatus.Allowed -> warningText = null
                }
            }
        }
    }

    private fun updateQueueLabel() {
        queueLabel = if (queue.isEmpty()) null else "${index + 1} of ${queue.size}"
    }

    private fun elapsedSeconds(): Int = ((System.currentTimeMillis() - playStartTime) / 1000).toInt()

    private fun currentPercent(): Int {
        val duration = player.duration
        if (duration <= 0) return 0
        return ((player.currentPosition * 100) / duration).toInt().coerceIn(0, 100)
    }

    private fun endCurrentEvent(completedPct: Int) {
        val elapsed = elapsedSeconds()
        if (elapsed > 0) PlayEventRecorder.endEvent(elapsed, completedPct)
    }
}
