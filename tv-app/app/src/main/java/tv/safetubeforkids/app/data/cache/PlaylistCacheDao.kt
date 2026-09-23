package tv.safetubeforkids.app.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Just enough of the cached video to illustrate a catalog card: the parent catalog carries a name
 * and a YouTube identifier but no artwork, so the TV takes artwork from the video the approved cache
 * already holds for that identifier. One query for the whole index, never one per card.
 */
data class VideoThumbnailRow(
    val videoId: String,
    val playlistId: String,
    val thumbnailUrl: String,
)

@Dao
interface PlaylistCacheDao {
    @Query("SELECT * FROM videos WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getByPlaylist(playlistId: String): List<VideoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(videos: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE playlistId = :playlistId")
    suspend fun deleteByPlaylist(playlistId: String)

    @Query("DELETE FROM videos")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM videos")
    suspend fun count(): Int

    /** Authorization lookup: a video is playable only while it is in the approved cache. */
    @Query("SELECT * FROM videos WHERE videoId = :videoId LIMIT 1")
    suspend fun getByVideoId(videoId: String): VideoEntity?

    @Query("SELECT COUNT(*) FROM videos WHERE videoId = :videoId")
    suspend fun countByVideoId(videoId: String): Int

    /**
     * The artwork index for the catalog UI: one row per cached video, a playlist's rows ordered by
     * their position so the first row for a playlist id is that playlist's opening video.
     *
     * Observed (not just read once) so artwork appears as soon as the approved cache is populated,
     * without the catalog UI ever asking the network for anything.
     */
    @Query("SELECT videoId, playlistId, thumbnailUrl FROM videos ORDER BY playlistId ASC, position ASC")
    fun observeThumbnailIndex(): Flow<List<VideoThumbnailRow>>
}
