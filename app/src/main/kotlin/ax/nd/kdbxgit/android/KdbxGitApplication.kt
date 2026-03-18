package ax.nd.kdbxgit.android

import android.app.Application
import ax.nd.kdbxgit.android.settings.SettingsRepository
import ax.nd.kdbxgit.android.sync.SyncRepository

class KdbxGitApplication : Application() {

    val settingsRepository by lazy { SettingsRepository(this) }

    val database by lazy { AppDatabase.getInstance(this) }

    val syncRepository by lazy { SyncRepository(this, settingsRepository, database.syncLogDao()) }
}
