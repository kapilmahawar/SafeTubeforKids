package tv.parentapproved.app.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
}
