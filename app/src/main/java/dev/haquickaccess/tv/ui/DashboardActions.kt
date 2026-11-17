package dev.haquickaccess.tv.ui

import dev.haquickaccess.tv.domain.model.*
import kotlinx.coroutines.Job

/** Events exposed to TV screens; lifecycle and persistence stay in the ViewModel. */
interface DashboardActions {
    fun onForeground(): Unit
    fun onBackground(): Unit
    fun updateSetupBaseUrl(value: String): Unit
    fun updateSetupToken(value: String): Unit
    fun saveConnection(): Unit
    fun clearConnection(): Job
    fun openAllControls(): Unit
    fun openFavorites(): Unit
    fun openSettings(): Unit
    fun openTileManager(): Unit
    fun openShortcutManager(): Unit
    fun openConnectionSetup(): Unit
    fun openDiagnostics(): Unit
    fun openPrivacyPolicy(): Unit
    fun closeScreen(): Unit
    fun dismissError(): Unit
    fun addTile(entity: HaEntity): Job
    fun removeTile(entityId: String): Job
    fun moveTile(entityId: String, direction: Int): Job
    fun saveFocus(entityId: String): Unit
    fun enableBenchmarkFixture(): Unit
    fun handleLaunchRequest(
        entityId: String?,
        behavior: String?,
        benchmarkFixtureRequested: Boolean = false,
    ): Unit
    fun focusEntity(entityId: String): Unit
    fun acknowledgeFocusRequest(sequence: Long): Unit
    fun showMissingLauncherShortcut(entityId: String = "", behavior: String? = null): Unit
    fun dismissLauncherRecovery(): Unit
    fun openLauncherRecoveryControls(): Unit
    fun performPrimaryAction(entity: HaEntity): Unit
    fun performPrimaryAction(entityId: String): Unit
    fun toggle(entity: HaEntity): Job
    fun supportsDetails(entity: HaEntity): Boolean
    fun openDetails(entity: HaEntity): Unit
    fun openDetails(entityId: String): Unit
    fun dismissCommandFailure(entityId: String): Unit
    fun openShortcutDetails(entity: HaEntity): Unit
    fun closeDetails(): Unit
    fun stageLevel(percent: Int): Unit
    fun applyLevel(): Unit
    fun setClimateMode(entity: HaEntity, mode: String): Unit
    fun stageClimateTemperature(value: Double): Unit
    fun applyClimateTemperature(): Unit
    fun coverCommand(command: ControlAction.CoverCommand.Command, position: Int? = null): Unit
    fun stageCoverPosition(position: Int): Unit
    fun applyCoverPosition(): Unit
    fun confirmSecureCover(): Unit
    fun cancelSecureCover(): Unit
    fun updateAlarmCode(code: String): Unit
    fun stageNumberValue(value: Double): Unit
    fun applyNumberValue(): Unit
    fun selectOption(option: String): Unit
    fun updateTextValue(value: String): Unit
    fun applyTextValue(): Unit
    fun armAlarm(mode: String): Unit
    fun disarmAlarm(): Unit
    fun setShortcut(entityId: String, behavior: ShortcutBehavior): Job
    fun removeShortcut(entityId: String): Job
    fun enableHomeChannel(): Job
    fun disableHomeChannel(): Job
    fun refreshHomeChannel(): Job
    fun refreshHomeChannelWhenReady(): Job
}
