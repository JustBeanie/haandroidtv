package dev.haquickaccess.tv.domain

import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.HaEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class HomeAssistantCommandFactoryTest {
    @Test
    fun `toggle turns an off light on`() {
        val result = HomeAssistantCommandFactory.create(ControlAction.Toggle(HaEntity("light.living_room", "off")))

        assertEquals("light", result.domain)
        assertEquals("turn_on", result.service)
        assertEquals("light.living_room", result.data["entity_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `dimmer produces brightness percentage`() {
        val entity = HaEntity("light.living_room", "on")
        val result = HomeAssistantCommandFactory.create(ControlAction.SetLevel(entity, 47))

        assertEquals("turn_on", result.service)
        assertEquals(47, result.data["brightness_pct"]?.jsonPrimitive?.int)
    }

    @Test
    fun `fan level uses fan percentage service`() {
        val entity = HaEntity("fan.tv", "on")
        val result = HomeAssistantCommandFactory.create(ControlAction.SetLevel(entity, 65))

        assertEquals("set_percentage", result.service)
        assertEquals(65, result.data["percentage"]?.jsonPrimitive?.int)
    }

    @Test
    fun `secure alarm disarm includes one-time code`() {
        val entity = HaEntity("alarm_control_panel.home", "armed_away")
        val result = HomeAssistantCommandFactory.create(ControlAction.DisarmAlarm(entity, "1234"))

        assertEquals("alarm_disarm", result.service)
        assertEquals("1234", result.data["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cover position calls explicit position service`() {
        val entity = HaEntity("cover.blinds", "open")
        val result = HomeAssistantCommandFactory.create(
            ControlAction.CoverCommand(entity, ControlAction.CoverCommand.Command.SET_POSITION, 30),
        )

        assertEquals("set_cover_position", result.service)
        assertEquals(30, result.data["position"]?.jsonPrimitive?.int)
    }

    @Test
    fun `climate commands preserve entity and requested values`() {
        val entity = HaEntity("climate.hall", "heat")
        val mode = HomeAssistantCommandFactory.create(ControlAction.SetClimateMode(entity, "cool"))
        val temperature = HomeAssistantCommandFactory.create(ControlAction.SetClimateTemperature(entity, 21.5))

        assertEquals("set_hvac_mode", mode.service)
        assertEquals("cool", mode.data["hvac_mode"]?.jsonPrimitive?.content)
        assertEquals("set_temperature", temperature.service)
        assertEquals("21.5", temperature.data["temperature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cover open close and stop map to safe Home Assistant services`() {
        val entity = HaEntity("cover.blinds", "open")

        assertEquals("open_cover", HomeAssistantCommandFactory.create(ControlAction.CoverCommand(entity, ControlAction.CoverCommand.Command.OPEN)).service)
        assertEquals("close_cover", HomeAssistantCommandFactory.create(ControlAction.CoverCommand(entity, ControlAction.CoverCommand.Command.CLOSE)).service)
        assertEquals("stop_cover", HomeAssistantCommandFactory.create(ControlAction.CoverCommand(entity, ControlAction.CoverCommand.Command.STOP)).service)
    }

    @Test
    fun `scene script and button actions retain their native service semantics`() {
        val scene = HomeAssistantCommandFactory.create(ControlAction.ActivateScene(HaEntity("scene.movie_time", "unknown")))
        val script = HomeAssistantCommandFactory.create(ControlAction.RunScript(HaEntity("script.goodnight", "off")))
        val button = HomeAssistantCommandFactory.create(ControlAction.PressButton(HaEntity("button.refresh", "unknown")))
        val inputButton = HomeAssistantCommandFactory.create(ControlAction.PressButton(HaEntity("input_button.test", "unknown")))

        assertEquals("scene", scene.domain)
        assertEquals("turn_on", scene.service)
        assertEquals("script", script.domain)
        assertEquals("turn_on", script.service)
        assertEquals("button", button.domain)
        assertEquals("press", button.service)
        assertEquals("input_button", inputButton.domain)
        assertEquals("press", inputButton.service)
    }

    @Test
    fun `generic groups use the universal homeassistant toggle service`() {
        val call = HomeAssistantCommandFactory.create(ControlAction.Toggle(HaEntity("group.downstairs", "off")))

        assertEquals("homeassistant", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("group.downstairs", call.data["entity_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `editable helper actions use each helpers native service`() {
        val number = HomeAssistantCommandFactory.create(
            ControlAction.SetNumberValue(HaEntity("input_number.sleep_timer", "15"), 30.5),
        )
        val select = HomeAssistantCommandFactory.create(
            ControlAction.SelectOption(HaEntity("input_select.tv_source", "HDMI 1"), "Apps"),
        )
        val text = HomeAssistantCommandFactory.create(
            ControlAction.SetTextValue(HaEntity("input_text.guest_message", "Welcome"), "Good evening"),
        )

        assertEquals("input_number", number.domain)
        assertEquals("set_value", number.service)
        assertEquals("30.5", number.data["value"]?.jsonPrimitive?.content)
        assertEquals("input_select", select.domain)
        assertEquals("select_option", select.service)
        assertEquals("Apps", select.data["option"]?.jsonPrimitive?.content)
        assertEquals("input_text", text.domain)
        assertEquals("set_value", text.service)
        assertEquals("Good evening", text.data["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `alarm arm omits absent code and disarm can send a fresh code`() {
        val entity = HaEntity("alarm_control_panel.home", "disarmed")
        val arm = HomeAssistantCommandFactory.create(ControlAction.ArmAlarm(entity, "away"))

        assertEquals("alarm_arm_away", arm.service)
        assertEquals(null, arm.data["code"])
        assertEquals("alarm_disarm", HomeAssistantCommandFactory.create(ControlAction.DisarmAlarm(entity, "9876")).service)
    }

    @Test
    fun `command boundary rejects malformed identifiers and unsafe values`() {
        assertFailsWith<IllegalArgumentException> {
            HomeAssistantCommandFactory.create(ControlAction.Toggle(HaEntity("../../token", "on")))
        }
        assertFailsWith<IllegalArgumentException> {
            HomeAssistantCommandFactory.create(ControlAction.SetLevel(HaEntity("light.den", "on"), 101))
        }
        assertFailsWith<IllegalArgumentException> {
            HomeAssistantCommandFactory.create(ControlAction.ArmAlarm(HaEntity("alarm_control_panel.home", "disarmed"), "disarm"))
        }
        assertFailsWith<IllegalArgumentException> {
            HomeAssistantCommandFactory.create(ControlAction.SetClimateTemperature(HaEntity("climate.hall", "heat"), Double.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            HomeAssistantCommandFactory.create(ControlAction.Toggle(HaEntity("script.goodnight", "on")))
        }
    }
}
