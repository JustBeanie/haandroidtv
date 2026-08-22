package dev.haquickaccess.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.haquickaccess.tv.data.AppSettings
import dev.haquickaccess.tv.data.ConnectionStatus
import dev.haquickaccess.tv.data.HomeAssistantSession
import dev.haquickaccess.tv.data.SettingsStore
import dev.haquickaccess.tv.data.UrlValidator
import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.ShortcutBehavior
import dev.haquickaccess.tv.domain.model.ShortcutConfiguration
import dev.haquickaccess.tv.domain.model.TileConfiguration
import dev.haquickaccess.tv.domain.model.alarmArmModes
import dev.haquickaccess.tv.domain.model.capabilities
import dev.haquickaccess.tv.domain.model.climateMaximum
import dev.haquickaccess.tv.domain.model.climateMinimum
import dev.haquickaccess.tv.domain.model.climateTarget
import dev.haquickaccess.tv.domain.model.levelPercent
import dev.haquickaccess.tv.platform.HomeChannelGateway
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AppScreen {
    data object Dashboard : AppScreen
    data object Settings : AppScreen
    data object ManageTiles : AppScreen
    data object ManageShortcuts : AppScreen
    data object ConnectionSetup : AppScreen
    data object Diagnostics : AppScreen
}

sealed interface DetailState {
    data class Level(val entity: HaEntity, val stagedPercent: Int) : DetailState
    data class Climate(
        val entity: HaEntity,
        val stagedTemperature: Double? = if (entity.capabilities().canSetClimate) {
            entity.climateTarget() ?: entity.climateMinimum()
        } else {
            null
        },
    ) : DetailState
    data class Cover(
        val entity: HaEntity,
        val stagedPosition: Int? = entity.levelPercent(),
        val pendingCommand: ControlAction.CoverCommand.Command? = null,
        val pendingPosition: Int? = null,
    ) : DetailState
    data class Alarm(val entity: HaEntity, val disarmCode: String = "", val armMode: String? = null) : DetailState
}

data class DashboardUiState(
    val settings: AppSettings = AppSettings(),
    val entities: Map<String, HaEntity> = emptyMap(),
    val screen: AppScreen = AppScreen.Dashboard,
    val details: DetailState? = null,
    val pendingEntityIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
    val setupBaseUrl: String = "",
    val setupToken: String = "",
    val isForeground: Boolean = false,
    val isSavingConnection: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
) {
    val isConfigured: Boolean get() = settings.baseUrl != null && settings.tokenEnvelope != null
    val tiles: List<HaEntity> get() = settings.tiles.sortedBy(TileConfiguration::position).mapNotNull { entities[it.entityId] }
    val availableEntities: List<HaEntity> get() = entities.values.sortedBy(HaEntity::name)
}

private data class DashboardConnectionState(
    val settings: AppSettings,
    val entities: Map<String, HaEntity>,
    val status: ConnectionStatus,
)

private data class DashboardInteractionState(
    val screen: AppScreen,
    val details: DetailState?,
    val pendingEntityIds: Set<String>,
    val errorMessage: String?,
)

private data class DashboardSetupState(
    val baseUrl: String,
    val token: String,
    val foreground: Boolean,
    val savingConnection: Boolean,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val settingsRepository: SettingsStore,
    private val homeAssistantRepository: HomeAssistantSession,
    private val homeChannelPublisher: HomeChannelGateway,
) : ViewModel() {
    private val screen = MutableStateFlow<AppScreen>(AppScreen.Dashboard)
    private val details = MutableStateFlow<DetailState?>(null)
    private val pending = MutableStateFlow<Set<String>>(emptySet())
    private val error = MutableStateFlow<String?>(null)
    private val setupBaseUrl = MutableStateFlow("")
    private val setupToken = MutableStateFlow("")
    private val foreground = MutableStateFlow(false)
    private val savingConnection = MutableStateFlow(false)
    private val _homeChannelRequests = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val homeChannelRequests = _homeChannelRequests
    private var latestSettings = AppSettings()
    private var latestEntities: Map<String, HaEntity> = emptyMap()
    private var activeSession: Pair<String, String>? = null
    private var connectionValidationJob: Job? = null

    private val connectionState = combine(
        settingsRepository.settings,
        homeAssistantRepository.entities,
        homeAssistantRepository.status,
    ) { currentSettings, entities, status ->
        latestSettings = currentSettings
        latestEntities = entities
        DashboardConnectionState(currentSettings, entities, status)
    }

    private val interactionState = combine(
        screen,
        details,
        pending,
        error,
    ) { currentScreen, currentDetails, currentPending, currentError ->
        DashboardInteractionState(currentScreen, currentDetails, currentPending, currentError)
    }

    private val setupState = combine(
        setupBaseUrl,
        setupToken,
        foreground,
        savingConnection,
    ) { baseUrl, token, isForeground, isSaving ->
        DashboardSetupState(baseUrl, token, isForeground, isSaving)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        connectionState,
        interactionState,
        setupState,
    ) { connection, interaction, setup ->
        DashboardUiState(
            settings = connection.settings,
            entities = connection.entities,
            screen = interaction.screen,
            details = interaction.details,
            pendingEntityIds = interaction.pendingEntityIds,
            errorMessage = interaction.errorMessage,
            setupBaseUrl = setup.baseUrl,
            setupToken = setup.token,
            isForeground = setup.foreground,
            isSavingConnection = setup.savingConnection,
            connectionStatus = connection.status,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        viewModelScope.launch {
            combine(settingsRepository.settings, foreground) { configuredSettings, isForeground ->
                configuredSettings to isForeground
            }.collect { (configuredSettings, isForeground) ->
                latestSettings = configuredSettings
                synchronizeForegroundSession(configuredSettings, isForeground)
            }
        }
    }

    fun onForeground() {
        foreground.value = true
    }

    fun onBackground() {
        foreground.value = false
        connectionValidationJob?.cancel()
        connectionValidationJob = null
        savingConnection.value = false
        setupToken.value = ""
        details.value = (details.value as? DetailState.Alarm)?.copy(disarmCode = "") ?: details.value
    }

    fun updateSetupBaseUrl(value: String) { setupBaseUrl.value = value }
    fun updateSetupToken(value: String) { setupToken.value = value }

    fun saveConnection() {
        connectionValidationJob?.cancel()
        connectionValidationJob = viewModelScope.launch {
            val normalizedUrl = UrlValidator.normalize(setupBaseUrl.value)
            val token = setupToken.value.trim()
            if (normalizedUrl.isFailure || token.isBlank()) {
                error.value = normalizedUrl.exceptionOrNull()?.message ?: "Enter a Home Assistant token"
                return@launch
            }
            val validatedUrl = normalizedUrl.getOrThrow()
            val previousSettings = latestSettings
            activeSession = null
            homeAssistantRepository.stop()
            savingConnection.value = true
            try {
                val validation = homeAssistantRepository.validateConnection(validatedUrl, token)
                if (validation.isFailure) {
                    error.value = validation.exceptionOrNull()?.message ?: "Could not connect to Home Assistant"
                    synchronizeForegroundSession(previousSettings, foreground.value)
                    return@launch
                }
                settingsRepository.saveConnection(validatedUrl, token)
                setupToken.value = ""
                error.value = null
                screen.value = AppScreen.Dashboard
            } finally {
                savingConnection.value = false
            }
        }
    }

    fun clearConnection() = viewModelScope.launch {
        connectionValidationJob?.cancel()
        connectionValidationJob = null
        homeAssistantRepository.stop()
        activeSession = null
        latestSettings.channelId?.let(homeChannelPublisher::remove)
        settingsRepository.clearConnection()
        details.value = null
        screen.value = AppScreen.Dashboard
    }

    fun openSettings() { screen.value = AppScreen.Settings }
    fun openTileManager() { screen.value = AppScreen.ManageTiles }
    fun openShortcutManager() { screen.value = AppScreen.ManageShortcuts }
    fun openConnectionSetup() {
        setupBaseUrl.value = latestSettings.baseUrl.orEmpty()
        setupToken.value = ""
        screen.value = AppScreen.ConnectionSetup
    }
    fun openDiagnostics() { screen.value = AppScreen.Diagnostics }
    fun closeScreen() { screen.value = AppScreen.Dashboard }
    fun dismissError() { error.value = null }

    fun addTile(entity: HaEntity) = viewModelScope.launch {
        if (latestSettings.tiles.any { it.entityId == entity.entityId }) return@launch
        settingsRepository.saveTiles(latestSettings.tiles + TileConfiguration(entity.entityId, latestSettings.tiles.size))
    }

    fun removeTile(entityId: String) = viewModelScope.launch {
        val tiles = latestSettings.tiles
            .filterNot { it.entityId == entityId }
            .mapIndexed { index, tile -> tile.copy(position = index) }
        val shortcuts = latestSettings.homeShortcuts.filterNot { it.entityId == entityId }
        settingsRepository.saveTiles(tiles)
        if (shortcuts.size != latestSettings.homeShortcuts.size) {
            settingsRepository.saveShortcuts(shortcuts)
            publishShortcutsIfEnabled(shortcuts)
        }
    }

    fun moveTile(entityId: String, direction: Int) = viewModelScope.launch {
        val tiles = latestSettings.tiles.sortedBy(TileConfiguration::position).toMutableList()
        val from = tiles.indexOfFirst { it.entityId == entityId }
        val to = (from + direction).coerceIn(0, tiles.lastIndex)
        if (from >= 0 && from != to) {
            val tile = tiles.removeAt(from)
            tiles.add(to, tile)
            settingsRepository.saveTiles(tiles.mapIndexed { index, config -> config.copy(position = index) })
        }
    }

    fun saveFocus(entityId: String) = viewModelScope.launch { settingsRepository.saveLastFocusedEntity(entityId) }

    fun focusEntity(entityId: String) {
        screen.value = AppScreen.Dashboard
        details.value = null
        saveFocus(entityId)
    }

    fun toggle(entity: HaEntity) = execute(ControlAction.Toggle(entity))

    fun openDetails(entity: HaEntity) {
        details.value = when {
            entity.capabilities().canSetLevel -> DetailState.Level(entity, entity.levelPercent() ?: 50)
            entity.domain == "climate" -> DetailState.Climate(entity)
            entity.domain == "cover" -> DetailState.Cover(entity)
            entity.domain == "alarm_control_panel" -> DetailState.Alarm(entity)
            else -> null
        }
    }

    fun closeDetails() { details.value = null }

    fun stageLevel(percent: Int) {
        details.value = (details.value as? DetailState.Level)?.copy(stagedPercent = percent.coerceIn(0, 100))
    }

    fun applyLevel() {
        val detail = details.value as? DetailState.Level ?: return
        execute(ControlAction.SetLevel(detail.entity, detail.stagedPercent))
        details.value = null
    }

    fun setClimateMode(entity: HaEntity, mode: String) {
        execute(ControlAction.SetClimateMode(entity, mode))
    }

    fun stageClimateTemperature(value: Double) {
        details.value = (details.value as? DetailState.Climate)?.let { detail ->
            detail.copy(
                stagedTemperature = value.coerceIn(
                    detail.entity.climateMinimum(),
                    detail.entity.climateMaximum(),
                ),
            )
        }
    }

    fun applyClimateTemperature() {
        val detail = details.value as? DetailState.Climate ?: return
        detail.stagedTemperature?.let { execute(ControlAction.SetClimateTemperature(detail.entity, it)) }
    }

    fun coverCommand(command: ControlAction.CoverCommand.Command, position: Int? = null) {
        val detail = details.value as? DetailState.Cover ?: return
        val opensSecureCover = command == ControlAction.CoverCommand.Command.OPEN ||
            (command == ControlAction.CoverCommand.Command.SET_POSITION &&
                position != null && position > (detail.entity.levelPercent() ?: 0))
        if (detail.entity.capabilities().requiresSecureCoverConfirmation && opensSecureCover && detail.pendingCommand == null) {
            details.value = detail.copy(pendingCommand = command, pendingPosition = position)
            return
        }
        execute(ControlAction.CoverCommand(detail.entity, command, position))
        details.value = null
    }

    fun stageCoverPosition(position: Int) {
        details.value = (details.value as? DetailState.Cover)?.copy(stagedPosition = position.coerceIn(0, 100))
    }

    fun applyCoverPosition() {
        val detail = details.value as? DetailState.Cover ?: return
        detail.stagedPosition?.let { coverCommand(ControlAction.CoverCommand.Command.SET_POSITION, it) }
    }

    fun confirmSecureCover() {
        val detail = details.value as? DetailState.Cover ?: return
        val command = detail.pendingCommand ?: return
        execute(ControlAction.CoverCommand(detail.entity, command, detail.pendingPosition))
        details.value = null
    }

    fun cancelSecureCover() {
        details.value = (details.value as? DetailState.Cover)?.copy(pendingCommand = null, pendingPosition = null)
    }

    fun updateAlarmCode(code: String) {
        details.value = (details.value as? DetailState.Alarm)?.copy(disarmCode = code.take(16))
    }

    fun armAlarm(mode: String) {
        val detail = details.value as? DetailState.Alarm ?: return
        if (mode !in detail.entity.alarmArmModes()) {
            error.value = "That arm mode is not supported by this alarm"
            return
        }
        if (detail.entity.capabilities().alarmCodeRequired && detail.disarmCode.isBlank()) {
            error.value = "Enter the Home Assistant alarm code"
            return
        }
        execute(ControlAction.ArmAlarm(detail.entity, mode, detail.disarmCode.ifBlank { null }))
        details.value = null
    }

    fun disarmAlarm() {
        val detail = details.value as? DetailState.Alarm ?: return
        if (detail.disarmCode.isBlank()) {
            error.value = "Enter the Home Assistant alarm code"
        } else {
            execute(ControlAction.DisarmAlarm(detail.entity, detail.disarmCode))
            details.value = null
        }
    }

    fun setShortcut(entityId: String, behavior: ShortcutBehavior) = viewModelScope.launch {
        val existing = latestSettings.homeShortcuts.filterNot { it.entityId == entityId }
        val shortcuts = (existing + ShortcutConfiguration(entityId, behavior)).take(4)
        settingsRepository.saveShortcuts(shortcuts)
        publishShortcutsIfEnabled(shortcuts)
    }

    fun removeShortcut(entityId: String) = viewModelScope.launch {
        val shortcuts = latestSettings.homeShortcuts.filterNot { it.entityId == entityId }
        if (shortcuts.size == latestSettings.homeShortcuts.size) return@launch
        settingsRepository.saveShortcuts(shortcuts)
        publishShortcutsIfEnabled(shortcuts)
    }

    fun enableHomeChannel() = viewModelScope.launch {
        val channelId = homeChannelPublisher.createOrUpdate(latestSettings, latestEntities)
        settingsRepository.setHomeChannel(enabled = true, channelId = channelId)
        _homeChannelRequests.emit(channelId)
    }

    fun disableHomeChannel() = viewModelScope.launch {
        latestSettings.channelId?.let(homeChannelPublisher::remove)
        settingsRepository.setHomeChannel(enabled = false)
    }

    fun refreshHomeChannel() = viewModelScope.launch {
        if (!latestSettings.homeChannelEnabled) return@launch
        val channelId = homeChannelPublisher.createOrUpdate(latestSettings, latestEntities)
        settingsRepository.setHomeChannel(enabled = true, channelId = channelId)
    }

    private suspend fun publishShortcutsIfEnabled(shortcuts: List<ShortcutConfiguration>) {
        if (!latestSettings.homeChannelEnabled) return
        val channelId = homeChannelPublisher.createOrUpdate(latestSettings.copy(homeShortcuts = shortcuts), latestEntities)
        settingsRepository.setHomeChannel(enabled = true, channelId = channelId)
    }

    private fun execute(action: ControlAction) = viewModelScope.launch {
        val id = when (action) {
            is ControlAction.Toggle -> action.entity.entityId
            is ControlAction.SetLevel -> action.entity.entityId
            is ControlAction.SetClimateMode -> action.entity.entityId
            is ControlAction.SetClimateTemperature -> action.entity.entityId
            is ControlAction.CoverCommand -> action.entity.entityId
            is ControlAction.ArmAlarm -> action.entity.entityId
            is ControlAction.DisarmAlarm -> action.entity.entityId
        }
        pending.value += id
        homeAssistantRepository.execute(action).onFailure { error.value = it.message ?: "Home Assistant did not accept the command" }
        pending.value -= id
    }

    private fun synchronizeForegroundSession(settings: AppSettings, isForeground: Boolean) {
        if (!isForeground) {
            if (activeSession != null) {
                homeAssistantRepository.stop()
                activeSession = null
            }
            return
        }
        val baseUrl = settings.baseUrl ?: run {
            if (activeSession != null) {
                homeAssistantRepository.stop()
                activeSession = null
            }
            return
        }
        val token = runCatching { settingsRepository.decryptToken(settings) }.getOrElse {
            error.value = "Saved token can no longer be read. Connect Home Assistant again."
            if (activeSession != null) {
                homeAssistantRepository.stop()
                activeSession = null
            }
            return
        } ?: return
        val requestedSession = baseUrl to token
        if (activeSession == requestedSession) return
        activeSession?.let { homeAssistantRepository.stop() }
        activeSession = requestedSession
        homeAssistantRepository.start(baseUrl, token)
    }

    override fun onCleared() {
        homeAssistantRepository.stop()
    }
}
