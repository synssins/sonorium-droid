package app.sonorium.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Settings › Connection (native-api.md Phase 3; `ext/connections.js`) and the settings field vocabulary (`ext/core.js`). */
object ConnectionRules {

    /** One field of the vocabulary: string, url, number, boolean, select, textarea, time, date, datetime, multiselect, secret. */
    data class Field(
        val name: String,
        val type: String,
        val label: String,
        val placeholder: String = "",
        val help: String = "",
        val required: Boolean = false,
        val min: Double? = null,
        val max: Double? = null,
        val default: JsonElement? = null,
        val options: List<Pair<String, String>> = emptyList(),
    )

    private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun n(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    fun fields(defs: JsonObject?): List<Field> = defs.orEmpty().mapNotNull { (name, e) ->
        val o = e as? JsonObject ?: return@mapNotNull null
        Field(
            name = name,
            type = s(o, "type") ?: "string",
            label = s(o, "label") ?: name,
            placeholder = s(o, "placeholder").orEmpty(),
            help = s(o, "help").orEmpty(),
            required = (o["required"] as? JsonPrimitive)?.booleanOrNull == true,
            min = n(o, "min"),
            max = n(o, "max"),
            default = o["default"]?.takeIf { it !is JsonNull },
            options = (o["options"] as? JsonArray).orEmpty().map { opt ->
                if (opt is JsonObject) {
                    val v = (opt["value"] as? JsonPrimitive)?.content.orEmpty()
                    v to ((opt["label"] as? JsonPrimitive)?.content ?: v)
                } else {
                    val v = (opt as? JsonPrimitive)?.content.orEmpty()
                    v to v
                }
            },
        )
    }

    /** The core rows' fields (`CORE` in `ext/connections.js`). */
    val HA_FIELDS = listOf(
        Field("ha_url", "url", "URL", "http://homeassistant.local:8123", "The address of Home Assistant"),
        Field("ha_token", "secret", "Access token", "Paste a long-lived access token", "Profile › Security › Long-lived access tokens"),
    )
    val MQTT_FIELDS = listOf(
        Field("mqtt_host", "string", "Broker", "192.168.1.10", "Host name or IP of your broker"),
        Field("mqtt_port", "number", "Port", "1883", min = 1.0, max = 65535.0),
        Field("mqtt_username", "string", "Username", "Anonymous", "Blank for anonymous"),
        Field("mqtt_password", "secret", "Password", help = "Blank keeps the saved password"),
    )

    /** A row of the list: HA, MQTT, or a plugin connection. */
    data class Row(
        val kind: String, // "ha", "mqtt", "plugin"
        val title: String,
        val state: String,
        val message: String,
        val pointsAt: String,
        val configured: Boolean,
        val test: Boolean,
        val fields: List<Field>,
        /** Saved values as shown in the form (secrets: `<name>_set`). */
        val values: JsonObject,
        val pluginId: String? = null,
        val connectionId: String? = null,
        val pluginName: String? = null,
        val icon: String = "",
    )

    private fun bool(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull == true
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

    fun coreRows(c: JsonObject): List<Row> {
        val haConfigured = str(c, "ha_url").isNotEmpty() || bool(c, "ha_token_set")
        val ha = Row(
            "ha", "Home Assistant",
            state = if (!haConfigured) "not_configured" else if (bool(c, "ha_connected")) "connected" else "disconnected",
            message = "", pointsAt = str(c, "ha_url"), configured = haConfigured, test = true, fields = HA_FIELDS, values = c, icon = "home",
        )
        val host = str(c, "mqtt_host")
        val port = str(c, "mqtt_port").ifEmpty { "1883" }
        val user = str(c, "mqtt_username")
        val mqtt = Row(
            "mqtt", "MQTT",
            state = if (host.isEmpty()) "not_configured" else if (bool(c, "mqtt_connected")) "connected" else "disconnected",
            message = "", pointsAt = if (host.isEmpty()) "" else "$host:$port" + if (user.isNotEmpty()) " · user $user" else "",
            configured = host.isNotEmpty(), test = true, fields = MQTT_FIELDS, values = c, icon = "radio",
        )
        return listOf(ha, mqtt)
    }

    fun pluginRows(extensions: JsonObject): List<Row> = (extensions["connections"] as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val fields = fields(o["fields"] as? JsonObject)
        val values = o["values"] as? JsonObject ?: JsonObject(emptyMap())
        val status = o["status"] as? JsonObject ?: JsonObject(emptyMap())
        val configured = fields.any { f ->
            if (f.type == "secret") bool(values, "${f.name}_set")
            else {
                val v = values[f.name]
                val text = (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
                text.isNotEmpty() && v != f.default
            }
        }
        val points = fields.filter { it.type != "secret" && it.type != "boolean" }
            .map { str(values, it.name) }.filter { it.isNotEmpty() }.take(2).joinToString(" · ")
        Row(
            "plugin", str(o, "title").ifEmpty { str(o, "id") },
            state = str(status, "state").ifEmpty { "unknown" }, message = str(status, "message"),
            pointsAt = points, configured = configured, test = bool(o, "test"), fields = fields, values = values,
            pluginId = str(o, "plugin_id"), connectionId = str(o, "id"), pluginName = str(o, "plugin_name").ifEmpty { null },
            icon = ((o["icon"] as? JsonObject)?.let { str(it, "value") }).orEmpty(),
        )
    }

    /** Status chip: word and whether it is good / bad / neutral. */
    fun statusWord(state: String): Pair<String, Int> = when (state) {
        "connected" -> "Connected" to 1
        "disconnected" -> "Not connected" to -1
        "error" -> "Not answering" to -1
        "not_configured" -> "Not configured" to 0
        else -> "Unknown" to 0
    }

    /** The first problem of the typed values (`<Label>: must start with http:// or https://`, …), or null. */
    fun check(fields: List<Field>, typed: Map<String, String>, saved: JsonObject): String? {
        fields.forEach { f ->
            val v = typed[f.name].orEmpty().trim()
            if (f.type == "url" && v.isNotEmpty() && !Regex("^https?://\\S+$").matches(v)) return "${f.label}: must start with http:// or https://"
            if (f.type == "number" && v.isNotEmpty()) {
                val x = v.toDoubleOrNull()
                if (x == null || (f.min != null && x < f.min) || (f.max != null && x > f.max)) {
                    return "${f.label}: ${f.min?.let { plain(it) } ?: ""}–${f.max?.let { plain(it) } ?: ""}"
                }
            }
            if (f.required && v.isEmpty() && !(f.type == "secret" && bool(saved, "${f.name}_set"))) return "${f.label} is needed"
        }
        return null
    }

    private fun plain(d: Double) = if (d == Math.floor(d)) "${d.toLong()}" else "$d"

    /** Values to send: strings trimmed; blank secrets and blank numbers left out (= the saved ones). */
    fun values(fields: List<Field>, typed: Map<String, String>, bools: Map<String, Boolean> = emptyMap(), forTest: Boolean = false): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        fields.forEach { f ->
            when (f.type) {
                "boolean" -> out[f.name] = JsonPrimitive(bools[f.name] == true)
                "multiselect" -> out[f.name] = JsonArray(typed[f.name].orEmpty().split(',').filter { it.isNotEmpty() }.map { JsonPrimitive(it) })
                "secret" -> typed[f.name]?.trim()?.takeIf { it.isNotEmpty() }?.let { out[f.name] = JsonPrimitive(it) }
                "number" -> typed[f.name]?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let {
                    out[f.name] = if (it == Math.floor(it)) JsonPrimitive(it.toLong()) else JsonPrimitive(it)
                }
                else -> {
                    val v = typed[f.name]?.trim().orEmpty()
                    if (!forTest || v.isNotEmpty()) out[f.name] = JsonPrimitive(v)
                }
            }
        }
        return JsonObject(out)
    }

    /** The form's start text for a field: secrets empty, else the saved value or its default. */
    fun initial(f: Field, saved: JsonObject): String = when (f.type) {
        "secret" -> ""
        "multiselect" -> (saved[f.name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString(",").orEmpty()
        else -> ((saved[f.name] ?: f.default) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
    }
}
