package dev.haquickaccess.tv.ui

import androidx.compose.ui.res.stringResource
import dev.haquickaccess.tv.R
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.haquickaccess.tv.domain.model.ControlKind
import dev.haquickaccess.tv.data.ConnectionStatus

@Composable
internal fun DashboardScreen(state: DashboardUiState, onEvent: DashboardActions, navigationFocusRequester: FocusRequester? = null) {
    val dashboardTiles = remember(state.settings.tiles, state.entities, state.areInitialStatesLoaded, state.tileSnapshot) { state.dashboardTiles }
    val gridState = rememberLazyGridState()
    val localNavigationFocus = remember { FocusRequester() }
    val settingsFocusRequester = navigationFocusRequester ?: localNavigationFocus
    val dashboardFocusRequester = remember { FocusRequester() }
    var initialFocusTarget by remember { mutableStateOf<String?>(null) }
    var restoredInitialPosition by remember { mutableStateOf(false) }
    var focusedTile by remember { mutableStateOf<DashboardTileUiModel?>(null) }
    val dashboardFocusSuppressed = state.details != null || state.launcherRecovery != null
    val restoredFocusIndex = dashboardTiles.indexOfFirst { it.entityId == state.settings.lastFocusedEntityId }
    val contextTile = focusedTile?.let { focused -> dashboardTiles.firstOrNull { it.entityId == focused.entityId } }
        ?: dashboardTiles.getOrNull(restoredFocusIndex)
        ?: dashboardTiles.firstOrNull()
    LaunchedEffect(dashboardTiles, state.focusRequest?.sequence, dashboardFocusSuppressed) {
        if (dashboardFocusSuppressed) {
            initialFocusTarget = null
            return@LaunchedEffect
        }
        val requestedFocusIndex = state.focusRequest
            ?.entityId
            ?.let { targetEntityId -> dashboardTiles.indexOfFirst { it.entityId == targetEntityId } }
            ?.takeIf { it >= 0 }
        if (dashboardTiles.isNotEmpty() && (requestedFocusIndex != null || !restoredInitialPosition)) {
            val targetIndex = requestedFocusIndex ?: restoredFocusIndex.takeIf { it >= 0 } ?: 0
            gridState.scrollToItem(targetIndex)
            // Lazy grid items are composed after the scroll request. Waiting for a
            // rendered frame prevents a cold launch from briefly having no real
            // D-pad target (and lets the first Select land on an unintended card).
            withFrameNanos { }
            initialFocusTarget = dashboardTiles[targetIndex].entityId
            restoredInitialPosition = true
        }
    }
    ReportDrawnWhen { dashboardTiles.isEmpty() || restoredInitialPosition }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 360.dp
        val minimumTileHeight = 128.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (compact && contextTile != null) {
                        HaText(focusedActionHint(contextTile, state.commandFeedback[contextTile.entityId]), 13.sp, HaMuted)
                    } else {
                        HaText(stringResource(R.string.favorites), 28.sp)
                        HaText(stringResource(R.string.favorites_description), 16.sp, HaMuted)
                    }
                }
                HaButton(stringResource(R.string.edit_favorites), onEvent::openTileManager)
            }
            contextTile?.takeIf { !compact || state.commandFeedback[it.entityId] is CommandFeedback.Failed }?.let { tile ->
                FocusedControlContext(
                    tile = tile,
                    feedback = state.commandFeedback[tile.entityId],
                    onDismissFailure = { onEvent.dismissCommandFailure(tile.entityId) },
                )
            }
            Spacer(Modifier.height(14.dp))
            if (dashboardTiles.isEmpty()) {
                val awaitingSavedControls = state.settings.tiles.isNotEmpty() && !state.areInitialStatesLoaded
                EmptyDashboard(
                    awaitingSavedControls = awaitingSavedControls,
                    connectionFailed = state.connectionStatus is ConnectionStatus.Failed,
                    onAction = if (awaitingSavedControls) onEvent::openDiagnostics else onEvent::openTileManager,
                    requestInitialFocus = state.launcherRecovery == null,
                    focusRequester = dashboardFocusRequester,
                    upFocusRequester = settingsFocusRequester,
                )
            } else {
                BoxWithConstraints(Modifier.weight(1f)) {
                    val columnCount = when {
                        maxWidth >= 1_100.dp -> 5
                        maxWidth >= 780.dp -> 4
                        else -> 3
                    }
                    // Reserve room for complete rows after the header/context strip.
                    // TV density makes a 1080p screen roughly 540dp tall.
                    val visibleRows = if (maxHeight >= minimumTileHeight * 2 + 28.dp) 2 else 1
                    val tileHeight = ((maxHeight - 8.dp - 20.dp * (visibleRows - 1)) / visibleRows)
                        .coerceIn(minimumTileHeight, maxOf(142.dp, minimumTileHeight))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columnCount),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().testTag("dashboard_grid"),
                    ) {
                        itemsIndexed(dashboardTiles, key = { _, tile -> tile.entityId }) { index, tile ->
                            HomeAssistantTile(
                                tile = tile,
                                height = tileHeight,
                                feedback = state.commandFeedback[tile.entityId],
                                onClick = { onEvent.performPrimaryAction(tile.entityId) },
                                onLongClick = { onEvent.openDetails(tile.entityId) },
                                onFocused = { focusedModel ->
                                    focusedTile = focusedModel
                                    onEvent.saveFocus(focusedModel.entityId)
                                },
                                focusRequester = dashboardFocusRequester.takeIf { index == 0 },
                                upFocusRequester = settingsFocusRequester.takeIf { index < columnCount },
                                requestInitialFocus = !dashboardFocusSuppressed && tile.entityId == initialFocusTarget,
                                onInitialFocusRequested = {
                                    initialFocusTarget = null
                                    state.focusRequest
                                        ?.takeIf { it.entityId == tile.entityId }
                                        ?.let { onEvent.acknowledgeFocusRequest(it.sequence) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun focusedActionHint(tile: DashboardTileUiModel, feedback: CommandFeedback?): String = when {
    feedback is CommandFeedback.Pending -> "Updating Home Assistant…"
    feedback is CommandFeedback.Succeeded -> "Updated successfully"
    feedback is CommandFeedback.Failed -> feedback.message
    !tile.live -> "Last known state · reconnecting before controls are enabled"
    tile.unavailable -> "Unavailable — check Home Assistant"
    tile.kind in setOf(ControlKind.LIGHT, ControlKind.SWITCH, ControlKind.FAN, ControlKind.INPUT_BOOLEAN, ControlKind.GROUP) ->
        if (tile.supportsDetails) "Select to toggle · Hold Select for details" else "Select to toggle"
    tile.kind in setOf(ControlKind.SCENE, ControlKind.SCRIPT, ControlKind.BUTTON) -> "Select to run"
    else -> "Select to view details"
}

@Composable
private fun FocusedControlContext(
    tile: DashboardTileUiModel,
    feedback: CommandFeedback?,
    onDismissFailure: () -> Unit,
) {
    val actionHint = focusedActionHint(tile, feedback)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .background(HaSurface, RoundedCornerShape(8.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(tileIcon(tile.kind), null, Modifier.size(22.dp), if (tile.active) HaAmber else HaCyan)
        Spacer(Modifier.width(10.dp))
        Text(tile.name, Modifier.widthIn(max = 200.dp), color = HaText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        HaText(actionHint, 14.sp, Modifier.weight(1f), if (feedback is CommandFeedback.Failed) HaRed else HaMuted)
        if (feedback is CommandFeedback.Failed) {
            Spacer(Modifier.width(12.dp))
            HaText(
                "Dismiss",
                13.sp,
                Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Dismiss command error"; role = Role.Button }
                    .clickable(onClick = onDismissFailure)
                    .padding(8.dp),
                HaRed,
            )
        }
    }
}

@Composable
private fun EmptyDashboard(
    awaitingSavedControls: Boolean,
    connectionFailed: Boolean,
    onAction: () -> Unit,
    requestInitialFocus: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Lightbulb, null, Modifier.size(58.dp), HaCyan)
        Spacer(Modifier.height(16.dp))
        HaText(
            if (awaitingSavedControls) stringResource(
                if (connectionFailed) R.string.saved_controls_unavailable else R.string.saved_controls_connecting,
            ) else "Choose your quick controls",
            25.sp,
        )
        Spacer(Modifier.height(8.dp))
        HaText(
            if (awaitingSavedControls) stringResource(R.string.saved_controls_help)
            else "Add Home Assistant entities to your TV dashboard.",
            16.sp,
            HaMuted,
        )
        Spacer(Modifier.height(20.dp))
        HaButton(
            label = stringResource(if (awaitingSavedControls) R.string.diagnostics else R.string.manage_controls),
            onClick = onAction,
            primary = true,
            requestInitialFocus = requestInitialFocus,
            focusRequester = focusRequester,
            upFocusRequester = upFocusRequester,
        )
    }
}

@Composable
internal fun LauncherRecoveryDialog(recovery: LauncherRecovery, onEvent: DashboardActions) {
    TvDialog("Launcher shortcut unavailable", onDismissRequest = onEvent::dismissLauncherRecovery) {
        HaText("Shortcut needs attention", 25.sp)
        Spacer(Modifier.height(12.dp))
        HaText(recovery.message, 17.sp, HaMuted)
        Spacer(Modifier.height(20.dp))
        HaButton(stringResource(R.string.manage_controls), onEvent::openLauncherRecoveryControls, primary = true, requestInitialFocus = true)
        Spacer(Modifier.height(12.dp))
        HaButton("Close", onEvent::dismissLauncherRecovery)
    }
}

@Composable
private fun HomeAssistantTile(
    tile: DashboardTileUiModel,
    height: Dp,
    feedback: CommandFeedback?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: (DashboardTileUiModel) -> Unit,
    focusRequester: FocusRequester?,
    upFocusRequester: FocusRequester?,
    requestInitialFocus: Boolean,
    onInitialFocusRequested: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    val localFocusRequester = remember { FocusRequester() }
    val activeFocusRequester = focusRequester ?: localFocusRequester
    val focusScale by animateFloatAsState(
        targetValue = if (focused) 1.025f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "tile focus scale",
    )
    val iconTint = when {
        tile.unavailable || tile.kind == ControlKind.ALARM -> HaRed
        tile.active -> HaAmber
        else -> HaCyan
    }
    val stateLabel = when {
        feedback is CommandFeedback.Pending -> "Updating"
        feedback is CommandFeedback.Succeeded -> "Updated ✓"
        feedback is CommandFeedback.Failed -> "Action failed"
        !tile.live -> "Last known · ${tile.stateLabel}"
        else -> tile.stateLabel
    }
    Box(
        modifier = Modifier
            .then(if (upFocusRequester == null) Modifier else Modifier.focusProperties { up = upFocusRequester })
            .focusRequester(activeFocusRequester)
            .height(height)
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused(tile) }
            .onPreviewKeyEvent { event ->
                val nativeEvent = event.nativeKeyEvent
                when {
                    upFocusRequester != null &&
                        nativeEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP &&
                        nativeEvent.action == AndroidKeyEvent.ACTION_DOWN -> {
                        upFocusRequester.requestFocus()
                        true
                    }
                    nativeEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER &&
                        nativeEvent.action == AndroidKeyEvent.ACTION_DOWN && nativeEvent.isLongPress -> {
                        if (!longPressHandled && tile.live && tile.supportsDetails && !tile.unavailable) onLongClick()
                        longPressHandled = true
                        true
                    }
                    nativeEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER &&
                        nativeEvent.action == AndroidKeyEvent.ACTION_UP && longPressHandled -> {
                        longPressHandled = false
                        true
                    }
                    else -> false
                }
            }
            .graphicsLayer {
                scaleX = focusScale
                scaleY = focusScale
                shadowElevation = 0f
            }
            .background(
                when {
                    focused -> HaSurfaceFocused
                    tile.active -> HaSurfaceActive
                    else -> HaSurface
                },
                RoundedCornerShape(12.dp),
            )
            .border(
                if (focused) 3.dp else 1.dp,
                when {
                    focused -> HaBlue
                    feedback is CommandFeedback.Failed -> HaRed
                    tile.active -> HaAmber.copy(alpha = .72f)
                    else -> HaBorder
                },
                RoundedCornerShape(12.dp),
            )
            .semantics {
                contentDescription = "${tile.name}, $stateLabel"
                role = Role.Button
                if (!tile.live || tile.unavailable) disabled()
            }
            .testTag("tile_${tile.entityId}")
            .combinedClickable(
                // A pending command must not remove the currently focused card
                // from the TV focus graph. Keep it focusable and ignore repeated
                // Select presses until Home Assistant answers.
                enabled = true,
                onClick = {
                    if (tile.live && !tile.unavailable && feedback !is CommandFeedback.Pending) onClick()
                },
                onLongClick = {
                    if (tile.live && tile.supportsDetails && !tile.unavailable) onLongClick()
                },
            )
            .padding(18.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(
                tileIcon(tile.kind),
                null,
                Modifier.size(42.dp).background(iconTint.copy(alpha = .18f), CircleShape).padding(10.dp),
                iconTint,
            )
            Text(stateLabel, Modifier.weight(1f).padding(start = 10.dp), color = if (tile.unavailable || feedback is CommandFeedback.Failed) HaRed else iconTint, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(tile.name, Modifier, color = HaText, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            activeFocusRequester.requestFocus()
            onInitialFocusRequested()
        }
    }
}
