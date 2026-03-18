package ax.nd.kdbxgit.android.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _serverConfig = MutableStateFlow(loadConfig())
    val serverConfig: StateFlow<ServerConfig?> = _serverConfig.asStateFlow()

    private fun loadConfig(): ServerConfig? {
        val url      = prefs.getString(KEY_SERVER_URL, null) ?: return null
        val clientId = prefs.getString(KEY_CLIENT_ID,  null) ?: return null
        val username = prefs.getString(KEY_USERNAME,   null) ?: return null
        val password = prefs.getString(KEY_PASSWORD,   null) ?: return null
        return ServerConfig(url, clientId, username, password)
    }

    fun save(serverUrl: String, clientId: String, username: String, password: String) {
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_CLIENT_ID,  clientId)
            .putString(KEY_USERNAME,   username)
            .putString(KEY_PASSWORD,   password)
            .apply()
        _serverConfig.value = loadConfig()
    }

    companion object {
        private const val PREFS_FILE      = "kdbx_git_settings"
        private const val KEY_SERVER_URL  = "server_url"
        private const val KEY_CLIENT_ID   = "client_id"
        private const val KEY_USERNAME    = "username"
        private const val KEY_PASSWORD    = "password"
    }
}
