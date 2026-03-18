package ax.nd.kdbxgit.android.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncLogDao {

    @Query("SELECT * FROM sync_log ORDER BY timestamp DESC LIMIT 200")
    fun getRecentEntries(): Flow<List<SyncLogEntry>>

    @Insert
    suspend fun insert(entry: SyncLogEntry)

    @Query("DELETE FROM sync_log WHERE id NOT IN (SELECT id FROM sync_log ORDER BY timestamp DESC LIMIT 200)")
    suspend fun trimToLimit()

    /** Insert and cap the table at 200 rows atomically. */
    @Transaction
    suspend fun insertCapped(entry: SyncLogEntry) {
        insert(entry)
        trimToLimit()
    }
}
