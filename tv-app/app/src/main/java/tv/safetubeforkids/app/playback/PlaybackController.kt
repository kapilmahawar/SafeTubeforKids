package tv.safetubeforkids.app.playback

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.PlaybackPositionEntity
import tv.safetubeforkids.app.data.events.PlayEventRecorder
import tv.safetubeforkids.app.data.models.VideoItem
import tv.safetubeforkids.app.timelimits.TimeLimitStatus
import tv.safetubeforkids.app.util.AppLogger

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

    // --- player menus -------------------------------------------------------
    var activeMenu by mutableStateOf<PlayerMenu?>(null)
        private set
    var menuTitle by mutableStateOf("")
        private set
    var menuOptions by mutableStateOf<List<PlayerOption>>(emptyList())
        private set
    var captionsLabel by mutableStateOf("Off")
        private set
    var audioLabel by mutableStateOf("Audio")
        private set
    var speed by mutableStateOf(1f)
        private set
    var aspectId by mutableStateOf(AspectChoices.FIT)
        private set

    val speedChoices = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    private var selectedCaptionTag: String? = null
    private var forcedQualityHeight: Int? = null
    private var forcedAudioTrackId: String? = null
    private var resumePositionMs: Long = 0L
    private var released = false
    private var menuAutoCloseJob: Job? = null

    private companion object {
        /** Below this, restarting is more natural than resuming. */
        const val RESUME_MIN_MS = 20_000L

        /** At or beyond this fraction the video counts as finished, not resumable. */
        const val RESUME_MAX_PERCENT = 95

        /** Menus disappear after this long without input, so they never linger as a distraction. */
        const val MENU_IDLE_MS = 8_000L
        const val MENU_AFTER_CHOICE_MS = 5_000L
    }

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
        // Never touch a released player: these loops outlive the screen by a few frames, and
        // an uncaught exception in a composition-scoped coroutine would take the app down.
        scope.launch {
            while (!released) {
                try {
                    PlayEventRecorder.currentPositionMs = player.currentPosition.coerceAtLeast(0L)
                } catch (e: Exception) {
                    AppLogger.warn("Playhead loop stopped: ${e.message}")
                    return@launch
                }
                delay(500)
            }
        }

        // Persist the playhead so "resume" survives leaving the video or restarting the app.
        scope.launch {
            while (!released) {
                delay(10_000)
                try {
                    savePosition()
                } catch (e: Exception) {
                    AppLogger.warn("Position save loop error: ${e.message}")
                }
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
                // Nothing is playing, so the status API must stop reporting the previous video.
                PlayEventRecorder.endEvent(0, 0)
                currentVideoId = ""
                resolved = null
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

    private suspend fun prepare(videoId: String, restorePositionMs: Long = 0L) {
        errorMessage = null
        val media = VideoResolver.resolve(videoId)
        if (media == null) {
            errorMessage = "Couldn't play this video"
            return
        }
        resolved = media

        // Drop any menu selection the new video cannot honour.
        if (forcedQualityHeight != null && media.qualities.none { it.height == forcedQualityHeight }) {
            forcedQualityHeight = null
        }
        if (forcedAudioTrackId != null && media.audioOptions.none { it.trackId == forcedAudioTrackId }) {
            forcedAudioTrackId = null
        }
        if (selectedCaptionTag != null && media.captions.none { it.languageTag == selectedCaptionTag }) {
            selectedCaptionTag = null
            captionsLabel = "Off"
        }

        // Resume: offer to continue where the child left off, unless the video was finished.
        resumePositionMs = 0L
        if (restorePositionMs == 0L) {
            val saved = try {
                withContext(Dispatchers.IO) { db.playbackPositionDao().get(videoId) }
            } catch (e: Exception) {
                null
            }
            val durationMs = media.durationMs
            if (saved != null && saved.positionMs >= RESUME_MIN_MS) {
                if (durationMs > 0 && saved.positionMs >= durationMs * RESUME_MAX_PERCENT / 100) {
                    withContext(Dispatchers.IO) { db.playbackPositionDao().delete(videoId) }
                    AppLogger.log("Previous session reached the end of $videoId - starting over")
                } else {
                    resumePositionMs = saved.positionMs
                    AppLogger.log("Resumable position for $videoId: ${saved.positionMs / 1000}s")
                }
            }
        }

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
            val quality = forcedQualityHeight
                ?.let { height -> media.qualities.firstOrNull { it.height == height } }
                ?: media.defaultQuality
            val audio = forcedAudioTrackId
                ?.let { trackId -> media.audioOptions.firstOrNull { it.trackId == trackId } }
                ?: media.defaultAudio
            audioLabel = audio?.label ?: "Audio"

            val source = PlayerMedia.mediaSource(
                media = media,
                quality = quality,
                audio = audio,
                preferDash = useDash,
                captions = media.captions,
            )
            player.setMediaSource(source)
            player.prepare()
            if (restorePositionMs > 0L) player.seekTo(restorePositionMs)
            player.play()
            AppLogger.success(
                "Playing $videoId (${if (useDash && media.isAdaptive) "dash" else "progressive"}, " +
                    "${media.qualities.size} renditions, ${media.captions.size} caption tracks)"
            )

            // Re-apply the caption choice to the freshly prepared track list.
            scope.launch {
                delay(600)
                applyCaptionSelection()
            }

            if (resumePositionMs > 0L) {
                // Ask instead of guessing, and stay paused so nothing plays under the prompt.
                player.pause()
                openMenu(PlayerMenu.RESUME)
            }
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
            savePosition()
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
        // Order matters: persist while the player is still alive, then stop the background
        // loops, and only then release the player they were reading from.
        savePosition()
        if (playStartTime > 0) {
            val elapsed = elapsedSeconds()
            if (elapsed > 0) PlayEventRecorder.endEvent(elapsed, currentPercent())
        }
        released = true
        player.release()
    }

    // --- player menus -------------------------------------------------------

    fun openMenu(menu: PlayerMenu) {
        activeMenu = menu
        menuTitle = when (menu) {
            PlayerMenu.RESUME -> "Continue watching?"
            PlayerMenu.CAPTIONS -> "Subtitles"
            PlayerMenu.QUALITY -> "Video quality"
            PlayerMenu.AUDIO -> "Audio"
            PlayerMenu.SPEED -> "Playback speed"
            PlayerMenu.ASPECT -> "Screen fit"
        }
        menuOptions = buildOptions(menu)
        AppLogger.log("Menu opened: ${menu.name} (${menuOptions.size} options)")
        // Settings menus are transient and must not linger. The resume prompt is a decision, not
        // a menu: auto-closing it after 8s silently made the choice for the viewer, and a press
        // arriving later landed on the player as play/pause instead of answering the prompt.
        if (menu != PlayerMenu.RESUME) scheduleMenuAutoClose()
    }

    /** Menus must not linger as a distraction: they close themselves when left alone. */
    fun noteMenuInteraction() {
        if (activeMenu != null) scheduleMenuAutoClose()
    }

    private fun scheduleMenuAutoClose(delayMs: Long = MENU_IDLE_MS) {
        menuAutoCloseJob?.cancel()
        menuAutoCloseJob = scope.launch {
            delay(delayMs)
            if (activeMenu != null) {
                AppLogger.log("Menu closed after ${delayMs / 1000}s of no input")
                closeMenu()
            }
        }
    }

    fun closeMenu() {
        menuAutoCloseJob?.cancel()
        if (activeMenu == PlayerMenu.RESUME && resumePositionMs > 0L) {
            // Dismissing the prompt means "carry on watching", not "sit there paused".
            player.seekTo(resumePositionMs)
            player.play()
            AppLogger.log("Resume prompt dismissed - continuing at ${resumePositionMs / 1000}s")
            resumePositionMs = 0L
        }
        activeMenu = null
        menuOptions = emptyList()
    }

    private fun buildOptions(menu: PlayerMenu): List<PlayerOption> = when (menu) {
        PlayerMenu.RESUME -> listOf(
            PlayerOption("resume", "Resume from ${formatClock(resumePositionMs)}", true),
            PlayerOption("restart", "Start over", false),
        )

        PlayerMenu.CAPTIONS -> {
            val captions = resolved?.captions.orEmpty()
            if (captions.isEmpty()) {
                listOf(PlayerOption("none", "No subtitles for this video", true))
            } else {
                listOf(PlayerOption("off", "Off", selectedCaptionTag == null)) +
                    captions.map { caption ->
                        PlayerOption(
                            id = "cap:${caption.languageTag}",
                            label = if (caption.isAutoGenerated) "${caption.label} (auto)" else caption.label,
                            selected = selectedCaptionTag == caption.languageTag,
                        )
                    }
            }
        }

        PlayerMenu.QUALITY -> {
            val group = adaptiveVideoGroup()
            if (group != null) {
                val auto = player.trackSelectionParameters.overrides.isEmpty()
                listOf(PlayerOption("auto", "Auto", auto)) + videoHeights(group).map { (height, trackIndex) ->
                    PlayerOption("h$height", "${height}p", isOverridden(group, trackIndex))
                }
            } else {
                val media = resolved
                val current = forcedQualityHeight ?: media?.defaultQuality?.height
                val options = media?.qualities.orEmpty().map { quality ->
                    PlayerOption("h${quality.height}", quality.label, quality.height == current)
                }
                options.ifEmpty { listOf(PlayerOption("none", "No quality options", true)) }
            }
        }

        PlayerMenu.AUDIO -> {
            val media = resolved
            val current = forcedAudioTrackId ?: media?.defaultAudio?.trackId
            val options = media?.audioOptions.orEmpty().map { audio ->
                val suffix = if (audio.isAutoDubbed) " (auto-dubbed)" else ""
                PlayerOption(
                    id = "at:${audio.trackId}",
                    label = "${audio.label}$suffix",
                    selected = audio.trackId == current,
                )
            }
            options.ifEmpty { listOf(PlayerOption("none", "One audio track", true)) }
        }

        PlayerMenu.SPEED -> speedChoices.map { choice ->
            PlayerOption("sp:$choice", "${choice}x", choice == speed)
        }

        PlayerMenu.ASPECT -> AspectChoices.ordered.map { (id, label) ->
            PlayerOption(id, label, id == aspectId)
        }
    }

    fun selectMenuOption(id: String) {
        val menu = activeMenu ?: return

        if (menu == PlayerMenu.RESUME) {
            if (id == "restart") {
                player.seekTo(0)
                scope.launch(Dispatchers.IO) { db.playbackPositionDao().delete(currentVideoId) }
                AppLogger.log("Start over chosen for $currentVideoId")
            } else {
                player.seekTo(resumePositionMs)
                AppLogger.log("Resume chosen: ${resumePositionMs / 1000}s into $currentVideoId")
            }
            player.play()
            resumePositionMs = 0L
            closeMenu()
            return
        }

        when {
            id == "none" -> Unit

            id == "off" -> {
                selectedCaptionTag = null
                captionsLabel = "Off"
                applyCaptionSelection()
            }

            id.startsWith("cap:") -> {
                selectedCaptionTag = id.removePrefix("cap:")
                captionsLabel = resolved?.captions
                    ?.firstOrNull { it.languageTag == selectedCaptionTag }?.label ?: "On"
                applyCaptionSelection()
            }

            menu == PlayerMenu.QUALITY || id == "auto" -> applyQuality(id)
            menu == PlayerMenu.AUDIO -> applyAudio(id)
            menu == PlayerMenu.SPEED -> {
                speed = id.removePrefix("sp:").toFloatOrNull() ?: 1f
                player.setPlaybackSpeed(speed)
            }
            menu == PlayerMenu.ASPECT -> applyAspect(id)
        }
        AppLogger.log("Player menu ${menu.name} -> $id")
        menuOptions = buildOptions(menu)
        // Give the child a moment to see the change before the menu goes away.
        scheduleMenuAutoClose(MENU_AFTER_CHOICE_MS)
    }

    // --- track selection ----------------------------------------------------

    private fun adaptiveVideoGroup(): TrackGroup? = player.currentTracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 1 }
        ?.mediaTrackGroup

    private fun adaptiveAudioGroup(): TrackGroup? = player.currentTracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 1 }
        ?.mediaTrackGroup

    private fun videoHeights(group: TrackGroup): List<Pair<Int, Int>> = (0 until group.length)
        .mapNotNull { trackIndex ->
            group.getFormat(trackIndex).height.takeIf { it > 0 }?.let { it to trackIndex }
        }
        .distinctBy { it.first }
        .sortedByDescending { it.first }

    private fun isOverridden(group: TrackGroup, trackIndex: Int): Boolean =
        player.trackSelectionParameters.overrides.values.any { override ->
            override.mediaTrackGroup == group && override.trackIndices.contains(trackIndex)
        }

    private fun applyQuality(id: String) {
        val media = resolved ?: return
        val group = adaptiveVideoGroup()
        if (group != null) {
            val builder = player.trackSelectionParameters.buildUpon()
            if (id == "auto") {
                builder.clearOverrides()
            } else {
                val height = id.removePrefix("h").toIntOrNull() ?: return
                val trackIndex = videoHeights(group).firstOrNull { it.first == height }?.second ?: return
                builder.setOverrideForType(TrackSelectionOverride(group, trackIndex))
            }
            player.trackSelectionParameters = builder.build()
            return
        }

        // Progressive: the rendition is baked into the stream, so reopening it is the only
        // honest way to change quality - keeping the playhead so the child sees no restart.
        val height = id.removePrefix("h").toIntOrNull() ?: return
        if (media.qualities.none { it.height == height }) return
        forcedQualityHeight = height
        val position = player.currentPosition
        scope.launch { prepare(currentVideoId, position) }
    }

    private fun applyAudio(id: String) {
        val media = resolved ?: return
        val trackId = id.removePrefix("at:")
        val option = media.audioOptions.firstOrNull { it.trackId == trackId } ?: return
        forcedAudioTrackId = trackId
        audioLabel = option.label
        AppLogger.log("Audio track selected: '${option.label}' (${option.languageTag}, ${option.bitrateKbps} kbps)")

        // Multi-language audio arrives as separate YouTube tracks, so the played stream has to
        // be reopened with the chosen language - keeping the playhead so nothing restarts.
        val position = player.currentPosition
        scope.launch { prepare(currentVideoId, position) }
    }

    private fun applyAspect(id: String) {
        aspectId = id
        resizeMode = when (id) {
            AspectChoices.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectChoices.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun applyCaptionSelection() {
        val builder = player.trackSelectionParameters.buildUpon()
        val tag = selectedCaptionTag
        if (tag == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            val match = player.currentTracks.groups.firstOrNull { group ->
                group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    format.language == tag || format.label?.contains(tag, ignoreCase = true) == true
                }
            }
            if (match != null) {
                builder.setOverrideForType(TrackSelectionOverride(match.mediaTrackGroup, 0))
            } else {
                AppLogger.warn("No caption track present for language '$tag'")
            }
        }
        player.trackSelectionParameters = builder.build()
        AppLogger.log("Captions selection: ${tag ?: "off"}")
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

    /** Persists the playhead for resume. Called periodically, on pause and on leaving. */
    private fun savePosition() {
        val videoId = currentVideoId
        if (videoId.isBlank()) return
        val position = player.currentPosition
        if (position <= 0L) return
        val reported = player.duration
        val durationMs = if (reported == C.TIME_UNSET || reported < 0) {
            resolved?.durationMs ?: 0L
        } else {
            reported
        }
        scope.launch(Dispatchers.IO) {
            try {
                db.playbackPositionDao().upsert(
                    PlaybackPositionEntity(
                        videoId = videoId,
                        positionMs = position,
                        durationMs = durationMs,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            } catch (e: Exception) {
                AppLogger.warn("Could not persist position for $videoId: ${e.message}")
            }
        }
    }

    private fun formatClock(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
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
