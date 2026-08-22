package dev.haquickaccess.tv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
import dev.haquickaccess.tv.domain.model.capabilities
import dev.haquickaccess.tv.domain.model.climateMaximum
import dev.haquickaccess.tv.domain.model.climateMinimum
import dev.haquickaccess.tv.domain.model.climateStep
import dev.haquickaccess.tv.domain.model.climateTarget
import dev.haquickaccess.tv.domain.model.hvacModes
import dev.haquickaccess.tv.domain.model.levelPercent
import kotlin.math.roundToInt

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
    BackHandler(enabled = state.errorMessage != null || state.details != null || state.screen != AppScreen.Dashboard) {
        when {
            state.errorMessage != null -> onEvent.dismissError()
            state.details != null -> onEvent.closeDetails()
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
    LaunchedEffect(deepLinkEntityId, deepLinkBehavior, state.entities) {
        if (handledDeepLink) return@LaunchedEffect
        deepLinkEntityId?.let { state.entities[it] }?.let { entity ->
            when (deepLinkBehavior) {
                "toggle" -> if (entity.capabilities().canToggle) onEvent.toggle(entity) else onEvent.openDetails(entity)
                "details" -> onEvent.openDetails(entity)
                "focus" -> onEvent.focusEntity(entity.entityId)
                else -> onEvent.openDetails(entity)
            }
            handledDeepLink = true
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(HaBackground).padding(horizontal = 44.dp, vertical = 28.dp)) {
            when {
                !state.isConfigured || state.screen == AppScreen.ConnectionSetup -> SetupScreen(state, onEvent)
                state.screen == AppScreen.Settings -> SettingsScreen(state, onEvent)
                state.screen == AppScreen.ManageTiles -> TileManagerScreen(state, onEvent)
                state.screen == AppScreen.ManageShortcuts -> ShortcutManagerScreen(state, onEvent)
                state.screen == AppScreen.Diagnostics -> DiagnosticsScreen(state, onEvent)
                else -> DashboardScreen(state, onEvent)
            }
            state.details?.let { DetailDialog(it, onEvent) }
            state.errorMessage?.let { ErrorMessage(it, onEvent::dismissError) }
        }
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
    var initialFocusTarget by remember { mutableStateOf<String?>(null) }
    var restoredInitialPosition by remember { mutableStateOf(false) }
    val restoredFocusIndex = state.tiles.indexOfFirst { it.entityId == state.settings.lastFocusedEntityId }
    LaunchedEffect(state.tiles.size) {
        if (!restoredInitialPosition && state.tiles.isNotEmpty()) {
            val targetIndex = restoredFocusIndex.takeIf { it >= 0 } ?: 0
            gridState.scrollToItem(targetIndex)
            initialFocusTarget = state.tiles[targetIndex].entityId
            restoredInitialPosition = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header(state.connectionStatus, onEvent::openSettings)
        Spacer(Modifier.height(22.dp))
        if (state.tiles.isEmpty()) {
            EmptyDashboard(onEvent::openTileManager)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                state = gridState,
                modifier = Modifier.weight(1f),
            ) {
                items(state.tiles, key = HaEntity::entityId) { entity ->
                    HomeAssistantTile(
                        entity = entity,
                        pending = entity.entityId in state.pendingEntityIds,
                        onClick = { if (entity.capabilities().canToggle) onEvent.toggle(entity) else onEvent.openDetails(entity) },
                        onLongClick = { onEvent.openDetails(entity) },
                        onFocused = { onEvent.saveFocus(entity.entityId) },
                        requestInitialFocus = entity.entityId == initialFocusTarget,
                        onInitialFocusRequested = { initialFocusTarget = null },
                    )
                }
            }
        }
        HaText("D-pad to move  •  Select to control  •  Hold Select for details", 14.sp, HaMuted)
    }
}

@Composable
private fun Header(status: ConnectionStatus, onSettings: () -> Unit) {
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
        Icon(
            Icons.Outlined.Settings,
            "Settings",
            Modifier.size(38.dp).background(HaSurface, CircleShape).padding(9.dp).clickable(onClick = onSettings),
            HaText,
        )
    }
}

@Composable
private fun EmptyDashboard(onManage: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Lightbulb, null, Modifier.size(58.dp), HaCyan)
        Spacer(Modifier.height(16.dp))
        HaText("Choose your quick controls", 25.sp)
        Spacer(Modifier.height(8.dp))
        HaText("Add Home Assistant entities to your TV dashboard.", 16.sp, HaMuted)
        Spacer(Modifier.height(20.dp))
        HaButton("Manage controls", onManage, primary = true)
    }
}

@Composable
private fun HomeAssistantTile(
    entity: HaEntity,
    pending: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    requestInitialFocus: Boolean,
    onInitialFocusRequested: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
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
        else -> entity.state.replace('_', ' ').replaceFirstChar(Char::uppercase)
    }
    Box(
        modifier = Modifier
            .focusRequester(focusRequester)
            .height(128.dp)
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                val nativeEvent = event.nativeKeyEvent
                when {
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
            .background(if (focused) HaSurfaceFocused else HaSurface, RoundedCornerShape(14.dp))
            .border(if (focused) 3.dp else 1.dp, if (focused) HaBlue else HaBorder, RoundedCornerShape(14.dp))
            .semantics { contentDescription = "${entity.name}, $stateLabel"; role = Role.Button }
            .combinedClickable(
                enabled = !entity.unavailable && !pending,
                onClick = onClick,
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
            focusRequester.requestFocus()
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
                        HaButton("Details", { onEvent.setShortcut(entity.entityId, dev.haquickaccess.tv.domain.model.ShortcutBehavior.DETAILS) }, primary = existing?.behavior?.name == "DETAILS")
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
private fun TileManagerScreen(state: DashboardUiState, onEvent: DashboardViewModel) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedDomain by remember { mutableStateOf<String?>(null) }
    val availableControls = state.availableEntities.filter { candidate ->
        state.settings.tiles.none { it.entityId == candidate.entityId }
    }
    val domains = ControlBrowser.domains(availableControls)
    val filteredControls = ControlBrowser.filter(availableControls, searchQuery, selectedDomain)

    Column(Modifier.fillMaxSize()) {
        HaText("Manage controls", 30.sp)
        Spacer(Modifier.height(12.dp))
        HaText("Search by name or entity ID, then filter by type. Green controls are already on the dashboard.", 15.sp, HaMuted)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            HaTextField(
                label = "Search controls",
                value = searchQuery,
                onValueChange = { searchQuery = it },
                secret = false,
                modifier = Modifier.weight(1f),
                requestInitialFocus = true,
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
            DomainFilter("All", selected = selectedDomain == null) { selectedDomain = null }
            domains.forEach { domain ->
                DomainFilter(
                    label = ControlBrowser.domainLabel(domain),
                    selected = selectedDomain == domain,
                ) { selectedDomain = domain }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f)) {
                HaText("Available controls · ${filteredControls.size}", 19.sp)
                Spacer(Modifier.height(10.dp))
                if (filteredControls.isEmpty()) {
                    EmptyControlResults(searchQuery, selectedDomain)
                } else {
                    EntityList(entities = filteredControls, onAdd = onEvent::addTile)
                }
            }
            Column(Modifier.weight(1f)) {
                HaText("Added to dashboard", 19.sp, HaGreen)
                Spacer(Modifier.height(10.dp))
                EntityList(state.tiles, showActions = true, onAdd = {}, onRemove = onEvent::removeTile, onMove = onEvent::moveTile)
            }
        }
        HaButton("Done", onEvent::closeScreen, primary = true)
    }
}

@Composable
private fun DomainFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    HaButton(label, onClick, primary = selected)
}

@Composable
private fun EmptyControlResults(query: String, domain: String?) {
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
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entities.forEach { entity ->
            EntityListItem(
                entity = entity,
                selected = showActions,
                showActions = showActions,
                onAdd = { onAdd(entity) },
                onRemove = { onRemove(entity.entityId) },
                onMove = { direction -> onMove(entity.entityId, direction) },
            )
        }
    }
}

@Composable
private fun EntityListItem(
    entity: HaEntity,
    selected: Boolean,
    showActions: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val rowModifier = Modifier
        .fillMaxWidth()
        .onFocusChanged { focused = it.isFocused }
        .semantics {
            contentDescription = if (selected) "${entity.name}, added to dashboard" else "${entity.name}, add to dashboard"
            role = Role.Button
        }
        .background(
            when {
                focused -> HaSurfaceFocused
                selected -> HaGreen.copy(alpha = .16f)
                else -> HaSurface
            },
            shape,
        )
        .border(
            if (focused) 2.dp else 1.dp,
            when {
                focused -> HaBlue
                selected -> HaGreen
                else -> HaBorder
            },
            shape,
        )
        .padding(12.dp)

    Row(
        if (showActions) rowModifier else rowModifier.clickable(onClick = onAdd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            HaText(entity.name, 16.sp)
            Spacer(Modifier.height(3.dp))
            HaText(entity.state.replace('_', ' ').replaceFirstChar(Char::uppercase), 14.sp, HaMuted)
        }
        if (showActions) {
            HaText("Added", 13.sp, HaGreen)
            Spacer(Modifier.width(8.dp))
            HaButton("↑", { onMove(-1) })
            HaButton("↓", { onMove(1) })
            HaButton("Remove", onRemove, destructive = true)
        } else {
            HaButton("Add", onAdd, primary = true)
        }
    }
}

@Composable
private fun DetailDialog(detail: DetailState, onEvent: DashboardViewModel) {
    val focusRequester = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(600.dp)
                .focusRequester(focusRequester)
                .focusable()
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
            }
        }
    }
    LaunchedEffect(detail) { focusRequester.requestFocus() }
}

@Composable
private fun LevelDetails(detail: DetailState.Level, onEvent: DashboardViewModel) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(8.dp))
    HaText("${if (detail.entity.domain == "fan") "Speed" else "Brightness"}: ${detail.stagedPercent}%", 17.sp, HaMuted)
    Spacer(Modifier.height(20.dp))
    ValueStepper(detail.stagedPercent, 0..100, { onEvent.stageLevel(it) })
    Spacer(Modifier.height(24.dp))
    ActionRow("Cancel", onEvent::closeDetails, "Apply ${detail.stagedPercent}%", onEvent::applyLevel)
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
        ActionRow("Close", onEvent::closeDetails, "Apply temperature", onEvent::applyClimateTemperature)
    } ?: HaButton("Close", onEvent::closeDetails)
}

@Composable
private fun CoverDetails(detail: DetailState.Cover, onEvent: DashboardViewModel) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(10.dp))
    if (detail.pendingCommand != null) {
        HaText("Open this secure cover?", 18.sp, HaRed)
        Spacer(Modifier.height(16.dp))
        ActionRow("Cancel", onEvent::cancelSecureCover, "Open", onEvent::confirmSecureCover)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HaButton("Open", { onEvent.coverCommand(ControlAction.CoverCommand.Command.OPEN) }, primary = true)
            HaButton("Close", { onEvent.coverCommand(ControlAction.CoverCommand.Command.CLOSE) })
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
        HaButton("Close", onEvent::closeDetails)
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
        ActionRow("Cancel", onEvent::closeDetails, "Disarm", onEvent::disarmAlarm, destructive = true)
    }
    Spacer(Modifier.height(20.dp))
    HaButton("Close", onEvent::closeDetails)
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
private fun ActionRow(cancelLabel: String, onCancel: () -> Unit, actionLabel: String, onAction: () -> Unit, destructive: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        HaButton(cancelLabel, onCancel)
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
    Column(
        Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = title }
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
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        HaText(label, 14.sp, HaMuted)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = HaText, fontSize = 17.sp),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Text),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label }
                .background(HaSurface, RoundedCornerShape(10.dp))
                .border(if (focused) 2.dp else 1.dp, if (focused) HaBlue else HaBorder, RoundedCornerShape(10.dp))
                .padding(14.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isBlank()) HaText("Type to search", 17.sp, HaMuted)
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
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val background = when {
        destructive -> HaRed.copy(alpha = .22f)
        primary && enabled -> HaBlue
        primary -> HaSurface
        focused -> HaSurfaceFocused
        else -> HaSurface
    }
    val foreground = if (primary) Color(0xFF082132) else HaText
    Box(
        Modifier
            .focusRequester(focusRequester)
            .background(background, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 1.dp, if (focused || primary) HaBlue else HaBorder, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) { HaText(label, 14.sp, foreground) }
    LaunchedEffect(requestInitialFocus, enabled) {
        if (requestInitialFocus && enabled) focusRequester.requestFocus()
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
    ControlKind.SWITCH, ControlKind.INPUT_BOOLEAN -> Icons.Outlined.Power
    ControlKind.UNSUPPORTED -> Icons.Outlined.WarningAmber
}
