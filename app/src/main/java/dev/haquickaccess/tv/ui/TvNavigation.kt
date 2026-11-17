package dev.haquickaccess.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import dev.haquickaccess.tv.R
import dev.haquickaccess.tv.data.ConnectionStatus

internal val TvColorScheme = darkColorScheme(
    primary = HaBlue, onPrimary = HaBackground,
    primaryContainer = HaSurfaceFocused, onPrimaryContainer = HaText,
    secondaryContainer = HaSurfaceFocused, onSecondaryContainer = HaText,
    background = HaBackground, onBackground = HaText,
    surface = HaSurface, onSurface = HaText,
    surfaceVariant = HaSurfaceFocused, onSurfaceVariant = HaMuted,
    error = HaRed,
)

@Composable
internal fun TvNavigation(
    state: DashboardUiState,
    actions: DashboardActions,
    navigationFocus: FocusRequester,
    contentFocus: FocusRequester,
) {
    val selected = when (state.screen) {
        AppScreen.Dashboard -> 0
        AppScreen.AllControls -> 1
        else -> 2
    }
    var focusedIndex by remember { mutableIntStateOf(selected) }
    val labels = listOf(stringResource(R.string.favorites), stringResource(R.string.all_controls), stringResource(R.string.settings))
    val destinations = listOf(actions::openFavorites, actions::openAllControls, actions::openSettings)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TabRow(selectedTabIndex = selected, modifier = Modifier.weight(1f),
            indicator = { positions, hasFocus ->
                positions.getOrNull(if (hasFocus) focusedIndex else selected)?.let { position ->
                    TabRowDefaults.PillIndicator(position, hasFocus, inactiveColor = HaSurfaceFocused)
                }
            },
        ) {
            labels.forEachIndexed { index, label ->
                Tab(
                    selected = selected == index,
                    onFocus = { focusedIndex = index },
                    colors = TabDefaults.pillIndicatorTabColors(
                        contentColor = HaText, inactiveContentColor = HaMuted, selectedContentColor = HaText,
                        focusedContentColor = HaBackground, focusedSelectedContentColor = HaBackground,
                    ),
                    onClick = { destinations[index]() },
                    modifier = Modifier
                        .then(if (selected == index) Modifier.focusRequester(navigationFocus) else Modifier)
                        .focusProperties { down = contentFocus }
                        .semantics { contentDescription = label },
                ) { Text(label, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), fontSize = 18.sp) }
            }
        }
        Spacer(Modifier.width(16.dp))
        val status = when {
            state.isShowingCachedSnapshot -> R.string.cached_connection
            state.connectionStatus is ConnectionStatus.Connected -> R.string.connected
            state.connectionStatus is ConnectionStatus.Connecting -> R.string.connecting
            state.connectionStatus is ConnectionStatus.Failed -> R.string.connection_problem
            else -> R.string.offline
        }
        Text(stringResource(status), color = if (state.connectionStatus is ConnectionStatus.Connected) HaGreen else HaMuted, fontSize = 14.sp)
    }
}
