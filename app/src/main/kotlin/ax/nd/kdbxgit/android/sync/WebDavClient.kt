package ax.nd.kdbxgit.android.sync

import android.util.Base64
import ax.nd.kdbxgit.android.settings.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class WebDavClient(private val config: ServerConfig) {

    private val http = OkHttpClient.Builder()
        .addInterceptor(BasicAuthInterceptor(config.username, config.password))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .apply {
            config.customCaCertPem?.let { pem ->
                val tm = buildTrustManagerWithCustomCa(pem)
                val sslContext = SSLContext.getInstance("TLS").also {
                    it.init(null, arrayOf(tm), null)
                }
                sslSocketFactory(sslContext.socketFactory, tm)
            }
        }
        .build()

    /**
     * Downloads the KDBX file from the server and returns its raw bytes.
     * Throws [WebDavException] on a non-2xx response.
     */
    suspend fun pull(): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(dbUrl())
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            response.requireSuccess()
            response.body.bytes()
        }
    }

    /**
     * Uploads [bytes] to the server as the KDBX file.
     * Throws [WebDavException] on a non-2xx response.
     */
    suspend fun push(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val body = bytes.toRequestBody(KDBX_MEDIA_TYPE)
        val request = Request.Builder()
            .url(dbUrl())
            .put(body)
            .build()
        http.newCall(request).execute().use { response ->
            response.requireSuccess()
        }
    }

    /**
     * Registers (or refreshes) a UnifiedPush [endpoint] URL with the server so the server
     * can deliver wakeup notifications when `main` advances.
     * Throws [WebDavException] on a non-2xx response.
     */
    suspend fun registerPushEndpoint(endpoint: String): Unit = withContext(Dispatchers.IO) {
        val body = """{"endpoint":"$endpoint"}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(pushEndpointUrl())
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            response.requireSuccess()
        }
    }

    /**
     * Removes this client's push endpoint registration from the server.
     * 404 is treated as success (idempotent).
     * Throws [WebDavException] on other non-2xx responses.
     */
    suspend fun deletePushEndpoint(): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(pushEndpointUrl())
            .delete()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw WebDavException(response.code, response.message)
            }
        }
    }

    private fun dbUrl(): String {
        val base = config.serverUrl.trimEnd('/')
        return "$base/dav/${config.clientId}/database.kdbx"
    }

    private fun pushEndpointUrl(): String {
        val base = config.serverUrl.trimEnd('/')
        return "$base/push/${config.clientId}/endpoint"
    }

    private fun Response.requireSuccess() {
        if (!isSuccessful) throw WebDavException(code, message)
    }

    // ── SSL helpers ───────────────────────────────────────────────────────

    /**
     * Builds an [X509TrustManager] that trusts both the Android system CA store and
     * the single custom CA in [pem]. This allows connecting to servers using a
     * private/self-signed CA without disabling certificate validation entirely.
     */
    private fun buildTrustManagerWithCustomCa(pem: String): X509TrustManager {
        val customCert = parsePemCert(pem)

        // System trust store (e.g. well-known public CAs).
        val systemTm = buildTrustManager(KeyStore.getInstance("AndroidCAStore").also {
            it.load(null)
        })

        // Single-entry keystore containing just the custom CA.
        val customKs = KeyStore.getInstance(KeyStore.getDefaultType()).also { ks ->
            ks.load(null, null)
            ks.setCertificateEntry("custom_ca", customCert)
        }
        val customTm = buildTrustManager(customKs)

        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                try {
                    systemTm.checkServerTrusted(chain, authType)
                } catch (_: Exception) {
                    // Fall back to the custom CA — throws its own exception if invalid.
                    customTm.checkServerTrusted(chain, authType)
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                systemTm.acceptedIssuers + customTm.acceptedIssuers
        }
    }

    private fun buildTrustManager(keyStore: KeyStore): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun parsePemCert(pem: String): X509Certificate {
        val stripped = pem
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s+".toRegex(), "")
        val decoded = Base64.decode(stripped, Base64.DEFAULT)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(decoded.inputStream()) as X509Certificate
    }

    // ── Inner classes ─────────────────────────────────────────────────────

    private class BasicAuthInterceptor(
        private val username: String,
        private val password: String,
    ) : Interceptor {
        private val credentials = Credentials.basic(username, password)

        override fun intercept(chain: Interceptor.Chain): Response =
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", credentials)
                    .build()
            )
    }

    companion object {
        private val KDBX_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}
