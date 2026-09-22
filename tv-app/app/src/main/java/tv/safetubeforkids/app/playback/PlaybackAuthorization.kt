package tv.safetubeforkids.app.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.models.VideoItem
import tv.safetubeforkids.app.util.AppLogger

/** Outcome of checking whether a video may play. */
sealed class PlaybackApproval {
    data class Approved(val video: VideoItem, val sourceId: String) : PlaybackApproval()
    data class Rejected(val reason: String) : PlaybackApproval()
}

/**
 * The single authorization gate for playback.
 *
 * Nothing reaches the player without passing through here: a video is playable only while it
 * is present in the approved cache AND its parent source still exists. The approved source's
 * cached videos are the only queue the player may walk - next/previous/autoplay never consult
 * YouTube for recommendations.
 */
object PlaybackAuthorization {

    suspend fun authorize(db: CacheDatabase, videoId: String): PlaybackApproval =
        withContext(Dispatchers.IO) {
            val entity = db.videoDao().getByVideoId(videoId)
            if (entity == null) {
                AppLogger.warn("Blocked playback of unapproved video: $videoId")
                return@withContext PlaybackApproval.Rejected("This video is not approved")
            }

            val source = db.channelDao().getBySourceId(entity.playlistId)
            if (source == null) {
                AppLogger.warn("Blocked playback from removed source: ${entity.playlistId}")
                return@withContext PlaybackApproval.Rejected("This source was removed")
            }

            PlaybackApproval.Approved(
                video = VideoItem(
                    videoId = entity.videoId,
                    title = entity.title,
                    thumbnailUrl = entity.thumbnailUrl,
                    durationSeconds = entity.durationSeconds,
                    playlistId = entity.playlistId,
                    position = entity.position,
                ),
                sourceId = source.sourceId,
            )
        }

    /**
     * The approved queue for a source: exactly the cached videos, in parent-defined order.
     * Returns empty for a source that is not (or no longer) approved.
     */
    suspend fun approvedQueue(db: CacheDatabase, sourceId: String): List<VideoItem> =
        withContext(Dispatchers.IO) {
            if (db.channelDao().getBySourceId(sourceId) == null) {
                AppLogger.warn("Refused queue for unapproved source: $sourceId")
                return@withContext emptyList()
            }
            db.videoDao().getByPlaylist(sourceId).map { e ->
                VideoItem(
                    videoId = e.videoId,
                    title = e.title,
                    thumbnailUrl = e.thumbnailUrl,
                    durationSeconds = e.durationSeconds,
                    playlistId = e.playlistId,
                    position = e.position,
                )
            }
        }
}
