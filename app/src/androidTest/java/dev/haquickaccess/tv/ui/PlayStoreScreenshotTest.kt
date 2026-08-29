package dev.haquickaccess.tv.ui

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.haquickaccess.tv.data.AppSettings
import dev.haquickaccess.tv.data.ConnectionStatus
import dev.haquickaccess.tv.data.HomeAssistantSession
import dev.haquickaccess.tv.data.SettingsStore
import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.ShortcutConfiguration
import dev.haquickaccess.tv.domain.model.TileConfiguration
import dev.haquickaccess.tv.platform.HomeChannelGateway
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayStoreScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun captureSetupScreen() {
        val viewModel = DashboardViewModel(
            ScreenshotSettingsStore(AppSettings()),
            ScreenshotSession(emptyMap()),
            ScreenshotChannelGateway(),
            Dispatchers.IO,
        )

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, viewModel)
        }

        composeRule.onNodeWithText("Connect Home Assistant").assertExists()
        capture("01-setup.png")
    }

    @Test
    fun captureConfiguredDashboard() {
        val entities = demoEntities()
        val settings = AppSettings(
            baseUrl = "https://demo.example",
            tokenEnvelope = "test-only-encrypted-token",
            tiles = entities.keys.mapIndexed { index, entityId -> TileConfiguration(entityId, index) },
            lastFocusedEntityId = "light.living_room",
        )
        val viewModel = DashboardViewModel(
            ScreenshotSettingsStore(settings),
            ScreenshotSession(entities),
            ScreenshotChannelGateway(),
            Dispatchers.IO,
        )

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            HaQuickAccessApp(state, viewModel)
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Living Room").fetchSemanticsNodes().isNotEmpty()
        }
        capture("02-dashboard.png")
    }

    private fun capture(fileName: String) {
        composeRule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "play-store")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create ${directory.absolutePath}" }
        val output = File(directory, fileName)
        FileOutputStream(output).use { stream ->
            check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    private fun demoEntities(): LinkedHashMap<String, HaEntity> = linkedMapOf(
        "light.living_room" to entity(
            "light.living_room",
            "on",
            "Living Room",
            "brightness" to JsonPrimitive(191),
        ),
        "switch.media_console" to entity("switch.media_console", "off", "Media Console"),
        "climate.downstairs" to entity(
            "climate.downstairs",
            "heat",
            "Downstairs",
            "temperature" to JsonPrimitive(70),
            "current_temperature" to JsonPrimitive(69),
            "hvac_modes" to JsonArray(listOf(JsonPrimitive("off"), JsonPrimitive("heat"), JsonPrimitive("cool"))),
        ),
        "cover.patio_blinds" to entity(
            "cover.patio_blinds",
            "open",
            "Patio Blinds",
            "current_position" to JsonPrimitive(65),
            "device_class" to JsonPrimitive("blind"),
        ),
        "scene.movie_night" to entity("scene.movie_night", "unknown", "Movie Night"),
        "fan.ceiling" to entity(
            "fan.ceiling",
            "on",
            "Ceiling Fan",
            "percentage" to JsonPrimitive(40),
        ),
        "alarm_control_panel.home" to entity(
            "alarm_control_panel.home",
            "disarmed",
            "Home Security",
            "supported_features" to JsonPrimitive(3),
        ),
        "switch.porch" to entity("switch.porch", "on", "Porch Lights"),
        "input_boolean.guest_mode" to entity("input_boolean.guest_mode", "off", "Guest Mode"),
        "button.good_night" to entity("button.good_night", "unknown", "Good Night"),
        "light.kitchen" to entity(
            "light.kitchen",
            "on",
            "Kitchen",
            "brightness" to JsonPrimitive(230),
        ),
        "cover.entry_shade" to entity(
            "cover.entry_shade",
            "closed",
            "Entry Shade",
            "current_position" to JsonPrimitive(0),
            "device_class" to JsonPrimitive("shade"),
        ),
    )

    private fun entity(
        id: String,
        state: String,
        friendlyName: String,
        vararg attributes: Pair<String, JsonElement>,
    ): HaEntity = HaEntity(
        entityId = id,
        state = state,
        attributes = JsonObject(mapOf("friendly_name" to JsonPrimitive(friendlyName)) + attributes.toMap()),
    )

    private class ScreenshotSettingsStore(initial: AppSettings) : SettingsStore {
        private val values = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = values.asStateFlow()

        override suspend fun saveConnection(baseUrl: String, token: String) = Unit
        override fun decryptToken(settings: AppSettings): String? = "test-token"
        override suspend fun saveTiles(tiles: List<TileConfiguration>) = Unit
        override suspend fun saveShortcuts(shortcuts: List<ShortcutConfiguration>) = Unit
        override suspend fun setHomeChannel(enabled: Boolean, channelId: Long?) = Unit
        override suspend fun saveLastFocusedEntity(entityId: String) = Unit
        override suspend fun clearConnection() = Unit
    }

    private class ScreenshotSession(entities: Map<String, HaEntity>) : HomeAssistantSession {
        override val entities: StateFlow<Map<String, HaEntity>> = MutableStateFlow(entities).asStateFlow()
        override val initialStatesLoaded: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
        override val status: StateFlow<ConnectionStatus> =
            MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("demo")).asStateFlow()

        override suspend fun validateConnection(baseUrl: String, token: String): Result<Unit> = Result.success(Unit)
        override fun start(baseUrl: String, token: String) = Unit
        override fun stop() = Unit
        override suspend fun execute(action: ControlAction): Result<Unit> = Result.success(Unit)
    }

    private class ScreenshotChannelGateway : HomeChannelGateway {
        override fun createOrUpdate(settings: AppSettings, entities: Map<String, HaEntity>): Long = 1L
        override fun remove(channelId: Long) = Unit
        override fun isProjectivyInstalled(): Boolean = false
    }
}
