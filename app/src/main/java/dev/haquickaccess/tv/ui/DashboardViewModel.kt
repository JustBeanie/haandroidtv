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
import dev.haquickaccess.tv.domain.model.levelPercent
import dev.haquickaccess.tv.domain.model.numberMaximum
import dev.haquickaccess.tv.domain.model.numberMinimum
import dev.haquickaccess.tv.domain.model.selectOptions
import dev.haquickaccess.tv.platform.HomeChannelGateway
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.withLock
import dev.haquickaccess.tv.platform.HomeChannelTransactions
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) : ViewModel(), DashboardActions {
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
    private val commandJobs = mutableSetOf<Job>()
    private var connectionValidationJob: Job? = null
    private var validationGeneration = 0L
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
        latestSettings = benchmarkOverride.value?.settings ?: currentSettings
        latestEntities = benchmarkOverride.value?.entities ?: entities
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

    private val displayedConnectionState = combine(connectionState, benchmarkOverride) { connection, fixture ->
        if (fixture == null) connection else {
            latestSettings = fixture.settings
            latestEntities = fixture.entities
            DashboardConnectionState(fixture.settings, true, fixture.entities, true, fixture.connectionStatus, null)
        }
    }

    private val liveUiState: StateFlow<DashboardUiState> = combine(
        displayedConnectionState,
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

    val uiState: StateFlow<DashboardUiState> = liveUiState

    init {
        viewModelScope.launch {
            combine(settingsRepository.settings, foreground, savingConnection) { configuredSettings, isForeground, isSaving ->
                Triple(configuredSettings, isForeground, isSaving)
            }.collectLatest { (configuredSettings, isForeground, isSaving) ->
                if (benchmarkOverride.value != null) return@collectLatest
                latestSettings = configuredSettings
                if (!isSaving) synchronizeForegroundSession(configuredSettings, isForeground)
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
                    return@combine null
                }
                currentSettings.connectionIdentity to currentSettings.tiles
                    .sortedBy(TileConfiguration::position)
                    .mapNotNull { currentEntities[it.entityId] }
                    .map { it.toDashboardTileUiModel().toSnapshotEntry() }
            }.distinctUntilChanged().collect { projection ->
                snapshotSaveJob?.cancel()
                val (identity, entries) = projection ?: return@collect
                if (entries.isEmpty()) return@collect
                snapshotSaveJob = viewModelScope.launch(ioDispatcher) {
                    delay(SNAPSHOT_SAVE_DEBOUNCE_MS)
                    HomeChannelTransactions.mutex.withLock {
                        if (settingsRepository.settings.first().connectionIdentity != identity) return@withLock
                        tileSnapshotStore.save(
                            TileSnapshot(
                                capturedAtEpochMillis = System.currentTimeMillis(),
                                tiles = entries,
                                connectionIdentity = identity,
                            ),
                        )
                    }
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

    override fun onForeground() {
        foreground.value = true
    }

    override fun onBackground() {
        activeSession = null
        foreground.value = false
        cancelCommands()
        homeAssistantRepository.stop()
        validationGeneration += 1
        connectionValidationJob?.cancel()
        connectionValidationJob = null
        savingConnection.value = false
        setupToken.value = ""
        details.value = (details.value as? DetailState.Alarm)?.copy(disarmCode = "") ?: details.value
        flushPendingFocus()
    }

    override fun updateSetupBaseUrl(value: String) {
        setupBaseUrl.value = value
        setupError.value = null
    }

    override fun updateSetupToken(value: String) {
        setupToken.value = value
        setupError.value = null
    }

    override fun saveConnection() {
        val generation = ++validationGeneration
        val previousJob = connectionValidationJob
        previousJob?.cancel()
        connectionValidationJob = viewModelScope.launch {
            previousJob?.join()
            val normalizedUrl = UrlValidator.normalize(setupBaseUrl.value)
            val token = setupToken.value.trim()
            if (normalizedUrl.isFailure || token.isBlank()) {
                savingConnection.value = false
                setupError.value = normalizedUrl.exceptionOrNull()?.message ?: "Enter a Home Assistant token"
                return@launch
            }
            val validatedUrl = normalizedUrl.getOrThrow()
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
                    return@launch
                }
                cancelCommands()
                snapshotSaveJob?.cancel()
                snapshotSaveJob?.join()
                HomeChannelTransactions.mutex.withLock {
                    // Stop the old feed before publishing new credentials or caching its entities.
                    activeSession = null
                    homeAssistantRepository.stop()
                    tileSnapshotStore.clear()
                    settingsRepository.saveConnection(validatedUrl, token)
                }
                setupToken.value = ""
                setupError.value = null
                error.value = null
                screen.value = AppScreen.Dashboard
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                setupError.value = "Could not save the connection. Please try again."
            } finally {
                if (validationGeneration == generation) savingConnection.value = false
            }
        }
    }

    override fun clearConnection() = viewModelScope.launch {
        validationGeneration += 1
        savingConnection.value = true
        try {
            cancelCommands()
            snapshotSaveJob?.cancel()
            snapshotSaveJob?.join()
            connectionValidationJob?.cancel()
            connectionValidationJob?.join()
            connectionValidationJob = null
            focusSaveJob?.cancel()
            focusSaveJob = null
            pendingFocusEntityId = null
            activeSession = null
            homeAssistantRepository.stop()
            HomeChannelTransactions.mutex.withLock {
                settingsRepository.settings.first().channelId?.let { removeHomeChannel(it) }
                tileSnapshotStore.clear()
                settingsRepository.clearConnection()
            }
            setupToken.value = ""
            pendingLaunchRequest.value = null
            details.value = null
            screen.value = AppScreen.Dashboard
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            error.value = "Could not forget the connection. Please try again."
        } finally {
            savingConnection.value = false
        }
    }

    override fun openAllControls() { screen.value = AppScreen.AllControls }
    override fun openFavorites() { screen.value = AppScreen.Dashboard; restoreDashboardFocus() }
    override fun openSettings() { screen.value = AppScreen.Settings }
    override fun openTileManager() { screen.value = AppScreen.ManageTiles }
    override fun openShortcutManager() { screen.value = AppScreen.ManageShortcuts }
    override fun openConnectionSetup() {
        setupBaseUrl.value = latestSettings.baseUrl.orEmpty()
        setupToken.value = ""
        setupError.value = null
        screen.value = AppScreen.ConnectionSetup
    }
    override fun openDiagnostics() { screen.value = AppScreen.Diagnostics }
    override fun openPrivacyPolicy() { screen.value = AppScreen.Privacy }
    override fun closeScreen() {
        screen.value = when (screen.value) {
            AppScreen.ConnectionSetup, AppScreen.Diagnostics, AppScreen.Privacy, AppScreen.ManageShortcuts, AppScreen.ManageTiles -> AppScreen.Settings
            else -> AppScreen.Dashboard
        }
        launcherShortcutReplacement.value = null
        if (screen.value == AppScreen.Dashboard) restoreDashboardFocus()
    }
    override fun dismissError() { error.value = null }

    override fun addTile(entity: HaEntity) = viewModelScope.launch {
        val replacement = launcherShortcutReplacement.value
        try {
            val updated = settingsRepository.updateControls { current ->
                val tiles = if (current.tiles.any { it.entityId == entity.entityId }) current.tiles
                    else current.tiles + TileConfiguration(entity.entityId, current.tiles.size)
                if (replacement == null) current.copy(tiles = tiles) else {
                    val remaining = current.homeShortcuts.filterNot {
                        it.entityId == replacement.staleEntityId || it.entityId == entity.entityId
                    }
                    require(remaining.size < MAX_HOME_SHORTCUTS) { "Home screen already has four shortcuts. Remove one to continue." }
                    val behavior = replacement.behavior.takeIf { it != ShortcutBehavior.DETAILS || supportsDetails(entity) }
                        ?: ShortcutBehavior.FOCUS
                    current.copy(tiles = tiles, homeShortcuts = remaining + ShortcutConfiguration(entity.entityId, behavior))
                }
            }
            if (replacement != null) {
                publishShortcutsIfEnabled(updated.homeShortcuts)
                launcherShortcutReplacement.value = null
                focusEntity(entity.entityId)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error.value = exception.message ?: "Could not update favorites"
        }
    }

    override fun removeTile(entityId: String) = viewModelScope.launch {
        val updated = settingsRepository.updateControls { current ->
            current.copy(
                tiles = current.tiles.filterNot { it.entityId == entityId }
                    .mapIndexed { index, tile -> tile.copy(position = index) },
                homeShortcuts = current.homeShortcuts.filterNot { it.entityId == entityId },
            )
        }
        publishShortcutsIfEnabled(updated.homeShortcuts)
    }

    override fun moveTile(entityId: String, direction: Int) = viewModelScope.launch {
        settingsRepository.updateControls { current ->
            val tiles = current.tiles.sortedBy(TileConfiguration::position).toMutableList()
            val from = tiles.indexOfFirst { it.entityId == entityId }
            if (from < 0) return@updateControls current
            val to = (from + direction.coerceIn(-1, 1)).coerceIn(0, tiles.lastIndex)
            if (from != to) tiles.add(to, tiles.removeAt(from))
            current.copy(tiles = tiles.mapIndexed { index, tile -> tile.copy(position = index) })
        }
    }

    override fun saveFocus(entityId: String) {
        if (benchmarkOverride.value != null) return
        if (entityId == pendingFocusEntityId) return
        if (focusSaveJob == null && entityId == latestSettings.lastFocusedEntityId) return
        pendingFocusEntityId = entityId
        focusSaveJob?.cancel()
        focusSaveJob = viewModelScope.launch {
            delay(FOCUS_SAVE_DEBOUNCE_MS)
            persistPendingFocus()
        }
    }

    override fun enableBenchmarkFixture() {
        if (BuildConfig.BENCHMARK_MODE) benchmarkOverride.value = BenchmarkFixture.dashboardState()
    }

    override fun handleLaunchRequest(
        entityId: String?,
        behavior: String?,
        benchmarkFixtureRequested: Boolean,
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

    override fun focusEntity(entityId: String) {
        screen.value = AppScreen.Dashboard
        details.value = null
        launcherRecovery.value = null
        nextFocusRequestSequence += 1
        focusRequest.value = FocusRequest(entityId, nextFocusRequestSequence)
        saveFocus(entityId)
    }

    override fun acknowledgeFocusRequest(sequence: Long) {
        if (focusRequest.value?.sequence == sequence) focusRequest.value = null
    }

    override fun showMissingLauncherShortcut(entityId: String, behavior: String?) {
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

    override fun dismissLauncherRecovery() {
        launcherRecovery.value = null
        launcherShortcutReplacement.value = null
        restoreDashboardFocus()
    }

    override fun openLauncherRecoveryControls() {
        launcherRecovery.value?.takeIf { it.staleEntityId.isNotBlank() }?.let { recovery ->
            launcherShortcutReplacement.value = LauncherShortcutReplacement(
                staleEntityId = recovery.staleEntityId,
                behavior = recovery.behavior,
            )
        }
        launcherRecovery.value = null
        openTileManager()
    }

    override fun performPrimaryAction(entity: HaEntity) {
        when {
            entity.capabilities().canToggle -> toggle(entity)
            entity.capabilities().canActivate -> execute(ControlAction.ActivateScene(entity))
            entity.capabilities().canRun -> execute(ControlAction.RunScript(entity))
            entity.capabilities().canPress -> execute(ControlAction.PressButton(entity))
            else -> openDetails(entity)
        }
    }

    override fun performPrimaryAction(entityId: String) {
        val entity = latestEntities[entityId]
        if (entity == null) {
            commandFeedback.value += entityId to CommandFeedback.Failed("Waiting for live Home Assistant state")
            return
        }
        performPrimaryAction(entity)
    }

    override fun toggle(entity: HaEntity) = execute(ControlAction.Toggle(entity))

    override fun supportsDetails(entity: HaEntity): Boolean =
        entity.capabilities().canSetLevel ||
            entity.domain == "climate" ||
            entity.domain == "cover" ||
            entity.domain == "alarm_control_panel" ||
            entity.capabilities().canSetNumber ||
            entity.capabilities().canSelectOption ||
            entity.capabilities().canSetText

    override fun openDetails(entity: HaEntity) {
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

    override fun openDetails(entityId: String) {
        val entity = latestEntities[entityId]
        if (entity == null) {
            commandFeedback.value += entityId to CommandFeedback.Failed("Details are available after Home Assistant reconnects")
            return
        }
        openDetails(entity)
    }

    override fun dismissCommandFailure(entityId: String) {
        if (commandFeedback.value[entityId] is CommandFeedback.Failed) {
            commandFeedback.value -= entityId
        }
    }

    override fun openShortcutDetails(entity: HaEntity) {
        screen.value = AppScreen.Dashboard
        details.value = null
        focusRequest.value = null
        launcherRecovery.value = null
        openDetails(entity)
        if (details.value == null) focusEntity(entity.entityId)
    }

    override fun closeDetails() {
        val entityId = details.value?.entityId()
        details.value = null
        if (screen.value == AppScreen.Dashboard) entityId?.let(::focusEntity)
    }

    override fun stageLevel(percent: Int) {
        details.value = (details.value as? DetailState.Level)?.copy(stagedPercent = percent.coerceIn(0, 100))
    }

    override fun applyLevel() {
        val detail = details.value as? DetailState.Level ?: return
        execute(ControlAction.SetLevel(detail.entity, detail.stagedPercent))
        closeDetails()
    }

    override fun setClimateMode(entity: HaEntity, mode: String) {
        execute(ControlAction.SetClimateMode(entity, mode))
    }

    override fun stageClimateTemperature(value: Double) {
        details.value = (details.value as? DetailState.Climate)?.let { detail ->
            detail.copy(
                stagedTemperature = value.coerceIn(
                    detail.entity.climateMinimum(),
                    detail.entity.climateMaximum(),
                ),
            )
        }
    }

    override fun applyClimateTemperature() {
        val detail = details.value as? DetailState.Climate ?: return
        detail.stagedTemperature?.let { execute(ControlAction.SetClimateTemperature(detail.entity, it)) }
    }

    override fun coverCommand(command: ControlAction.CoverCommand.Command, position: Int?) {
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

    override fun stageCoverPosition(position: Int) {
        details.value = (details.value as? DetailState.Cover)?.copy(stagedPosition = position.coerceIn(0, 100))
    }

    override fun applyCoverPosition() {
        val detail = details.value as? DetailState.Cover ?: return
        detail.stagedPosition?.let { coverCommand(ControlAction.CoverCommand.Command.SET_POSITION, it) }
    }

    override fun confirmSecureCover() {
        val detail = details.value as? DetailState.Cover ?: return
        val command = detail.pendingCommand ?: return
        execute(ControlAction.CoverCommand(detail.entity, command, detail.pendingPosition))
        closeDetails()
    }

    override fun cancelSecureCover() {
        details.value = (details.value as? DetailState.Cover)?.copy(pendingCommand = null, pendingPosition = null)
    }

    override fun updateAlarmCode(code: String) {
        details.value = (details.value as? DetailState.Alarm)?.copy(disarmCode = code.take(16))
    }

    override fun stageNumberValue(value: Double) {
        details.value = (details.value as? DetailState.Number)?.let { detail ->
            detail.copy(stagedValue = value.coerceIn(detail.entity.numberMinimum(), detail.entity.numberMaximum()))
        }
    }

    override fun applyNumberValue() {
        val detail = details.value as? DetailState.Number ?: return
        execute(ControlAction.SetNumberValue(detail.entity, detail.stagedValue))
        closeDetails()
    }

    override fun selectOption(option: String) {
        val detail = details.value as? DetailState.Select ?: return
        if (option !in detail.entity.selectOptions()) return
        execute(ControlAction.SelectOption(detail.entity, option))
        closeDetails()
    }

    override fun updateTextValue(value: String) {
        details.value = (details.value as? DetailState.Text)?.copy(stagedValue = value)
    }

    override fun applyTextValue() {
        val detail = details.value as? DetailState.Text ?: return
        execute(ControlAction.SetTextValue(detail.entity, detail.stagedValue))
        closeDetails()
    }

    override fun armAlarm(mode: String) {
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

    override fun disarmAlarm() {
        val detail = details.value as? DetailState.Alarm ?: return
        if (detail.disarmCode.isBlank()) {
            error.value = "Enter the Home Assistant alarm code"
        } else {
            execute(ControlAction.DisarmAlarm(detail.entity, detail.disarmCode))
            closeDetails()
        }
    }

    override fun setShortcut(entityId: String, behavior: ShortcutBehavior) = viewModelScope.launch {
        try {
            val updated = settingsRepository.updateControls { current ->
                val existing = current.homeShortcuts.filterNot { it.entityId == entityId }
                require(existing.size < MAX_HOME_SHORTCUTS) { "Home screen already has four shortcuts. Remove one to continue." }
                current.copy(homeShortcuts = existing + ShortcutConfiguration(entityId, behavior))
            }
            publishShortcutsIfEnabled(updated.homeShortcuts)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error.value = exception.message ?: "Could not update shortcuts"
        }
    }

    override fun removeShortcut(entityId: String) = viewModelScope.launch {
        var changed = false
        val updated = settingsRepository.updateControls { current ->
            changed = current.homeShortcuts.any { it.entityId == entityId }
            current.copy(homeShortcuts = current.homeShortcuts.filterNot { it.entityId == entityId })
        }
        if (changed) publishShortcutsIfEnabled(updated.homeShortcuts)
    }

    override fun enableHomeChannel() = viewModelScope.launch {
        if (latestSettings.homeShortcuts.isEmpty()) {
            error.value = "Choose at least one Home screen shortcut first"
            return@launch
        }
        publishHomeChannel(latestSettings, requestHomeChannel = true)
    }

    override fun disableHomeChannel() = viewModelScope.launch {
        HomeChannelTransactions.mutex.withLock {
            val channelId = settingsRepository.settings.first().channelId
            if (channelId != null && !removeHomeChannel(channelId)) return@withLock
            settingsRepository.setHomeChannel(enabled = false)
        }
    }

    override fun refreshHomeChannel() = viewModelScope.launch {
        if (!latestSettings.homeChannelEnabled) return@launch
        publishHomeChannel(latestSettings)
    }

    override fun refreshHomeChannelWhenReady() = viewModelScope.launch {
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
        HomeChannelTransactions.mutex.withLock {
            val current = settingsRepository.settings.first()
            if (!requestHomeChannel && !current.homeChannelEnabled) return@withLock
            if (current.baseUrl != settings.baseUrl || current.tokenEnvelope != settings.tokenEnvelope) return@withLock
            val entities = latestEntities
            val channelId = try {
                // A provider write and its saved ID must finish together even if the screen closes.
                withContext(NonCancellable + ioDispatcher) {
                    val id = homeChannelPublisher.createOrUpdate(current, entities)
                    settingsRepository.setHomeChannel(enabled = true, channelId = id)
                    id
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                error.value = "Could not update the Android TV Home channel"
                return@withLock
            }
            if (requestHomeChannel) _homeChannelRequests.emit(channelId)
        }
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

    private fun execute(action: ControlAction): Job {
        val job = viewModelScope.launch {
            val id = action.entityId()
            if (commandFeedback.value[id] is CommandFeedback.Pending) return@launch
            commandFeedback.value += id to CommandFeedback.Pending
            val result = if (benchmarkOverride.value != null) Result.success(Unit) else homeAssistantRepository.execute(action)
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
        commandJobs += job
        job.invokeOnCompletion { commandJobs.remove(job) }
        return job
    }

    private fun cancelCommands() {
        commandJobs.toList().forEach { it.cancel() }
        commandJobs.clear()
        commandFeedback.value = emptyMap()
    }

    private suspend fun synchronizeForegroundSession(settings: AppSettings, isForeground: Boolean) {
        if (!isForeground || settings.baseUrl == null || settings.tokenEnvelope == null) {
            if (activeSession != null) {
                homeAssistantRepository.stop()
                activeSession = null
            }
            return
        }
        val baseUrl = settings.baseUrl
        if (savingConnection.value) return
        val envelope = settings.tokenEnvelope
        val requestedSession = baseUrl to envelope
        if (activeSession == requestedSession) return
        val token = try {
            withContext(ioDispatcher) { settingsRepository.decryptToken(settings) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            error.value = "Saved token can no longer be read. Connect Home Assistant again."
            if (activeSession != null) {
                homeAssistantRepository.stop()
                activeSession = null
            }
            return
        } ?: return
        if (!foreground.value) return
        cancelCommands()
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
