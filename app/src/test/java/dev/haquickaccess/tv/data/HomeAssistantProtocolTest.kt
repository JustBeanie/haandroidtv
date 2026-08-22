package dev.haquickaccess.tv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class HomeAssistantProtocolTest {
    @Test
    fun `websocket URL preserves a Home Assistant base path and port`() {
        assertEquals(
            "wss://ha.example:8123/house/api/websocket",
            HomeAssistantProtocol.webSocketUrl("https://ha.example:8123/house/"),
        )
    }

    @Test
    fun `auth and command payloads follow the websocket protocol`() {
        val auth = HomeAssistantProtocol.authMessage("long-lived-token")
        val command = HomeAssistantProtocol.command(
            id = 7,
            type = "subscribe_events",
            extra = JsonObject(mapOf("event_type" to JsonPrimitive("state_changed"))),
        )

        assertEquals("auth", auth["type"]?.jsonPrimitive?.content)
        assertEquals("long-lived-token", auth["access_token"]?.jsonPrimitive?.content)
        assertEquals("7", command["id"]?.jsonPrimitive?.content)
        assertEquals("subscribe_events", command["type"]?.jsonPrimitive?.content)
        assertEquals("state_changed", command["event_type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `state mapping accepts complete entities and ignores malformed payloads`() {
        val entity = HomeAssistantProtocol.entityFromJson(
            JsonObject(
                mapOf(
                    "entity_id" to JsonPrimitive("light.den"),
                    "state" to JsonPrimitive("on"),
                    "attributes" to JsonObject(mapOf("friendly_name" to JsonPrimitive("Den light"))),
                ),
            ),
        )

        assertEquals("Den light", entity?.name)
        assertTrue(entity?.isOn == true)
        assertNull(HomeAssistantProtocol.entityFromJson(JsonPrimitive("not-an-object")))
        assertNull(HomeAssistantProtocol.entityFromJson(JsonObject(mapOf("entity_id" to JsonPrimitive("light.den")))))
    }
}
