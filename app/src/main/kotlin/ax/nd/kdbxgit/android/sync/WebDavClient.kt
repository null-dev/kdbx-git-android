package ax.nd.kdbxgit.android.sync

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
import java.util.concurrent.TimeUnit

class WebDavClient(private val config: ServerConfig) {

    private val http = OkHttpClient.Builder()
        .addInterceptor(BasicAuthInterceptor(config.username, config.password))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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
            response.body?.bytes() ?: throw WebDavException(-1, "Empty response body")
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

    private fun dbUrl(): String {
        val base = config.serverUrl.trimEnd('/')
        return "$base/dav/${config.clientId}/database.kdbx"
    }

    private fun Response.requireSuccess() {
        if (!isSuccessful) throw WebDavException(code, message)
    }

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
