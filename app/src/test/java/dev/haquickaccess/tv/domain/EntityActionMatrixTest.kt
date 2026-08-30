package dev.haquickaccess.tv.domain

import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.ControlKind
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.capabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Simulated end-to-end command matrix for every supported entity family. */
class EntityActionMatrixTest {
    @Test
    fun `every entity family exposes its primary or special action`() {
        val light = HaEntity("light.den", "on", JsonObject(mapOf("brightness" to JsonPrimitive(128))))
        val fan = HaEntity("fan.ceiling", "on", JsonObject(mapOf("percentage" to JsonPrimitive(60))))
        val climate = HaEntity("climate.hall", "heat", JsonObject(mapOf("temperature" to JsonPrimitive(20.0))))
        val cover = HaEntity("cover.blinds", "closed", JsonObject(mapOf("current_position" to JsonPrimitive(0))))
        val alarm = HaEntity("alarm_control_panel.home", "disarmed", JsonObject(mapOf("supported_features" to JsonPrimitive(3))))
        val number = HaEntity("input_number.timer", "15")
        val select = HaEntity("input_select.source", "TV", JsonObject(mapOf("options" to JsonArray(listOf(JsonPrimitive("TV"), JsonPrimitive("Apps"))))))
        val text = HaEntity("input_text.message", "Ready")
        val nativeNumber = HaEntity("number.volume_limit", "25")
        val nativeSelect = HaEntity("select.source", "TV", JsonObject(mapOf("options" to JsonArray(listOf(JsonPrimitive("TV"), JsonPrimitive("Apps"))))))
        val nativeText = HaEntity("text.message", "Ready")

        val primaryActions = listOf(
            ControlAction.Toggle(light),
            ControlAction.Toggle(HaEntity("switch.reading", "off")),
            ControlAction.Toggle(HaEntity("fan.ceiling", "off")),
            ControlAction.Toggle(HaEntity("input_boolean.guest_mode", "off")),
            ControlAction.Toggle(HaEntity("group.downstairs", "off")),
            ControlAction.ActivateScene(HaEntity("scene.movie_time", "unknown")),
            ControlAction.RunScript(HaEntity("script.goodnight", "off")),
            ControlAction.PressButton(HaEntity("button.refresh", "unknown")),
            ControlAction.PressButton(HaEntity("input_button.snapshot", "unknown")),
        )
        primaryActions.forEach { action ->
            val call = HomeAssistantCommandFactory.create(action)
            assertTrue(call.data.containsKey("entity_id"), "Missing entity id for $action")
        }

        val specialActions = listOf(
            ControlAction.SetLevel(light, 50),
            ControlAction.SetLevel(fan, 60),
            ControlAction.SetClimateMode(climate, "cool"),
            ControlAction.SetClimateTemperature(climate, 21.5),
            ControlAction.CoverCommand(cover, ControlAction.CoverCommand.Command.OPEN),
            ControlAction.CoverCommand(cover, ControlAction.CoverCommand.Command.CLOSE),
            ControlAction.CoverCommand(cover, ControlAction.CoverCommand.Command.STOP),
            ControlAction.CoverCommand(cover, ControlAction.CoverCommand.Command.SET_POSITION, 42),
            ControlAction.ArmAlarm(alarm, "away", "1234"),
            ControlAction.DisarmAlarm(alarm, "1234"),
            ControlAction.SetNumberValue(number, 30.0),
            ControlAction.SetNumberValue(nativeNumber, 30.0),
            ControlAction.SelectOption(select, "Apps"),
            ControlAction.SelectOption(nativeSelect, "Apps"),
            ControlAction.SetTextValue(text, "Good evening"),
            ControlAction.SetTextValue(nativeText, "Good evening"),
        )
        specialActions.forEach { action ->
            assertTrue(HomeAssistantCommandFactory.create(action).service.isNotBlank(), "No service for $action")
        }

        assertEquals(ControlKind.LIGHT, light.capabilities().kind)
        assertEquals(ControlKind.CLIMATE, climate.capabilities().kind)
        assertEquals(ControlKind.COVER, cover.capabilities().kind)
        assertEquals(ControlKind.ALARM, alarm.capabilities().kind)
        assertEquals(ControlKind.NUMBER, number.capabilities().kind)
        assertEquals(ControlKind.NUMBER, nativeNumber.capabilities().kind)
        assertEquals(ControlKind.SELECT, select.capabilities().kind)
        assertEquals(ControlKind.SELECT, nativeSelect.capabilities().kind)
        assertEquals(ControlKind.TEXT, text.capabilities().kind)
        assertEquals(ControlKind.TEXT, nativeText.capabilities().kind)
        assertEquals(ControlKind.UNSUPPORTED, HaEntity("media_player.tv", "on").capabilities().kind)
    }
}
