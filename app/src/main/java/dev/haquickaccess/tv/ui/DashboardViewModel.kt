package dev.haquickaccess.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.haquickaccess.tv.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.haquickaccess.tv.data.AppSettings
import dev.haquickaccess.tv.data.ConnectionStatus
import dev.haquickaccess.tv.data.EmptyTileSnapshotStore
import dev.haquickaccess.tv.data.HomeAssistantSession
import dev.haquickaccess.tv.data.SettingsStore
import dev.haquickaccess.tv.data.TileSnapshot
import dev.haquickaccess.tv.data.TileSnapshotStore
import dev.haquickaccess.tv.data.UrlValidator
import dev.haquickaccess.tv.di.IoDispatcher
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
import dev.haquickaccess.tv.domain.model.numberMaximum
import dev.haquickaccess.tv.domain.model.numberMinimum
import dev.haquickaccess.tv.domain.model.selectOptions
import dev.haquickaccess.tv.platform.HomeChannelGateway
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AppScreen {
    data object Dashboard : AppScreen
    data object Settings : AppScreen
    data object ManageTiles : AppScreen
    data object ManageShortcuts : AppScreen
    data object ConnectionSetup : AppScreen
    data object Diagnostics : AppScreen
    data object Privacy : AppScreen
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
    data class Number(
        val entity: HaEntity,
        val stagedValue: Double = entity.state.toDoubleOrNull()?.coerceIn(entity.numberMinimum(), entity.numberMaximum())
            ?: entity.numberMinimum(),
    ) : DetailState
    data class Select(val entity: HaEntity, val selectedOption: String = entity.state) : DetailState
    data class Text(val entity: HaEntity, val stagedValue: String = entity.state.takeUnless { it == "unknown" }.orEmpty()) : DetailState
}

data class FocusRequest(
    val entityId: String,
    val sequence: Long,
)

data class LauncherRecovery(
    val message: String,
    val staleEntityId: String,
    val behavior: ShortcutBehavior,
)

data class LauncherShortcutReplacement(
    val staleEntityId: String,
    val behavior: ShortcutBehavior,
)

data class DashboardUiState(
    val settings: AppSettings = AppSettings(),
    val isSettingsLoaded: Boolean = false,
    val isProjectivyInstalled: Boolean = false,
    val entities: Map<String, HaEntity> = emptyMap(),
    val areInitialStatesLoaded: Boolean = false,
    val screen: AppScreen = AppScreen.Dashboard,
    val details: DetailState? = null,
    val commandFeedback: Map<String, CommandFeedback> = emptyMap(),
    val errorMessage: String? = null,
    val setupBaseUrl: String = "",
    val setupToken: String = "",
    val setupErrorMessage: String? = null,
    val isForeground: Boolean = false,
    val isSavingConnection: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val focusRequest: FocusRequest? = null,
    val launcherRecovery: LauncherRecovery? = null,
    val launcherShortcutReplacement: LauncherShortcutReplacement? = null,
    val tileSnapshot: TileSnapshot? = null,
) {
    val isConfigured: Boolean get() = settings.baseUrl != null && settings.tokenEnvelope != null
    val pendingEntityIds: Set<String> get() = commandFeedback.filterValues { it is CommandFeedback.Pending }.keys
    val tiles: List<HaEntity> by lazy(LazyThreadSafetyMode.NONE) {
        settings.tiles.sortedBy(TileConfiguration::position).mapNotNull { entities[it.entityId] }
    }
    val dashboardTiles: List<DashboardTileUiModel> by lazy(LazyThreadSafetyMode.NONE) {
        val orderedConfigurations = settings.tiles.sortedBy(TileConfiguration::position)
        val liveTiles = orderedConfigurations.mapNotNull { configuration ->
            entities[configuration.entityId]?.toDashboardTileUiModel()
        }
        if (areInitialStatesLoaded) {
            liveTiles
        } else {
            val liveTilesById = liveTiles.associateBy { it.entityId }
            val cachedTiles = tileSnapshot?.tiles.orEmpty().associateBy { it.entityId }
            orderedConfigurations.mapNotNull { configuration ->
                liveTilesById[configuration.entityId]
                    ?: cachedTiles[configuration.entityId]?.toDashboardTileUiModel()
            }
        }
    }
    val isShowingCachedSnapshot: Boolean get() = dashboardTiles.any { !it.live }
    val availableEntities: List<HaEntity> by lazy(LazyThreadSafetyMode.NONE) {
        entities.values.sortedBy(HaEntity::name)
    }
}

private data class DashboardConnectionState(
    val settings: AppSettings,
    val settingsLoaded: Boolean,
    val entities: Map<String, HaEntity>,
    val initialStatesLoaded: Boolean,
    val status: ConnectionStatus,
    val tileSnapshot: TileSnapshot?,
)

private data class DashboardInteractionState(
    val screen: AppScreen,
    val details: DetailState?,
    val commandFeedback: Map<String, CommandFeedback>,
    val errorMessage: String?,
    val focusRequest: FocusRequest?,
    val launcherRecovery: LauncherRecovery?,
    val launcherShortcutReplacement: LauncherShortcutReplacement?,
)

private data class DashboardSetupState(
    val baseUrl: String,
    val token: String,
    val errorMessage: String?,
    val foreground: Boolean,
    val savingConnection: Boolean,
)

private data class LaunchRequest(
    val entityId: String,
    val behavior: String?,
    val sequence: Long,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val settingsRepository: SettingsStore,
    private val homeAssistantRepository: HomeAssistantSession,
    private val homeChannelPublisher: HomeChannelGateway,
    private val tileSnapshotStore: TileSnapshotStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    constructor(
        settingsRepository: SettingsStore,
        homeAssistantRepository: HomeAssistantSession,
        homeChannelPublisher: HomeChannelGateway,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        settingsRepository,
        homeAssistantRepository,
        homeChannelPublisher,
        EmptyTileSnapshotStore,
        ioDispatcher,
    )

    private val screen = MutableStateFlow<AppScreen>(AppScreen.Dashboard)
    private val details = MutableStateFlow<DetailState?>(null)
    private val commandFeedback = MutableStateFlow<Map<String, CommandFeedback>>(emptyMap())
    private val error = MutableStateFlow<String?>(null)
    private val focusRequest = MutableStateFlow<FocusRequest?>(null)
    private val launcherRecovery = MutableStateFlow<LauncherRecovery?>(null)
    private val launcherShortcutReplacement = MutableStateFlow<LauncherShortcutReplacement?>(null)
    private val setupBaseUrl = MutableStateFlow("")
    private val setupToken = MutableStateFlow("")
    private val setupError = MutableStateFlow<String?>(null)
    private val foreground = MutableStateFlow(false)
    private val savingConnection = MutableStateFlow(false)
    private val benchmarkOverride = MutableStateFlow<DashboardUiState?>(null)
    private val pendingLaunchRequest = MutableStateFlow<LaunchRequest?>(null)
    private val _homeChannelRequests = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val homeChannelRequests: SharedFlow<Long> = _homeChannelRequests.asSharedFlow()
    private var latestSettings = AppSettings()
    private var latestEntities: Map<String, HaEntity> = emptyMap()
    private var activeSession: Pair<String, String>? = null
    private var connectionValidationJob: Job? = null
    private var focusSaveJob: Job? = null
    private var snapshotSaveJob: Job? = null
    private var pendingFocusEntityId: String? = null
    private var nextFocusRequestSequence = 0L
    private var nextCommandFeedbackSequence = 0L
    private var nextLaunchRequestSequence = 0L
    private val isProjectivyInstalled = homeChannelPublisher.isProjectivyInstalled()

    private val connectionState = combine(
        settingsRepository.settings,
        homeAssistantRepository.entities,
        homeAssistantRepository.initialStatesLoaded,
        homeAssistantRepository.status,
        tileSnapshotStore.snapshot,
    ) { currentSettings, entities, initialStatesLoaded, status, snapshot ->
        latestSettings = currentSettings
        latestEntities = entities
        DashboardConnectionState(
            settings = currentSettings,
            settingsLoaded = true,
            entities = entities,
            initialStatesLoaded = initialStatesLoaded,
            status = status,
            tileSnapshot = snapshot,
        )
    }

    private val interactionState = combine(
        combine(
            screen,
            details,
            commandFeedback,
            error,
            focusRequest,
        ) { currentScreen, currentDetails, currentFeedback, currentError, currentFocusRequest ->
            DashboardInteractionState(currentScreen, currentDetails, currentFeedback, currentError, currentFocusRequest, null, null)
        },
        launcherRecovery,
        launcherShortcutReplacement,
    ) { interaction, currentLauncherRecovery, currentLauncherShortcutReplacement ->
        interaction.copy(
            launcherRecovery = currentLauncherRecovery,
            launcherShortcutReplacement = currentLauncherShortcutReplacement,
        )
    }

    private val setupState = combine(
        setupBaseUrl,
        setupToken,
        setupError,
        foreground,
        savingConnection,
    ) { baseUrl, token, setupErrorMessage, isForeground, isSaving ->
        DashboardSetupState(baseUrl, token, setupErrorMessage, isForeground, isSaving)
    }

    private val liveUiState: StateFlow<DashboardUiState> = combine(
        connectionState,
        interactionState,
        setupState,
    ) { connection, interaction, setup ->
        DashboardUiState(
            settings = connection.settings,
            isSettingsLoaded = connection.settingsLoaded,
            isProjectivyInstalled = isProjectivyInstalled,
            entities = connection.entities,
            areInitialStatesLoaded = connection.initialStatesLoaded,
            screen = interaction.screen,
            details = interaction.details,
            commandFeedback = interaction.commandFeedback,
            errorMessage = interaction.errorMessage,
            setupBaseUrl = setup.baseUrl,
            setupToken = setup.token,
            setupErrorMessage = setup.errorMessage,
            isForeground = setup.foreground,
            isSavingConnection = setup.savingConnection,
            connectionStatus = connection.status,
            focusRequest = interaction.focusRequest,
            launcherRecovery = interaction.launcherRecovery,
            launcherShortcutReplacement = interaction.launcherShortcutReplacement,
            tileSnapshot = connection.tileSnapshot,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    val uiState: StateFlow<DashboardUiState> = combine(liveUiState, benchmarkOverride) { live, benchmark ->
        benchmark ?: live
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
        viewModelScope.launch {
            var previousEntities: Map<String, HaEntity> = emptyMap()
            homeAssistantRepository.entities.collect { currentEntities ->
                if (previousEntities.isNotEmpty()) {
                    val confirmedFailures = commandFeedback.value
                        .filterValues { it is CommandFeedback.Failed }
                        .keys
                        .filter { entityId ->
                            val previous = previousEntities[entityId]
                            val current = currentEntities[entityId]
                            previous != null && current != null && previous != current
                        }
                    if (confirmedFailures.isNotEmpty()) {
                        commandFeedback.value -= confirmedFailures.toSet()
                    }
                }
                previousEntities = currentEntities
            }
        }
        viewModelScope.launch {
            combine(
                settingsRepository.settings,
                homeAssistantRepository.entities,
                homeAssistantRepository.initialStatesLoaded,
            ) { currentSettings, currentEntities, initialStatesLoaded ->
                if (!initialStatesLoaded || currentSettings.baseUrl == null || currentSettings.tokenEnvelope == null) {
                    return@combine emptyList()
                }
                currentSettings.tiles
                    .sortedBy(TileConfiguration::position)
                    .mapNotNull { currentEntities[it.entityId] }
                    .map { it.toDashboardTileUiModel().toSnapshotEntry() }
            }.collect { entries ->
                snapshotSaveJob?.cancel()
                if (entries.isEmpty()) return@collect
                snapshotSaveJob = viewModelScope.launch(ioDispatcher) {
                    delay(SNAPSHOT_SAVE_DEBOUNCE_MS)
                    tileSnapshotStore.save(
                        TileSnapshot(
                            capturedAtEpochMillis = System.currentTimeMillis(),
                            tiles = entries,
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(
                pendingLaunchRequest,
                homeAssistantRepository.entities,
                homeAssistantRepository.initialStatesLoaded,
                homeAssistantRepository.status,
            ) { request, entities, initialStatesLoaded, status ->
                val ready = initialStatesLoaded || status is ConnectionStatus.Failed
                Triple(request, entities, ready)
            }.collect { (request, entities, ready) ->
                request ?: return@collect
                if (!ready) return@collect
                pendingLaunchRequest.value = null
                val entity = entities[request.entityId]
                if (entity == null) {
                    showMissingLauncherShortcut(request.entityId, request.behavior)
                    return@collect
                }
                when (request.behavior?.lowercase()) {
                    "toggle" -> performPrimaryAction(entity)
                    "focus" -> focusEntity(entity.entityId)
                    else -> openShortcutDetails(entity)
                }
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
        flushPendingFocus()
    }

    fun updateSetupBaseUrl(value: String) {
        setupBaseUrl.value = value
        setupError.value = null
    }

    fun updateSetupToken(value: String) {
        setupToken.value = value
        setupError.value = null
    }

    fun saveConnection() {
        connectionValidationJob?.cancel()
        connectionValidationJob = viewModelScope.launch {
            val normalizedUrl = UrlValidator.normalize(setupBaseUrl.value)
            val token = setupToken.value.trim()
            if (normalizedUrl.isFailure || token.isBlank()) {
                setupError.value = normalizedUrl.exceptionOrNull()?.message ?: "Enter a Home Assistant token"
                return@launch
            }
            val validatedUrl = normalizedUrl.getOrThrow()
            val previousSettings = latestSettings
            activeSession = null
            homeAssistantRepository.stop()
            savingConnection.value = true
            try {
                val validation = try {
                    homeAssistantRepository.validateConnection(validatedUrl, token)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Result.failure(exception)
                }
                if (validation.isFailure) {
                    setupError.value = validation.exceptionOrNull()?.message ?: "Could not connect to Home Assistant"
                    synchronizeForegroundSession(previousSettings, foreground.value)
                    return@launch
                }
                tileSnapshotStore.clear()
                settingsRepository.saveConnection(validatedUrl, token)
                setupToken.value = ""
                setupError.value = null
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
        focusSaveJob?.cancel()
        focusSaveJob = null
        pendingFocusEntityId = null
        homeAssistantRepository.stop()
        activeSession = null
        latestSettings.channelId?.let { removeHomeChannel(it) }
        tileSnapshotStore.clear()
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
        setupError.value = null
        screen.value = AppScreen.ConnectionSetup
    }
    fun openDiagnostics() { screen.value = AppScreen.Diagnostics }
    fun openPrivacyPolicy() { screen.value = AppScreen.Privacy }
    fun closeScreen() {
        screen.value = AppScreen.Dashboard
        launcherShortcutReplacement.value = null
        restoreDashboardFocus()
    }
    fun dismissError() { error.value = null }

    fun addTile(entity: HaEntity) = viewModelScope.launch {
        val replacement = launcherShortcutReplacement.value
        val isAlreadyConfigured = latestSettings.tiles.any { it.entityId == entity.entityId }
        val tiles = if (isAlreadyConfigured) {
            latestSettings.tiles
        } else {
            latestSettings.tiles + TileConfiguration(entity.entityId, latestSettings.tiles.size)
        }
        if (replacement == null) {
            if (isAlreadyConfigured) return@launch
            settingsRepository.saveTiles(tiles)
            return@launch
        }

        val remainingShortcuts = latestSettings.homeShortcuts.filterNot {
            it.entityId == replacement.staleEntityId || it.entityId == entity.entityId
        }
        if (remainingShortcuts.size >= 4) {
            error.value = "Home screen already has four shortcuts. Remove one to continue."
            return@launch
        }
        val behavior = replacement.behavior.takeIf { it != ShortcutBehavior.DETAILS || supportsDetails(entity) }
            ?: ShortcutBehavior.FOCUS
        settingsRepository.saveTiles(tiles)
        val shortcuts = remainingShortcuts + ShortcutConfiguration(entity.entityId, behavior)
        settingsRepository.saveShortcuts(shortcuts)
        publishShortcutsIfEnabled(shortcuts)
        launcherShortcutReplacement.value = null
        focusEntity(entity.entityId)
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

    fun saveFocus(entityId: String) {
        if (entityId == pendingFocusEntityId) return
        if (focusSaveJob == null && entityId == latestSettings.lastFocusedEntityId) return
        pendingFocusEntityId = entityId
        focusSaveJob?.cancel()
        focusSaveJob = viewModelScope.launch {
            delay(FOCUS_SAVE_DEBOUNCE_MS)
            persistPendingFocus()
        }
    }

    fun enableBenchmarkFixture() {
        if (BuildConfig.BENCHMARK_MODE) benchmarkOverride.value = BenchmarkFixture.dashboardState()
    }

    fun handleLaunchRequest(
        entityId: String?,
        behavior: String?,
        benchmarkFixtureRequested: Boolean = false,
    ) {
        if (benchmarkFixtureRequested) enableBenchmarkFixture()
        val targetEntityId = entityId?.takeIf(String::isNotBlank) ?: return
        nextLaunchRequestSequence += 1
        pendingLaunchRequest.value = LaunchRequest(
            entityId = targetEntityId,
            behavior = behavior,
            sequence = nextLaunchRequestSequence,
        )
    }

    fun focusEntity(entityId: String) {
        screen.value = AppScreen.Dashboard
        details.value = null
        launcherRecovery.value = null
        nextFocusRequestSequence += 1
        focusRequest.value = FocusRequest(entityId, nextFocusRequestSequence)
        saveFocus(entityId)
    }

    fun acknowledgeFocusRequest(sequence: Long) {
        if (focusRequest.value?.sequence == sequence) focusRequest.value = null
    }

    fun showMissingLauncherShortcut(entityId: String = "", behavior: String? = null) {
        screen.value = AppScreen.Dashboard
        details.value = null
        focusRequest.value = null
        launcherShortcutReplacement.value = null
        val shortcutBehavior = when (behavior?.lowercase()) {
            "toggle" -> ShortcutBehavior.TOGGLE
            "details" -> ShortcutBehavior.DETAILS
            else -> ShortcutBehavior.FOCUS
        }
        launcherRecovery.value = LauncherRecovery(
            message = "This launcher shortcut is no longer available.",
            staleEntityId = entityId,
            behavior = shortcutBehavior,
        )
    }

    fun dismissLauncherRecovery() {
        launcherRecovery.value = null
        launcherShortcutReplacement.value = null
        restoreDashboardFocus()
    }

    fun openLauncherRecoveryControls() {
        launcherRecovery.value?.takeIf { it.staleEntityId.isNotBlank() }?.let { recovery ->
            launcherShortcutReplacement.value = LauncherShortcutReplacement(
                staleEntityId = recovery.staleEntityId,
                behavior = recovery.behavior,
            )
        }
        launcherRecovery.value = null
        openTileManager()
    }

    fun performPrimaryAction(entity: HaEntity) {
        when {
            entity.capabilities().canToggle -> toggle(entity)
            entity.capabilities().canActivate -> execute(ControlAction.ActivateScene(entity))
            entity.capabilities().canRun -> execute(ControlAction.RunScript(entity))
            entity.capabilities().canPress -> execute(ControlAction.PressButton(entity))
            else -> openDetails(entity)
        }
    }

    fun performPrimaryAction(entityId: String) {
        val entity = latestEntities[entityId]
        if (entity == null) {
            commandFeedback.value += entityId to CommandFeedback.Failed("Waiting for live Home Assistant state")
            return
        }
        performPrimaryAction(entity)
    }

    fun toggle(entity: HaEntity) = execute(ControlAction.Toggle(entity))

    fun supportsDetails(entity: HaEntity): Boolean =
        entity.capabilities().canSetLevel ||
            entity.domain == "climate" ||
            entity.domain == "cover" ||
            entity.domain == "alarm_control_panel" ||
            entity.capabilities().canSetNumber ||
            entity.capabilities().canSelectOption ||
            entity.capabilities().canSetText

    fun openDetails(entity: HaEntity) {
        details.value = when {
            entity.capabilities().canSetLevel -> DetailState.Level(entity, entity.levelPercent() ?: 50)
            entity.domain == "climate" -> DetailState.Climate(entity)
            entity.domain == "cover" -> DetailState.Cover(entity)
            entity.domain == "alarm_control_panel" -> DetailState.Alarm(entity)
            entity.capabilities().canSetNumber -> DetailState.Number(entity)
            entity.capabilities().canSelectOption -> DetailState.Select(entity)
            entity.capabilities().canSetText -> DetailState.Text(entity)
            else -> null
        }
    }

    fun openDetails(entityId: String) {
        val entity = latestEntities[entityId]
        if (entity == null) {
            commandFeedback.value += entityId to CommandFeedback.Failed("Details are available after Home Assistant reconnects")
            return
        }
        openDetails(entity)
    }

    fun dismissCommandFailure(entityId: String) {
        if (commandFeedback.value[entityId] is CommandFeedback.Failed) {
            commandFeedback.value -= entityId
        }
    }

    fun openShortcutDetails(entity: HaEntity) {
        screen.value = AppScreen.Dashboard
        details.value = null
        focusRequest.value = null
        launcherRecovery.value = null
        openDetails(entity)
        if (details.value == null) focusEntity(entity.entityId)
    }

    fun closeDetails() {
        val entityId = details.value?.entityId()
        details.value = null
        entityId?.let(::focusEntity)
    }

    fun stageLevel(percent: Int) {
        details.value = (details.value as? DetailState.Level)?.copy(stagedPercent = percent.coerceIn(0, 100))
    }

    fun applyLevel() {
        val detail = details.value as? DetailState.Level ?: return
        execute(ControlAction.SetLevel(detail.entity, detail.stagedPercent))
        closeDetails()
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
        closeDetails()
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
        closeDetails()
    }

    fun cancelSecureCover() {
        details.value = (details.value as? DetailState.Cover)?.copy(pendingCommand = null, pendingPosition = null)
    }

    fun updateAlarmCode(code: String) {
        details.value = (details.value as? DetailState.Alarm)?.copy(disarmCode = code.take(16))
    }

    fun stageNumberValue(value: Double) {
        details.value = (details.value as? DetailState.Number)?.let { detail ->
            detail.copy(stagedValue = value.coerceIn(detail.entity.numberMinimum(), detail.entity.numberMaximum()))
        }
    }

    fun applyNumberValue() {
        val detail = details.value as? DetailState.Number ?: return
        execute(ControlAction.SetNumberValue(detail.entity, detail.stagedValue))
        closeDetails()
    }

    fun selectOption(option: String) {
        val detail = details.value as? DetailState.Select ?: return
        if (option !in detail.entity.selectOptions()) return
        execute(ControlAction.SelectOption(detail.entity, option))
        closeDetails()
    }

    fun updateTextValue(value: String) {
        details.value = (details.value as? DetailState.Text)?.copy(stagedValue = value)
    }

    fun applyTextValue() {
        val detail = details.value as? DetailState.Text ?: return
        execute(ControlAction.SetTextValue(detail.entity, detail.stagedValue))
        closeDetails()
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
        closeDetails()
    }

    fun disarmAlarm() {
        val detail = details.value as? DetailState.Alarm ?: return
        if (detail.disarmCode.isBlank()) {
            error.value = "Enter the Home Assistant alarm code"
        } else {
            execute(ControlAction.DisarmAlarm(detail.entity, detail.disarmCode))
            closeDetails()
        }
    }

    fun setShortcut(entityId: String, behavior: ShortcutBehavior) = viewModelScope.launch {
        val existing = latestSettings.homeShortcuts.filterNot { it.entityId == entityId }
        if (existing.size >= MAX_HOME_SHORTCUTS) {
            error.value = "Home screen already has four shortcuts. Remove one to continue."
            return@launch
        }
        val shortcuts = existing + ShortcutConfiguration(entityId, behavior)
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
        if (latestSettings.homeShortcuts.isEmpty()) {
            error.value = "Choose at least one Home screen shortcut first"
            return@launch
        }
        publishHomeChannel(latestSettings, requestHomeChannel = true)
    }

    fun disableHomeChannel() = viewModelScope.launch {
        val channelId = latestSettings.channelId
        if (channelId != null && !removeHomeChannel(channelId)) return@launch
        settingsRepository.setHomeChannel(enabled = false)
    }

    fun refreshHomeChannel() = viewModelScope.launch {
        if (!latestSettings.homeChannelEnabled) return@launch
        publishHomeChannel(latestSettings)
    }

    fun refreshHomeChannelWhenReady() = viewModelScope.launch {
        val readyState = combine(
            settingsRepository.settings,
            homeAssistantRepository.initialStatesLoaded,
            homeAssistantRepository.status,
        ) { settings, initialStatesLoaded, status ->
            Triple(settings, initialStatesLoaded, status)
        }.first { (settings, initialStatesLoaded, status) ->
            val configured = settings.baseUrl != null && settings.tokenEnvelope != null
            !settings.homeChannelEnabled || !configured || initialStatesLoaded || status is ConnectionStatus.Failed
        }
        val (settings, initialStatesLoaded, _) = readyState
        val configured = settings.baseUrl != null && settings.tokenEnvelope != null
        if (settings.homeChannelEnabled && configured && initialStatesLoaded) publishHomeChannel(settings)
    }

    private suspend fun publishShortcutsIfEnabled(shortcuts: List<ShortcutConfiguration>) {
        if (!latestSettings.homeChannelEnabled) return
        publishHomeChannel(latestSettings.copy(homeShortcuts = shortcuts))
    }

    private suspend fun publishHomeChannel(settings: AppSettings, requestHomeChannel: Boolean = false) {
        val channelId = try {
            withContext(ioDispatcher) { homeChannelPublisher.createOrUpdate(settings, latestEntities) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            error.value = "Could not update the Android TV Home channel"
            return
        }
        settingsRepository.setHomeChannel(enabled = true, channelId = channelId)
        if (requestHomeChannel) _homeChannelRequests.emit(channelId)
    }

    private suspend fun removeHomeChannel(channelId: Long): Boolean = try {
        withContext(ioDispatcher) { homeChannelPublisher.remove(channelId) }
        true
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        error.value = "Could not remove the Android TV Home channel"
        false
    }

    private fun restoreDashboardFocus() {
        val targetEntityId = latestSettings.lastFocusedEntityId
            ?.takeIf { it in latestEntities }
            ?: latestSettings.tiles
                .sortedBy(TileConfiguration::position)
                .firstNotNullOfOrNull { tile -> tile.entityId.takeIf { it in latestEntities } }
        targetEntityId?.let(::focusEntity)
    }

    private fun flushPendingFocus() {
        focusSaveJob?.cancel()
        focusSaveJob = null
        val entityId = pendingFocusEntityId ?: return
        pendingFocusEntityId = null
        lateinit var saveJob: Job
        saveJob = viewModelScope.launch {
            if (entityId != latestSettings.lastFocusedEntityId) {
                settingsRepository.saveLastFocusedEntity(entityId)
            }
        }
        focusSaveJob = saveJob
        saveJob.invokeOnCompletion {
            if (focusSaveJob === saveJob) focusSaveJob = null
        }
    }

    private suspend fun persistPendingFocus() {
        val entityId = pendingFocusEntityId ?: return
        pendingFocusEntityId = null
        focusSaveJob = null
        if (entityId != latestSettings.lastFocusedEntityId) {
            settingsRepository.saveLastFocusedEntity(entityId)
        }
    }

    private fun DetailState.entityId(): String = when (this) {
        is DetailState.Level -> entity.entityId
        is DetailState.Climate -> entity.entityId
        is DetailState.Cover -> entity.entityId
        is DetailState.Alarm -> entity.entityId
        is DetailState.Number -> entity.entityId
        is DetailState.Select -> entity.entityId
        is DetailState.Text -> entity.entityId
    }

    private fun ControlAction.entityId(): String = when (this) {
        is ControlAction.Toggle -> entity.entityId
        is ControlAction.SetLevel -> entity.entityId
        is ControlAction.SetClimateMode -> entity.entityId
        is ControlAction.SetClimateTemperature -> entity.entityId
        is ControlAction.ActivateScene -> entity.entityId
        is ControlAction.RunScript -> entity.entityId
        is ControlAction.PressButton -> entity.entityId
        is ControlAction.SetNumberValue -> entity.entityId
        is ControlAction.SelectOption -> entity.entityId
        is ControlAction.SetTextValue -> entity.entityId
        is ControlAction.CoverCommand -> entity.entityId
        is ControlAction.ArmAlarm -> entity.entityId
        is ControlAction.DisarmAlarm -> entity.entityId
    }

    private fun execute(action: ControlAction) = viewModelScope.launch {
        val id = action.entityId()
        if (commandFeedback.value[id] is CommandFeedback.Pending) return@launch
        commandFeedback.value += id to CommandFeedback.Pending
        val result = homeAssistantRepository.execute(action)
        if (result.isSuccess) {
            nextCommandFeedbackSequence += 1
            val success = CommandFeedback.Succeeded(nextCommandFeedbackSequence)
            commandFeedback.value += id to success
            delay(COMMAND_SUCCESS_VISIBLE_MS)
            if (commandFeedback.value[id] == success) commandFeedback.value -= id
        } else {
            commandFeedback.value += id to CommandFeedback.Failed(
                result.exceptionOrNull()?.message ?: "Home Assistant did not accept the command",
            )
        }
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
        focusSaveJob?.cancel()
        snapshotSaveJob?.cancel()
        homeAssistantRepository.stop()
    }

    private companion object {
        const val MAX_HOME_SHORTCUTS = 4
        const val FOCUS_SAVE_DEBOUNCE_MS = 300L
        const val SNAPSHOT_SAVE_DEBOUNCE_MS = 1_000L
        const val COMMAND_SUCCESS_VISIBLE_MS = 1_200L
    }
}

sealed interface CommandFeedback {
    data object Pending : CommandFeedback
    data class Succeeded(val sequence: Long) : CommandFeedback
    data class Failed(val message: String) : CommandFeedback
}
