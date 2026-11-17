package dev.haquickaccess.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.haquickaccess.tv.R
import dev.haquickaccess.tv.data.ConnectionStatus
import dev.haquickaccess.tv.domain.model.ControlBrowser
import dev.haquickaccess.tv.domain.model.actionLabel
import dev.haquickaccess.tv.domain.model.capabilities

@Composable
internal fun AllControlsScreen(state: DashboardUiState, actions: DashboardActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val domains = remember(state.entities) { ControlBrowser.domains(state.entities.values) }
    val controls = remember(state.entities, query, domain) { ControlBrowser.filter(state.entities.values, query, domain) }
    val selected = state.entities[selectedId]
    val online = state.connectionStatus is ConnectionStatus.Connected && state.areInitialStatesLoaded
    val listFocus = remember { FocusRequester() }
    BackHandler(selectedId != null) { selectedId = null }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 360.dp
        Column(Modifier.fillMaxSize()) {
            if (!compact) {
                HaText(stringResource(R.string.all_controls), 28.sp)
                Spacer(Modifier.height(12.dp))
            }
            HaTextField(stringResource(R.string.search_controls), query, { query = it }, false,
                placeholder = stringResource(R.string.search_hint), downFocusRequester = listFocus)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HaButton(stringResource(R.string.all_filter), { domain = null }, primary = domain == null)
                domains.forEach { value -> HaButton(ControlBrowser.domainLabel(value), { domain = value }, primary = domain == value) }
            }
            Spacer(Modifier.height(12.dp))
            if (controls.isEmpty()) {
                HaText(stringResource(R.string.no_controls), 22.sp)
                HaText(stringResource(R.string.no_controls_help), 16.sp, HaMuted)
            } else {
                LazyColumn(Modifier.weight(1f).focusRequester(listFocus), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
                    items(controls, key = { it.entityId }) { entity ->
                        TvListRow(entity.name, entity.actionLabel() + if (state.settings.tiles.any { it.entityId == entity.entityId }) stringResource(R.string.favorite_suffix) else "",
                            onClick = { keyboard?.hide(); selectedId = entity.entityId })
                    }
                }
            }
        }
    }
    if (selected != null) {
        TvDialog(selected.name, onDismissRequest = { selectedId = null }) {
            HaText(selected.name, 26.sp)
            HaText(selected.actionLabel(), 18.sp, HaMuted)
            Spacer(Modifier.height(20.dp))
            val pinned = state.settings.tiles.any { it.entityId == selected.entityId }
            HaButton(stringResource(if (pinned) R.string.remove_favorite else R.string.add_favorite), {
                if (pinned) actions.removeTile(selected.entityId) else actions.addTile(selected)
            }, requestInitialFocus = true)
            Spacer(Modifier.height(12.dp))
            if (actions.supportsDetails(selected)) {
                HaButton(stringResource(R.string.details), { selectedId = null; actions.openDetails(selected) }, enabled = online && !selected.unavailable)
            } else if (selected.capabilities().hasPrimaryAction) {
                HaButton(selected.actionLabel(), { actions.performPrimaryAction(selected) }, enabled = online && !selected.unavailable && selected.entityId !in state.pendingEntityIds)
            }
            Spacer(Modifier.height(12.dp))
            HaButton(stringResource(R.string.close), { selectedId = null })
        }
    }
}
