package dev.haquickaccess.tv.domain

import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.homeAssistantEntityIdPattern
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ServiceCall(
    val domain: String,
    val service: String,
    val data: JsonObject,
)

object HomeAssistantCommandFactory {
    fun create(action: ControlAction): ServiceCall {
        validate(action)
        return when (action) {
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
        is ControlAction.SetNumberValue -> ServiceCall(
            domain = action.entity.domain,
            service = "set_value",
            data = JsonObject(entityData(action.entity.entityId) + ("value" to JsonPrimitive(action.value))),
        )
        is ControlAction.SelectOption -> ServiceCall(
            domain = action.entity.domain,
            service = "select_option",
            data = JsonObject(entityData(action.entity.entityId) + ("option" to JsonPrimitive(action.option))),
        )
        is ControlAction.SetTextValue -> ServiceCall(
            domain = action.entity.domain,
            service = "set_value",
            data = JsonObject(entityData(action.entity.entityId) + ("value" to JsonPrimitive(action.value))),
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
    }

    private fun entityData(entityId: String): JsonObject {
        require(homeAssistantEntityIdPattern.matches(entityId)) { "Invalid Home Assistant entity ID" }
        return JsonObject(mapOf("entity_id" to JsonPrimitive(entityId)))
    }

    private fun validate(action: ControlAction) {
        require(entityId(action).length <= MAX_ENTITY_ID_LENGTH) { "Home Assistant entity ID is too long" }
        when (action) {
            is ControlAction.Toggle -> require(action.entity.domain in toggleDomains) { "Entity does not support toggling" }
            is ControlAction.SetLevel -> {
                require(action.entity.domain in levelDomains) { "Entity does not support levels" }
                require(action.percent in 0..100) { "Level must be between 0 and 100" }
            }
            is ControlAction.SetClimateMode -> {
                require(action.entity.domain == "climate") { "Entity does not support climate modes" }
                requireBounded(action.mode, MAX_OPTION_LENGTH)
            }
            is ControlAction.SetClimateTemperature -> {
                require(action.entity.domain == "climate") { "Entity does not support climate temperature" }
                require(action.temperature.isFinite()) { "Temperature must be finite" }
            }
            is ControlAction.ActivateScene -> require(action.entity.domain == "scene") { "Entity is not a scene" }
            is ControlAction.RunScript -> require(action.entity.domain == "script") { "Entity is not a script" }
            is ControlAction.PressButton -> require(action.entity.domain in buttonDomains) { "Entity is not a button" }
            is ControlAction.SetNumberValue -> {
                require(action.entity.domain in numberDomains) { "Entity does not support number values" }
                require(action.value.isFinite()) { "Number value must be finite" }
            }
            is ControlAction.SelectOption -> {
                require(action.entity.domain in selectDomains) { "Entity does not support options" }
                requireBounded(action.option, MAX_OPTION_LENGTH)
            }
            is ControlAction.SetTextValue -> {
                require(action.entity.domain in textDomains) { "Entity does not support text values" }
                requireBounded(action.value, MAX_TEXT_LENGTH)
            }
            is ControlAction.CoverCommand -> {
                require(action.entity.domain == "cover") { "Entity is not a cover" }
                require(action.position == null || action.position in 0..100) { "Cover position must be between 0 and 100" }
            }
            is ControlAction.ArmAlarm -> {
                require(action.entity.domain == "alarm_control_panel") { "Entity is not an alarm panel" }
                require(action.mode in alarmModes) { "Unsupported alarm mode" }
                action.code?.let { requireBounded(it, MAX_CODE_LENGTH) }
            }
            is ControlAction.DisarmAlarm -> {
                require(action.entity.domain == "alarm_control_panel") { "Entity is not an alarm panel" }
                require(action.code.isNotBlank()) { "Alarm code cannot be blank" }
                requireBounded(action.code, MAX_CODE_LENGTH)
            }
        }
    }

    private fun requireBounded(value: String, maxLength: Int) {
        require(value.isNotBlank() && value.length <= maxLength) { "Home Assistant input is invalid" }
    }

    private fun entityId(action: ControlAction): String = when (action) {
        is ControlAction.Toggle -> action.entity.entityId
        is ControlAction.SetLevel -> action.entity.entityId
        is ControlAction.SetClimateMode -> action.entity.entityId
        is ControlAction.SetClimateTemperature -> action.entity.entityId
        is ControlAction.ActivateScene -> action.entity.entityId
        is ControlAction.RunScript -> action.entity.entityId
        is ControlAction.PressButton -> action.entity.entityId
        is ControlAction.SetNumberValue -> action.entity.entityId
        is ControlAction.SelectOption -> action.entity.entityId
        is ControlAction.SetTextValue -> action.entity.entityId
        is ControlAction.CoverCommand -> action.entity.entityId
        is ControlAction.ArmAlarm -> action.entity.entityId
        is ControlAction.DisarmAlarm -> action.entity.entityId
    }

    private fun optionalCode(code: String?) = code?.let { mapOf("code" to JsonPrimitive(it)) }.orEmpty()

    private val alarmModes = setOf("home", "away", "night", "vacation", "custom_bypass")
    private val toggleDomains = setOf("light", "switch", "fan", "input_boolean", "group")
    private val levelDomains = setOf("light", "fan")
    private val buttonDomains = setOf("button", "input_button")
    private val numberDomains = setOf("number", "input_number")
    private val selectDomains = setOf("select", "input_select")
    private val textDomains = setOf("text", "input_text")
    private const val MAX_ENTITY_ID_LENGTH = 256
    private const val MAX_CODE_LENGTH = 128
    private const val MAX_OPTION_LENGTH = 256
    private const val MAX_TEXT_LENGTH = 4096
}
