package tv.safetubeforkids.app.data.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton row holding everything the local catalog knows about synchronization, so the TV can
 * always answer two questions without the server or the browser running:
 *
 *  - which catalog version is currently stored locally ([catalogVersion], 0 = never synced);
 *  - when it was last replaced by a successful synchronization ([lastSuccessfulSyncAt]).
 *
 * [serverVersion] records the newest version the server has advertised, and [lastAttemptAt] is
 * stamped at the start of every attempt, so a `lastAttemptAt` newer than `lastSuccessfulSyncAt`
 * means the most recent attempt did not complete. Phase 2 stores this metadata only; the network
 * sync itself belongs to Phase 3.
 */
@Entity(tableName = "catalog_metadata")
data class CatalogMetadataEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "catalog_version") val catalogVersion: Long = 0L,
    @ColumnInfo(name = "server_version") val serverVersion: Long? = null,
    @ColumnInfo(name = "last_successful_sync_at") val lastSuccessfulSyncAt: Long? = null,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Long? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
