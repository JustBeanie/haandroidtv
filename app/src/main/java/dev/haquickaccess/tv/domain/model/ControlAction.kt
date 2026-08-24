package dev.haquickaccess.tv.domain.model

sealed interface ControlAction {
    data class Toggle(val entity: HaEntity) : ControlAction
    data class SetLevel(val entity: HaEntity, val percent: Int) : ControlAction
    data class SetClimateMode(val entity: HaEntity, val mode: String) : ControlAction
    data class SetClimateTemperature(val entity: HaEntity, val temperature: Double) : ControlAction
    data class ActivateScene(val entity: HaEntity) : ControlAction
    data class RunScript(val entity: HaEntity) : ControlAction
    data class PressButton(val entity: HaEntity) : ControlAction
    data class SetNumberValue(val entity: HaEntity, val value: Double) : ControlAction
    data class SelectOption(val entity: HaEntity, val option: String) : ControlAction
    data class SetTextValue(val entity: HaEntity, val value: String) : ControlAction
    data class CoverCommand(val entity: HaEntity, val command: Command, val position: Int? = null) : ControlAction {
        enum class Command { OPEN, CLOSE, STOP, SET_POSITION }
    }
    data class ArmAlarm(val entity: HaEntity, val mode: String, val code: String? = null) : ControlAction
    data class DisarmAlarm(val entity: HaEntity, val code: String) : ControlAction
}
