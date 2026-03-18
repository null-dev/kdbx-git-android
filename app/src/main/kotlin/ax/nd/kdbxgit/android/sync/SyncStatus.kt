package ax.nd.kdbxgit.android.sync

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data object Pulling : SyncStatus()
    data object Pushing : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}
