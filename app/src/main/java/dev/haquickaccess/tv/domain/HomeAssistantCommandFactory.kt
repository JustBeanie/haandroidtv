package dev.haquickaccess.tv.domain

import dev.haquickaccess.tv.domain.model.ControlAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ServiceCall(
    val domain: String,
    val service: String,
    val data: JsonObject,
)

object HomeAssistantCommandFactory {
    fun create(action: ControlAction): ServiceCall = when (action) {
        is ControlAction.Toggle -> ServiceCall(
            domain = action.entity.domain.takeUnless { it == "group" } ?: "homeassistant",
            service = if (action.entity.isOn) "turn_off" else "turn_on",
            data = entityData(action.entity.entityId),
        )
        is ControlAction.SetLevel -> ServiceCall(
            domain = action.entity.domain,
            service = if (action.entity.domain == "fan") "set_percentage" else "turn_on",
            data = JsonObject(entityData(action.entity.entityId) + (if (action.entity.domain == "fan") {
                "percentage" to JsonPrimitive(action.percent)
            } else {
                "brightness_pct" to JsonPrimitive(action.percent)
            })),
        )
        is ControlAction.SetClimateMode -> ServiceCall(
            domain = "climate",
            service = "set_hvac_mode",
            data = JsonObject(entityData(action.entity.entityId) + ("hvac_mode" to JsonPrimitive(action.mode))),
        )
        is ControlAction.SetClimateTemperature -> ServiceCall(
            domain = "climate",
            service = "set_temperature",
            data = JsonObject(entityData(action.entity.entityId) + ("temperature" to JsonPrimitive(action.temperature))),
        )
        is ControlAction.ActivateScene -> ServiceCall(
            domain = "scene",
            service = "turn_on",
            data = entityData(action.entity.entityId),
        )
        is ControlAction.RunScript -> ServiceCall(
            domain = "script",
            service = "turn_on",
            data = entityData(action.entity.entityId),
        )
        is ControlAction.PressButton -> ServiceCall(
            domain = action.entity.domain,
            service = "press",
            data = entityData(action.entity.entityId),
        )
        is ControlAction.CoverCommand -> ServiceCall(
            domain = "cover",
            service = when (action.command) {
                ControlAction.CoverCommand.Command.OPEN -> "open_cover"
                ControlAction.CoverCommand.Command.CLOSE -> "close_cover"
                ControlAction.CoverCommand.Command.STOP -> "stop_cover"
                ControlAction.CoverCommand.Command.SET_POSITION -> "set_cover_position"
            },
            data = JsonObject(entityData(action.entity.entityId) + if (action.position == null) emptyMap() else mapOf("position" to JsonPrimitive(action.position))),
        )
        is ControlAction.ArmAlarm -> ServiceCall(
            domain = "alarm_control_panel",
            service = "alarm_arm_${action.mode}",
            data = JsonObject(entityData(action.entity.entityId) + optionalCode(action.code)),
        )
        is ControlAction.DisarmAlarm -> ServiceCall(
            domain = "alarm_control_panel",
            service = "alarm_disarm",
            data = JsonObject(entityData(action.entity.entityId) + optionalCode(action.code)),
        )
    }

    private fun entityData(entityId: String) = JsonObject(mapOf("entity_id" to JsonPrimitive(entityId)))
    private fun optionalCode(code: String?) = code?.let { mapOf("code" to JsonPrimitive(it)) }.orEmpty()
}
