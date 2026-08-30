package dev.haquickaccess.tv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Blinds
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Toys
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.haquickaccess.tv.R
import dev.haquickaccess.tv.data.ConnectionStatus
import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.ControlBrowser
import dev.haquickaccess.tv.domain.model.ControlKind
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.ShortcutBehavior
import dev.haquickaccess.tv.domain.model.alarmArmModes
import dev.haquickaccess.tv.domain.model.alarmCodeIsNumeric
import dev.haquickaccess.tv.domain.model.actionLabel
import dev.haquickaccess.tv.domain.model.capabilities
import dev.haquickaccess.tv.domain.model.climateMaximum
import dev.haquickaccess.tv.domain.model.climateMinimum
import dev.haquickaccess.tv.domain.model.climateStep
import dev.haquickaccess.tv.domain.model.climateTarget
import dev.haquickaccess.tv.domain.model.hvacModes
import dev.haquickaccess.tv.domain.model.levelPercent
import dev.haquickaccess.tv.domain.model.numberMaximum
import dev.haquickaccess.tv.domain.model.numberMinimum
import dev.haquickaccess.tv.domain.model.numberStep
import dev.haquickaccess.tv.domain.model.selectOptions
import kotlin.math.roundToInt
import java.util.Locale

@Composable
internal fun DashboardScreen(state: DashboardUiState, onEvent: DashboardViewModel) {
    val dashboardTiles = state.dashboardTiles
    val gridState = rememberLazyGridState()
    val settingsFocusRequester = remember { FocusRequester() }
    val dashboardFocusRequester = remember { FocusRequester() }
    var initialFocusTarget by remember { mutableStateOf<String?>(null) }
    var restoredInitialPosition by remember { mutableStateOf(false) }
    var focusedTile by remember { mutableStateOf<DashboardTileUiModel?>(null) }
    val dashboardFocusSuppressed = state.details != null || state.launcherRecovery != null
    val restoredFocusIndex = dashboardTiles.indexOfFirst { it.entityId == state.settings.lastFocusedEntityId }
    val contextTile = focusedTile?.takeIf { focused -> dashboardTiles.any { it.entityId == focused.entityId } }
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
    Column(Modifier.fillMaxSize()) {
        DashboardHero(
            status = state.connectionStatus,
            controlCount = dashboardTiles.size,
            showingCachedSnapshot = state.isShowingCachedSnapshot,
            onSettings = onEvent::openSettings,
            focusRequester = settingsFocusRequester,
            downFocusRequester = dashboardFocusRequester,
        )
        contextTile?.let { tile ->
            FocusedControlContext(
                tile = tile,
                feedback = state.commandFeedback[tile.entityId],
                onDismissFailure = { onEvent.dismissCommandFailure(tile.entityId) },
            )
        }
        Spacer(Modifier.height(14.dp))
        if (dashboardTiles.isEmpty()) {
            EmptyDashboard(
                onManage = onEvent::openTileManager,
                requestInitialFocus = state.launcherRecovery == null,
                focusRequester = dashboardFocusRequester,
                upFocusRequester = settingsFocusRequester,
            )
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HaText("QUICK CONTROLS", 12.sp, HaCyan)
                Spacer(Modifier.width(10.dp))
                HaText("${dashboardTiles.size} pinned", 12.sp, HaMuted)
            }
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints(Modifier.weight(1f)) {
                val columnCount = when {
                    maxWidth >= 1_500.dp -> 5
                    maxWidth >= 1_000.dp -> 4
                    else -> 3
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columnCount),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().testTag("dashboard_grid"),
                ) {
                    itemsIndexed(dashboardTiles, key = { _, tile -> tile.entityId }) { index, tile ->
                        HomeAssistantTile(
                            tile = tile,
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

@Composable
private fun DashboardHero(
    status: ConnectionStatus,
    controlCount: Int,
    showingCachedSnapshot: Boolean,
    onSettings: () -> Unit,
    focusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, HaBorder, RoundedCornerShape(24.dp)),
    ) {
        Image(
            painter = painterResource(R.drawable.ambient_home_banner_v2),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(HaSurface.copy(alpha = .78f))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).background(HaBlue.copy(alpha = .14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Lightbulb, null, Modifier.size(26.dp), HaBlue)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                HaText("HA Quick Access", 26.sp)
                Spacer(Modifier.height(2.dp))
                HaText("$controlCount controls ready for your remote", 14.sp, HaMuted)
            }
            val (label, color) = when {
                showingCachedSnapshot -> "Last known · reconnecting" to HaAmber
                else -> when (status) {
                    is ConnectionStatus.Connected -> "Home connected" to HaBlue
                    is ConnectionStatus.Connecting -> "Connecting" to HaAmber
                    is ConnectionStatus.Failed -> "Connection problem" to HaRed
                    ConnectionStatus.Disconnected -> "Offline" to HaMuted
                }
            }
            Row(
                Modifier.background(color.copy(alpha = .12f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(7.dp))
                HaText(label, 13.sp, color)
            }
            Spacer(Modifier.width(16.dp))
            HeaderSettingsButton(onSettings, focusRequester, downFocusRequester)
        }
    }
}

@Composable
private fun HeaderSettingsButton(
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }
    val focusScale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "settings focus scale",
    )
    Box(
        Modifier
            .size(48.dp)
            .focusProperties { down = downFocusRequester }
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (
                    event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN &&
                    event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN
                ) {
                    downFocusRequester.requestFocus()
                    true
                } else {
                    false
                }
            }
            .graphicsLayer {
                scaleX = focusScale
                scaleY = focusScale
                shadowElevation = if (focused) 14.dp.toPx() else 0f
            }
            .semantics { contentDescription = "Settings"; role = Role.Button }
            .background(if (focused) HaSurfaceFocused else HaSurface, CircleShape)
            .border(if (focused) 2.dp else 1.dp, if (focused) HaBlue else HaBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Settings, null, Modifier.size(24.dp), HaText)
    }
}

@Composable
private fun FocusedControlContext(
    tile: DashboardTileUiModel,
    feedback: CommandFeedback?,
    onDismissFailure: () -> Unit,
) {
    val actionHint = when {
        feedback is CommandFeedback.Pending -> "Updating Home Assistant…"
        feedback is CommandFeedback.Succeeded -> "Updated successfully"
        feedback is CommandFeedback.Failed -> feedback.message
        !tile.live -> "Last known state · reconnecting before controls are enabled"
        tile.unavailable -> "Unavailable — check Home Assistant"
        tile.kind in setOf(ControlKind.LIGHT, ControlKind.SWITCH, ControlKind.FAN, ControlKind.INPUT_BOOLEAN, ControlKind.GROUP) ->
            "Select to toggle · Hold Select for details"
        tile.kind in setOf(ControlKind.SCENE, ControlKind.SCRIPT, ControlKind.BUTTON) -> "Select to run"
        else -> "Select to view details"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .background(HaSurface.copy(alpha = .72f), RoundedCornerShape(16.dp))
            .border(1.dp, HaBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(tileIcon(tile.kind), null, Modifier.size(22.dp), if (tile.active) HaAmber else HaCyan)
        Spacer(Modifier.width(10.dp))
        HaText(tile.name, 16.sp)
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
    onManage: () -> Unit,
    requestInitialFocus: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Lightbulb, null, Modifier.size(58.dp), HaCyan)
        Spacer(Modifier.height(16.dp))
        HaText("Choose your quick controls", 25.sp)
        Spacer(Modifier.height(8.dp))
        HaText("Add Home Assistant entities to your TV dashboard.", 16.sp, HaMuted)
        Spacer(Modifier.height(20.dp))
        HaButton(
            label = "Manage controls",
            onClick = onManage,
            primary = true,
            requestInitialFocus = requestInitialFocus,
            focusRequester = focusRequester,
            upFocusRequester = upFocusRequester,
        )
    }
}

@Composable
internal fun LauncherRecoveryDialog(recovery: LauncherRecovery, onEvent: DashboardViewModel) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(560.dp)
                .semantics { contentDescription = "Launcher shortcut unavailable" }
                .background(HaSurface, RoundedCornerShape(18.dp))
                .border(1.dp, HaBorder, RoundedCornerShape(18.dp))
                .padding(28.dp),
        ) {
            Icon(Icons.Outlined.WarningAmber, null, Modifier.size(36.dp), HaAmber)
            Spacer(Modifier.height(14.dp))
            HaText("Shortcut needs attention", 25.sp)
            Spacer(Modifier.height(8.dp))
            HaText(recovery.message, 16.sp, HaMuted)
            Spacer(Modifier.height(10.dp))
            HaText("Choose a current Home Assistant control to replace it.", 15.sp, HaMuted)
            Spacer(Modifier.height(24.dp))
            HaButton("Manage controls", onEvent::openLauncherRecoveryControls, primary = true, requestInitialFocus = true)
            Spacer(Modifier.height(12.dp))
            HaText("Press Back to dismiss", 14.sp, HaMuted)
        }
    }
}

@Composable
private fun HomeAssistantTile(
    tile: DashboardTileUiModel,
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
        targetValue = if (focused) 1.04f else 1f,
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
            .height(142.dp)
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
                shadowElevation = if (focused) 16.dp.toPx() else 0f
            }
            .background(
                when {
                    focused -> HaSurfaceFocused
                    tile.active -> HaSurfaceActive
                    else -> HaSurface
                },
                RoundedCornerShape(20.dp),
            )
            .border(
                if (focused) 3.dp else 1.dp,
                when {
                    focused -> HaBlue
                    feedback is CommandFeedback.Failed -> HaRed
                    tile.active -> HaAmber.copy(alpha = .72f)
                    else -> HaBorder
                },
                RoundedCornerShape(20.dp),
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(
                tileIcon(tile.kind),
                null,
                Modifier.size(42.dp).background(iconTint.copy(alpha = .18f), CircleShape).padding(10.dp),
                iconTint,
            )
            HaText(stateLabel, 14.sp, if (tile.unavailable || feedback is CommandFeedback.Failed) HaRed else iconTint)
        }
        HaText(tile.name, 17.sp, Modifier.align(Alignment.BottomStart), HaText)
    }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            activeFocusRequester.requestFocus()
            onInitialFocusRequested()
        }
    }
}
