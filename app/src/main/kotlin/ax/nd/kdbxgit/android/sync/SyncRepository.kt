package ax.nd.kdbxgit.android.sync

import android.content.Context
import android.provider.DocumentsContract
import ax.nd.kdbxgit.android.settings.SettingsRepository
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
) {
    private val notifier = SyncNotifier(context)
    /** The live KDBX file served to KeePass clients via KdbxDocumentsProvider. */
    val dbFile = File(context.filesDir, DB_FILENAME)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /** SHA-256 hex of the KDBX bytes last confirmed on the server. Null = never synced. */
    val lastSyncedHash: String?
        get() = prefs.getString(KEY_LAST_HASH, null)

    /** True if the local file has been written since the last successful push. */
    val localDirty: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_DIRTY, false)

    /** Number of consecutive sync failures since the last success. */
    private var consecutiveFailures: Int
        get() = prefs.getInt(KEY_CONSECUTIVE_FAILURES, 0)
        set(v) = prefs.edit().putInt(KEY_CONSECUTIVE_FAILURES, v).apply()

    // Ensures at most one sync runs at a time. A second caller will suspend until
    // the first finishes, then run with the freshest dirty/hash state.
    val syncMutex = Mutex()

    /**
     * Wipes the local KDBX file and resets all sync state.
     * Called when the user changes the server URL or client ID so stale data from
     * a different server is not retained.
     */
    fun clearLocalData() {
        dbFile.delete()
        prefs.edit()
            .remove(KEY_LAST_HASH)
            .putBoolean(KEY_LOCAL_DIRTY, false)
            .putInt(KEY_CONSECUTIVE_FAILURES, 0)
            .apply()
        _syncStatus.value = SyncStatus.Idle
        notifier.onSuccess() // clear any lingering error notification
    }

    /**
     * Called by [ax.nd.kdbxgit.android.provider.KdbxDocumentsProvider] after a
     * successful write so the next sync knows there is local work to push.
     */
    fun markDirty() {
        prefs.edit().putBoolean(KEY_LOCAL_DIRTY, true).apply()
    }

    /**
     * Runs one full sync cycle. Implements the four scenarios from the ROADMAP:
     *
     *  - **In-sync**     (clean, hash matches)   → no-op
     *  - **Remote ahead** (clean, hash differs)  → pull → overwrite local
     *  - **Local ahead**  (dirty, no diverge)    → push → pull-to-confirm
     *  - **Diverged**     (dirty + remote moved) → push (server merges) → pull result
     *
     * Concurrent calls serialize via [syncMutex]; the second caller sees any
     * dirty state accumulated while the first was running.
     *
     * On network or server errors [syncStatus] is set to [SyncStatus.Error].
     * The dirty flag and last hash are only updated on success, so the next
     * sync call will retry cleanly.
     */
    suspend fun sync(trigger: SyncTrigger) {
        val config = settingsRepository.serverConfig.value ?: return

        syncMutex.withLock {
            val startMs = System.currentTimeMillis()
            val client = WebDavClient(config)
            var bytesDown = 0L
            var bytesUp = 0L

            try {
                if (!localDirty || !dbFile.exists()) {
                    // ── Clean path: check whether remote has advanced ────────────
                    _syncStatus.value = SyncStatus.Pulling
                    val remoteBytes = client.pull()
                    bytesDown = remoteBytes.size.toLong()
                    val remoteHash = remoteBytes.sha256Hex()

                    if (remoteHash != lastSyncedHash) {
                        writeAtomically(remoteBytes)
                        prefs.edit().putString(KEY_LAST_HASH, remoteHash).apply()
                        notifyFileChanged()
                        log(trigger, SyncType.PULL, SyncOutcome.SUCCESS, bytesDown, 0L, startMs)
                    } else {
                        log(trigger, SyncType.PULL, SyncOutcome.NO_CHANGE, 0L, 0L, startMs)
                    }

                } else {
                    // ── Dirty path: push → pull ──────────────────────────────────
                    _syncStatus.value = SyncStatus.Pushing
                    val localBytes = dbFile.readBytes()
                    client.push(localBytes)
                    bytesUp = localBytes.size.toLong()
                    // If push succeeds but the following pull throws, local_dirty stays
                    // true and last_synced_hash is unchanged → next sync retries full cycle.

                    _syncStatus.value = SyncStatus.Pulling
                    val remoteBytes = client.pull()
                    bytesDown = remoteBytes.size.toLong()
                    val remoteHash = remoteBytes.sha256Hex()

                    val isMerged = remoteHash != localBytes.sha256Hex()

                    writeAtomically(remoteBytes)
                    prefs.edit()
                        .putString(KEY_LAST_HASH, remoteHash)
                        .putBoolean(KEY_LOCAL_DIRTY, false)
                        .apply()
                    notifyFileChanged()

                    val outcome = if (isMerged) SyncOutcome.MERGED else SyncOutcome.SUCCESS
                    log(trigger, SyncType.PUSH_PULL, outcome, bytesDown, bytesUp, startMs)
                }

                consecutiveFailures = 0
                notifier.onSuccess()
                _syncStatus.value = SyncStatus.Idle

            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                _syncStatus.value = SyncStatus.Error(msg)
                val type = if (bytesUp > 0) SyncType.PUSH_PULL else SyncType.PULL
                log(trigger, type, SyncOutcome.FAILURE, bytesDown, bytesUp, startMs, msg)
                consecutiveFailures++
                notifier.onFailure(consecutiveFailures, msg)
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

    private fun writeAtomically(bytes: ByteArray) {
        val tmp = File(dbFile.parentFile, "${dbFile.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dbFile)) {
            dbFile.writeBytes(bytes)
            tmp.delete()
        }
    }

    private fun notifyFileChanged() {
        val uri = DocumentsContract.buildDocumentUri(DOCUMENTS_AUTHORITY, DB_DOC_ID)
        context.contentResolver.notifyChange(uri, null)
    }

    companion object {
        const val DOCUMENTS_AUTHORITY = "ax.nd.kdbxgit.android.documents"
        const val DB_DOC_ID           = "database.kdbx"

        private const val DB_FILENAME            = "database.kdbx"
        private const val PREFS_NAME             = "kdbx_git_sync_state"
        private const val KEY_LOCAL_DIRTY        = "local_dirty"
        private const val KEY_LAST_HASH          = "last_synced_hash"
        private const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
    }
}
