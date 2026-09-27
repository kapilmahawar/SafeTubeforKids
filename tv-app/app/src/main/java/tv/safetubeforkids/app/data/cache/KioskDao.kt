package tv.safetubeforkids.app.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface KioskDao {
    @Query("SELECT * FROM kiosk_config WHERE id = 1")
    suspend fun getConfig(): KioskConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(config: KioskConfigEntity)

    @Query("UPDATE kiosk_config SET kioskEnabled = :enabled WHERE id = 1")
    suspend fun setKioskEnabled(enabled: Boolean)

    @Query("UPDATE kiosk_config SET enforceTimeLimitsOnAllApps = :enforce WHERE id = 1")
    suspend fun setEnforceTimeLimitsOnAllApps(enforce: Boolean)

    /**
     * Used by the destructive reset (W10), which must leave nothing of SafeTube's own configuration
     * behind. Kiosk *device-owner* state lives in Android, not here: this clears what SafeTube asked
     * for, and the reset tells the kiosk manager to let go of the device separately.
     */
    @Query("DELETE FROM kiosk_config")
    suspend fun deleteAll()
}
