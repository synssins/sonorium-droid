package app.sonorium.server

import app.sonorium.server.ConnectionRules.Field
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Settings › Plugins (native-api.md Phase 3; app.js `renderPluginsView`). */
object PluginRules {
    data class Plugin(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val author: String,
        val enabled: Boolean,
        val builtin: Boolean,
        val authProvider: Boolean,
        val settings: JsonObject,
        val form: List<FormField>,
        val actions: List<Action>,
        val settingsFields: List<Field>,
    ) {
        val hasOptions: Boolean get() = form.isNotEmpty() || settingsFields.isNotEmpty()
    }

    data class FormField(val field: Field, val conditionField: String?, val conditionValue: String?)

    data class Action(val id: String, val label: String, val primary: Boolean)

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
    private fun bool(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull == true

    fun plugins(list: JsonArray): List<Plugin> = list.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val ui = o["ui_schema"] as? JsonObject ?: JsonObject(emptyMap())
        val form = (ui["fields"] as? JsonArray).orEmpty().mapNotNull { f ->
            val fo = f as? JsonObject ?: return@mapNotNull null
            val name = str(fo, "name").ifEmpty { return@mapNotNull null }
            val field = ConnectionRules.fields(JsonObject(mapOf(name to fo))).first()
            val cond = fo["condition"] as? JsonObject
            FormField(field, cond?.let { str(it, "field") }, cond?.let { (it["value"] as? JsonPrimitive)?.content })
        }
        val actions = (ui["actions"] as? JsonArray).orEmpty().mapNotNull { a ->
            val ao = a as? JsonObject ?: return@mapNotNull null
            Action(str(ao, "id"), str(ao, "label").ifEmpty { str(ao, "id") }, bool(ao, "primary"))
        }.sortedBy { if (it.primary) 0 else 1 }
        Plugin(
            id = str(o, "id"), name = str(o, "name").ifEmpty { str(o, "id") }, version = str(o, "version"),
            description = str(o, "description"), author = str(o, "author"),
            enabled = bool(o, "enabled"), builtin = bool(o, "builtin"), authProvider = bool(o, "auth_provider"),
            settings = o["settings"] as? JsonObject ?: JsonObject(emptyMap()),
            form = form, actions = actions,
            settingsFields = ConnectionRules.fields(o["settings_schema"] as? JsonObject),
        )
    }

    /** A form field's start value: select → first option; boolean → false; else default or empty. */
    fun formDefault(f: Field): String = when (f.type) {
        "select" -> f.options.firstOrNull()?.first.orEmpty()
        "boolean" -> "false"
        else -> (f.default as? JsonPrimitive)?.content.orEmpty()
    }

    /** `condition: {field, value}`: shown while the other field's value matches. */
    fun visible(f: FormField, values: Map<String, String>): Boolean =
        f.conditionField == null || values[f.conditionField].orEmpty() == f.conditionValue.orEmpty()

    /** The action's data: every field (hidden ones too); booleans as booleans, everything else as strings. */
    fun actionData(form: List<FormField>, values: Map<String, String>): JsonObject = JsonObject(form.associate { ff ->
        val v = values[ff.field.name] ?: formDefault(ff.field)
        ff.field.name to (if (ff.field.type == "boolean") JsonPrimitive(v == "true") else JsonPrimitive(v)) as JsonElement
    })

    /** A setting's value as text: saved ?? default ?? (false for boolean, else empty). */
    fun settingText(f: Field, saved: JsonObject): String =
        ((saved[f.name]?.takeIf { it !is JsonNull } ?: f.default) as? JsonPrimitive)?.content ?: if (f.type == "boolean") "false" else ""

    private fun plain(d: Double) = if (d == Math.floor(d)) "${d.toLong()}" else "$d"

    /** `pluginSettingProblem`. */
    fun settingProblem(f: Field, text: String): String? {
        val label = f.label.ifEmpty { "This setting" }
        val v = text.trim()
        if (f.type == "number") {
            val x = v.toDoubleOrNull() ?: return "$label must be a number"
            if (f.min != null && f.max != null && (x < f.min || x > f.max)) return "$label must be ${plain(f.min)} to ${plain(f.max)}"
            if (f.min != null && x < f.min) return "$label must be at least ${plain(f.min)}"
            if (f.max != null && x > f.max) return "$label must be at most ${plain(f.max)}"
        }
        if (f.type == "url" && v.isNotEmpty() && !Regex("^https?://[^\\s]+$").matches(v)) return "$label must start with http:// or https://"
        if (f.required && v.isEmpty()) return "$label can't be empty"
        return null
    }

    /** The value a setting saves: trimmed string, a number, or a boolean. */
    fun settingValue(f: Field, text: String): JsonElement = when (f.type) {
        "boolean" -> JsonPrimitive(text == "true")
        "number" -> text.trim().toDouble().let { if (it == Math.floor(it)) JsonPrimitive(it.toLong()) else JsonPrimitive(it) }
        else -> JsonPrimitive(text.trim())
    }
}
