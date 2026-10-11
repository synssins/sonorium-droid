package app.sonorium.web

import app.sonorium.net.PinnedTls
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoopbackProxyTest {
    private val cert = HeldCertificate.Builder().commonName("sonorium.local").ecdsa256().build()
    private val apiServer = MockWebServer()
    private val streamServer = MockWebServer()
    private lateinit var proxy: LoopbackProxy
    private var unlinked = 0
    private val page = OkHttpClient()

    @Before
    fun start() {
        apiServer.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        apiServer.start()
        streamServer.start()
        proxy = LoopbackProxy(
            api = PinnedTls.pinnedClient(PinnedTls.baseClient(), PinnedTls.fingerprintOf(cert.certificate)),
            apiBase = apiServer.url("/"),
            streams = OkHttpClient(),
            streamBase = streamServer.url("/"),
            token = "sd_token",
            secret = "s3cret",
            onUnlinked = { unlinked++ },
        ).also { it.start() }
    }

    @After
    fun stop() {
        proxy.stop()
        apiServer.close()
        streamServer.close()
    }

    private fun get(path: String, secret: String? = "s3cret") = Request.Builder().url(proxy.origin + path).apply {
        secret?.let { header("Cookie", "other=1; ${LoopbackProxy.COOKIE}=$it") }
    }

    @Test
    fun refusesRequestsWithoutTheSecret() {
        page.newCall(get("/api/sessions", secret = null).build()).execute().use { assertEquals(403, it.code) }
        page.newCall(get("/api/sessions", secret = "wrong").build()).execute().use { assertEquals(403, it.code) }
        assertEquals(0, apiServer.requestCount)
    }

    @Test
    fun forwardsApiCallsWithTheTokenAndWithoutCookies() {
        apiServer.enqueue(
            MockResponse.Builder().body("""[{"id":"7c18b15c"}]""").addHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "sonorium_session=x").build(),
        )
        page.newCall(get("/api/sessions?x=1").header("Origin", proxy.origin).build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("""[{"id":"7c18b15c"}]""", it.body.string())
            assertNull(it.header("Set-Cookie"))
        }
        val seen = apiServer.takeRequest()
        assertEquals("/api/sessions?x=1", seen.target)
        assertEquals("Bearer sd_token", seen.headers["Authorization"])
        assertNull(seen.headers["Cookie"])
        assertNull(seen.headers["Origin"])
    }

    @Test
    fun forwardsBodies() {
        apiServer.enqueue(MockResponse.Builder().code(201).body("{}").build())
        val body = """{"volume":40}""".toRequestBody("application/json".toMediaType())
        page.newCall(get("/api/sessions/7c18b15c/volume").post(body).build()).execute().use { assertEquals(201, it.code) }
        val seen = apiServer.takeRequest()
        assertEquals("POST", seen.method)
        assertEquals("""{"volume":40}""", seen.body!!.utf8())
        assertTrue(seen.headers["Content-Type"]!!.startsWith("application/json"))
    }

    @Test
    fun sendsStreamsToTheHttpPortWithoutTheToken() {
        streamServer.enqueue(MockResponse.Builder().body("mp3").addHeader("Content-Type", "audio/mpeg").build())
        page.newCall(get("/stream/channel1?local=1").build()).execute().use {
            assertEquals("audio/mpeg", it.header("Content-Type"))
            assertEquals("mp3", it.body.string())
        }
        val seen = streamServer.takeRequest()
        assertEquals("/stream/channel1?local=1", seen.target)
        assertNull(seen.headers["Authorization"])
        assertEquals(0, apiServer.requestCount)
    }

    @Test
    fun reportsUnlinkedTokens() {
        apiServer.enqueue(MockResponse.Builder().code(401).body("""{"detail":"Link it again.","pair": true}""").build())
        page.newCall(get("/api/auth/me").build()).execute().use {
            assertEquals(401, it.code)
            assertTrue(it.body.string().contains("pair"))
        }
        assertEquals(1, unlinked)

        apiServer.enqueue(MockResponse.Builder().code(401).body("""{"detail":"Wrong code"}""").build())
        page.newCall(get("/api/pair").build()).execute().use { assertFalse(it.isSuccessful) }
        assertEquals(1, unlinked)
    }
}
