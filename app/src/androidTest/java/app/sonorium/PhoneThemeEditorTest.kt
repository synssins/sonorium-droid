package app.sonorium

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.audio.PcmDecoder
import app.sonorium.engine.PhoneEngine
import app.sonorium.engine.ThemePrep
import app.sonorium.local.PhoneThemeApi
import app.sonorium.net.ApiException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Theme Editor on a theme on this phone: its requests go through [PhoneThemeApi] (OkHttp
 * interceptor → phone_api.py → sonorium.core.theme_edit) on a prepared copy in the app's files, a change
 * comes back in the next answer and in the theme's own folder, and the playing engine follows it.
 */
@RunWith(AndroidJUnit4::class)
class PhoneThemeEditorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aVolumeChangeRoundTripsAndThePlayingMixFollows() = runBlocking {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val work = File(context.cacheDir, "phone-editor-test").apply { deleteRecursively(); mkdirs() }
        // The theme's own folder (as in the user's themes folder) ...
        val source = File(Python.getInstance().getModule("lab").callAttr("make_test_theme", work.path).toString())
        // ... and its copy laid out as ThemePrep prepares it: settings files, empty stand-ins, decoded audio
        val folder = "phone-editor-test"
        val themeDir = File(PhoneThemeApi.themesRoot(context), folder).apply { deleteRecursively(); mkdirs() }
        val pcmDir = File(PhoneThemeApi.pcmRoot(context), folder).apply { deleteRecursively(); mkdirs() }
        try {
            source.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(source).path
                val out = File(themeDir, rel).apply { parentFile?.mkdirs() }
                if (f.extension == "json") f.copyTo(out, overwrite = true) else {
                    out.createNewFile()
                    PcmDecoder.decodeToWav(f, File(pcmDir, rel.substringBeforeLast('.') + ".wav"))
                }
            }
            val session = PhoneThemeApi.forFolder(context, folder, writeBackTo = source, audioDir = source)
            val api = session.api

            val tracks = api.editorTracks(folder)
            val drone = tracks.first { it.name == "Drone" }
            assertEquals(0.8, drone.volume, 1e-9)
            assertTrue("No length from the decoded audio: ${drone.durationSeconds}", drone.durationSeconds > 5.0)
            assertEquals(listOf("Birds"), api.trackGroups(folder).map { it.name })

            // Volume: saved by theme_edit, answered back, and written to the theme's own folder
            api.setTrack(folder, "Drone", "volume", buildJsonObject { put("volume", 0.4) })
            assertEquals(0.4, api.editorTracks(folder).first { it.name == "Drone" }.volume, 1e-9)
            val saved = JSONObject(File(source, "metadata.json").readText()).getJSONObject("tracks").getJSONObject("Drone")
            assertEquals(0.4, saved.getDouble("volume"), 1e-9)

            // A preset made from the mix, then the editor's other reads
            val created = api.createPreset(folder, "Evening")
            assertEquals("evening", created.presetId)
            assertTrue(api.editorPresets(folder).presets.any { it.id == "evening" })
            assertTrue(File(source, "presets.json").readText().contains("\"evening\""))
            assertTrue(api.sequenceDefaults(folder).holdMax > 0)
            assertTrue((api.json("GET", "settings/playback-values")["values"] as kotlinx.serialization.json.JsonObject).containsKey("group_crossfade_min"))

            // Upload and groups: Core changes the prepared copy, and its file operations reach the theme's folder
            api.uploadTrack(folder, "Hum.wav", File(source, "Drone.wav").readBytes(), "Hums")
            assertTrue(File(source, "Hums/Hum.wav").length() > 1000)
            assertEquals(0L, File(themeDir, "Hums/Hum.wav").length())  // back to a stand-in
            assertTrue(JSONObject(File(source, "metadata.json").readText()).getJSONObject("tracks").has("Hums/Hum"))
            api.renameTrackGroup(folder, "Hums", "Drones")
            assertTrue(File(source, "Drones/Hum.wav").isFile)
            assertTrue(api.editorTracks(folder).any { it.name == "Drones/Hum" })
            api.deleteTrackGroup(folder, "Drones")
            assertTrue(File(source, "Hum.wav").isFile)
            assertTrue(!File(source, "Drones").exists())

            // What the phone can't do yet answers 501 with a message
            try {
                api.moveTrack(folder, "Hum", "Birds")
                fail("Moving a track should not work on the phone")
            } catch (e: ApiException) {
                assertEquals(501, e.status)
                assertTrue(e.detail, e.detail.contains("phone"))
            }

            // ▶ preview: the track's own file through the same client
            session.client.newCall(Request.Builder().url(api.trackAudioUrl(folder, "Drone")).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body.bytes().size > 1000)
            }

            // While the engine plays the theme, a change reloads it (no error) and the mix goes on
            PhoneEngine.open(context, ThemePrep.Prepared(themeDir, pcmDir), "evening", 1f)
            val buf = ByteArray(44_100 * 2)
            PhoneEngine.render(buf, 0, buf.size)
            api.setTrack(folder, "Drone", "volume", buildJsonObject { put("volume", 0.2) })
            api.updateTrackGroup(folder, "Birds", buildJsonObject { put("muted", JsonPrimitive(true)) })
            api.savePreset(folder, "evening")
            PhoneEngine.reloadTheme()
            PhoneEngine.render(buf, 0, buf.size)
            assertEquals(0.2, JSONObject(File(themeDir, "presets.json").readText()).getJSONObject("presets")
                .getJSONObject("evening").getJSONObject("tracks").getJSONObject("Drone").getDouble("volume"), 1e-9)
        } finally {
            PhoneEngine.close()
            themeDir.deleteRecursively()
            pcmDir.deleteRecursively()
            work.deleteRecursively()
        }
    }
}
