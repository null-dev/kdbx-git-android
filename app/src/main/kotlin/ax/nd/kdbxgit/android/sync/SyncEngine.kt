package ax.nd.kdbxgit.android.sync

import kotlinx.coroutines.CancellationException

data class SyncEngineResult(
    val type: SyncType,
    val outcome: SyncOutcome,
    val bytesDown: Long,
    val bytesUp: Long,
    val documentChanged: Boolean,
    val errorMessage: String? = null,
)

enum class SyncEnginePhase { PULLING, PUSHING }

class SyncEngine(
    private val fileStore: DatabaseFileStore,
    private val stateStore: SyncStateStore,
) {

    suspend fun sync(
        client: RemoteDatabaseClient,
        onPhase: (SyncEnginePhase) -> Unit = {},
    ): SyncEngineResult {
        var bytesDown = 0L
        var bytesUp = 0L
        var documentChanged = false

        return try {
            // Snapshot once: this is the local version this sync attempt may push
            // or conditionally replace. Later local writes must win over this run.
            val localSnapshot = fileStore.snapshot()
            val localBytes = localSnapshot.bytes
            val localHash = localSnapshot.hash
            val confirmedHash = stateStore.lastSyncedHash

            // Treat the file hash as authoritative: a mismatch means local bytes
            // need to be uploaded, regardless of how this sync was triggered.
            val localNeedsUpload = localBytes != null && localHash != confirmedHash

            onPhase(SyncEnginePhase.PULLING)
            val remoteBytes = client.pull()
            val remoteHash = remoteBytes.sha256Hex()

            if (localBytes == null) {
                // First sync or local reset: install the remote database if no
                // local writer created one while the pull was in flight.
                bytesDown = remoteBytes.size.toLong()
                documentChanged = fileStore.replaceWithIfCurrent(localHash, remoteBytes)
                stateStore.lastSyncedHash = remoteHash
                return SyncEngineResult(
                    type = SyncType.PULL,
                    outcome = SyncOutcome.SUCCESS,
                    bytesDown = bytesDown,
                    bytesUp = 0L,
                    documentChanged = documentChanged,
                )
            }

            if (localNeedsUpload) {
                if (remoteHash == localHash) {
                    stateStore.lastSyncedHash = remoteHash
                    return SyncEngineResult(
                        type = SyncType.PULL,
                        outcome = SyncOutcome.NO_CHANGE,
                        bytesDown = 0L,
                        bytesUp = 0L,
                        documentChanged = false,
                    )
                }

                onPhase(SyncEnginePhase.PUSHING)
                client.push(localBytes)
                bytesUp = localBytes.size.toLong()

                onPhase(SyncEnginePhase.PULLING)
                val confirmedBytes = client.pull()
                bytesDown = confirmedBytes.size.toLong()
                val confirmedRemoteHash = confirmedBytes.sha256Hex()
                val outcome =
                    if (confirmedRemoteHash == localHash) SyncOutcome.SUCCESS else SyncOutcome.MERGED

                // Pull back the server's post-merge result, but only replace the
                // live file if it is still the snapshot we just uploaded.
                documentChanged = fileStore.replaceWithIfCurrent(localHash, confirmedBytes)
                stateStore.lastSyncedHash = confirmedRemoteHash

                SyncEngineResult(
                    type = SyncType.PUSH_PULL,
                    outcome = outcome,
                    bytesDown = bytesDown,
                    bytesUp = bytesUp,
                    documentChanged = documentChanged,
                )
            } else if (remoteHash != confirmedHash) {
                // Clean local state, remote advanced: apply it only if no local
                // write has appeared since the initial snapshot.
                bytesDown = remoteBytes.size.toLong()
                documentChanged = fileStore.replaceWithIfCurrent(localHash, remoteBytes)
                stateStore.lastSyncedHash = remoteHash
                SyncEngineResult(
                    type = SyncType.PULL,
                    outcome = SyncOutcome.SUCCESS,
                    bytesDown = bytesDown,
                    bytesUp = 0L,
                    documentChanged = documentChanged,
                )
            } else {
                SyncEngineResult(
                    type = SyncType.PULL,
                    outcome = SyncOutcome.NO_CHANGE,
                    bytesDown = 0L,
                    bytesUp = 0L,
                    documentChanged = false,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            SyncEngineResult(
                type = if (bytesUp > 0L) SyncType.PUSH_PULL else SyncType.PULL,
                outcome = SyncOutcome.FAILURE,
                bytesDown = bytesDown,
                bytesUp = bytesUp,
                documentChanged = documentChanged,
                errorMessage = e.message ?: "Unknown error",
            )
        }
    }
}
