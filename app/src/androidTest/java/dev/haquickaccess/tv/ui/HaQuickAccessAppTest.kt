package dev.haquickaccess.tv.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.haquickaccess.tv.data.AppSettings
import dev.haquickaccess.tv.data.ConnectionStatus
import dev.haquickaccess.tv.data.HomeAssistantSession
import dev.haquickaccess.tv.data.SettingsStore
import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.ShortcutConfiguration
import dev.haquickaccess.tv.domain.model.TileConfiguration
import dev.haquickaccess.tv.platform.HomeChannelGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@RunWith(AndroidJUnit4::class)
class HaQuickAccessAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun saved_focus_is_restored_and_dpad_select_toggles_a_safe_tile() {
        val den = HaEntity("light.den", "off")
        val reading = HaEntity("switch.reading", "off")
        val settings = UiSettingsStore(
            AppSettings(
                baseUrl = "https://ha.example",
                tokenEnvelope = "encrypted",
                tiles = listOf(TileConfiguration(den.entityId, 0), TileConfiguration(reading.entityId, 1)),
                lastFocusedEntityId = den.entityId,
            ),
        )
        val session = UiSession(mapOf(den.entityId to den, reading.entityId to reading))
        val viewModel = DashboardViewModel(settings, session, UiChannelGateway(), Dispatchers.IO)

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(
                state = state,
                deepLinkEntityId = null,
                deepLinkBehavior = null,
                onEvent = viewModel,
            )
        }

        composeRule.waitUntil(5_000) { session.starts == listOf("https://ha.example" to "token") }
        composeRule.onNodeWithContentDescription("den, Off").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.onNodeWithContentDescription("reading, Off").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitUntil(5_000) { session.actions.isNotEmpty() }

        assertEquals(ControlAction.Toggle(reading), session.actions.single())
    }

    @Test
    fun long_press_opens_staged_dimmer_details_without_toggling() {
        val lamp = HaEntity("light.den", "on", JsonObject(mapOf("brightness" to JsonPrimitive(128))))
        val settings = UiSettingsStore(
            AppSettings(
                baseUrl = "https://ha.example",
                tokenEnvelope = "encrypted",
                tiles = listOf(TileConfiguration(lamp.entityId, 0)),
            ),
        )
        val session = UiSession(mapOf(lamp.entityId to lamp))
        val viewModel = DashboardViewModel(settings, session, UiChannelGateway(), Dispatchers.IO)

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, null, null, viewModel)
        }

        composeRule.onNodeWithContentDescription("den, 50%").performTouchInput { longClick() }
        composeRule.onNodeWithText("Brightness: 50%").assertIsDisplayed()
        assertTrue(session.actions.isEmpty())
    }

    @Test
    fun details_panel_takes_dpad_focus_away_from_the_underlying_tile() {
        val lamp = HaEntity("light.den", "on", JsonObject(mapOf("brightness" to JsonPrimitive(128))))
        val settings = UiSettingsStore(
            AppSettings(
                baseUrl = "https://ha.example",
                tokenEnvelope = "encrypted",
                tiles = listOf(TileConfiguration(lamp.entityId, 0)),
            ),
        )
        val viewModel = DashboardViewModel(settings, UiSession(mapOf(lamp.entityId to lamp)), UiChannelGateway(), Dispatchers.IO)

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, null, null, viewModel)
        }

        composeRule.onNodeWithContentDescription("den, 50%").performTouchInput { longClick() }

        composeRule.onNodeWithContentDescription("Cancel").assertIsFocused()
        composeRule.onNodeWithText("Brightness: 50%").assertIsDisplayed()
    }

    @Test
    fun settings_screen_starts_on_its_first_remote_control() {
        val settings = UiSettingsStore(
            AppSettings(baseUrl = "https://ha.example", tokenEnvelope = "encrypted"),
        )
        val viewModel = DashboardViewModel(settings, UiSession(emptyMap()), UiChannelGateway(), Dispatchers.IO)
        viewModel.openSettings()

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, null, null, viewModel)
        }

        composeRule.onNodeWithContentDescription("Manage controls").assertIsFocused()
    }

    @Test
    fun empty_dashboard_manager_uses_a_single_full_width_browser() {
        val lamp = HaEntity("light.den", "off")
        val settings = UiSettingsStore(
            AppSettings(baseUrl = "https://ha.example", tokenEnvelope = "encrypted"),
        )
        val viewModel = DashboardViewModel(settings, UiSession(mapOf(lamp.entityId to lamp)), UiChannelGateway(), Dispatchers.IO)
        viewModel.openTileManager()

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, null, null, viewModel)
        }

        composeRule.onNodeWithText("Ready TV controls · 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("All").assertIsFocused()
    }

    @Test
    fun configured_tile_manager_shows_order_preview_and_bounded_reorder_actions() {
        val den = HaEntity("light.den", "off")
        val reading = HaEntity("switch.reading", "off")
        val settings = UiSettingsStore(
            AppSettings(
                baseUrl = "https://ha.example",
                tokenEnvelope = "encrypted",
                tiles = listOf(TileConfiguration(den.entityId, 0), TileConfiguration(reading.entityId, 1)),
            ),
        )
        val viewModel = DashboardViewModel(
            settings,
            UiSession(mapOf(den.entityId to den, reading.entityId to reading)),
            UiChannelGateway(),
            Dispatchers.IO,
        )
        viewModel.openTileManager()

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, null, null, viewModel)
        }

        composeRule.onNodeWithContentDescription("Current dashboard order: 1. den, 2. reading").assertIsDisplayed()
        composeRule.onNodeWithText("Tile 1 of 2").assertIsDisplayed()
        composeRule.onAllNodesWithText("Earlier")[0].assertIsNotEnabled()
    }

    @Test
    fun launcher_details_deep_link_opens_the_target_panel() {
        val lamp = HaEntity("light.den", "on", JsonObject(mapOf("brightness" to JsonPrimitive(128))))
        val settings = UiSettingsStore(
            AppSettings(baseUrl = "https://ha.example", tokenEnvelope = "encrypted", tiles = listOf(TileConfiguration(lamp.entityId, 0))),
        )
        val viewModel = DashboardViewModel(settings, UiSession(mapOf(lamp.entityId to lamp)), UiChannelGateway(), Dispatchers.IO)

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, lamp.entityId, "details", viewModel)
        }

        composeRule.onNodeWithText("Brightness: 50%").assertIsDisplayed()
    }

    @Test
    fun stale_launcher_deep_link_offers_a_visible_recovery_action() {
        val settings = UiSettingsStore(
            AppSettings(baseUrl = "https://ha.example", tokenEnvelope = "encrypted"),
        )
        val viewModel = DashboardViewModel(settings, UiSession(emptyMap()), UiChannelGateway(), Dispatchers.IO)

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, "light.removed", "focus", viewModel)
        }

        composeRule.onNodeWithText("Shortcut needs attention").assertIsDisplayed()
    }

    private class UiSettingsStore(initial: AppSettings) : SettingsStore {
        private val values = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = values.asStateFlow()

        override suspend fun saveConnection(baseUrl: String, token: String) {
            values.value = values.value.copy(baseUrl = baseUrl, tokenEnvelope = "encrypted")
        }

        override fun decryptToken(settings: AppSettings): String? = "token"

        override suspend fun saveTiles(tiles: List<TileConfiguration>) {
            values.value = values.value.copy(tiles = tiles)
        }

        override suspend fun saveShortcuts(shortcuts: List<ShortcutConfiguration>) {
            values.value = values.value.copy(homeShortcuts = shortcuts)
        }

        override suspend fun setHomeChannel(enabled: Boolean, channelId: Long?) {
            values.value = values.value.copy(homeChannelEnabled = enabled, channelId = channelId)
        }

        override suspend fun saveLastFocusedEntity(entityId: String) {
            values.value = values.value.copy(lastFocusedEntityId = entityId)
        }

        override suspend fun clearConnection() {
            values.value = AppSettings()
        }
    }

    private class UiSession(entities: Map<String, HaEntity>) : HomeAssistantSession {
        override val entities: StateFlow<Map<String, HaEntity>> = MutableStateFlow(entities).asStateFlow()
        override val initialStatesLoaded: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
        override val status: StateFlow<ConnectionStatus> = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("test")).asStateFlow()
        val actions = mutableListOf<ControlAction>()
        val starts = mutableListOf<Pair<String, String>>()

        override suspend fun validateConnection(baseUrl: String, token: String): Result<Unit> = Result.success(Unit)
        override fun start(baseUrl: String, token: String) {
            starts += baseUrl to token
        }
        override fun stop() = Unit

        override suspend fun execute(action: ControlAction): Result<Unit> {
            actions += action
            return Result.success(Unit)
        }
    }

    private class UiChannelGateway : HomeChannelGateway {
        override fun createOrUpdate(settings: AppSettings, entities: Map<String, HaEntity>): Long = 1L
        override fun remove(channelId: Long) = Unit
        override fun isProjectivyInstalled(): Boolean = false
    }
}
