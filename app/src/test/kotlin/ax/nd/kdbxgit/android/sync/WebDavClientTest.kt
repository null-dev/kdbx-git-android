package ax.nd.kdbxgit.android.sync

import ax.nd.kdbxgit.android.settings.ServerConfig
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WebDavClient(
            ServerConfig(
                serverUrl = server.url("/").toString().trimEnd('/'),
                clientId  = "test-client",
                username  = "alice",
                password  = "s3cr3t",
            )
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── pull ─────────────────────────────────────────────────────────────

    @Test
    fun `pull returns body bytes on 200`() = runTest {
        val expected = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        server.enqueue(MockResponse().setBody(Buffer().write(expected)).setResponseCode(200))

        val result = client.pull()

        assertArrayEquals(expected, result)
    }

    @Test
    fun `pull sends GET to correct path`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))

        client.pull()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/dav/test-client/database.kdbx", request.path)
    }

    @Test
    fun `pull sends Basic Auth header`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))

        client.pull()

        val authHeader = server.takeRequest().getHeader("Authorization")
        assertEquals(Credentials.basic("alice", "s3cr3t"), authHeader)
    }

    @Test(expected = WebDavException::class)
    fun `pull throws WebDavException on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        client.pull()
    }

    @Test(expected = WebDavException::class)
    fun `pull throws WebDavException on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        client.pull()
    }

    @Test(expected = WebDavException::class)
    fun `pull throws WebDavException on 500`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        client.pull()
    }

    @Test
    fun `pull captures status code in WebDavException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val ex = runCatching { client.pull() }.exceptionOrNull() as? WebDavException
        assertEquals(403, ex?.statusCode)
    }

    // ── push ─────────────────────────────────────────────────────────────

    @Test
    fun `push sends PUT to correct path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        client.push(byteArrayOf(0xAA.toByte()))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/dav/test-client/database.kdbx", request.path)
    }

    @Test
    fun `push transmits exact bytes`() = runTest {
        val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        server.enqueue(MockResponse().setResponseCode(200))

        client.push(payload)

        val body = server.takeRequest().body.readByteArray()
        assertArrayEquals(payload, body)
    }

    @Test
    fun `push sends Basic Auth header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        client.push(byteArrayOf(0x00))

        val authHeader = server.takeRequest().getHeader("Authorization")
        assertEquals(Credentials.basic("alice", "s3cr3t"), authHeader)
    }

    @Test(expected = WebDavException::class)
    fun `push throws WebDavException on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        client.push(byteArrayOf(0x00))
    }

    @Test(expected = WebDavException::class)
    fun `push throws WebDavException on 500`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        client.push(byteArrayOf(0x00))
    }

    // ── URL handling ──────────────────────────────────────────────────────

    @Test
    fun `trailing slash on serverUrl is normalised`() = runTest {
        val clientWithSlash = WebDavClient(
            ServerConfig(
                serverUrl = server.url("/").toString(), // has trailing slash
                clientId  = "test-client",
                username  = "alice",
                password  = "s3cr3t",
            )
        )
        server.enqueue(MockResponse().setBody("ok"))

        clientWithSlash.pull()

        assertEquals("/dav/test-client/database.kdbx", server.takeRequest().path)
    }

    // ── SHA-256 utility ───────────────────────────────────────────────────

    @Test
    fun `sha256 produces correct digest for known input`() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        val hash = byteArrayOf().sha256Hex()
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `sha256 of same bytes is stable`() {
        val input = byteArrayOf(1, 2, 3)
        assertEquals(input.sha256Hex(), input.sha256Hex())
    }

    @Test
    fun `sha256 of different bytes differs`() {
        val a = byteArrayOf(1, 2, 3).sha256Hex()
        val b = byteArrayOf(1, 2, 4).sha256Hex()
        assert(a != b)
    }
}
