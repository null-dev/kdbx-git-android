package ax.nd.kdbxgit.android.sync

import android.content.Context
import android.provider.DocumentsContract
import ax.nd.kdbxgit.android.DatabaseDocumentContract
import ax.nd.kdbxgit.android.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class SyncRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val syncLogDao: SyncLogDao,
    private val fileStore: DatabaseFileStore,
) {
    private val notifier = SyncNotifier(context)
    private val stateStore = SharedPreferencesSyncStateStore.from(context)
    private val syncEngine = SyncEngine(fileStore, stateStore)

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    val databaseFileStore: DatabaseFileStore
        get() = fileStore

    /** The live KDBX file served to KeePass clients via KdbxDocumentsProvider. */
    val dbFile: File
        get() = fileStore.dbFile

    /** SHA-256 hex of the KDBX bytes last confirmed on the server. Null = never synced. */
    val lastSyncedHash: String?
        get() = stateStore.lastSyncedHash

    // Ensures at most one sync runs at a time. A second caller will suspend until
    // the first finishes, then run with the freshest file/hash state.
    val syncMutex = Mutex()

    init {
        fileStore.setLocalChangeObserver {
            notifyFileChanged()
            SyncWorker.enqueueSyncNow(context, SyncTrigger.WRITE)
        }
    }

    /**
     * Wipes the local KDBX file and resets all sync state.
     * Called when the user changes the server URL or client ID so stale data from
     * a different server is not retained.
     */
    fun clearLocalData() {
        fileStore.clear()
        stateStore.reset()
        _syncStatus.value = SyncStatus.Idle
        notifier.onSuccess() // clear any lingering error notification
    }

    /**
     * Runs one full sync cycle. Concurrent calls serialize via [syncMutex]; the
     * second caller sees any file changes accumulated while the first was running.
     */
    suspend fun sync(trigger: SyncTrigger) {
        val config = settingsRepository.serverConfig.value ?: return

        syncMutex.withLock {
            val startMs = System.currentTimeMillis()
            val client = WebDavClient(config)

            val result = try {
                syncEngine.sync(client) { phase ->
                    _syncStatus.value = when (phase) {
                        SyncEnginePhase.PULLING -> SyncStatus.Pulling
                        SyncEnginePhase.PUSHING -> SyncStatus.Pushing
                    }
                }
            } catch (e: CancellationException) {
                _syncStatus.value = SyncStatus.Idle
                throw e
            }

            log(
                trigger = trigger,
                type = result.type,
                outcome = result.outcome,
                bytesDown = result.bytesDown,
                bytesUp = result.bytesUp,
                startMs = startMs,
                errorMessage = result.errorMessage,
            )

            if (result.outcome == SyncOutcome.FAILURE) {
                val msg = result.errorMessage ?: "Unknown error"
                stateStore.consecutiveFailures++
                _syncStatus.value = SyncStatus.Error(msg)
                notifier.onFailure(stateStore.consecutiveFailures, msg)
            } else {
                stateStore.consecutiveFailures = 0
                notifier.onSuccess()
                _syncStatus.value = SyncStatus.Idle
                if (result.documentChanged) {
                    notifyFileChanged()
                }
            }
        }
    }

    private suspend fun log(
        trigger: SyncTrigger,
        type: SyncType,
        outcome: SyncOutcome,
        bytesDown: Long,
        bytesUp: Long,
        startMs: Long,
        errorMessage: String? = null,
    ) {
        syncLogDao.insertCapped(
            SyncLogEntry(
                timestamp    = System.currentTimeMillis(),
                trigger      = trigger,
                type         = type,
                outcome      = outcome,
                bytesDown    = bytesDown,
                bytesUp      = bytesUp,
                durationMs   = System.currentTimeMillis() - startMs,
                errorMessage = errorMessage,
            )
        )
    }

    private fun notifyFileChanged() {
        val uri = DocumentsContract.buildDocumentUri(
            DatabaseDocumentContract.DOCUMENTS_AUTHORITY,
            DatabaseDocumentContract.DB_DOC_ID,
        )
        context.contentResolver.notifyChange(uri, null)
    }
}
