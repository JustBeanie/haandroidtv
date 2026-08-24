package dev.haquickaccess.tv.domain.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class HaEntity(
    val entityId: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
) {
    val domain: String get() = entityId.substringBefore('.', "")
    val name: String get() = attributes["friendly_name"]?.jsonPrimitive?.contentOrNull ?: entityId.substringAfter('.')
    val unavailable: Boolean get() = state == "unavailable" || (state == "unknown" && !isStatelessAction)
    val isOn: Boolean get() = state in setOf("on", "open", "opening")
    val deviceClass: String? get() = attributes.string("device_class")
    private val isStatelessAction: Boolean get() = domain in setOf("scene", "button", "input_button")

    fun string(name: String): String? = attributes.string(name)
    fun number(name: String): Double? = attributes.number(name)
    fun strings(name: String): List<String> = attributes.strings(name)
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.number(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull
private fun JsonObject.strings(name: String): List<String> =
    this[name]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

enum class ControlKind {
    LIGHT,
    SWITCH,
    FAN,
    INPUT_BOOLEAN,
    CLIMATE,
    COVER,
    ALARM,
    GROUP,
    SCENE,
    SCRIPT,
    BUTTON,
    NUMBER,
    SELECT,
    TEXT,
    UNSUPPORTED,
}

data class ControlCapabilities(
    val kind: ControlKind,
    val canToggle: Boolean,
    val canSetLevel: Boolean = false,
    val canSetClimate: Boolean = false,
    val canSetCoverPosition: Boolean = false,
    val canArm: Boolean = false,
    val canActivate: Boolean = false,
    val canRun: Boolean = false,
    val canPress: Boolean = false,
    val canSetNumber: Boolean = false,
    val canSelectOption: Boolean = false,
    val canSetText: Boolean = false,
    val requiresSecureCoverConfirmation: Boolean = false,
    val alarmCodeRequired: Boolean = false,
) {
    val hasPrimaryAction: Boolean get() = canToggle || canActivate || canRun || canPress
}

fun HaEntity.capabilities(): ControlCapabilities = when (domain) {
    "light" -> ControlCapabilities(ControlKind.LIGHT, canToggle = true, canSetLevel = number("brightness") != null)
    "switch" -> ControlCapabilities(ControlKind.SWITCH, canToggle = true)
    "fan" -> ControlCapabilities(ControlKind.FAN, canToggle = true, canSetLevel = number("percentage") != null)
    "input_boolean" -> ControlCapabilities(ControlKind.INPUT_BOOLEAN, canToggle = true)
    "climate" -> ControlCapabilities(
        kind = ControlKind.CLIMATE,
        canToggle = false,
        canSetClimate = number("temperature") != null || number("min_temp") != null,
    )
    "cover" -> ControlCapabilities(
        kind = ControlKind.COVER,
        canToggle = false,
        canSetCoverPosition = number("current_position") != null,
        requiresSecureCoverConfirmation = deviceClass in setOf("garage", "gate", "door"),
    )
    "alarm_control_panel" -> ControlCapabilities(
        kind = ControlKind.ALARM,
        canToggle = false,
        canArm = alarmArmModes().isNotEmpty(),
        alarmCodeRequired = attributes["code_arm_required"]?.jsonPrimitive?.booleanOrNull ?: true,
    )
    "group" -> ControlCapabilities(ControlKind.GROUP, canToggle = true)
    "scene" -> ControlCapabilities(ControlKind.SCENE, canToggle = false, canActivate = true)
    "script" -> ControlCapabilities(ControlKind.SCRIPT, canToggle = false, canRun = true)
    "button", "input_button" -> ControlCapabilities(ControlKind.BUTTON, canToggle = false, canPress = true)
    "number", "input_number" -> ControlCapabilities(ControlKind.NUMBER, canToggle = false, canSetNumber = true)
    "select", "input_select" -> ControlCapabilities(ControlKind.SELECT, canToggle = false, canSelectOption = strings("options").isNotEmpty())
    "text", "input_text" -> ControlCapabilities(ControlKind.TEXT, canToggle = false, canSetText = true)
    else -> ControlCapabilities(ControlKind.UNSUPPORTED, canToggle = false)
}

fun HaEntity.actionLabel(): String = when {
    capabilities().canActivate -> "Activate"
    capabilities().canRun -> "Run"
    capabilities().canPress -> "Press"
    else -> state.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

fun HaEntity.levelPercent(): Int? = when (domain) {
    "light" -> number("brightness")?.let { ((it / 255.0) * 100).toInt().coerceIn(0, 100) }
    "fan" -> number("percentage")?.toInt()?.coerceIn(0, 100)
    "cover" -> number("current_position")?.toInt()?.coerceIn(0, 100)
    else -> null
}

fun HaEntity.climateStep(): Double = number("target_temp_step") ?: 1.0
fun HaEntity.climateMinimum(): Double = number("min_temp") ?: 7.0
fun HaEntity.climateMaximum(): Double = number("max_temp") ?: 35.0
fun HaEntity.climateTarget(): Double? = number("temperature")
fun HaEntity.hvacModes(): List<String> = strings("hvac_modes")
fun HaEntity.numberMinimum(): Double = number("min") ?: 0.0
fun HaEntity.numberMaximum(): Double = number("max") ?: 100.0
fun HaEntity.numberStep(): Double = number("step")?.takeIf { it > 0 } ?: 1.0
fun HaEntity.selectOptions(): List<String> = strings("options")

/**
 * The alarm-control-panel integration exposes this bit field in state attributes.
 * Do not offer a service an individual panel did not declare as supported.
 */
fun HaEntity.alarmArmModes(): List<String> {
    val supportedFeatures = number("supported_features")?.toInt() ?: return emptyList()
    return buildList {
        if (supportedFeatures and ALARM_ARM_HOME != 0) add("home")
        if (supportedFeatures and ALARM_ARM_AWAY != 0) add("away")
        if (supportedFeatures and ALARM_ARM_NIGHT != 0) add("night")
        if (supportedFeatures and ALARM_ARM_VACATION != 0) add("vacation")
        if (supportedFeatures and ALARM_ARM_CUSTOM_BYPASS != 0) add("custom_bypass")
    }
}

fun HaEntity.alarmCodeIsNumeric(): Boolean = string("code_format") == "number"

private const val ALARM_ARM_HOME = 1
private const val ALARM_ARM_AWAY = 2
private const val ALARM_ARM_NIGHT = 4
private const val ALARM_ARM_CUSTOM_BYPASS = 16
private const val ALARM_ARM_VACATION = 32
