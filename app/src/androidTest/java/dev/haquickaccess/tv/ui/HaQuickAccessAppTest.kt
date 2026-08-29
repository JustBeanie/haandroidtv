package dev.haquickaccess.tv.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import dev.haquickaccess.tv.data.AppSettings
import dev.haquickaccess.tv.data.ConnectionStatus
import dev.haquickaccess.tv.data.HomeAssistantSession
import dev.haquickaccess.tv.data.SettingsStore
import dev.haquickaccess.tv.data.TileSnapshot
import dev.haquickaccess.tv.data.TileSnapshotEntry
import dev.haquickaccess.tv.data.TileSnapshotStore
import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.ShortcutConfiguration
import dev.haquickaccess.tv.domain.model.TileConfiguration
import dev.haquickaccess.tv.platform.HomeChannelGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
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
            HaQuickAccessApp(state, viewModel)
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
            HaQuickAccessApp(state, viewModel)
        }

        composeRule.onNodeWithContentDescription("den, 50%").performTouchInput { longClick() }

        composeRule.onNodeWithContentDescription("Cancel").assertIsFocused()
        composeRule.onNodeWithContentDescription("den, 50%").assertIsNotFocused()
        composeRule.onNodeWithText("Brightness: 50%").assertIsDisplayed()
        assertExactlyOneFocusedNode()

        pressBack()

        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onNodeWithContentDescription("den, 50%").assertIsFocused()
            }.isSuccess
        }
        composeRule.onNodeWithContentDescription("den, 50%").assertIsFocused()
        assertExactlyOneFocusedNode()
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
            HaQuickAccessApp(state, viewModel)
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
            HaQuickAccessApp(state, viewModel)
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
            HaQuickAccessApp(state, viewModel)
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
        viewModel.handleLaunchRequest(lamp.entityId, "details")

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, viewModel)
        }

        composeRule.onNodeWithText("Brightness: 50%").assertIsDisplayed()
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onNodeWithContentDescription("Cancel").assertIsFocused()
            }.isSuccess
        }
        composeRule.onNodeWithContentDescription("Cancel").assertIsFocused()
        assertExactlyOneFocusedNode()
    }

    @Test
    fun stale_launcher_deep_link_offers_a_visible_recovery_action() {
        val settings = UiSettingsStore(
            AppSettings(baseUrl = "https://ha.example", tokenEnvelope = "encrypted"),
        )
        val viewModel = DashboardViewModel(settings, UiSession(emptyMap()), UiChannelGateway(), Dispatchers.IO)
        viewModel.handleLaunchRequest("light.removed", "focus")

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, viewModel)
        }

        composeRule.onNodeWithText("Shortcut needs attention").assertIsDisplayed()
    }

    @Test
    fun cached_snapshot_is_visible_but_disabled_until_live_state_arrives() {
        val settings = UiSettingsStore(
            AppSettings(
                baseUrl = "https://ha.example",
                tokenEnvelope = "encrypted",
                tiles = listOf(TileConfiguration("light.den", 0)),
            ),
        )
        val snapshots = UiSnapshotStore(
            TileSnapshot(
                capturedAtEpochMillis = 1234L,
                tiles = listOf(TileSnapshotEntry("light.den", "den", "LIGHT", "Off", false, false)),
            ),
        )
        val session = UiSession(emptyMap(), initialStatesLoaded = false, connectionStatus = ConnectionStatus.Connecting)
        val viewModel = DashboardViewModel(settings, session, UiChannelGateway(), snapshots, Dispatchers.IO)

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, viewModel)
        }

        composeRule.onNodeWithText("Last known · reconnecting").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("den, Last known · Off")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertTrue(session.actions.isEmpty())
    }

    @Test
    fun failed_command_stays_inline_and_keeps_dashboard_available() {
        val lamp = HaEntity("light.den", "off")
        val settings = UiSettingsStore(
            AppSettings(
                baseUrl = "https://ha.example",
                tokenEnvelope = "encrypted",
                tiles = listOf(TileConfiguration(lamp.entityId, 0)),
            ),
        )
        val session = UiSession(
            entities = mapOf(lamp.entityId to lamp),
            executeResult = Result.failure(IllegalStateException("Service unavailable")),
        )
        val viewModel = DashboardViewModel(settings, session, UiChannelGateway(), Dispatchers.IO)

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, viewModel)
        }

        composeRule.onNodeWithContentDescription("den, Off").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Service unavailable").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Dismiss command error").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("den, Action failed").assertIsFocused()
        composeRule.onNodeWithTag("dashboard_grid").assertIsDisplayed()
        assertExactlyOneFocusedNode()
    }

    @Test
    fun command_feedback_renders_pending_then_success_without_moving_focus() {
        val lamp = HaEntity("light.den", "off")
        val execution = CompletableDeferred<Result<Unit>>()
        val settings = UiSettingsStore(
            AppSettings(
                baseUrl = "https://ha.example",
                tokenEnvelope = "encrypted",
                tiles = listOf(TileConfiguration(lamp.entityId, 0)),
            ),
        )
        val session = UiSession(mapOf(lamp.entityId to lamp), executeGate = execution)
        val viewModel = DashboardViewModel(settings, session, UiChannelGateway(), Dispatchers.IO)

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, viewModel)
        }

        composeRule.onNodeWithContentDescription("den, Off").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithContentDescription("den, Updating").assertIsFocused()
        assertExactlyOneFocusedNode()

        execution.complete(Result.success(Unit))
        composeRule.onNodeWithContentDescription("den, Updated ✓").assertIsFocused()
        assertExactlyOneFocusedNode()
    }

    @Test
    fun setup_validation_error_is_inline_and_preserves_field_focus() {
        val viewModel = DashboardViewModel(
            UiSettingsStore(AppSettings()),
            UiSession(emptyMap()),
            UiChannelGateway(),
            Dispatchers.IO,
        )

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, viewModel)
        }
        composeRule.onNodeWithText("Connect Home Assistant").assertIsDisplayed()

        composeRule.runOnIdle {
            viewModel.updateSetupBaseUrl("http://ha.example")
            viewModel.updateSetupToken("token")
            viewModel.saveConnection()
        }

        composeRule.onNodeWithText("Use an HTTPS URL").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Home Assistant URL").assertIsFocused()
        assertExactlyOneFocusedNode()
    }

    @Test
    fun dashboard_fits_two_complete_tile_rows_in_a_1080p_viewport() {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val widthDp = 1920f / density
        val heightDp = 1080f / density
        val fixture = BenchmarkFixture.dashboardState(tileCount = 6)
        val viewModel = DashboardViewModel(
            UiSettingsStore(fixture.settings),
            UiSession(fixture.entities),
            UiChannelGateway(),
            Dispatchers.IO,
        )

        composeRule.setContent {
            Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                val state by viewModel.uiState.collectAsState()
                HaQuickAccessApp(state, viewModel)
            }
        }

        val firstTile = composeRule.onNodeWithTag("tile_light.benchmark_1").getUnclippedBoundsInRoot()
        val secondTile = composeRule.onNodeWithTag("tile_fan.benchmark_2").getUnclippedBoundsInRoot()
        val fourthTile = composeRule.onNodeWithTag("tile_input_boolean.benchmark_4").getUnclippedBoundsInRoot()
        val secondRowBottom = composeRule.onNodeWithTag("tile_light.benchmark_6")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
            .bottom.value
        assertTrue("Second dashboard row must fit at 1080p", secondRowBottom <= heightDp)
        assertTrue("Focused cards must not overlap horizontally", firstTile.right < secondTile.left)
        assertTrue("Focused cards must not overlap vertically", firstTile.bottom < fourthTile.top)
        assertExactlyOneFocusedNode()
    }

    @Test
    fun dashboard_primary_controls_fit_in_a_720p_viewport() {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val widthDp = 1280f / density
        val heightDp = 720f / density
        val fixture = BenchmarkFixture.dashboardState(tileCount = 3)
        val viewModel = DashboardViewModel(
            UiSettingsStore(fixture.settings),
            UiSession(fixture.entities),
            UiChannelGateway(),
            Dispatchers.IO,
        )

        composeRule.setContent {
            Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                val state by viewModel.uiState.collectAsState()
                HaQuickAccessApp(state, viewModel)
            }
        }

        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        val firstRowBottom = composeRule.onNodeWithTag("tile_scene.benchmark_3")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
            .bottom.value
        assertTrue("First dashboard row must fit at 720p", firstRowBottom <= heightDp)
        assertExactlyOneFocusedNode()
    }

    private fun assertExactlyOneFocusedNode() {
        val focusedNodes = composeRule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("Exactly one visible control should own focus", 1, focusedNodes.size)
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

    private class UiSession(
        entities: Map<String, HaEntity>,
        initialStatesLoaded: Boolean = true,
        connectionStatus: ConnectionStatus = ConnectionStatus.Connected("test"),
        private val executeResult: Result<Unit> = Result.success(Unit),
        private val executeGate: CompletableDeferred<Result<Unit>>? = null,
    ) : HomeAssistantSession {
        override val entities: StateFlow<Map<String, HaEntity>> = MutableStateFlow(entities).asStateFlow()
        override val initialStatesLoaded: StateFlow<Boolean> = MutableStateFlow(initialStatesLoaded).asStateFlow()
        override val status: StateFlow<ConnectionStatus> = MutableStateFlow(connectionStatus).asStateFlow()
        val actions = mutableListOf<ControlAction>()
        val starts = mutableListOf<Pair<String, String>>()

        override suspend fun validateConnection(baseUrl: String, token: String): Result<Unit> = Result.success(Unit)
        override fun start(baseUrl: String, token: String) {
            starts += baseUrl to token
        }
        override fun stop() = Unit

        override suspend fun execute(action: ControlAction): Result<Unit> {
            actions += action
            return executeGate?.await() ?: executeResult
        }
    }

    private class UiSnapshotStore(initial: TileSnapshot?) : TileSnapshotStore {
        private val values = MutableStateFlow(initial)
        override val snapshot = values.asStateFlow()

        override suspend fun save(snapshot: TileSnapshot) {
            values.value = snapshot
        }

        override suspend fun clear() {
            values.value = null
        }
    }

    private class UiChannelGateway : HomeChannelGateway {
        override fun createOrUpdate(settings: AppSettings, entities: Map<String, HaEntity>): Long = 1L
        override fun remove(channelId: Long) = Unit
        override fun isProjectivyInstalled(): Boolean = false
    }
}
