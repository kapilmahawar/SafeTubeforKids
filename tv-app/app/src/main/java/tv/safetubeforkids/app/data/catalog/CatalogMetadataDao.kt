package tv.safetubeforkids.app.data.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The single `catalog_metadata` row (`id = 1`). */
@Dao
interface CatalogMetadataDao {

    @Query("SELECT * FROM catalog_metadata WHERE id = 1")
    fun observe(): Flow<CatalogMetadataEntity?>

    @Query("SELECT * FROM catalog_metadata WHERE id = 1")
    suspend fun get(): CatalogMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: CatalogMetadataEntity)

    /**
     * Marks a completed synchronization. Returns the number of rows touched, so a caller can tell
     * that the singleton row did not exist yet.
     */
    @Query(
        "UPDATE catalog_metadata SET catalog_version = :version, " +
            "last_successful_sync_at = :syncedAt, last_attempt_at = :syncedAt WHERE id = 1"
    )
    suspend fun markSyncSucceeded(version: Long, syncedAt: Long): Int

    /** Stamps an attempt that has started; a failure simply never updates the success columns. */
    @Query("UPDATE catalog_metadata SET last_attempt_at = :attemptedAt WHERE id = 1")
    suspend fun markSyncAttempt(attemptedAt: Long): Int

    @Query("UPDATE catalog_metadata SET server_version = :version WHERE id = 1")
    suspend fun markServerVersion(version: Long): Int
}
