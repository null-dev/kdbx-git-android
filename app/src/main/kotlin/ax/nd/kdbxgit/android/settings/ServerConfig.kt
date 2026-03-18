package ax.nd.kdbxgit.android.settings

data class ServerConfig(
    val serverUrl: String,
    val clientId: String,
    val password: String,
    /** PEM-encoded CA certificate for servers using a self-signed or private CA. Null = use system trust store only. */
    val customCaCertPem: String? = null,
) {
    /** The server uses the client ID as the HTTP Basic Auth username. */
    val username: String get() = clientId
}
