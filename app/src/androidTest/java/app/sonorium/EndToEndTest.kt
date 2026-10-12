package app.sonorium

import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.sonorium.listen.ListenController
import app.sonorium.web.WebViewSupport
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against a real Sonorium server (CI starts one in Docker; see
 * .github/workflows/android.yml). Instrumentation arguments:
 *  - `linkUri`: a Link App code, base64 (it holds `&` and `#`)
 *  - `channelName`: a channel the server has
 *
 * Drives the native screens the way a person would. Screenshots go to the app's
 * external files folder for CI to collect.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val args = InstrumentationRegistry.getArguments()
    private val shots = File(instrumentation.targetContext.getExternalFilesDir(null), "e2e").apply { mkdirs() }

    @Test
    fun linksAndControlsTheServerNatively() {
        val link = String(Base64.decode(args.getString("linkUri")!!, Base64.DEFAULT))
        val channelName = args.getString("channelName") ?: "CI channel"

        // 1. The Link App code arrives as it would from the camera (App Link).
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            .setClassName(instrumentation.targetContext, MainActivity::class.java.name)
        ActivityScenario.launch<MainActivity>(intent).use {
            val open = device.wait(Until.findObject(By.text("Open")), 30_000)
            screenshot("01-linked")
            assertNotNull("Linking didn't finish; see 01-linked.png", open)

            // A WebView too old for the server's pages (an Android 8 image never updated) only
            // matters for pages not native yet; the native screens don't need it.
            val oldWebView = WebViewSupport.outdated(instrumentation.targetContext)
            open.click()

            // 2. The native Channels screen shows the server's channel, collapsed on a phone
            //    (approved design): its small Play does what the card's big Play does.
            need(By.text(channelName), 60_000, "02-channels", "The channel didn't appear")
            Thread.sleep(1_500)
            screenshot("02-channels-collapsed")
            find(By.desc("Play $channelName"), "No small Play button on the collapsed row").click()
            need(By.text("No speakers selected"), 10_000, "02-row-play", "No error toast for the row's Play without speakers")
            Thread.sleep(3_500) // let the toast go
            find(By.desc("Open $channelName"), "No chevron to open the channel").click()
            need(By.desc("Collapse $channelName"), 10_000, "02-open", "The card didn't open")
            Thread.sleep(800)
            screenshot("02-channels-portrait")

            // 3. Preset: pick Default settings → the web UI's toast text.
            find(By.desc("Preset"), "No preset field").click()
            need(By.text("Default settings"), 10_000, "03-preset-menu", "No preset menu").click()
            need(By.text("Using default settings"), 10_000, "03-preset-toast", "No toast after choosing a preset")
            screenshot("03-preset")

            // 4. Play with no speakers: the server's refusal shows as a toast, verbatim.
            Thread.sleep(3_500)
            find(By.text("Play"), "No Play button").click()
            need(By.text("No speakers selected"), 10_000, "04-play-refused", "No error toast for Play without speakers")

            // 5. Listen here plays natively, with a playback notification; the same button stops it.
            find(By.desc("Listen on this device"), "No listen button").click()
            assertTrue("The native player didn't start: ${ListenController.state.value}", waitFor(30_000) { ListenController.state.value?.playing == true })
            screenshot("05-listening")
            device.openNotification()
            val notification = device.wait(Until.findObject(By.textContains(channelName)), 10_000)
            screenshot("06-notification")
            closeNotificationShade()
            assertNotNull("No playback notification for $channelName", notification)
            find(By.desc("Listen on this device"), "No listen button").click()
            assertTrue("The native player didn't stop", waitFor(15_000) { ListenController.state.value?.playing != true })

            // 6. The editor opens with the channel and closes with Cancel.
            find(By.desc("Edit"), "No Edit button").click()
            need(By.text("Edit Channel"), 10_000, "07-editor", "The editor didn't open")
            screenshot("07-editor")
            find(By.text("Cancel"), "No Cancel").click()

            // 7. A new channel: pick a theme, create → toast and a second card.
            find(By.text("New Channel"), "No New Channel button").click()
            need(By.text("New Channel"), 10_000, "08-new", "The new channel editor didn't open")
            hideKeyboard()
            device.wait(Until.findObject(By.textStartsWith("A Rainy Day")), 10_000)?.click()
            Thread.sleep(500)
            screenshot("08-new-channel")
            find(By.text("Create Channel"), "No Create Channel button").click()
            need(By.text("Channel created"), 15_000, "09-created", "No toast after creating a channel")
            screenshot("09-created")

            // Phase 2 native screens. Playlists: add one with an entry → the web UI's toast.
            openPage("Playlists")
            need(By.desc("Add playlist"), 15_000, "20-playlists", "No Add playlist button").click()
            typeInto(need(By.clazz("android.widget.EditText"), 10_000, "20-playlist-editor", "The playlist editor didn't open"), "CI playlist")
            hideKeyboard()
            find(By.text("Add entry"), "No Add entry button").click()
            find(By.desc("Theme 1"), "No theme field on the new entry").click()
            need(By.textContains("A Rainy Day"), 10_000, "20-theme-list", "No theme list").click()
            Thread.sleep(500)
            screenshot("20-playlist-editor")
            find(By.desc("Save playlist"), "No save button in the playlist editor").click()
            need(By.text("Playlist added"), 15_000, "20-playlist-added", "No toast after adding a playlist")
            need(By.text("CI playlist"), 10_000, "20-playlist-row", "The new playlist isn't listed")
            screenshot("20-playlists")

            // Status: the poll fills the page
            openPage("Status")
            need(By.text("Active Channels"), 15_000, "21-status", "The Status page didn't open")
            Thread.sleep(1_000)
            screenshot("21-status")

            // Themes: the cards, and a favorite → toast
            openPage("Themes")
            need(By.textContains("A Rainy Day"), 15_000, "22-themes", "No theme cards")
            screenshot("22-themes")
            // ★ toggles; the CI themes may start as favorites, so flip it and flip it back
            val add = device.findObject(By.desc("Add to favorites"))
            (add ?: find(By.desc("Remove from favorites"), "No favorite button")).click()
            need(By.text(if (add != null) "Added to favorites" else "Removed from favorites"), 10_000, "22-favorite", "No toast after ★")
            Thread.sleep(3_500)
            find(By.desc(if (add != null) "Remove from favorites" else "Add to favorites"), "The ★ didn't change").click()
            need(By.text(if (add != null) "Removed from favorites" else "Added to favorites"), 10_000, "22-favorite-back", "No toast after the second ★")

            // Theme Editor (Phase 3): open it, a new preset → "Saved", the preset is selected, Cancel
            Thread.sleep(3_500)
            find(By.text("Edit"), "No Edit on a theme card").click()
            need(By.text("Edit theme"), 15_000, "27-theme-editor", "The Theme Editor didn't open")
            need(By.text("Tracks"), 15_000, "27-theme-sections", "No tracks toolbar in the Theme Editor")
            Thread.sleep(1_000)
            screenshot("27-theme-editor")
            find(By.text("New"), "No New preset button").click()
            typeInto(need(By.desc("Preset name"), 10_000, "27-new-preset", "The New preset popover didn't open"), "CI preset")
            find(By.text("OK"), "No OK in the New preset popover").click()
            need(By.text("Saved"), 10_000, "27-preset-saved", "No toast after New preset")
            need(By.desc("Preset: CI preset"), 10_000, "27-preset-selected", "The new preset isn't selected")
            screenshot("27-theme-editor-preset")
            // Sequences › Add sequence…: a name and no presets → the web UI's refusal
            find(By.desc("Sequences"), "No Sequences button").click()
            find(By.text("Add sequence…"), "No Add sequence in the Sequences menu").click()
            need(By.text("Add sequence"), 10_000, "28-sequence-editor", "The sequence editor didn't open")
            typeInto(find(By.desc("Sequence name"), "No sequence name field"), "CI sequence")
            hideKeyboard()
            screenshot("28-sequence-editor")
            find(By.text("Save"), "No Save in the sequence editor").click()
            // the summary line says it too; the toast makes a second one
            if (!waitFor(10_000) { device.findObjects(By.text("Add at least 2 presets")).size >= 2 }) {
                screenshot("99-28-sequence-refused")
                throw AssertionError("No toast for a sequence without presets; see 99-28-sequence-refused.png")
            }
            find(By.text("Cancel"), "No Cancel in the sequence editor").click()
            need(By.text("Edit theme"), 10_000, "28-sequence-closed", "The sequence editor didn't close")
            find(By.text("Cancel"), "No Cancel in the Theme Editor").click()
            need(By.textContains("A Rainy Day"), 15_000, "27-editor-closed", "The Theme Editor didn't close")

            // Settings › Speakers
            openPage("Speakers", settings = true)
            need(By.text("Hide offline"), 15_000, "23-speakers", "The Speakers page didn't open")
            Thread.sleep(1_000)
            screenshot("23-speakers")

            // Settings › Speaker Groups: nothing selected → the web UI's refusal
            openPage("Speaker Groups", settings = true)
            need(By.desc("Add group"), 15_000, "24-groups", "No Add group button").click()
            typeInto(need(By.clazz("android.widget.EditText"), 10_000, "24-group-editor", "The group editor didn't open"), "CI group")
            hideKeyboard()
            screenshot("24-group-editor")
            find(By.desc("Save group"), "No save button in the group editor").click()
            need(By.text("Select at least one speaker"), 10_000, "24-group-refused", "No toast for a group without speakers")
            find(By.text("Cancel"), "No Cancel in the group editor").click()

            // Settings › Floors & Areas: add a floor when the server lets this install edit them
            openPage("Floors & Areas", settings = true)
            need(By.text(java.util.regex.Pattern.compile("^(Group speakers by floor|From Home Assistant).*", java.util.regex.Pattern.DOTALL)), 15_000, "25-spaces", "The Floors & Areas page didn't open")
            device.findObject(By.text("Add floor"))?.let { add ->
                add.click()
                typeInto(need(By.clazz("android.widget.EditText"), 10_000, "25-add-floor", "The Add floor dialog didn't open"), "CI floor")
                find(By.desc("Save floor"), "No save button in the Add floor dialog").click()
                need(By.text("CI floor"), 10_000, "25-floor-added", "The new floor isn't listed")
            }
            screenshot("25-spaces")

            // Settings › Connection (Phase 3): the core rows on a standalone server; Edit MQTT, Cancel
            openPage("Connection", settings = true)
            need(By.text("MQTT"), 15_000, "29-connection", "The Connection page didn't list MQTT")
            find(By.text("Home Assistant"), "No Home Assistant row")
            screenshot("29-connection")
            device.findObjects(By.text("Edit")).getOrNull(1)?.click() ?: throw AssertionError("No Edit on the MQTT row")
            need(By.desc("Broker"), 10_000, "29-connection-edit", "The MQTT dialog didn't open")
            screenshot("29-connection-edit")
            find(By.text("Cancel"), "No Cancel in the MQTT dialog").click()

            // Settings › Audio Settings: a slider makes a draft; Cancel puts it back
            openPage("Audio Settings", settings = true)
            need(By.text("Master output gain"), 15_000, "30-audio", "The Audio Settings page didn't open")
            screenshot("30-audio")

            // Settings › Plugins: the installed list (CI's server has the built-in plugins)
            openPage("Plugins", settings = true)
            need(By.textContains("Installed ("), 15_000, "31-plugins", "The Plugins page didn't open")
            Thread.sleep(1_500)
            screenshot("31-plugins")

            // Settings › Logs: lines arrive from the server
            openPage("Logs", settings = true)
            need(By.textContains("log level:"), 15_000, "32-logs", "The Logs page didn't load")
            Thread.sleep(1_000)
            screenshot("32-logs")

            // Settings › Advanced: the playback values
            openPage("Advanced", settings = true)
            need(By.text("Wait between plays"), 15_000, "33-advanced", "The Advanced page didn't list the playback values")
            screenshot("33-advanced")
            openPage("Channels")
            need(By.text(channelName), 15_000, "26-back", "Channels didn't come back")

            // 8. Landscape.
            device.setOrientationLandscape()
            Thread.sleep(2_500)
            screenshot("10-channels-landscape")
            device.setOrientationNatural()
            Thread.sleep(2_000)

            // 9. The server button returns to the server list.
            find(By.descStartsWith("Servers:"), "No server button").click()
            need(By.text("Scan Link App code"), 15_000, "11-servers", "The server button didn't return to the list")
            screenshot("11-servers")
            oldWebView?.let { File(shots, "old-webview.txt").writeText("WebView ${it.versionName}\n") }
        }
    }

    /** Types into a Compose text field: focus it first, then set the text; checks it arrived. */
    private fun typeInto(field: UiObject2, text: String) {
        field.click()
        Thread.sleep(400)
        field.text = text
        Thread.sleep(400)
        if (device.findObject(By.text(text)) == null) {
            screenshot("99-typing")
            throw AssertionError("Couldn't type \"$text\"; see 99-typing.png")
        }
    }

    /** Opens a page from the menu (side panel on phones); Settings items open the Settings group first. */
    private fun openPage(title: String, settings: Boolean = false) {
        device.findObject(By.desc("Menu"))?.click()
        Thread.sleep(1_200)
        if (settings && !device.hasObject(By.text("Speaker Groups"))) {
            find(By.text("Settings"), "No Settings in the menu").click()
            Thread.sleep(600)
        }
        find(By.text(title), "No $title in the menu").click()
        Thread.sleep(1_200)
    }

    private fun find(selector: BySelector, message: String): UiObject2 =
        device.wait(Until.findObject(selector), 10_000) ?: run {
            screenshot("99-missing")
            throw AssertionError(message)
        }

    private fun need(selector: BySelector, timeoutMs: Long, shot: String, message: String): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs) ?: run {
            screenshot("99-$shot")
            throw AssertionError("$message; see 99-$shot.png")
        }

    /** Back doesn't always close the shade on newer Android; swipe it up too. */
    private fun closeNotificationShade() {
        // Back only while the shade shows: a Back that reaches the app leaves the server
        if (device.currentPackageName == "com.android.systemui") {
            device.pressBack()
            Thread.sleep(800)
        }
        if (device.currentPackageName == "com.android.systemui" || device.hasObject(By.res("com.android.systemui:id/notification_stack_scroller"))) {
            device.swipe(device.displayWidth / 2, device.displayHeight - 50, device.displayWidth / 2, 50, 12)
            Thread.sleep(800)
        }
    }

    /** Android 8 opens the keyboard on the editor's first field; close it. */
    private fun hideKeyboard() {
        Thread.sleep(800)
        val imeShown = instrumentation.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (imeShown) {
            device.pressBack()
            Thread.sleep(500)
        }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(500)
        }
        return condition()
    }

    private fun screenshot(name: String) {
        device.takeScreenshot(File(shots, "$name.png"))
    }
}
