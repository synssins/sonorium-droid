package app.sonorium

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.audio.PcmDecoder
import app.sonorium.engine.PhoneEngine
import app.sonorium.engine.ThemePrep
import app.sonorium.local.PackageRules
import app.sonorium.local.ThemeDownloader
import app.sonorium.local.ThemeStore
import app.sonorium.net.PinnedTls
import app.sonorium.net.SonoriumApi
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A theme downloaded from the linked server (theme packages, APP_API.md 10): every file checked
 * against its sha256, resumed after a cut, then mixed by the engine on the phone with the server's
 * playback values. Runs after EndToEndTest has linked the CI server.
 */
@RunWith(AndroidJUnit4::class)
class PackageDownloadTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as SonoriumApp

    @Test
    fun downloadsAThemeAndPlaysItOnThePhone() = runBlocking {
        val record = app.servers.servers.first().firstOrNull { !it.device }
        assumeNotNull("No linked server (EndToEndTest links one)", record)
        record!!
        val token = app.servers.token(record.id)
        assertNotNull("No token for ${record.displayName}", token)
        val pinned = PinnedTls.pinnedClient(app.baseClient, record.fingerprint)
        val reached = app.linker.reach(pinned, record.hosts, record.httpsPort)
        assertNotNull("Can't reach the server", reached)
        val api = SonoriumApi(pinned, SonoriumApi.baseUrl(reached!!.host, record.httpsPort), token)

        val pkg = api.themePackages().filter { it.redistributable != false && it.trackCount > 0 }.minByOrNull { it.sizeBytes }
        assertNotNull("The server lists no theme packages", pkg)
        pkg!!
        val dir = File(context.cacheDir, "package-test").apply { deleteRecursively(); mkdirs() }
        val downloader = ThemeDownloader(context, app.themeStore)
        // Half of the first file first, as if the connection dropped; the rest must resume from there
        val full = api.themePackage(pkg.themeId)
        val first = full.files.maxBy { it.size }
        File(dir, first.path).apply { parentFile?.mkdirs() }.outputStream().use { out ->
            val half = File(context.cacheDir, "half").apply { delete() }
            api.downloadThemeFile(pkg.themeId, first, half) {}
            out.write(half.readBytes().copyOf((first.size / 2).toInt()))
        }
        var lastDone = 0L
        val got = downloader.fetch(api, pkg.themeId, dir) { done, _ -> lastDone = done }
        assertEquals(pkg.contentHash, got.contentHash)
        assertEquals(PackageRules.contentHash(got.files), got.contentHash)
        assertEquals(got.files.sumOf { it.size }, lastDone)
        File(dir, ThemeStore.MARKER).writeText(downloader.marker(got))

        // Decode as ThemePrep does, then mix 2 s on the phone
        val pcm = File(context.cacheDir, "package-test-pcm").apply { deleteRecursively(); mkdirs() }
        got.files.filter { ThemeStore.isAudio(it.path) }.forEach { f ->
            PcmDecoder.decodeToWav(File(dir, f.path), File(pcm, f.path.substringBeforeLast('.') + ".wav"))
        }
        PhoneEngine.open(context, ThemePrep.Prepared(dir, pcm), null, 1f)
        val buf = ByteArray(PhoneEngine.BYTES_PER_SECOND * 2)
        PhoneEngine.render(buf, 0, buf.size)
        PhoneEngine.close()
        val samples = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var peak = 0
        while (samples.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(samples.get().toInt()))
        assertTrue("“${pkg.name}” from the server rendered silence on the phone (peak $peak)", peak > 100)
    }
}
