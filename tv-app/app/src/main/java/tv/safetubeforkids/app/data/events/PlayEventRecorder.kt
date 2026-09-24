package tv.safetubeforkids.app.data.events

import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object PlayEventRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var db: CacheDatabase? = null
    @Volatile private var currentEventId: Long? = null
    @Volatile private var currentStartTime: Long = 0
    private var clock: () -> Long = { System.currentTimeMillis() }
    @Volatile private var pausedElapsedMs: Long = 0

    @Volatile var currentVideoId: String? = null
        private set
    @Volatile var currentPlaylistId: String? = null
        private set
    @Volatile var currentTitle: String? = null
        private set
    @Volatile var currentPlaylistTitle: String? = null
        private set
    @Volatile var currentDurationMs: Long = 0
        private set
    @Volatile var isPlaying: Boolean = false
        private set

    /**
     * Live playhead position, published by the player while it runs. Exposed through the status
     * API so remote-control behaviour (seeking, pause, resume) can be verified from outside the
     * app on a real device.
     */
    @Volatile var currentPositionMs: Long = 0

    fun init(database: CacheDatabase, clock: () -> Long = { System.currentTimeMillis() }) {
        this.db = database
        this.clock = clock
    }

    fun startEvent(videoId: String, playlistId: String, title: String = "", playlistTitle: String = "", durationMs: Long = 0) {
        val dao = db?.playEventDao() ?: return
        currentStartTime = clock()
        currentVideoId = videoId
        currentPlaylistId = playlistId
        currentTitle = title
        currentPlaylistTitle = playlistTitle
        currentDurationMs = durationMs
        isPlaying = true
        pausedElapsedMs = 0
        scope.launch {
            val event = PlayEventEntity(
                videoId = videoId,
                playlistId = playlistId,
                startedAt = currentStartTime,
                title = title,
            )
            currentEventId = dao.insert(event)
            AppLogger.log("Play event started: $videoId")
        }
    }

    fun updateTitle(title: String) {
        currentTitle = title
        val dao = db?.playEventDao() ?: return
        val eventId = currentEventId ?: return
        scope.launch {
            val event = dao.getById(eventId) ?: return@launch
            if (event.title.isBlank() || event.title == event.videoId) {
                dao.update(event.copy(title = title))
            }
        }
    }

    fun onPause() {
        if (isPlaying) {
            pausedElapsedMs = clock() - currentStartTime
            isPlaying = false
        }
    }

    fun onResume() {
        if (!isPlaying && currentVideoId != null) {
            currentStartTime = clock() - pausedElapsedMs
            isPlaying = true
        }
    }

    fun getElapsedMs(): Long {
        return if (isPlaying) {
            clock() - currentStartTime
        } else {
            pausedElapsedMs
        }
    }

    fun updateEvent(durationSec: Int, completedPct: Int) {
        val dao = db?.playEventDao() ?: return
        val eventId = currentEventId ?: return
        scope.launch {
            val event = dao.getById(eventId) ?: return@launch
            dao.update(event.copy(durationSec = durationSec, completedPct = completedPct))
        }
    }

    fun endEvent(durationSec: Int, completedPct: Int) {
        updateEvent(durationSec, completedPct)
        clearNowPlaying()
        currentEventId = null
        pausedElapsedMs = 0
    }

    /**
     * Clears only the *published* current-session state - what `GET /status` reports as
     * `currentlyPlaying` - and writes nothing.
     *
     * This exists because two different concerns were sharing one guard. Recording a play event is
     * only worth doing for a session that actually ran, which is why the callers skip
     * [endEvent] when `elapsed == 0`. But clearing the published state is not optional: a session
     * that opened and was terminated inside the same second (a video that never started playing, or
     * a screen that went away immediately) still has [startEvent] having set `isPlaying = true`,
     * and if nothing clears it the app keeps telling the status API - and the parent dashboard -
     * that a video is playing when Media3 is not playing anything.
     *
     * So the guard stays exactly where it was, for history, and this is what runs when there is no
     * row worth writing. No database row, catalog, authorization, queue or resume state is touched.
     */
    fun clearNowPlaying() {
        currentVideoId = null
        currentPlaylistId = null
        currentTitle = null
        currentPlaylistTitle = null
        currentDurationMs = 0
        isPlaying = false
    }

    fun flushCurrentEvent() {
        if (currentEventId != null) {
            val elapsed = (getElapsedMs() / 1000).toInt()
            if (elapsed > 0) {
                updateEvent(elapsed, 0)
            }
        }
    }

    fun clearAll() {
        val dao = db?.playEventDao() ?: return
        scope.launch { dao.deleteAll() }
    }
}
