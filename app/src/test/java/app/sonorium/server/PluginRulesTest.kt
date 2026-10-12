package app.sonorium.server

import app.sonorium.net.SonoriumApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRulesTest {
    private val list = SonoriumApi.json.parseToJsonElement("""[
        {"id": "theme_merge", "name": "Theme Merge", "version": "2.0.0", "enabled": true, "builtin": true, "settings": {},
         "ui_schema": {"type": "form", "fields": [
            {"name": "target", "type": "select", "label": "Target", "options": [{"value": "__new__", "label": "New"}, {"value": "x", "label": "X"}]},
            {"name": "new_name", "type": "string", "label": "New theme name", "condition": {"field": "target", "value": "__new__"}},
            {"name": "keep", "type": "boolean", "label": "Keep"}],
          "actions": [{"id": "preview", "label": "Preview"}, {"id": "merge", "label": "Merge", "primary": true}]},
         "settings_schema": {}},
        {"id": "ambient_mixer", "name": "Ambient Mixer Importer", "enabled": false, "settings": {"hash_check_duplicates": true},
         "ui_schema": {}, "settings_schema": {"hash_check_duplicates": {"type": "boolean", "default": true, "label": "Use hash"},
            "workers": {"type": "number", "label": "Workers", "min": 1, "max": 8, "default": 2}}},
        {"id": "users", "name": "Users", "enabled": true, "auth_provider": true, "ui_schema": {}, "settings_schema": {}}
    ]""") as JsonArray

    @Test fun `parse and form rules`() {
        val (merge, mixer, users) = PluginRules.plugins(list)
        assertEquals(listOf("merge", "preview"), merge.actions.map { it.id })
        assertTrue(merge.hasOptions)
        assertFalse(users.hasOptions)
        assertTrue(users.authProvider)
        val target = merge.form[0]
        assertEquals("__new__", PluginRules.formDefault(target.field))
        assertTrue(PluginRules.visible(merge.form[1], mapOf("target" to "__new__")))
        assertFalse(PluginRules.visible(merge.form[1], mapOf("target" to "x")))
        val data = PluginRules.actionData(merge.form, mapOf("target" to "x"))
        assertEquals(JsonPrimitive("x"), data["target"])
        assertEquals(JsonPrimitive(""), data["new_name"])
        assertEquals(JsonPrimitive(false), data["keep"])
        assertEquals("true", PluginRules.settingText(mixer.settingsFields[0], mixer.settings))
        assertEquals("2", PluginRules.settingText(mixer.settingsFields[1], mixer.settings))
    }

    @Test fun `setting problems`() {
        val workers = PluginRules.plugins(list)[1].settingsFields[1]
        assertEquals("Workers must be a number", PluginRules.settingProblem(workers, ""))
        assertEquals("Workers must be 1 to 8", PluginRules.settingProblem(workers, "9"))
        assertNull(PluginRules.settingProblem(workers, "3"))
        assertEquals(JsonPrimitive(3L), PluginRules.settingValue(workers, " 3 "))
    }
}
