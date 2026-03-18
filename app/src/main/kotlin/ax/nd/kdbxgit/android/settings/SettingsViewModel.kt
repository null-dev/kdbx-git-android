package ax.nd.kdbxgit.android.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import ax.nd.kdbxgit.android.KdbxGitApplication
import ax.nd.kdbxgit.android.sync.SyncWorker
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as KdbxGitApplication).settingsRepository

    val serverConfig: StateFlow<ServerConfig?> = repository.serverConfig

    fun save(serverUrl: String, clientId: String, username: String, password: String) {
        repository.save(serverUrl.trim(), clientId.trim(), username.trim(), password)
        SyncWorker.schedulePeriodicSync(getApplication())
    }
}
