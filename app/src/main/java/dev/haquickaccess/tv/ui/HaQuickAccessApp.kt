package dev.haquickaccess.tv.ui

import androidx.compose.ui.res.stringResource
import dev.haquickaccess.tv.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme

internal val HaBackground = Color(0xFF080D19)
internal val HaSurface = Color(0xFF121B2C)
internal val HaSurfaceFocused = Color(0xFF1A2942)
internal val HaSurfaceActive = Color(0xFF211F1C)
internal val HaBorder = Color(0xFF263753)
internal val HaBlue = Color(0xFF59D5FF)
internal val HaGreen = Color(0xFF4CAF50)
internal val HaAmber = Color(0xFFFF9800)
internal val HaCyan = Color(0xFF9CE7FF)
internal val HaRed = Color(0xFFFF6B78)
internal val HaText = Color(0xFFF6F8FF)
internal val HaMuted = Color(0xFFAFBED4)
internal val HaViolet = Color(0xFFAA9CFF)

@Composable
fun HaQuickAccessApp(
    state: DashboardUiState,
    onEvent: DashboardActions,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val contentFocus = remember { FocusRequester() }
    val navigationFocus = remember { FocusRequester() }
    val screenState = rememberSaveableStateHolder()
    var managerSearchFocused by remember { mutableStateOf(false) }
    LaunchedEffect(state.details != null || state.launcherRecovery != null) {
        if (state.details != null || state.launcherRecovery != null) focusManager.clearFocus(force = true)
    }
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

    MaterialTheme(colorScheme = TvColorScheme) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(HaBackground)
                .semantics { testTagsAsResourceId = true }
                .testTag("app_root"),
        ) {
            val horizontalSafeMargin = (maxWidth * .05f).coerceAtLeast(24.dp)
            val verticalSafeMargin = (maxHeight * .05f).coerceAtLeast(20.dp)
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalSafeMargin, vertical = verticalSafeMargin),
            ) {
                Column(Modifier.fillMaxSize()) {
                    if (state.isConfigured && state.screen != AppScreen.ConnectionSetup) {
                        TvNavigation(state, onEvent, navigationFocus, contentFocus)
                        Spacer(Modifier.height(20.dp))
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().focusRequester(contentFocus)
                        // An explicit tile request must override the group's saved child.
                        .then(if (state.focusRequest == null) Modifier.focusRestorer() else Modifier)
                        .focusGroup()) {
                        screenState.SaveableStateProvider(state.screen.toString()) {
                            when {
                                !state.isSettingsLoaded -> LoadingScreen()
                                !state.isConfigured || state.screen == AppScreen.ConnectionSetup -> SetupScreen(state, onEvent)
                                state.screen == AppScreen.AllControls -> AllControlsScreen(state, onEvent)
                                state.screen == AppScreen.Settings -> SettingsScreen(state, onEvent)
                                state.screen == AppScreen.ManageTiles -> TileManagerScreen(state, onEvent) { managerSearchFocused = it }
                                state.screen == AppScreen.ManageShortcuts -> ShortcutManagerScreen(state, onEvent)
                                state.screen == AppScreen.Diagnostics -> DiagnosticsScreen(state, onEvent)
                                state.screen == AppScreen.Privacy -> PrivacyPolicyScreen(onEvent)
                                else -> DashboardScreen(state, onEvent, navigationFocus)
                            }
                        }
                    }
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
        HaText(stringResource(R.string.loading), 20.sp)
    }
}
