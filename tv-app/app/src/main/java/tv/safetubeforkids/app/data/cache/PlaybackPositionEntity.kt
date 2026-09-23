package tv.safetubeforkids.app.data.cache

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Where the child left off in an approved video, so a reopened video can offer
 * "Resume" or "Start over" instead of silently restarting.
 */
@Entity(tableName = "playback_positions")
data class PlaybackPositionEntity(
    @PrimaryKey val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/**
 * A video that is both half-watched and still approved, for the Continue Watching shelf.
 *
 * Produced by a join, and the join is the security property: `videos` only holds videos of
 * approved sources and `channels` only holds the sources that are still approved, so a video whose
 * parent source was removed drops out of this shelf even though its playback position remains.
 * Watching history therefore cannot become a playback permission - and selecting a card from this
 * shelf still goes through `PlaybackAuthorization` like every other card.
 */
data class ResumableVideoRow(
    val videoId: String,
    val playlistId: String,
    val title: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Dao
interface PlaybackPositionDao {
    @Query("SELECT * FROM playback_positions WHERE videoId = :videoId LIMIT 1")
    suspend fun get(videoId: String): PlaybackPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: PlaybackPositionEntity)

    @Query("DELETE FROM playback_positions WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("DELETE FROM playback_positions")
    suspend fun deleteAll()

    /**
     * The resumable-and-still-approved videos, most recently watched first.
     *
     * `updatedAt` is the only recency signal this app stores - there is no separate history table -
     * so it is what orders the shelf. The position bounds keep the shelf to what the player would
     * actually offer to resume: a few seconds in is not worth a card, and a video at the end would
     * simply start over.
     */
    @Query(
        """
        SELECT p.videoId AS videoId,
               v.playlistId AS playlistId,
               v.title AS title,
               v.thumbnailUrl AS thumbnailUrl,
               v.durationSeconds AS durationSeconds,
               p.positionMs AS positionMs,
               p.durationMs AS durationMs,
               p.updatedAt AS updatedAt
        FROM playback_positions AS p
        INNER JOIN videos AS v ON v.videoId = p.videoId
        INNER JOIN channels AS c ON c.source_id = v.playlistId
        WHERE p.positionMs >= :minPositionMs
          AND (p.durationMs <= 0 OR p.positionMs * 100 < p.durationMs * :maxPercent)
        ORDER BY p.updatedAt DESC
        """
    )
    fun observeResumable(minPositionMs: Long, maxPercent: Int): Flow<List<ResumableVideoRow>>
}
