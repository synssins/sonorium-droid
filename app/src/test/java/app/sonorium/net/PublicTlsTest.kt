package app.sonorium.net

import app.sonorium.link.LinkOutcome
import app.sonorium.link.LinkUri
import app.sonorium.link.Linker
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

/**
 * A Sonorium behind a reverse proxy with a public authority's certificate (stood in for by a test authority the
 * client trusts): trusted the normal way, nothing pinned (its certificate is renewed every few months), while a
 * self-signed Sonorium stays pinned.
 */
class PublicTlsTest {
    private val authority = HeldCertificate.Builder().certificateAuthority(0).commonName("Test CA").ecdsa256().build()
    private val proxyCert = HeldCertificate.Builder().signedBy(authority).addSubjectAlternativeName("localhost").ecdsa256().build()
    private val server = MockWebServer()
    /** The device's normal trust, with the test authority in it. */
    private val base = PinnedTls.baseClient().newBuilder().apply {
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(authority.certificate).build()
        sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
    }.build()
    /** Sonorium's own self-signed certificate, as /api/info and a link code name it (not the proxy's). */
    private val sonoriumFingerprint = PinnedTls.fingerprintOf(HeldCertificate.Builder().commonName("sonorium").ecdsa256().build().certificate)

    @Before
    fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(proxyCert, authority.certificate).build().sslSocketFactory())
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun info() = MockResponse.Builder().body(
        """{"id":"$SERVER_ID","name":"Sonorium","version":"1.7.4","api":1,"install":"docker",
           "ports":{"http":8008,"https":8808},"login_required":true,"pair":true,"plugins":["users"]}""",
    ).build()

    @Test
    fun aPublicCertificateIsTrustedWithoutPinning() = runTest {
        server.enqueue(info())
        val api = SonoriumApi(PinnedTls.pinnedClient(base, PinnedTls.PUBLIC), SonoriumApi.baseUrl("localhost", server.port))
        assertEquals(SERVER_ID, api.info().id)
    }

    @Test
    fun aLinkCodeFallsBackToThePublicCertificate() = runTest {
        server.enqueue(info()) // pinned try: the handshake fails before any request
        server.enqueue(
            MockResponse.Builder().body(
                """{"device_id":"t_1","token":"sd_secret","user":{"id":"u_1","username":"admin","role":"admin","role_label":"Admin"},"server_id":"$SERVER_ID"}""",
            ).build(),
        )
        val code = (LinkUri.parse(
            "https://sonorium.app/link#v=1&id=$SERVER_ID&n=Sonorium&h=localhost&p=8008&s=${server.port}&fp=$sonoriumFingerprint&c=83KS6GG3&u=admin",
        ) as LinkUri.Result.Ok).link
        val outcome = Linker(base, Linker.DeviceInfo("Pixel", "Pixel 8")).link(code)
        outcome as LinkOutcome.Linked
        // Stored as "trusted the normal way": nothing pinned that would break when the proxy renews its certificate
        assertEquals(PinnedTls.PUBLIC, outcome.record.fingerprint)
        assertTrue(outcome.record.publicTls)
    }

    @Test
    fun withoutTheAuthorityItIsNotTrusted() = runTest {
        server.enqueue(info())
        val plain = PinnedTls.baseClient()
        val result = runCatching { SonoriumApi(plain, SonoriumApi.baseUrl("localhost", server.port)).info() }
        assertTrue(result.isFailure)
    }

    private companion object {
        const val SERVER_ID = "f4c26f39-3512-497a-8703-62875a3d4736"
    }
}
