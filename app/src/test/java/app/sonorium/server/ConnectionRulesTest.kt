package app.sonorium.server

import app.sonorium.net.SonoriumApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRulesTest {
    private fun json(s: String) = SonoriumApi.json.parseToJsonElement(s) as JsonObject

    @Test fun `core rows`() {
        val c = json("""{"standalone": true, "ha_url": "http://ha:8123", "ha_token_set": true, "ha_connected": false,
            "mqtt_host": "10.0.0.2", "mqtt_port": 1884, "mqtt_username": "sonorium", "mqtt_connected": true}""")
        val (ha, mqtt) = ConnectionRules.coreRows(c)
        assertEquals("disconnected", ha.state)
        assertEquals("http://ha:8123", ha.pointsAt)
        assertEquals("connected", mqtt.state)
        assertEquals("10.0.0.2:1884 · user sonorium", mqtt.pointsAt)
        val empty = ConnectionRules.coreRows(json("{}"))
        assertEquals("not_configured", empty[0].state)
        assertFalse(empty[1].configured)
        assertEquals("" , empty[1].pointsAt)
    }

    @Test fun `plugin rows`() {
        val e = json("""{"connections": [{"plugin_id": "hubitat", "id": "hub", "title": "Hubitat", "test": true, "plugin_name": "Hubitat",
            "fields": {"host": {"type": "string", "label": "Hub address", "required": true}, "token": {"type": "secret", "label": "Access token"},
                       "port": {"type": "number", "label": "Port", "default": 80}},
            "status": {"state": "error", "message": "timeout"}, "values": {"host": "192.0.2.20", "port": 80, "token_set": true}}]}""")
        val r = ConnectionRules.pluginRows(e).single()
        assertTrue(r.configured)
        assertEquals("192.0.2.20 · 80", r.pointsAt)
        assertEquals("Not answering" to -1, ConnectionRules.statusWord(r.state))
        assertEquals("Unknown" to 0, ConnectionRules.statusWord("weird"))
    }

    @Test fun `checks and values`() {
        val f = ConnectionRules.MQTT_FIELDS
        assertEquals("Port: 1–65535", ConnectionRules.check(f, mapOf("mqtt_port" to "70000"), json("{}")))
        assertEquals("URL: must start with http:// or https://", ConnectionRules.check(ConnectionRules.HA_FIELDS, mapOf("ha_url" to "ha:8123"), json("{}")))
        val req = listOf(ConnectionRules.Field("token", "secret", "Token", required = true))
        assertEquals("Token is needed", ConnectionRules.check(req, emptyMap(), json("{}")))
        assertNull(ConnectionRules.check(req, emptyMap(), json("""{"token_set": true}""")))
        val v = ConnectionRules.values(f, mapOf("mqtt_host" to " b ", "mqtt_port" to "", "mqtt_password" to ""))
        assertEquals(setOf("mqtt_host", "mqtt_username"), v.keys)
        assertEquals(JsonPrimitive("b"), v["mqtt_host"])
        assertEquals(JsonPrimitive(1883L), ConnectionRules.values(f, mapOf("mqtt_port" to "1883"))["mqtt_port"])
    }
}
