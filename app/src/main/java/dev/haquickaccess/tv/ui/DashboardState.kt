package dev.haquickaccess.tv.ui

import dev.haquickaccess.tv.data.AppSettings
import dev.haquickaccess.tv.data.ConnectionStatus
import dev.haquickaccess.tv.data.TileSnapshot
import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.ShortcutBehavior
import dev.haquickaccess.tv.domain.model.TileConfiguration
import dev.haquickaccess.tv.domain.model.capabilities
import dev.haquickaccess.tv.domain.model.climateMinimum
import dev.haquickaccess.tv.domain.model.climateTarget
import dev.haquickaccess.tv.domain.model.levelPercent
import dev.haquickaccess.tv.domain.model.numberMaximum
import dev.haquickaccess.tv.domain.model.numberMinimum

sealed interface AppScreen {
    data object Dashboard : AppScreen
    data object AllControls : AppScreen
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
            val cachedTiles = tileSnapshot?.takeIf { it.connectionIdentity == settings.connectionIdentity }?.tiles.orEmpty().associateBy { it.entityId }
            orderedConfigurations.mapNotNull { configuration ->
                liveTilesById[configuration.entityId]
                    ?: cachedTiles[configuration.entityId]?.toDashboardTileUiModel()
            }
        }
    }
    val isShowingCachedSnapshot: Boolean get() = !areInitialStatesLoaded &&
        tileSnapshot?.connectionIdentity == settings.connectionIdentity &&
        tileSnapshot?.tiles.orEmpty().any { cached -> settings.tiles.any { it.entityId == cached.entityId } && cached.entityId !in entities }
    val availableEntities: List<HaEntity> by lazy(LazyThreadSafetyMode.NONE) {
        entities.values.sortedBy(HaEntity::name)
    }
}
