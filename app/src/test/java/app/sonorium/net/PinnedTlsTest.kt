package app.sonorium.net

import app.sonorium.link.Linker
import app.sonorium.link.LinkOutcome
import app.sonorium.link.LinkUri
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Pinning and linking against a local HTTPS server with a self-signed certificate, as Sonorium has. */
class PinnedTlsTest {
    private val cert = HeldCertificate.Builder().commonName("sonorium.local").ecdsa256().build()
    private val fingerprint = PinnedTls.fingerprintOf(cert.certificate)
    private val server = MockWebServer()
    private val base = PinnedTls.baseClient()

    @Before
    fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun info(id: String = SERVER_ID) = MockResponse.Builder().body(
        """{"id":"$id","name":"Sonorium","version":"1.6.2","api":1,"install":"docker",
           "ports":{"http":8008,"https":${server.port}},"host":"192.168.1.25","hostname":"sonorium.local",
           "login_required":true,"pair":true,"fingerprint":"$fingerprint","plugins":["users"],"extra":1}""",
    ).build()

    private fun api(client: okhttp3.OkHttpClient) = SonoriumApi(client, SonoriumApi.baseUrl("127.0.0.1", server.port))

    @Test
    fun pinnedClientAcceptsTheRightCertificate() = runTest {
        server.enqueue(info())
        assertEquals(SERVER_ID, api(PinnedTls.pinnedClient(base, fingerprint)).info().id)
    }

    @Test
    fun pinnedClientRefusesAnotherCertificate() = runTest {
        server.enqueue(info())
        val result = runCatching { api(PinnedTls.pinnedClient(base, "00".repeat(32))).info() }
        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun recordingClientReportsTheFingerprint() = runTest {
        server.enqueue(info())
        val (client, recorder) = PinnedTls.recordingClient(base)
        api(client).info()
        assertEquals(fingerprint, recorder.fingerprint)
        assertEquals(listOf("forest", "candy", "award", "fancy").size, SecurityWords.forFingerprint(fingerprint).size)
    }

    @Test
    fun linksWithACode() = runTest {
        server.enqueue(info())
        server.enqueue(
            MockResponse.Builder().body(
                """{"device_id":"t_1","token":"sd_secret","user":{"id":"u_1","username":"admin","role":"admin","role_label":"Admin"},"server_id":"$SERVER_ID"}""",
            ).build(),
        )
        val outcome = Linker(base, Linker.DeviceInfo("Pixel", "Pixel 8")).link(code())
        outcome as LinkOutcome.Linked
        assertEquals("sd_secret", outcome.token)
        assertEquals("127.0.0.1", outcome.record.hosts.first())
        assertTrue("sonorium.local" in outcome.record.hosts)
        assertEquals("admin", outcome.record.username)

        server.takeRequest() // info
        val pair = server.takeRequest()
        assertEquals("/api/pair", pair.url.encodedPath)
        assertTrue(pair.body!!.utf8().contains("\"code\":\"83KS6GG3\""))
    }

    @Test
    fun refusesADifferentServerId() = runTest {
        server.enqueue(info(id = "someone-else"))
        assertEquals(LinkOutcome.WrongServer, Linker(base, Linker.DeviceInfo("P", "P")).link(code()))
    }

    @Test
    fun reportsUnlinkedTokens() = runTest {
        server.enqueue(
            MockResponse.Builder().code(401)
                .body("""{"detail":"This phone is not linked to Sonorium. Link it again.","pair":true}""").build(),
        )
        val error = runCatching { api(PinnedTls.pinnedClient(base, fingerprint)).me() }.exceptionOrNull() as ApiException
        assertTrue(error.unlinked)
        assertEquals("This phone is not linked to Sonorium. Link it again.", error.detail)
    }

    private fun code(): LinkUri = (LinkUri.parse(
        "https://sonorium.app/link#v=1&id=$SERVER_ID&n=Sonorium&h=127.0.0.1&p=8008&s=${server.port}&fp=$fingerprint&c=83KS6GG3&u=admin",
    ) as LinkUri.Result.Ok).link

    private companion object {
        const val SERVER_ID = "f4c26f39-3512-497a-8703-62875a3d4736"
    }
}
