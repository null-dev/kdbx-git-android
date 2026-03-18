package ax.nd.kdbxgit.android.sync

import android.content.Context
import android.provider.DocumentsContract
import ax.nd.kdbxgit.android.settings.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class SyncRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
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

    // Ensures at most one sync runs at a time. A second caller will suspend until
    // the first finishes, then run with the freshest dirty/hash state.
    private val syncMutex = Mutex()

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
     *
     * @param trigger What initiated this sync (used by the sync log in Phase 7).
     */
    suspend fun sync(trigger: SyncTrigger) {
        val config = settingsRepository.serverConfig.value ?: return

        syncMutex.withLock {
            val client = WebDavClient(config)
            try {
                if (!localDirty || !dbFile.exists()) {
                    // ── Clean path: check whether remote has advanced ────────────
                    _syncStatus.value = SyncStatus.Pulling
                    val remoteBytes = client.pull()
                    val remoteHash  = remoteBytes.sha256Hex()

                    if (remoteHash != lastSyncedHash) {
                        // Remote ahead (or first launch) — overwrite local.
                        writeAtomically(remoteBytes)
                        prefs.edit().putString(KEY_LAST_HASH, remoteHash).apply()
                        notifyFileChanged()
                        // TODO Phase 7: log trigger / PULL / SUCCESS
                    }
                    // else: in-sync — TODO Phase 7: log trigger / PULL / NO_CHANGE

                } else {
                    // ── Dirty path: push → pull (handles both "local ahead" and "diverged") ──
                    _syncStatus.value = SyncStatus.Pushing
                    val localBytes = dbFile.readBytes()
                    client.push(localBytes)
                    // NOTE: if push succeeds but the following pull throws, local_dirty
                    // stays true and last_synced_hash is unchanged, so the next sync
                    // will retry the full push → pull cycle (ROADMAP scenario 6).

                    _syncStatus.value = SyncStatus.Pulling
                    val remoteBytes = client.pull()
                    val remoteHash  = remoteBytes.sha256Hex()

                    // Detect server-side merge: remote differs from what we uploaded.
                    @Suppress("UnnecessaryVariable")
                    val isMerged = remoteHash != localBytes.sha256Hex()

                    writeAtomically(remoteBytes)
                    prefs.edit()
                        .putString(KEY_LAST_HASH, remoteHash)
                        .putBoolean(KEY_LOCAL_DIRTY, false)
                        .apply()
                    notifyFileChanged()
                    // TODO Phase 7: log trigger / PUSH_PULL / (if isMerged MERGED else SUCCESS)
                }

                _syncStatus.value = SyncStatus.Idle

            } catch (e: Exception) {
                _syncStatus.value = SyncStatus.Error(e.message ?: "Unknown error")
                // TODO Phase 7: log trigger / … / FAILURE
                // TODO Phase 8: exponential back-off; user notification after 3 failures
            }
        }
    }

    /**
     * Writes [bytes] to a temp file beside [dbFile] then atomically renames it.
     * This ensures KeePass clients reading concurrently never see a partial file.
     */
    private fun writeAtomically(bytes: ByteArray) {
        val tmp = File(dbFile.parentFile, "${dbFile.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dbFile)) {
            // renameTo is atomic on Linux as long as src and dst are on the same
            // filesystem (they always are here). The fallback is non-atomic but
            // should never be reached in practice.
            dbFile.writeBytes(bytes)
            tmp.delete()
        }
    }

    /**
     * Pings ContentResolver so any app holding a URI to [DB_DOC_ID] (e.g. KeePassDX)
     * is notified to reload the file.
     */
    private fun notifyFileChanged() {
        val uri = DocumentsContract.buildDocumentUri(DOCUMENTS_AUTHORITY, DB_DOC_ID)
        context.contentResolver.notifyChange(uri, null)
    }

    companion object {
        /** SAF authority declared in AndroidManifest.xml. */
        const val DOCUMENTS_AUTHORITY = "ax.nd.kdbxgit.android.documents"

        /** Document ID of the single exposed KDBX file. */
        const val DB_DOC_ID = "database.kdbx"

        private const val DB_FILENAME   = "database.kdbx"
        private const val PREFS_NAME    = "kdbx_git_sync_state"
        private const val KEY_LOCAL_DIRTY = "local_dirty"
        private const val KEY_LAST_HASH   = "last_synced_hash"
    }
}
