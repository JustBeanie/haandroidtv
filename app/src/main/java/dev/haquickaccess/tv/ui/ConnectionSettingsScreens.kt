package dev.haquickaccess.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.res.stringResource
import dev.haquickaccess.tv.R
import dev.haquickaccess.tv.data.UrlValidator
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.haquickaccess.tv.data.ConnectionStatus

@Composable
internal fun SetupScreen(state: DashboardUiState, onEvent: DashboardActions) {
    var tokenStep by rememberSaveable { mutableStateOf(false) }
    var addressError by remember { mutableStateOf<String?>(null) }
    BackHandler(tokenStep && !state.isSavingConnection) { tokenStep = false }
    Column(
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            HaText(stringResource(if (state.isConfigured) R.string.update_connection else R.string.connect_title), 30.sp)
            Spacer(Modifier.height(12.dp))
            HaText(stringResource(R.string.setup_help), 17.sp, HaMuted)
            Spacer(Modifier.height(24.dp))
            if (!tokenStep) {
                HaTextField(stringResource(R.string.server_url), state.setupBaseUrl,
                    { addressError = null; onEvent.updateSetupBaseUrl(it) }, false,
                    placeholder = "https://homeassistant.local:8123", requestInitialFocus = true)
                (addressError ?: state.setupErrorMessage)?.let { Spacer(Modifier.height(8.dp)); HaText(it, 16.sp, HaRed) }
                Spacer(Modifier.height(20.dp))
                HaButton(stringResource(R.string.continue_setup), {
                    val result = UrlValidator.normalize(state.setupBaseUrl)
                    if (result.isSuccess) tokenStep = true else addressError = result.exceptionOrNull()?.message
                }, primary = true)
            } else {
                HaText(state.setupBaseUrl, 16.sp, HaMuted)
                Spacer(Modifier.height(16.dp))
                HaTextField(stringResource(R.string.token_label), state.setupToken, onEvent::updateSetupToken,
                    true, requestInitialFocus = true)
                state.setupErrorMessage?.let { Spacer(Modifier.height(10.dp)); HaText(it, 16.sp, HaRed) }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HaButton(stringResource(R.string.edit_address), { tokenStep = false }, enabled = !state.isSavingConnection)
                    HaButton(stringResource(if (state.isSavingConnection) R.string.verifying else R.string.validate_save),
                        onEvent::saveConnection, primary = true, enabled = !state.isSavingConnection)
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(state: DashboardUiState, onEvent: DashboardActions) {
    var confirmForget by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        HaText(stringResource(R.string.settings), 28.sp)
        Spacer(Modifier.height(22.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            HaText(stringResource(R.string.controls_heading), 18.sp, HaMuted)
            Spacer(Modifier.height(8.dp))
            SettingRow(
                title = stringResource(R.string.manage_controls),
                subtitle = stringResource(R.string.manage_controls_description),
                onClick = onEvent::openTileManager,
                requestInitialFocus = true,
            )
            SettingRow(stringResource(R.string.home_shortcuts), stringResource(R.string.home_shortcuts_description), onEvent::openShortcutManager)
            Spacer(Modifier.height(8.dp))
            HaText(stringResource(R.string.connection_app_heading), 18.sp, HaMuted)
            Spacer(Modifier.height(8.dp))
            SettingRow(stringResource(R.string.connection_label), state.settings.baseUrl.orEmpty(), onEvent::openConnectionSetup)
            SettingRow(stringResource(R.string.diagnostics), stringResource(R.string.diagnostics_description), onEvent::openDiagnostics)
            SettingRow(
                stringResource(R.string.privacy_policy),
                stringResource(R.string.privacy_description),
                onClick = onEvent::openPrivacyPolicy,
            )
            Spacer(Modifier.height(10.dp))
            HaButton(stringResource(R.string.forget), { confirmForget = true }, destructive = true)
            Spacer(Modifier.height(20.dp))
            HaButton(stringResource(R.string.back), onEvent::closeScreen)
        }
    }
    if (confirmForget) {
        TvDialog(stringResource(R.string.forget_title), onDismissRequest = { confirmForget = false }) {
            HaText(stringResource(R.string.forget_title), 26.sp)
            Spacer(Modifier.height(12.dp))
            HaText(stringResource(R.string.forget_body), 17.sp, HaMuted)
            Spacer(Modifier.height(20.dp))
            HaButton(stringResource(R.string.cancel), { confirmForget = false }, requestInitialFocus = true)
            Spacer(Modifier.height(12.dp))
            HaButton(stringResource(R.string.forget), { confirmForget = false; onEvent.clearConnection() }, destructive = true)
        }
    }
}

@Composable
internal fun DiagnosticsScreen(state: DashboardUiState, onEvent: DashboardActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HaText(stringResource(R.string.diagnostics), 30.sp)
        Spacer(Modifier.height(18.dp))
        val connection = when (val status = state.connectionStatus) {
            is ConnectionStatus.Connected -> "Connected${status.version?.let { " · Home Assistant $it" }.orEmpty()}"
            is ConnectionStatus.Connecting -> "Connecting securely"
            is ConnectionStatus.Failed -> "Connection problem${status.message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
            ConnectionStatus.Disconnected -> "Offline"
        }
        DiagnosticRow(stringResource(R.string.connection_label), connection)
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
        HaButton(stringResource(R.string.back), onEvent::closeScreen)
    }
}

@Composable
internal fun DiagnosticRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().background(HaSurface, RoundedCornerShape(12.dp)).padding(16.dp)) {
        HaText(label, 14.sp, HaMuted)
        Spacer(Modifier.height(4.dp))
        HaText(value, 17.sp)
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
internal fun PrivacyPolicyScreen(onEvent: DashboardActions) {
    Column(Modifier.fillMaxSize()) {
        HaText(stringResource(R.string.privacy_policy), 30.sp)
        Spacer(Modifier.height(6.dp))
        HaText("Last updated November 16, 2026", 14.sp, color = HaMuted)
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HaText(
                "HA Quick Access is an independent Android TV client for a Home Assistant server chosen and operated by you. It is not affiliated with Home Assistant.",
                16.sp,
            )
            HaText("Data handling", 20.sp, color = HaCyan)
            HaText(
                "The app does not collect, sell, share, or transmit your data to the app developer or to advertising, analytics, or crash-reporting services.",
                16.sp,
                color = HaMuted,
            )
            HaText(
                "Your server address, dashboard settings, last selected tile, and cached favorite names and states stay on this device. Cached states support offline display. Your Home Assistant token is encrypted with a non-exportable Android Keystore key, and app backup and device-transfer backup are disabled.",
                16.sp,
                color = HaMuted,
            )
            HaText(
                "The app sends the token and your control requests only to the HTTPS/WSS Home Assistant server that you enter. That user-operated server has its own privacy practices.",
                16.sp,
                color = HaMuted,
            )
            HaText("Deletion", 20.sp, color = HaCyan)
            HaText(
                "Choose Forget Home Assistant to remove locally stored connection information and cached favorite states. Uninstalling also removes local app data and the associated Keystore key.",
                16.sp,
                color = HaMuted,
            )
            HaText(
                "Questions can be submitted at github.com/JustBeanie/haandroidtv/issues.",
                16.sp,
                color = HaMuted,
            )
        }
        Spacer(Modifier.height(16.dp))
        HaButton(stringResource(R.string.back), onEvent::closeScreen, requestInitialFocus = true)
    }
}

