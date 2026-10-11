package app.sonorium.net

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Trust for Sonorium's self-signed certificate (APP_API.md 2.1): the app
 * ignores names and the CA chain and checks only the SHA-256 of the leaf
 * certificate's DER bytes. Hostname checks are off only on these clients.
 */
object PinnedTls {
    fun fingerprintOf(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).toHex()

    /** A client that talks only to the server whose certificate has [fingerprint]. */
    fun pinnedClient(base: OkHttpClient, fingerprint: String): OkHttpClient =
        base.withTrust(PinnedTrustManager(fingerprint))

    /**
     * A client for the first contact with a typed address: it accepts any
     * certificate and records its fingerprint so the user can compare the
     * security words. Never send a token or password on it until the user
     * has confirmed; afterwards use [pinnedClient] with the recorded value.
     */
    fun recordingClient(base: OkHttpClient): Pair<OkHttpClient, RecordingTrustManager> {
        val tm = RecordingTrustManager()
        return base.withTrust(tm) to tm
    }

    private fun OkHttpClient.withTrust(tm: X509TrustManager): OkHttpClient {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        return newBuilder()
            .sslSocketFactory(context.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /** The shared base client; pinned clients derive from it and share its pool. */
    fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

class PinnedTrustManager(fingerprint: String) : X509TrustManager {
    private val expected = fingerprint.lowercase()

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("No certificate")
        val actual = PinnedTls.fingerprintOf(leaf)
        if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) {
            throw CertificateMismatchException(expected, actual)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class RecordingTrustManager : X509TrustManager {
    @Volatile
    var fingerprint: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("No certificate")
        fingerprint = PinnedTls.fingerprintOf(leaf)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** The server answered with a different certificate than the one pinned. */
class CertificateMismatchException(val expected: String, val actual: String) :
    CertificateException("Certificate fingerprint does not match the linked server")

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
