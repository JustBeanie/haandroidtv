package dev.haquickaccess.tv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import dev.haquickaccess.tv.data.ConnectionStatus
import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.ControlBrowser
import dev.haquickaccess.tv.domain.model.ControlKind
import dev.haquickaccess.tv.domain.model.HaEntity
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

private val HaBackground = Color(0xFF111111)
private val HaSurface = Color(0xFF1C1C1C)
private val HaSurfaceFocused = Color(0xFF242424)
private val HaBorder = Color(0xFF303030)
private val HaBlue = Color(0xFF03A9F4)
private val HaGreen = Color(0xFF4CAF50)
private val HaAmber = Color(0xFFFF9800)
private val HaCyan = Color(0xFF8BCFF5)
private val HaRed = Color(0xFFF44336)
private val HaText = Color(0xFFF4F6F9)
private val HaMuted = Color(0xFFAEB7C4)

@Composable
fun HaQuickAccessApp(
    state: DashboardUiState,
    deepLinkEntityId: String?,
    deepLinkBehavior: String?,
    onEvent: DashboardViewModel,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    var managerSearchFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = state.errorMessage != null || state.launcherRecovery != null || state.details != null || state.screen != AppScreen.Dashboard) {
        when {
            state.errorMessage != null -> onEvent.dismissError()
            state.launcherRecovery != null -> onEvent.dismissLauncherRecovery()
            state.details != null -> onEvent.closeDetails()
            managerSearchFocused -> {
                focusManager.clearFocus(force = true)
                managerSearchFocused = false
            }
            else -> onEvent.closeScreen()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onEvent.onForeground()
                Lifecycle.Event.ON_STOP -> onEvent.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            onEvent.onForeground()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onEvent.onBackground()
        }
    }

    var handledDeepLink by remember(deepLinkEntityId, deepLinkBehavior) { mutableStateOf(false) }
    LaunchedEffect(
        deepLinkEntityId,
        deepLinkBehavior,
        state.isSettingsLoaded,
        state.areInitialStatesLoaded,
        state.connectionStatus,
        state.entities,
    ) {
        if (handledDeepLink) return@LaunchedEffect
        val entityId = deepLinkEntityId ?: return@LaunchedEffect
        if (!state.isSettingsLoaded) return@LaunchedEffect
        if (!state.areInitialStatesLoaded) {
            if (state.connectionStatus is ConnectionStatus.Failed) {
                onEvent.showMissingLauncherShortcut(entityId, deepLinkBehavior)
                handledDeepLink = true
            }
            return@LaunchedEffect
        }
        state.entities[entityId]?.let { entity ->
            when (deepLinkBehavior) {
                "toggle" -> onEvent.performPrimaryAction(entity)
                "details" -> onEvent.openShortcutDetails(entity)
                "focus" -> onEvent.focusEntity(entity.entityId)
                else -> onEvent.openShortcutDetails(entity)
            }
            handledDeepLink = true
        } ?: run {
            val terminalConnectionState = state.connectionStatus is ConnectionStatus.Failed
            if (state.areInitialStatesLoaded || terminalConnectionState) {
                onEvent.showMissingLauncherShortcut(entityId, deepLinkBehavior)
                handledDeepLink = true
            }
        }
    }

    MaterialTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(HaBackground)) {
            val horizontalSafeMargin = (maxWidth * .05f).coerceAtLeast(24.dp)
            val verticalSafeMargin = (maxHeight * .05f).coerceAtLeast(20.dp)
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalSafeMargin, vertical = verticalSafeMargin),
            ) {
                when {
                    !state.isSettingsLoaded -> LoadingScreen()
                    !state.isConfigured || state.screen == AppScreen.ConnectionSetup -> SetupScreen(state, onEvent)
                    state.screen == AppScreen.Settings -> SettingsScreen(state, onEvent)
                    state.screen == AppScreen.ManageTiles -> TileManagerScreen(
                        state = state,
                        onEvent = onEvent,
                        onSearchFocusChanged = { managerSearchFocused = it },
                    )
                    state.screen == AppScreen.ManageShortcuts -> ShortcutManagerScreen(state, onEvent)
                    state.screen == AppScreen.Diagnostics -> DiagnosticsScreen(state, onEvent)
                    else -> DashboardScreen(state, onEvent)
                }
                state.details?.let { DetailDialog(it, onEvent) }
                state.launcherRecovery?.let { LauncherRecoveryDialog(it, onEvent) }
                state.errorMessage?.let { ErrorMessage(it, onEvent::dismissError) }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Lightbulb,
            null,
            Modifier.size(52.dp).background(HaCyan.copy(alpha = .16f), CircleShape).padding(10.dp),
            HaCyan,
        )
        Spacer(Modifier.height(16.dp))
        HaText("Loading Home Assistant…", 20.sp)
    }
}

@Composable
private fun SetupScreen(state: DashboardUiState, onEvent: DashboardViewModel) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Lightbulb, null, Modifier.size(58.dp).background(HaAmber.copy(alpha = .18f), CircleShape).padding(12.dp), HaAmber)
        Spacer(Modifier.height(20.dp))
        HaText(if (state.isConfigured) "Update Home Assistant" else "Connect Home Assistant", 30.sp)
        Spacer(Modifier.height(8.dp))
        HaText("Use a verified HTTPS URL and a long-lived access token.", 16.sp, HaMuted)
        Spacer(Modifier.height(28.dp))
        HaTextField(
            label = "Home Assistant URL",
            value = state.setupBaseUrl,
            onValueChange = onEvent::updateSetupBaseUrl,
            secret = false,
            placeholder = "https://homeassistant.local:8123",
            requestInitialFocus = true,
        )
        Spacer(Modifier.height(14.dp))
        HaTextField("Long-lived access token", state.setupToken, onEvent::updateSetupToken, true)
        Spacer(Modifier.height(22.dp))
        HaButton(
            if (state.isSavingConnection) "Verifying…" else "Validate and save",
            onEvent::saveConnection,
            primary = true,
            enabled = !state.isSavingConnection,
        )
    }
}

@Composable
private fun DashboardScreen(state: DashboardUiState, onEvent: DashboardViewModel) {
    val gridState = rememberLazyGridState()
    val settingsFocusRequester = remember { FocusRequester() }
    val dashboardFocusRequester = remember { FocusRequester() }
    var initialFocusTarget by remember { mutableStateOf<String?>(null) }
    var restoredInitialPosition by remember { mutableStateOf(false) }
    var focusedTile by remember { mutableStateOf<HaEntity?>(null) }
    val restoredFocusIndex = state.tiles.indexOfFirst { it.entityId == state.settings.lastFocusedEntityId }
    val contextTile = focusedTile?.takeIf { focused -> state.tiles.any { it.entityId == focused.entityId } }
        ?: state.tiles.getOrNull(restoredFocusIndex)
        ?: state.tiles.firstOrNull()
    LaunchedEffect(state.tiles, state.focusRequest?.sequence) {
        val requestedFocusIndex = state.focusRequest
            ?.entityId
            ?.let { targetEntityId -> state.tiles.indexOfFirst { it.entityId == targetEntityId } }
            ?.takeIf { it >= 0 }
        if (state.tiles.isNotEmpty() && (requestedFocusIndex != null || !restoredInitialPosition)) {
            val targetIndex = requestedFocusIndex ?: restoredFocusIndex.takeIf { it >= 0 } ?: 0
            gridState.scrollToItem(targetIndex)
            // Lazy grid items are composed after the scroll request. Waiting for a
            // rendered frame prevents a cold launch from briefly having no real
            // D-pad target (and lets the first Select land on an unintended card).
            withFrameNanos { }
            initialFocusTarget = state.tiles[targetIndex].entityId
            restoredInitialPosition = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header(
            status = state.connectionStatus,
            onSettings = onEvent::openSettings,
            focusRequester = settingsFocusRequester,
            downFocusRequester = dashboardFocusRequester,
        )
        contextTile?.let { FocusedControlContext(it, it.entityId in state.pendingEntityIds) }
        Spacer(Modifier.height(18.dp))
        if (state.tiles.isEmpty()) {
            EmptyDashboard(
                onManage = onEvent::openTileManager,
                requestInitialFocus = state.launcherRecovery == null,
                focusRequester = dashboardFocusRequester,
                upFocusRequester = settingsFocusRequester,
            )
        } else {
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
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(state.tiles, key = { _, entity -> entity.entityId }) { index, entity ->
                        HomeAssistantTile(
                            entity = entity,
                            pending = entity.entityId in state.pendingEntityIds,
                            onClick = { onEvent.performPrimaryAction(entity) },
                            onLongClick = { onEvent.openDetails(entity) },
                            onFocused = { focusedEntity ->
                                focusedTile = focusedEntity
                                onEvent.saveFocus(focusedEntity.entityId)
                            },
                            focusRequester = dashboardFocusRequester.takeIf { index == 0 },
                            upFocusRequester = settingsFocusRequester.takeIf { index < columnCount },
                            requestInitialFocus = entity.entityId == initialFocusTarget,
                            onInitialFocusRequested = {
                                initialFocusTarget = null
                                state.focusRequest
                                    ?.takeIf { it.entityId == entity.entityId }
                                    ?.let { onEvent.acknowledgeFocusRequest(it.sequence) }
                            },
                        )
                    }
                }
            }
        }
        HaText("D-pad to move  •  Select to control  •  Hold Select for details", 14.sp, HaMuted)
    }
}

@Composable
private fun Header(
    status: ConnectionStatus,
    onSettings: () -> Unit,
    focusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HaText("HA Quick Access", 29.sp)
        Spacer(Modifier.weight(1f))
        val (label, color) = when (status) {
            is ConnectionStatus.Connected -> "Home connected" to HaBlue
            is ConnectionStatus.Connecting -> "Connecting" to HaAmber
            is ConnectionStatus.Failed -> "Connection problem" to HaRed
            ConnectionStatus.Disconnected -> "Offline" to HaMuted
        }
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        HaText(label, 14.sp, HaMuted)
        Spacer(Modifier.width(16.dp))
        HeaderSettingsButton(onSettings, focusRequester, downFocusRequester)
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
private fun FocusedControlContext(entity: HaEntity, pending: Boolean) {
    val actionHint = when {
        pending -> "Updating Home Assistant…"
        entity.unavailable -> "Unavailable — check Home Assistant"
        entity.capabilities().canToggle -> "Select to toggle · Hold Select for details"
        entity.capabilities().canActivate || entity.capabilities().canRun || entity.capabilities().canPress ->
            "Select to run · Hold Select for details"
        else -> "Select to view details"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .background(HaSurface.copy(alpha = .72f), RoundedCornerShape(12.dp))
            .border(1.dp, HaBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(tileIcon(entity.capabilities().kind), null, Modifier.size(22.dp), HaCyan)
        Spacer(Modifier.width(10.dp))
        HaText(entity.name, 16.sp)
        Spacer(Modifier.width(12.dp))
        HaText(actionHint, 14.sp, HaMuted, Modifier.weight(1f))
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
private fun LauncherRecoveryDialog(recovery: LauncherRecovery, onEvent: DashboardViewModel) {
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
    entity: HaEntity,
    pending: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: (HaEntity) -> Unit,
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
    val capability = entity.capabilities()
    val iconTint = when {
        entity.unavailable || entity.domain == "alarm_control_panel" -> HaRed
        entity.isOn -> HaAmber
        else -> HaCyan
    }
    val stateLabel = when {
        pending -> "Updating"
        entity.unavailable -> "Unavailable"
        entity.domain == "climate" -> listOfNotNull(entity.climateTarget()?.roundToInt()?.let { "$it°" }, entity.state.replaceFirstChar(Char::uppercase)).joinToString(" · ")
        entity.levelPercent() != null -> "${entity.levelPercent()}%"
        else -> entity.actionLabel()
    }
    Box(
        modifier = Modifier
            .then(if (upFocusRequester == null) Modifier else Modifier.focusProperties { up = upFocusRequester })
            .focusRequester(activeFocusRequester)
            .height(128.dp)
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused(entity) }
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
                        if (!longPressHandled) onLongClick()
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
            .background(if (focused) HaSurfaceFocused else HaSurface, RoundedCornerShape(14.dp))
            .border(if (focused) 3.dp else 1.dp, if (focused) HaBlue else HaBorder, RoundedCornerShape(14.dp))
            .semantics { contentDescription = "${entity.name}, $stateLabel"; role = Role.Button }
            .combinedClickable(
                // A pending command must not remove the currently focused card
                // from the TV focus graph. Keep it focusable and ignore repeated
                // Select presses until Home Assistant answers.
                enabled = !entity.unavailable,
                onClick = { if (!pending) onClick() },
                onLongClick = onLongClick,
            )
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(
                tileIcon(capability.kind),
                null,
                Modifier.size(42.dp).background(iconTint.copy(alpha = .18f), CircleShape).padding(10.dp),
                iconTint,
            )
            HaText(stateLabel, 14.sp, if (entity.unavailable) HaRed else iconTint)
        }
        HaText(entity.name, 17.sp, HaText, Modifier.align(Alignment.BottomStart))
    }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            activeFocusRequester.requestFocus()
            onInitialFocusRequested()
        }
    }
}

@Composable
private fun SettingsScreen(state: DashboardUiState, onEvent: DashboardViewModel) {
    Column(Modifier.fillMaxSize()) {
        HaText("Settings", 30.sp)
        Spacer(Modifier.height(20.dp))
        SettingRow(
            title = "Manage controls",
            subtitle = "Add, remove, and reorder dashboard tiles",
            onClick = onEvent::openTileManager,
            requestInitialFocus = true,
        )
        SettingRow("Home screen shortcuts", "Configure up to four Android TV launcher shortcuts", onEvent::openShortcutManager)
        SettingRow("Connection", state.settings.baseUrl.orEmpty(), onEvent::openConnectionSetup)
        SettingRow("Diagnostics", "Connection, cache, and launcher-channel status", onEvent::openDiagnostics)
        Spacer(Modifier.height(18.dp))
        HaButton("Forget Home Assistant", onEvent::clearConnection, destructive = true)
        Spacer(Modifier.weight(1f))
        HaButton("Back", onEvent::closeScreen)
    }
}

@Composable
private fun DiagnosticsScreen(state: DashboardUiState, onEvent: DashboardViewModel) {
    Column(Modifier.fillMaxSize()) {
        HaText("Diagnostics", 30.sp)
        Spacer(Modifier.height(18.dp))
        val connection = when (val status = state.connectionStatus) {
            is ConnectionStatus.Connected -> "Connected${status.version?.let { " · Home Assistant $it" }.orEmpty()}"
            is ConnectionStatus.Connecting -> "Connecting securely"
            is ConnectionStatus.Failed -> "Connection problem${status.message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
            ConnectionStatus.Disconnected -> "Offline"
        }
        DiagnosticRow("Connection", connection)
        DiagnosticRow("Configured endpoint", state.settings.baseUrl ?: "Not configured")
        DiagnosticRow("Cached entities", state.entities.size.toString())
        DiagnosticRow("Dashboard controls", state.settings.tiles.size.toString())
        DiagnosticRow(
            "Android TV Home channel",
            if (state.settings.homeChannelEnabled) "Enabled · ${state.settings.homeShortcuts.size} shortcuts" else "Disabled",
        )
        Spacer(Modifier.height(18.dp))
        if (state.settings.homeChannelEnabled) {
            HaButton("Refresh Home screen channel", onEvent::refreshHomeChannel, primary = true)
            Spacer(Modifier.height(10.dp))
        }
        HaButton("Back", onEvent::closeScreen)
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().background(HaSurface, RoundedCornerShape(12.dp)).padding(16.dp)) {
        HaText(label, 14.sp, HaMuted)
        Spacer(Modifier.height(4.dp))
        HaText(value, 17.sp)
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ShortcutManagerScreen(state: DashboardUiState, onEvent: DashboardViewModel) {
    Column(Modifier.fillMaxSize()) {
        HaText("Home screen shortcuts", 30.sp)
        Spacer(Modifier.height(10.dp))
        HaText("Choose up to four controls and what selecting each shortcut does.", 15.sp, HaMuted)
        if (state.isProjectivyInstalled) {
            Spacer(Modifier.height(10.dp))
            HaText("Projectivy detected", 16.sp, HaGreen)
            HaText("In Projectivy, open Settings › Edit Channels › HA Quick Access to show these tiles.", 14.sp, HaMuted)
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.tiles.forEach { entity ->
                val existing = state.settings.homeShortcuts.firstOrNull { it.entityId == entity.entityId }
                Column(Modifier.fillMaxWidth().background(HaSurface, RoundedCornerShape(12.dp)).padding(14.dp)) {
                    HaText(entity.name, 17.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entity.capabilities().canToggle) HaButton("Toggle", { onEvent.setShortcut(entity.entityId, dev.haquickaccess.tv.domain.model.ShortcutBehavior.TOGGLE) }, primary = existing?.behavior?.name == "TOGGLE")
                        HaButton("Focus", { onEvent.setShortcut(entity.entityId, dev.haquickaccess.tv.domain.model.ShortcutBehavior.FOCUS) }, primary = existing?.behavior?.name == "FOCUS")
                        if (onEvent.supportsDetails(entity)) HaButton("Details", { onEvent.setShortcut(entity.entityId, dev.haquickaccess.tv.domain.model.ShortcutBehavior.DETAILS) }, primary = existing?.behavior?.name == "DETAILS")
                        if (existing != null) HaButton("Remove", { onEvent.removeShortcut(entity.entityId) }, destructive = true)
                    }
                }
            }
        }
        if (state.settings.homeChannelEnabled) {
            HaButton("Refresh Home screen channel", onEvent::refreshHomeChannel, primary = true)
            Spacer(Modifier.height(10.dp))
            HaButton("Remove Home screen channel", onEvent::disableHomeChannel, destructive = true)
        } else {
            HaButton("Add to Android TV Home", onEvent::enableHomeChannel, primary = true)
        }
        Spacer(Modifier.height(10.dp))
        HaButton("Done", onEvent::closeScreen)
    }
}

@Composable
private fun TileManagerScreen(
    state: DashboardUiState,
    onEvent: DashboardViewModel,
    onSearchFocusChanged: (Boolean) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedDomain by remember { mutableStateOf<String?>(null) }
    var includeUnavailable by remember { mutableStateOf(false) }
    var includeReadOnly by remember { mutableStateOf(false) }
    var isSearchFocused by remember { mutableStateOf(false) }
    var restoreFilterFocus by remember { mutableStateOf(false) }
    var movingTileId by remember { mutableStateOf<String?>(null) }
    val allFilterFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    BackHandler(enabled = isSearchFocused) {
        // IMEs on TV can consume directional input while the search field is active.
        // Complete the Back transition before requesting a real TV-focus target.
        keyboardController?.hide()
        restoreFilterFocus = true
    }
    LaunchedEffect(restoreFilterFocus) {
        if (restoreFilterFocus) {
            focusManager.clearFocus(force = true)
            allFilterFocusRequester.requestFocus()
            restoreFilterFocus = false
        }
    }
    val isReplacingLauncherShortcut = state.launcherShortcutReplacement != null
    val selectionLabel = if (isReplacingLauncherShortcut) "Select to replace" else "Select to add"
    val selectableControls = state.availableEntities.filter { candidate ->
        state.settings.tiles.none { it.entityId == candidate.entityId }
    }
    val unavailableControlCount = selectableControls.count(HaEntity::unavailable)
    val readOnlyControlCount = selectableControls.count { !it.capabilities().isQuickControl }
    val availableControls = selectableControls.filter { entity ->
        (includeUnavailable || !entity.unavailable) &&
            (includeReadOnly || entity.capabilities().isQuickControl)
    }
    val domains = ControlBrowser.domains(availableControls)
    val filteredControls = ControlBrowser.filter(availableControls, searchQuery, selectedDomain)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                HaText(if (isReplacingLauncherShortcut) "Replace launcher shortcut" else "Manage controls", 30.sp)
                Spacer(Modifier.height(12.dp))
                HaText(
                    if (isReplacingLauncherShortcut) {
                        "Choose a control to add to the dashboard and use for this Home shortcut."
                    } else {
                        "Browse by type or search by name. Add only the controls you want on the TV dashboard."
                    },
                    15.sp,
                    HaMuted,
                )
            }
            HaButton("Done", onEvent::closeScreen, primary = true)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            HaTextField(
                label = "Search controls",
                value = searchQuery,
                onValueChange = { searchQuery = it },
                secret = false,
                modifier = Modifier.weight(1f),
                placeholder = "Search name or entity ID",
                downFocusRequester = allFilterFocusRequester,
                onFocusChanged = {
                    isSearchFocused = it
                    onSearchFocusChanged(it)
                },
            )
            if (searchQuery.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                HaButton("Clear", { searchQuery = "" })
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DomainFilter(
                label = "All",
                selected = selectedDomain == null,
                requestInitialFocus = true,
                focusRequester = allFilterFocusRequester,
            ) { selectedDomain = null }
            if (unavailableControlCount > 0) {
                DomainFilter(
                    label = if (includeUnavailable) "Hide unavailable" else "Show unavailable",
                    selected = includeUnavailable,
                ) { includeUnavailable = !includeUnavailable }
            }
            if (readOnlyControlCount > 0) {
                DomainFilter(
                    label = if (includeReadOnly) "Hide read-only" else "Show read-only",
                    selected = includeReadOnly,
                ) { includeReadOnly = !includeReadOnly }
            }
            domains.forEach { domain ->
                DomainFilter(
                    label = ControlBrowser.domainLabel(domain),
                    selected = selectedDomain == domain,
                ) { selectedDomain = domain }
            }
        }
        Spacer(Modifier.height(14.dp))
        val hasDashboardControls = state.tiles.isNotEmpty()
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(if (hasDashboardControls) Modifier.weight(1f) else Modifier.fillMaxWidth()) {
                val heading = when {
                    includeReadOnly && includeUnavailable -> "All Home Assistant entities"
                    includeReadOnly -> "Available entities"
                    includeUnavailable -> "TV controls"
                    else -> "Ready TV controls"
                }
                HaText("$heading · ${filteredControls.size}", 19.sp)
                Spacer(Modifier.height(10.dp))
                if (filteredControls.isEmpty()) {
                    EmptyControlResults(searchQuery, selectedDomain, includeUnavailable, includeReadOnly)
                } else {
                    EntityList(entities = filteredControls, onAdd = onEvent::addTile, selectionLabel = selectionLabel)
                }
            }
            if (hasDashboardControls) {
                Column(Modifier.weight(1f)) {
                    val movingTile = state.tiles.firstOrNull { it.entityId == movingTileId }
                    HaText(if (movingTile == null) "Added to dashboard" else "Reorder dashboard", 19.sp, HaGreen)
                    if (movingTile != null) {
                        Spacer(Modifier.height(4.dp))
                        HaText(
                            "Moving ${movingTile.name}. Choose Earlier or Later, then finish moving.",
                            14.sp,
                            HaMuted,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TileOrderPreview(state.tiles)
                    Spacer(Modifier.height(10.dp))
                    EntityList(
                        entities = state.tiles,
                        showActions = true,
                        onAdd = {},
                        onRemove = onEvent::removeTile,
                        onMove = onEvent::moveTile,
                        movingEntityId = movingTileId,
                        onStartMove = { movingTileId = it },
                        onFinishMove = { movingTileId = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun TileOrderPreview(tiles: List<HaEntity>) {
    val orderDescription = tiles.mapIndexed { index, entity -> "${index + 1}. ${entity.name}" }.joinToString(", ")
    Column(
        Modifier
            .fillMaxWidth()
            .background(HaSurface, RoundedCornerShape(10.dp))
            .padding(10.dp)
            .semantics { contentDescription = "Current dashboard order: $orderDescription" },
    ) {
        HaText("Current dashboard order", 14.sp, HaMuted)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tiles.forEachIndexed { index, entity ->
                Box(
                    Modifier
                        .width(190.dp)
                        .height(48.dp)
                        .background(HaBackground, RoundedCornerShape(8.dp))
                        .border(1.dp, HaBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "${index + 1}. ${entity.name}",
                        color = HaText,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (index < tiles.lastIndex) HaText("›", 20.sp, HaMuted)
            }
        }
    }
}

@Composable
private fun DomainFilter(
    label: String,
    selected: Boolean,
    requestInitialFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    HaButton(
        label = label,
        onClick = onClick,
        primary = selected,
        requestInitialFocus = requestInitialFocus,
        focusRequester = focusRequester,
    )
}

@Composable
private fun EmptyControlResults(query: String, domain: String?, includeUnavailable: Boolean, includeReadOnly: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(HaSurface, RoundedCornerShape(12.dp))
            .padding(18.dp),
    ) {
        HaText("No matching controls", 17.sp)
        Spacer(Modifier.height(6.dp))
        val guidance = when {
            query.isNotBlank() -> "Try a different name or entity ID."
            domain != null -> "No ${ControlBrowser.domainLabel(domain).lowercase()} controls are currently available."
            !includeUnavailable -> "No controls are currently available. Choose Show unavailable to review offline controls."
            !includeReadOnly -> "No TV controls match. Choose Show read-only to review status-only entities."
            else -> "Connected Home Assistant controls will appear here."
        }
        HaText(guidance, 14.sp, HaMuted)
    }
}

@Composable
private fun EntityList(
    entities: List<HaEntity>,
    showActions: Boolean = false,
    onAdd: (HaEntity) -> Unit,
    onRemove: (String) -> Unit = {},
    onMove: (String, Int) -> Unit = { _, _ -> },
    movingEntityId: String? = null,
    onStartMove: (String) -> Unit = {},
    onFinishMove: () -> Unit = {},
    selectionLabel: String = "Select to add",
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entities.forEachIndexed { index, entity ->
            item(key = entity.entityId) {
                EntityListItem(
                    entity = entity,
                    selected = showActions,
                    showActions = showActions,
                    position = index,
                    itemCount = entities.size,
                    onAdd = { onAdd(entity) },
                    onRemove = { onRemove(entity.entityId) },
                    onMove = { direction -> onMove(entity.entityId, direction) },
                    isMoving = entity.entityId == movingEntityId,
                    onStartMove = { onStartMove(entity.entityId) },
                    onFinishMove = onFinishMove,
                    selectionLabel = selectionLabel,
                )
            }
        }
    }
}

@Composable
private fun EntityListItem(
    entity: HaEntity,
    selected: Boolean,
    showActions: Boolean,
    position: Int,
    itemCount: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    isMoving: Boolean,
    onStartMove: () -> Unit,
    onFinishMove: () -> Unit,
    selectionLabel: String,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val focusScale by animateFloatAsState(
        targetValue = if (focused) 1.015f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "control row focus scale",
    )
    val rowModifier = Modifier
        .fillMaxWidth()
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            scaleX = focusScale
            scaleY = focusScale
            shadowElevation = if (focused) 10.dp.toPx() else 0f
        }
        .heightIn(min = 76.dp)
        .background(
            when {
                focused -> HaSurfaceFocused
                isMoving -> HaBlue.copy(alpha = .16f)
                selected -> HaGreen.copy(alpha = .16f)
                else -> HaSurface
            },
            shape,
        )
        .border(
            if (focused) 2.dp else 1.dp,
            when {
                focused -> HaBlue
                isMoving -> HaBlue
                selected -> HaGreen
                else -> HaBorder
            },
            shape,
        )
        .padding(12.dp)

    if (showActions) {
        Column(rowModifier.semantics { contentDescription = "${entity.name}, added to dashboard" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HaText(entity.name, 16.sp)
                    Spacer(Modifier.height(3.dp))
                    HaText(entity.actionLabel(), 14.sp, HaMuted)
                }
                HaText(
                    if (isMoving) "Moving tile ${position + 1} of $itemCount" else "Tile ${position + 1} of $itemCount",
                    13.sp,
                    HaGreen,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isMoving) {
                    HaButton("Earlier", { onMove(-1) }, enabled = position > 0, requestInitialFocus = position > 0)
                    HaButton("Later", { onMove(1) }, enabled = position < itemCount - 1, requestInitialFocus = position == 0 && itemCount > 1)
                    HaButton("Finish moving", onFinishMove, primary = true)
                } else {
                    HaButton("Move", onStartMove)
                    HaButton("Earlier", { onMove(-1) }, enabled = position > 0)
                    HaButton("Later", { onMove(1) }, enabled = position < itemCount - 1)
                    HaButton("Remove", onRemove, destructive = true)
                }
            }
        }
    } else {
        Row(
            rowModifier
                .semantics {
                    contentDescription = "${entity.name}, ${entity.actionLabel()}. $selectionLabel"
                    role = Role.Button
                }
                .clickable(onClick = onAdd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Column(Modifier.weight(1f)) {
            HaText(entity.name, 16.sp)
            Spacer(Modifier.height(3.dp))
            HaText(entity.actionLabel(), 14.sp, HaMuted)
        }
        HaText(selectionLabel, 14.sp, HaCyan)
        }
    }
}

@Composable
private fun DetailDialog(detail: DetailState, onEvent: DashboardViewModel) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(600.dp)
                .semantics { contentDescription = "Control details" }
                .background(HaSurface, RoundedCornerShape(18.dp))
                .border(1.dp, HaBorder, RoundedCornerShape(18.dp))
                .padding(28.dp),
        ) {
            when (detail) {
                is DetailState.Level -> LevelDetails(detail, onEvent)
                is DetailState.Climate -> ClimateDetails(detail, onEvent)
                is DetailState.Cover -> CoverDetails(detail, onEvent)
                is DetailState.Alarm -> AlarmDetails(detail, onEvent)
                is DetailState.Number -> NumberDetails(detail, onEvent)
                is DetailState.Select -> SelectDetails(detail, onEvent)
                is DetailState.Text -> TextDetails(detail, onEvent)
            }
        }
    }
}

@Composable
private fun LevelDetails(detail: DetailState.Level, onEvent: DashboardViewModel) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(8.dp))
    HaText("${if (detail.entity.domain == "fan") "Speed" else "Brightness"}: ${detail.stagedPercent}%", 17.sp, HaMuted)
    Spacer(Modifier.height(20.dp))
    ValueStepper(detail.stagedPercent, 0..100, { onEvent.stageLevel(it) })
    Spacer(Modifier.height(24.dp))
    ActionRow("Cancel", onEvent::closeDetails, "Apply ${detail.stagedPercent}%", onEvent::applyLevel, requestInitialFocus = true)
}

@Composable
private fun ClimateDetails(detail: DetailState.Climate, onEvent: DashboardViewModel) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(8.dp))
    HaText("${detail.entity.state.replaceFirstChar(Char::uppercase)} · ${detail.entity.string("hvac_action") ?: "Idle"}", 17.sp, HaMuted)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        detail.entity.hvacModes().forEach { mode -> HaButton(mode.replace('_', ' '), { onEvent.setClimateMode(detail.entity, mode) }) }
    }
    Spacer(Modifier.height(20.dp))
    detail.stagedTemperature?.let { temperature ->
        HaText("Target: ${"%.1f".format(temperature)}°", 17.sp, HaMuted)
        ValueStepper(
            value = (temperature * 10).roundToInt(),
            range = (detail.entity.climateMinimum() * 10).roundToInt()..(detail.entity.climateMaximum() * 10).roundToInt(),
            onChange = { onEvent.stageClimateTemperature(it / 10.0) },
            increment = (detail.entity.climateStep() * 10).roundToInt().coerceAtLeast(1),
        )
        Spacer(Modifier.height(20.dp))
        ActionRow("Close", onEvent::closeDetails, "Apply temperature", onEvent::applyClimateTemperature, requestInitialFocus = true)
    } ?: HaButton("Close", onEvent::closeDetails, requestInitialFocus = true)
}

@Composable
private fun CoverDetails(detail: DetailState.Cover, onEvent: DashboardViewModel) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(10.dp))
    if (detail.pendingCommand != null) {
        HaText("Open this secure cover?", 18.sp, HaRed)
        Spacer(Modifier.height(16.dp))
        ActionRow("Cancel", onEvent::cancelSecureCover, "Open", onEvent::confirmSecureCover, requestInitialFocus = true)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HaButton("Open cover", { onEvent.coverCommand(ControlAction.CoverCommand.Command.OPEN) }, primary = true)
            HaButton("Close cover", { onEvent.coverCommand(ControlAction.CoverCommand.Command.CLOSE) })
            HaButton("Stop", { onEvent.coverCommand(ControlAction.CoverCommand.Command.STOP) })
        }
        if (detail.entity.capabilities().canSetCoverPosition) {
            Spacer(Modifier.height(20.dp))
            val position = detail.stagedPosition ?: 0
            HaText("Position: $position%", 17.sp, HaMuted)
            ValueStepper(
                value = position,
                range = 0..100,
                onChange = onEvent::stageCoverPosition,
            )
            Spacer(Modifier.height(14.dp))
            HaButton("Apply position", onEvent::applyCoverPosition, primary = true)
        }
        Spacer(Modifier.height(22.dp))
        HaButton("Done", onEvent::closeDetails, requestInitialFocus = true)
    }
}

@Composable
private fun AlarmDetails(detail: DetailState.Alarm, onEvent: DashboardViewModel) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(8.dp))
    HaText(detail.entity.state.replace('_', ' ').replaceFirstChar(Char::uppercase), 17.sp, HaRed)
    Spacer(Modifier.height(18.dp))
    if (detail.entity.state == "disarmed") {
        if (detail.entity.capabilities().alarmCodeRequired) {
            HaTextField(
                "Alarm code",
                detail.disarmCode,
                onEvent::updateAlarmCode,
                true,
                numeric = detail.entity.alarmCodeIsNumeric(),
            )
            Spacer(Modifier.height(16.dp))
            HaText("The code is used once and never stored.", 14.sp, HaMuted)
            Spacer(Modifier.height(16.dp))
        }
        val supportedModes = detail.entity.alarmArmModes()
        if (supportedModes.isEmpty()) {
            HaText("This alarm did not report any supported arm modes.", 15.sp, HaMuted)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                supportedModes.forEach { mode ->
                    HaButton("Arm ${mode.replace('_', ' ')}", { onEvent.armAlarm(mode) })
                }
            }
        }
    } else {
        HaTextField(
            "Alarm code",
            detail.disarmCode,
            onEvent::updateAlarmCode,
            true,
            numeric = detail.entity.alarmCodeIsNumeric(),
        )
        Spacer(Modifier.height(16.dp))
        HaText("The code is used once and never stored.", 14.sp, HaMuted)
        Spacer(Modifier.height(18.dp))
        ActionRow("Cancel", onEvent::closeDetails, "Disarm", onEvent::disarmAlarm, destructive = true, requestInitialFocus = true)
    }
    Spacer(Modifier.height(20.dp))
    HaButton("Close", onEvent::closeDetails, requestInitialFocus = detail.entity.state == "disarmed")
}

@Composable
private fun NumberDetails(detail: DetailState.Number, onEvent: DashboardViewModel) {
    val minimum = detail.entity.numberMinimum()
    val maximum = detail.entity.numberMaximum()
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(8.dp))
    HaText("Value: ${formatNumber(detail.stagedValue)}", 17.sp, HaMuted)
    Spacer(Modifier.height(4.dp))
    HaText("Range ${formatNumber(minimum)}–${formatNumber(maximum)} · step ${formatNumber(detail.entity.numberStep())}", 14.sp, HaMuted)
    Spacer(Modifier.height(20.dp))
    DecimalStepper(
        value = detail.stagedValue,
        minimum = minimum,
        maximum = maximum,
        step = detail.entity.numberStep(),
        onChange = onEvent::stageNumberValue,
    )
    Spacer(Modifier.height(24.dp))
    ActionRow("Cancel", onEvent::closeDetails, "Apply", onEvent::applyNumberValue, requestInitialFocus = true)
}

@Composable
private fun SelectDetails(detail: DetailState.Select, onEvent: DashboardViewModel) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(8.dp))
    HaText("Choose an option", 17.sp, HaMuted)
    Spacer(Modifier.height(18.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        detail.entity.selectOptions().forEach { option ->
            HaButton(
                label = option,
                onClick = { onEvent.selectOption(option) },
                primary = option == detail.selectedOption,
            )
        }
    }
    Spacer(Modifier.height(22.dp))
    HaButton("Cancel", onEvent::closeDetails, requestInitialFocus = true)
}

@Composable
private fun TextDetails(detail: DetailState.Text, onEvent: DashboardViewModel) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(8.dp))
    HaText("Update the text value", 17.sp, HaMuted)
    Spacer(Modifier.height(18.dp))
    HaTextField(
        label = "Value",
        value = detail.stagedValue,
        onValueChange = onEvent::updateTextValue,
        secret = false,
        placeholder = "Enter value",
    )
    Spacer(Modifier.height(24.dp))
    ActionRow("Cancel", onEvent::closeDetails, "Apply", onEvent::applyTextValue, requestInitialFocus = true)
}

@Composable
private fun ValueStepper(value: Int, range: IntRange, onChange: (Int) -> Unit, increment: Int = 5) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HaButton("−", { onChange((value - increment).coerceAtLeast(range.first)) })
        Box(Modifier.weight(1f).height(12.dp).background(HaBorder, RoundedCornerShape(8.dp))) {
            Box(Modifier.fillMaxWidth((value - range.first).toFloat() / (range.last - range.first).coerceAtLeast(1)).height(12.dp).background(HaAmber, RoundedCornerShape(8.dp)))
        }
        HaButton("+", { onChange((value + increment).coerceAtMost(range.last)) })
    }
}

@Composable
private fun DecimalStepper(
    value: Double,
    minimum: Double,
    maximum: Double,
    step: Double,
    onChange: (Double) -> Unit,
) {
    val span = (maximum - minimum).takeIf { it > 0 } ?: 1.0
    val progress = ((value - minimum) / span).toFloat().coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HaButton("−", { onChange((value - step).coerceAtLeast(minimum)) })
        Box(Modifier.weight(1f).height(12.dp).background(HaBorder, RoundedCornerShape(8.dp))) {
            Box(Modifier.fillMaxWidth(progress).height(12.dp).background(HaAmber, RoundedCornerShape(8.dp)))
        }
        HaButton("+", { onChange((value + step).coerceAtMost(maximum)) })
    }
}

@Composable
private fun ActionRow(
    cancelLabel: String,
    onCancel: () -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
    destructive: Boolean = false,
    requestInitialFocus: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        HaButton(cancelLabel, onCancel, requestInitialFocus = requestInitialFocus)
        Spacer(Modifier.width(10.dp))
        HaButton(actionLabel, onAction, primary = !destructive, destructive = destructive)
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    requestInitialFocus: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusScale by animateFloatAsState(
        targetValue = if (focused) 1.015f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "setting row focus scale",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = title }
            .graphicsLayer {
                scaleX = focusScale
                scaleY = focusScale
                shadowElevation = if (focused) 12.dp.toPx() else 0f
            }
            .heightIn(min = 76.dp)
            .background(if (focused) HaSurfaceFocused else HaSurface, RoundedCornerShape(12.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) HaBlue else HaBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        HaText(title, 18.sp)
        Spacer(Modifier.height(4.dp))
        HaText(subtitle, 14.sp, HaMuted)
    }
    Spacer(Modifier.height(12.dp))
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) focusRequester.requestFocus()
    }
}

@Composable
private fun HaTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    secret: Boolean,
    numeric: Boolean = false,
    requestInitialFocus: Boolean = false,
    modifier: Modifier = Modifier.width(520.dp),
    placeholder: String = "Enter value",
    downFocusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        HaText(label, 14.sp, HaMuted)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = HaText, fontSize = 17.sp),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Text),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusChanged(it.isFocused)
                }
                .onPreviewKeyEvent { event ->
                    if (
                        downFocusRequester != null &&
                        event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN &&
                        event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN
                    ) {
                        downFocusRequester.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                .semantics { contentDescription = label }
                .background(HaSurface, RoundedCornerShape(10.dp))
                .border(if (focused) 2.dp else 1.dp, if (focused) HaBlue else HaBorder, RoundedCornerShape(10.dp))
                .padding(14.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isBlank()) HaText(placeholder, 17.sp, HaMuted)
                    innerTextField()
                }
            },
        )
    }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) focusRequester.requestFocus()
    }
}

@Composable
private fun HaButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    requestInitialFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val localFocusRequester = remember { FocusRequester() }
    val activeFocusRequester = focusRequester ?: localFocusRequester
    val focusScale by animateFloatAsState(
        targetValue = if (focused) 1.03f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "button focus scale",
    )
    val background = when {
        destructive -> HaRed.copy(alpha = .22f)
        primary && enabled -> HaBlue
        primary -> HaSurface
        focused -> HaSurfaceFocused
        else -> HaSurface
    }
    val foreground = when {
        !enabled -> HaMuted
        primary -> Color(0xFF082132)
        else -> HaText
    }
    Box(
        Modifier
            .then(if (upFocusRequester == null) Modifier else Modifier.focusProperties { up = upFocusRequester })
            .focusRequester(activeFocusRequester)
            .onPreviewKeyEvent { event ->
                if (
                    upFocusRequester != null &&
                    event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP &&
                    event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN
                ) {
                    upFocusRequester.requestFocus()
                    true
                } else {
                    false
                }
            }
            .graphicsLayer {
                scaleX = focusScale
                scaleY = focusScale
                shadowElevation = if (focused) 10.dp.toPx() else 0f
            }
            .heightIn(min = 48.dp)
            .semantics { contentDescription = label; role = Role.Button }
            .background(background, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 1.dp, if (focused || primary) HaBlue else HaBorder, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { HaText(label, 14.sp, foreground) }
    LaunchedEffect(requestInitialFocus, enabled) {
        if (requestInitialFocus && enabled) activeFocusRequester.requestFocus()
    }
}

@Composable
private fun ErrorMessage(message: String, dismiss: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(12.dp).background(HaRed.copy(alpha = .22f), RoundedCornerShape(10.dp)).clickable(onClick = dismiss).padding(14.dp), contentAlignment = Alignment.Center) {
        HaText(message, 15.sp, HaText)
    }
}

@Composable
private fun HaText(value: String, size: androidx.compose.ui.unit.TextUnit, color: Color = HaText, modifier: Modifier = Modifier) {
    Text(value, modifier = modifier, color = color, fontSize = size)
}

private fun tileIcon(kind: ControlKind) = when (kind) {
    ControlKind.LIGHT -> Icons.Outlined.Lightbulb
    ControlKind.FAN -> Icons.Outlined.Toys
    ControlKind.CLIMATE -> Icons.Outlined.Thermostat
    ControlKind.COVER -> Icons.Outlined.Blinds
    ControlKind.ALARM -> Icons.Outlined.Security
    ControlKind.SCENE, ControlKind.SCRIPT -> Icons.Outlined.PlayArrow
    ControlKind.BUTTON -> Icons.Outlined.Toys
    ControlKind.NUMBER, ControlKind.SELECT, ControlKind.TEXT -> Icons.Outlined.Settings
    ControlKind.SWITCH, ControlKind.INPUT_BOOLEAN, ControlKind.GROUP -> Icons.Outlined.Power
    ControlKind.UNSUPPORTED -> Icons.Outlined.WarningAmber
}

private fun formatNumber(value: Double): String = String.format(Locale.US, "%.3f", value)
    .trimEnd('0')
    .trimEnd('.')
