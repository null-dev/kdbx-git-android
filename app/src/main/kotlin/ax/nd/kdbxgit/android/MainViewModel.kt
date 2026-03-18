package ax.nd.kdbxgit.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ax.nd.kdbxgit.android.sync.SyncLogEntry
import ax.nd.kdbxgit.android.sync.SyncStatus
import ax.nd.kdbxgit.android.sync.SyncTrigger
import ax.nd.kdbxgit.android.sync.SyncWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KdbxGitApplication

    val syncStatus: StateFlow<SyncStatus> = app.syncRepository.syncStatus

    val syncLog: StateFlow<List<SyncLogEntry>> =
        app.database.syncLogDao().getRecentEntries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True while a sync is actively running (Pulling or Pushing). */
    val isSyncing: Boolean
        get() = syncStatus.value.let { it is SyncStatus.Pulling || it is SyncStatus.Pushing }

    fun syncNow() {
        SyncWorker.enqueueSyncNow(getApplication(), SyncTrigger.MANUAL)
    }
}
