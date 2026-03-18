package ax.nd.kdbxgit.android.settings

data class ServerConfig(
    val serverUrl: String,
    val clientId: String,
    val username: String,
    val password: String,
    /** PEM-encoded CA certificate for servers using a self-signed or private CA. Null = use system trust store only. */
    val customCaCertPem: String? = null,
)
