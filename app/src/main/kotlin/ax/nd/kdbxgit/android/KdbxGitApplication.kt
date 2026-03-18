package ax.nd.kdbxgit.android

import android.app.Application
import ax.nd.kdbxgit.android.settings.SettingsRepository

class KdbxGitApplication : Application() {

    val settingsRepository by lazy { SettingsRepository(this) }
}
