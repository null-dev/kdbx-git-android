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
            val localBytes = fileStore.readBytesOrNull()
            val localHash = localBytes?.sha256Hex()
            val confirmedHash = stateStore.lastSyncedHash
            val dirtyAtStart = stateStore.localDirty
            val hasDirtyLocalBytes = dirtyAtStart && localBytes != null && localHash != confirmedHash
            val staleDirty = dirtyAtStart && !hasDirtyLocalBytes

            if (!hasDirtyLocalBytes) {
                onPhase(SyncEnginePhase.PULLING)
                val remoteBytes = client.pull()
                val remoteHash = remoteBytes.sha256Hex()

                if (remoteHash != stateStore.lastSyncedHash) {
                    bytesDown = remoteBytes.size.toLong()
                    val liveHash = fileStore.hashOrNull()
                    if (liveHash == localHash) {
                        fileStore.replaceWith(remoteBytes)
                        stateStore.lastSyncedHash = remoteHash
                        documentChanged = true
                        if (staleDirty && fileStore.hashOrNull() == remoteHash) {
                            stateStore.localDirty = false
                        }
                    } else {
                        stateStore.lastSyncedHash = remoteHash
                    }
                    SyncEngineResult(
                        type = SyncType.PULL,
                        outcome = SyncOutcome.SUCCESS,
                        bytesDown = bytesDown,
                        bytesUp = 0L,
                        documentChanged = documentChanged,
                    )
                } else {
                    if (staleDirty && fileStore.hashOrNull() == remoteHash) {
                        stateStore.localDirty = false
                    }
                    SyncEngineResult(
                        type = SyncType.PULL,
                        outcome = SyncOutcome.NO_CHANGE,
                        bytesDown = 0L,
                        bytesUp = 0L,
                        documentChanged = false,
                    )
                }
            } else {
                onPhase(SyncEnginePhase.PULLING)
                val remoteBytes = client.pull()
                val remoteHash = remoteBytes.sha256Hex()

                if (remoteHash == localHash) {
                    stateStore.lastSyncedHash = remoteHash
                    val liveHash = fileStore.hashOrNull()
                    if (liveHash == localHash) {
                        stateStore.localDirty = false
                    }
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

                val liveHash = fileStore.hashOrNull()
                if (liveHash == localHash) {
                    fileStore.replaceWith(confirmedBytes)
                    documentChanged = true
                    stateStore.localDirty = false
                }
                stateStore.lastSyncedHash = confirmedRemoteHash

                SyncEngineResult(
                    type = SyncType.PUSH_PULL,
                    outcome = outcome,
                    bytesDown = bytesDown,
                    bytesUp = bytesUp,
                    documentChanged = documentChanged,
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
