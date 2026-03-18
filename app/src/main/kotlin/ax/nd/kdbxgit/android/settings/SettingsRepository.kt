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

    private val _pushEndpoint = MutableStateFlow(prefs.getString(KEY_PUSH_ENDPOINT, null))
    val pushEndpoint: StateFlow<String?> = _pushEndpoint.asStateFlow()

    /** Base64url-encoded P-256 public key from the UP library (Web Push `p256dh`). */
    val pushP256dh: String? get() = prefs.getString(KEY_PUSH_P256DH, null)
    /** Base64url-encoded auth secret from the UP library (Web Push `auth`). */
    val pushAuth: String?   get() = prefs.getString(KEY_PUSH_AUTH,   null)

    private val _pollIntervalMinutes = MutableStateFlow(
        prefs.getLong(KEY_POLL_INTERVAL_MINUTES, DEFAULT_POLL_INTERVAL_MINUTES)
    )
    val pollIntervalMinutes: StateFlow<Long> = _pollIntervalMinutes.asStateFlow()

    private fun loadConfig(): ServerConfig? {
        val url      = prefs.getString(KEY_SERVER_URL,     null) ?: return null
        val clientId = prefs.getString(KEY_CLIENT_ID,      null) ?: return null
        val password = prefs.getString(KEY_PASSWORD,       null) ?: return null
        val caCert   = prefs.getString(KEY_CUSTOM_CA_CERT, null)
        return ServerConfig(url, clientId, password, caCert)
    }

    fun save(
        serverUrl: String,
        clientId: String,
        password: String,
        customCaCertPem: String? = null,
    ) {
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_CLIENT_ID,  clientId)
            .putString(KEY_PASSWORD,   password)
            .apply {
                if (customCaCertPem != null) putString(KEY_CUSTOM_CA_CERT, customCaCertPem)
                else remove(KEY_CUSTOM_CA_CERT)
            }
            .apply()
        _serverConfig.value = loadConfig()
    }

    fun savePollInterval(minutes: Long) {
        prefs.edit().putLong(KEY_POLL_INTERVAL_MINUTES, minutes).apply()
        _pollIntervalMinutes.value = minutes
    }

    fun savePushEndpoint(endpoint: String, p256dh: String?, auth: String?) {
        prefs.edit()
            .putString(KEY_PUSH_ENDPOINT, endpoint)
            .apply {
                if (p256dh != null) putString(KEY_PUSH_P256DH, p256dh) else remove(KEY_PUSH_P256DH)
                if (auth   != null) putString(KEY_PUSH_AUTH,   auth)   else remove(KEY_PUSH_AUTH)
            }
            .apply()
        _pushEndpoint.value = endpoint
    }

    fun clearPushEndpoint() {
        prefs.edit()
            .remove(KEY_PUSH_ENDPOINT)
            .remove(KEY_PUSH_P256DH)
            .remove(KEY_PUSH_AUTH)
            .apply()
        _pushEndpoint.value = null
    }

    companion object {
        private const val PREFS_FILE         = "kdbx_git_settings"
        private const val KEY_SERVER_URL     = "server_url"
        private const val KEY_CLIENT_ID      = "client_id"
        private const val KEY_PASSWORD       = "password"
        private const val KEY_CUSTOM_CA_CERT = "custom_ca_cert"
        private const val KEY_PUSH_ENDPOINT          = "push_endpoint"
        private const val KEY_PUSH_P256DH            = "push_p256dh"
        private const val KEY_PUSH_AUTH              = "push_auth"
        private const val KEY_POLL_INTERVAL_MINUTES  = "poll_interval_minutes"
        const val DEFAULT_POLL_INTERVAL_MINUTES      = 15L
    }
}
