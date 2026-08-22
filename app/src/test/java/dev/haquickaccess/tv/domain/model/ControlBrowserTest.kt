package dev.haquickaccess.tv.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ControlBrowserTest {
    private val entities = listOf(
        HaEntity("light.kitchen", "off", attributes("Kitchen pendant")),
        HaEntity("light.bedroom", "on", attributes("Bedroom lamp")),
        HaEntity("input_boolean.guest_mode", "off", attributes("Guest mode")),
        HaEntity("switch.kitchen_fan", "on", attributes("Kitchen fan")),
    )

    @Test
    fun `search matches friendly names and entity ids irrespective of case`() {
        assertEquals(
            listOf("switch.kitchen_fan", "light.kitchen"),
            ControlBrowser.filter(entities, "KiTcHeN").map(HaEntity::entityId),
        )
        assertEquals(
            listOf("input_boolean.guest_mode"),
            ControlBrowser.filter(entities, "INPUT_BOOLEAN.GUEST").map(HaEntity::entityId),
        )
    }

    @Test
    fun `domain filtering and result order stay stable`() {
        assertEquals(
            listOf("light.bedroom", "light.kitchen"),
            ControlBrowser.filter(entities, query = "", domain = "light").map(HaEntity::entityId),
        )
        assertEquals(listOf("input_boolean", "light", "switch"), ControlBrowser.domains(entities))
    }

    @Test
    fun `domain labels make technical domains understandable`() {
        assertEquals("Helpers", ControlBrowser.domainLabel("input_boolean"))
        assertEquals("Security", ControlBrowser.domainLabel("alarm_control_panel"))
        assertEquals("Media player", ControlBrowser.domainLabel("media_player"))
    }

    private fun attributes(name: String) = JsonObject(mapOf("friendly_name" to JsonPrimitive(name)))
}
