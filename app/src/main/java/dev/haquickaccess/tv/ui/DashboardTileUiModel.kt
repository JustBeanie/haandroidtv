package dev.haquickaccess.tv.ui

import androidx.compose.runtime.Immutable
import dev.haquickaccess.tv.data.TileSnapshotEntry
import dev.haquickaccess.tv.domain.model.ControlKind
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.actionLabel
import dev.haquickaccess.tv.domain.model.capabilities
import dev.haquickaccess.tv.domain.model.climateTarget
import dev.haquickaccess.tv.domain.model.levelPercent
import kotlin.math.roundToInt

@Immutable
data class DashboardTileUiModel(
    val entityId: String,
    val name: String,
    val kind: ControlKind,
    val stateLabel: String,
    val active: Boolean,
    val unavailable: Boolean,
    val live: Boolean,
    val supportsDetails: Boolean,
)

internal fun HaEntity.toDashboardTileUiModel(): DashboardTileUiModel {
    val capability = capabilities()
    val label = when {
        unavailable -> "Unavailable"
        domain == "climate" -> listOfNotNull(
            climateTarget()?.roundToInt()?.let { "$it°" },
            state.replaceFirstChar(Char::uppercase),
        ).joinToString(" · ")
        levelPercent() != null -> "${levelPercent()}%"
        else -> actionLabel()
    }
    return DashboardTileUiModel(
        entityId = entityId,
        name = name,
        kind = capability.kind,
        stateLabel = label,
        active = isOn,
        unavailable = unavailable,
        live = true,
        supportsDetails = capability.canSetLevel ||
            domain == "climate" ||
            domain == "cover" ||
            domain == "alarm_control_panel" ||
            capability.canSetNumber ||
            capability.canSelectOption ||
            capability.canSetText,
    )
}

internal fun TileSnapshotEntry.toDashboardTileUiModel(): DashboardTileUiModel = DashboardTileUiModel(
    entityId = entityId,
    name = name,
    kind = runCatching { ControlKind.valueOf(kind) }.getOrDefault(ControlKind.UNSUPPORTED),
    stateLabel = stateLabel,
    active = active,
    unavailable = unavailable,
    live = false,
    supportsDetails = false,
)

internal fun DashboardTileUiModel.toSnapshotEntry(): TileSnapshotEntry = TileSnapshotEntry(
    entityId = entityId,
    name = name,
    kind = kind.name,
    stateLabel = stateLabel,
    active = active,
    unavailable = unavailable,
)
