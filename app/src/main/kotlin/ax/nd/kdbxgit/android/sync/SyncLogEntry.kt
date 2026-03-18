package ax.nd.kdbxgit.android.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SyncType { PULL, PUSH, PUSH_PULL }
enum class SyncOutcome { SUCCESS, MERGED, NO_CHANGE, FAILURE }

/**
 * A single sync attempt record stored in Room.
 *
 * Trigger values:
 *  - [SyncTrigger.MANUAL]       — user pressed "Sync now"
 *  - [SyncTrigger.WRITE]        — local DB was written via DocumentsProvider
 *  - [SyncTrigger.CONNECTIVITY] — network became available (WorkManager constraint fired)
 *  - [SyncTrigger.PERIODIC]     — WorkManager periodic job
 *  - [SyncTrigger.PUSH]         — UnifiedPush notification (future)
 *
 * Outcome values:
 *  - [SyncOutcome.SUCCESS]   — sync completed, data transferred
 *  - [SyncOutcome.MERGED]    — server returned a database that differed from what was uploaded
 *  - [SyncOutcome.NO_CHANGE] — neither side had changed; nothing done
 *  - [SyncOutcome.FAILURE]   — network or server error
 */
@Entity(tableName = "sync_log", indices = [Index("timestamp")])
data class SyncLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val trigger: SyncTrigger,
    val type: SyncType,
    val outcome: SyncOutcome,
    val bytesDown: Long,
    val bytesUp: Long,
    val durationMs: Long,
    val errorMessage: String? = null,
)
