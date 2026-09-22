package tv.safetubeforkids.app.data.cache

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

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
}
