package dev.haquickaccess.tv.ui

import androidx.compose.ui.res.stringResource
import dev.haquickaccess.tv.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.haquickaccess.tv.domain.model.ControlBrowser
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.ShortcutBehavior
import dev.haquickaccess.tv.domain.model.actionLabel
import dev.haquickaccess.tv.domain.model.capabilities

@Composable
internal fun ShortcutManagerScreen(state: DashboardUiState, onEvent: DashboardActions) {
    Column(Modifier.fillMaxSize()) {
        HaText(stringResource(R.string.home_shortcuts), 30.sp)
        Spacer(Modifier.height(10.dp))
        HaText("Choose up to four controls and what selecting each shortcut does.", 15.sp, HaMuted)
        if (state.isProjectivyInstalled) {
            Spacer(Modifier.height(10.dp))
            HaText("Projectivy detected", 16.sp, HaGreen)
            HaText("In Projectivy, open Settings › Edit Channels › HA Quick Access to show these tiles.", 14.sp, HaMuted)
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.tiles.forEach { entity ->
                item(key = entity.entityId) {
                    val existing = state.settings.homeShortcuts.firstOrNull { it.entityId == entity.entityId }
                    Column(Modifier.fillMaxWidth().background(HaSurface, RoundedCornerShape(12.dp)).padding(14.dp)) {
                        HaText(entity.name, 17.sp)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (entity.capabilities().canToggle) HaButton("Toggle", { onEvent.setShortcut(entity.entityId, ShortcutBehavior.TOGGLE) }, primary = existing?.behavior == ShortcutBehavior.TOGGLE)
                            HaButton("Focus", { onEvent.setShortcut(entity.entityId, ShortcutBehavior.FOCUS) }, primary = existing?.behavior == ShortcutBehavior.FOCUS)
                            if (onEvent.supportsDetails(entity)) HaButton("Details", { onEvent.setShortcut(entity.entityId, ShortcutBehavior.DETAILS) }, primary = existing?.behavior == ShortcutBehavior.DETAILS)
                            if (existing != null) HaButton("Remove", { onEvent.removeShortcut(entity.entityId) }, destructive = true)
                        }
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
internal fun TileManagerScreen(
    state: DashboardUiState,
    onEvent: DashboardActions,
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
    val selectableControls = remember(state.availableEntities, state.settings.tiles) {
        val configuredEntityIds = state.settings.tiles.mapTo(hashSetOf()) { it.entityId }
        state.availableEntities.filterNot { it.entityId in configuredEntityIds }
    }
    val unavailableControlCount = remember(selectableControls) { selectableControls.count(HaEntity::unavailable) }
    val readOnlyControlCount = remember(selectableControls) { selectableControls.count { !it.capabilities().isQuickControl } }
    val availableControls = remember(selectableControls, includeUnavailable, includeReadOnly) {
        selectableControls.filter { entity ->
            (includeUnavailable || !entity.unavailable) &&
                (includeReadOnly || entity.capabilities().isQuickControl)
        }
    }
    val domains = remember(availableControls) { ControlBrowser.domains(availableControls) }
    val filteredControls = remember(availableControls, searchQuery, selectedDomain) {
        ControlBrowser.filter(availableControls, searchQuery, selectedDomain)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                HaText(if (isReplacingLauncherShortcut) "Replace launcher shortcut" else stringResource(R.string.manage_controls), 30.sp)
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
                label = stringResource(R.string.all_filter),
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
internal fun TileOrderPreview(tiles: List<HaEntity>) {
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
internal fun DomainFilter(
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
internal fun EmptyControlResults(query: String, domain: String?, includeUnavailable: Boolean, includeReadOnly: Boolean) {
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
internal fun EntityList(
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
internal fun EntityListItem(
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
            shadowElevation = 0f
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isMoving) {
                    HaButton("Earlier", { onMove(-1) }, enabled = position > 0, requestInitialFocus = position > 0)
                    HaButton("Later", { onMove(1) }, enabled = position < itemCount - 1, requestInitialFocus = position == 0 && itemCount > 1)
                    HaButton("Finish moving", onFinishMove, primary = true)
                } else {
                    HaButton("Move", onStartMove)
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

