package dev.haquickaccess.tv.ui

import dev.haquickaccess.tv.data.AppSettings
import dev.haquickaccess.tv.data.ConnectionStatus
import dev.haquickaccess.tv.data.HomeAssistantSession
import dev.haquickaccess.tv.data.SettingsStore
import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.ShortcutBehavior
import dev.haquickaccess.tv.domain.model.ShortcutConfiguration
import dev.haquickaccess.tv.domain.model.TileConfiguration
import dev.haquickaccess.tv.platform.HomeChannelGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `secure cover open requires a second explicit action`() = runTest {
        val session = FakeSession()
        val viewModel = viewModel(session = session)
        observe(viewModel)
        val garage = entity("cover.garage", "closed", attributes = mapOf("device_class" to JsonPrimitive("garage")))

        viewModel.openDetails(garage)
        viewModel.coverCommand(ControlAction.CoverCommand.Command.OPEN)

        val confirmation = assertIs<DetailState.Cover>(viewModel.uiState.value.details)
        assertEquals(ControlAction.CoverCommand.Command.OPEN, confirmation.pendingCommand)
        assertTrue(session.actions.isEmpty())

        viewModel.confirmSecureCover()

        assertEquals(
            ControlAction.CoverCommand(garage, ControlAction.CoverCommand.Command.OPEN),
            session.actions.single(),
        )
        assertNull(viewModel.uiState.value.details)
    }

    @Test
    fun `opening a secure cover by position also requires confirmation`() = runTest {
        val session = FakeSession()
        val garage = entity(
            "cover.garage",
            "closed",
            attributes = mapOf(
                "device_class" to JsonPrimitive("garage"),
                "current_position" to JsonPrimitive(0),
            ),
        )
        val viewModel = viewModel(session = session)
        observe(viewModel)

        viewModel.openDetails(garage)
        viewModel.stageCoverPosition(100)
        viewModel.applyCoverPosition()

        val confirmation = assertIs<DetailState.Cover>(viewModel.uiState.value.details)
        assertEquals(ControlAction.CoverCommand.Command.SET_POSITION, confirmation.pendingCommand)
        assertEquals(100, confirmation.pendingPosition)
        assertTrue(session.actions.isEmpty())

        viewModel.confirmSecureCover()

        assertEquals(
            ControlAction.CoverCommand(garage, ControlAction.CoverCommand.Command.SET_POSITION, 100),
            session.actions.single(),
        )
        assertNull(viewModel.uiState.value.details)
    }

    @Test
    fun `secure cover position only confirms moves that open it`() = runTest {
        val session = FakeSession()
        val garage = entity(
            "cover.garage",
            "open",
            attributes = mapOf(
                "device_class" to JsonPrimitive("garage"),
                "current_position" to JsonPrimitive(50),
            ),
        )
        val viewModel = viewModel(session = session)
        observe(viewModel)

        viewModel.openDetails(garage)
        viewModel.stageCoverPosition(-20)
        assertEquals(0, assertIs<DetailState.Cover>(viewModel.uiState.value.details).stagedPosition)
        viewModel.applyCoverPosition()

        assertEquals(
            ControlAction.CoverCommand(garage, ControlAction.CoverCommand.Command.SET_POSITION, 0),
            session.actions.single(),
        )
        assertNull(viewModel.uiState.value.details)

        viewModel.openDetails(garage)
        viewModel.coverCommand(ControlAction.CoverCommand.Command.CLOSE)
        viewModel.openDetails(garage)
        viewModel.confirmSecureCover()

        assertEquals(
            listOf<ControlAction>(
                ControlAction.CoverCommand(garage, ControlAction.CoverCommand.Command.SET_POSITION, 0),
                ControlAction.CoverCommand(garage, ControlAction.CoverCommand.Command.CLOSE),
            ),
            session.actions,
        )
        assertIs<DetailState.Cover>(viewModel.uiState.value.details)
    }

    @Test
    fun `dimmer applies a bounded staged value only when confirmed`() = runTest {
        val session = FakeSession()
        val lamp = entity("light.den", "on", attributes = mapOf("brightness" to JsonPrimitive(128)))
        val viewModel = viewModel(session = session)
        observe(viewModel)

        viewModel.openDetails(lamp)
        viewModel.stageLevel(140)

        assertEquals(100, assertIs<DetailState.Level>(viewModel.uiState.value.details).stagedPercent)
        assertTrue(session.actions.isEmpty())

        viewModel.closeDetails()
        assertTrue(session.actions.isEmpty())
        viewModel.openDetails(lamp)
        viewModel.stageLevel(100)

        viewModel.applyLevel()

        assertEquals(ControlAction.SetLevel(lamp, 100), session.actions.single())
        assertNull(viewModel.uiState.value.details)
    }

    @Test
    fun `disarm requires a fresh code and disposes it after the command`() = runTest {
        val session = FakeSession()
        val alarm = entity("alarm_control_panel.home", "armed_away")
        val viewModel = viewModel(session = session)
        observe(viewModel)

        viewModel.openDetails(alarm)
        viewModel.disarmAlarm()

        assertEquals("Enter the Home Assistant alarm code", viewModel.uiState.value.errorMessage)
        assertTrue(session.actions.isEmpty())

        viewModel.updateAlarmCode("12345678901234567890")
        assertEquals(16, assertIs<DetailState.Alarm>(viewModel.uiState.value.details).disarmCode.length)
        viewModel.disarmAlarm()

        assertEquals(ControlAction.DisarmAlarm(alarm, "1234567890123456"), session.actions.single())
        assertNull(viewModel.uiState.value.details)
    }

    @Test
    fun `tile management reorders without duplicating and caps shortcuts at four`() = runTest {
        val first = entity("light.first", "off")
        val second = entity("switch.second", "off")
        val third = entity("fan.third", "off")
        val fourth = entity("input_boolean.fourth", "off")
        val fifth = entity("light.fifth", "off")
        val settings = FakeSettingsStore(
            AppSettings(
                tiles = listOf(
                    TileConfiguration(first.entityId, 0),
                    TileConfiguration(second.entityId, 1),
                ),
            ),
        )
        val viewModel = viewModel(settings = settings, session = FakeSession(mapOf(
            first.entityId to first,
            second.entityId to second,
            third.entityId to third,
            fourth.entityId to fourth,
            fifth.entityId to fifth,
        )))
        observe(viewModel)

        viewModel.addTile(first)
        viewModel.addTile(third)
        viewModel.moveTile(first.entityId, 1)
        viewModel.setShortcut(first.entityId, ShortcutBehavior.TOGGLE)
        viewModel.setShortcut(second.entityId, ShortcutBehavior.FOCUS)
        viewModel.setShortcut(third.entityId, ShortcutBehavior.DETAILS)
        viewModel.setShortcut(fourth.entityId, ShortcutBehavior.FOCUS)
        viewModel.setShortcut(fifth.entityId, ShortcutBehavior.DETAILS)

        assertEquals(listOf(second.entityId, first.entityId, third.entityId), settings.value.tiles.sortedBy(TileConfiguration::position).map(TileConfiguration::entityId))
        assertEquals(4, settings.value.homeShortcuts.size)
        assertTrue(settings.value.homeShortcuts.none { it.entityId == fifth.entityId })
    }

    @Test
    fun `removing a dashboard tile also removes its Home shortcut`() = runTest {
        val lamp = entity("light.den", "off")
        val settings = FakeSettingsStore(
            AppSettings(
                tiles = listOf(TileConfiguration(lamp.entityId, 0)),
                homeShortcuts = listOf(dev.haquickaccess.tv.domain.model.ShortcutConfiguration(lamp.entityId, ShortcutBehavior.TOGGLE)),
                homeChannelEnabled = true,
                channelId = 9L,
            ),
        )
        val channel = FakeChannelGateway(channelId = 9L)
        val viewModel = viewModel(settings, FakeSession(mapOf(lamp.entityId to lamp)), channel)
        observe(viewModel)

        viewModel.removeTile(lamp.entityId)
        runCurrent()

        assertTrue(settings.value.tiles.isEmpty())
        assertTrue(settings.value.homeShortcuts.isEmpty())
        assertEquals(1, channel.creates)
    }

    @Test
    fun `foreground session validates setup and requests an opt in channel`() = runTest {
        val lamp = entity("light.den", "on")
        val settings = FakeSettingsStore(
            AppSettings(
                baseUrl = "https://ha.example",
                tokenEnvelope = "saved-token",
                homeShortcuts = listOf(
                    dev.haquickaccess.tv.domain.model.ShortcutConfiguration(lamp.entityId, ShortcutBehavior.TOGGLE),
                ),
            ),
        )
        val session = FakeSession(mapOf(lamp.entityId to lamp))
        val channel = FakeChannelGateway(channelId = 41L)
        val viewModel = viewModel(settings, session, channel)
        observe(viewModel)

        viewModel.onForeground()
        assertEquals(listOf("https://ha.example" to "decrypted-token"), session.starts)

        viewModel.updateSetupBaseUrl("http://not-secure.example")
        viewModel.updateSetupToken("token")
        viewModel.saveConnection()
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("HTTPS"))

        viewModel.updateSetupBaseUrl("https://ha.example/base/")
        viewModel.updateSetupToken("new-token")
        viewModel.saveConnection()
        assertEquals("https://ha.example/base", settings.value.baseUrl)
        assertEquals("new-token", settings.savedConnection?.second)
        assertEquals(listOf("https://ha.example/base" to "new-token"), session.validations)

        viewModel.enableHomeChannel()
        assertEquals(41L, settings.value.channelId)
        assertTrue(settings.value.homeChannelEnabled)
        assertEquals(1, channel.creates)

        viewModel.onBackground()
        assertEquals(2, session.stops)
    }

    @Test
    fun `Home channel requires a shortcut and reports publisher failures`() = runTest {
        val lamp = entity("light.den", "on")
        val emptyChannel = FakeChannelGateway()
        val emptyViewModel = viewModel(channel = emptyChannel)
        observe(emptyViewModel)

        emptyViewModel.enableHomeChannel()

        assertEquals("Choose at least one Home screen shortcut first", emptyViewModel.uiState.value.errorMessage)
        assertEquals(0, emptyChannel.creates)

        val settings = FakeSettingsStore(
            AppSettings(
                homeShortcuts = listOf(
                    dev.haquickaccess.tv.domain.model.ShortcutConfiguration(lamp.entityId, ShortcutBehavior.TOGGLE),
                ),
            ),
        )
        val failingChannel = FakeChannelGateway().apply { createFailure = SecurityException() }
        val viewModel = viewModel(settings, FakeSession(mapOf(lamp.entityId to lamp)), failingChannel)
        observe(viewModel)

        viewModel.enableHomeChannel()

        assertEquals("Could not update the Android TV Home channel", viewModel.uiState.value.errorMessage)
        assertFalse(settings.value.homeChannelEnabled)
    }

    @Test
    fun `failed Home channel removal keeps the channel configured for retry`() = runTest {
        val settings = FakeSettingsStore(AppSettings(homeChannelEnabled = true, channelId = 11L))
        val channel = FakeChannelGateway().apply { removeFailure = SecurityException() }
        val viewModel = viewModel(settings, channel = channel)
        observe(viewModel)

        viewModel.disableHomeChannel()

        assertEquals("Could not remove the Android TV Home channel", viewModel.uiState.value.errorMessage)
        assertTrue(settings.value.homeChannelEnabled)
        assertEquals(11L, settings.value.channelId)
    }

    @Test
    fun `primary actions and detail commands route through the session`() = runTest {
        val session = FakeSession()
        val viewModel = viewModel(session = session)
        observe(viewModel)
        val climate = entity(
            "climate.hall",
            "heat",
            attributes = mapOf("temperature" to JsonPrimitive(19.0), "min_temp" to JsonPrimitive(10.0)),
        )
        val blind = entity("cover.blinds", "open", attributes = mapOf("current_position" to JsonPrimitive(40)))
        val alarm = entity(
            "alarm_control_panel.home",
            "disarmed",
            attributes = mapOf(
                "supported_features" to JsonPrimitive(2),
                "code_arm_required" to JsonPrimitive(false),
            ),
        )
        val switch = entity("switch.coffee", "off")
        val scene = entity("scene.movie_time", "unknown")
        val script = entity("script.goodnight", "off")
        val button = entity("button.refresh", "unknown")

        viewModel.openDetails(climate)
        viewModel.stageClimateTemperature(22.0)
        viewModel.applyClimateTemperature()
        viewModel.setClimateMode(climate, "cool")
        viewModel.closeDetails()
        viewModel.openDetails(blind)
        viewModel.coverCommand(ControlAction.CoverCommand.Command.SET_POSITION, 55)
        viewModel.openDetails(alarm)
        viewModel.armAlarm("away")
        viewModel.toggle(switch)
        viewModel.performPrimaryAction(scene)
        viewModel.performPrimaryAction(script)
        viewModel.performPrimaryAction(button)

        assertEquals(
            listOf(
                ControlAction.SetClimateTemperature(climate, 22.0),
                ControlAction.SetClimateMode(climate, "cool"),
                ControlAction.CoverCommand(blind, ControlAction.CoverCommand.Command.SET_POSITION, 55),
                ControlAction.ArmAlarm(alarm, "away"),
                ControlAction.Toggle(switch),
                ControlAction.ActivateScene(scene),
                ControlAction.RunScript(script),
                ControlAction.PressButton(button),
            ),
            session.actions,
        )
        assertNull(viewModel.uiState.value.details)
    }

    @Test
    fun `climate staging stays within the entity temperature bounds`() = runTest {
        val climate = entity(
            "climate.hall",
            "heat",
            attributes = mapOf(
                "temperature" to JsonPrimitive(20.0),
                "min_temp" to JsonPrimitive(10.0),
                "max_temp" to JsonPrimitive(24.0),
            ),
        )
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.openDetails(climate)
        viewModel.stageClimateTemperature(50.0)
        assertEquals(24.0, assertIs<DetailState.Climate>(viewModel.uiState.value.details).stagedTemperature)
        viewModel.stageClimateTemperature(2.0)
        assertEquals(10.0, assertIs<DetailState.Climate>(viewModel.uiState.value.details).stagedTemperature)
    }

    @Test
    fun `climate with bounds but no reported setpoint starts at its minimum`() = runTest {
        val session = FakeSession()
        val climate = entity(
            "climate.hall",
            "heat",
            attributes = mapOf(
                "min_temp" to JsonPrimitive(10.0),
                "max_temp" to JsonPrimitive(24.0),
                "target_temp_step" to JsonPrimitive(0.5),
            ),
        )
        val viewModel = viewModel(session = session)
        observe(viewModel)

        viewModel.openDetails(climate)
        assertEquals(10.0, assertIs<DetailState.Climate>(viewModel.uiState.value.details).stagedTemperature)
        viewModel.stageClimateTemperature(20.5)
        viewModel.applyClimateTemperature()

        assertEquals(ControlAction.SetClimateTemperature(climate, 20.5), session.actions.single())
    }

    @Test
    fun `editable helpers stage valid values and send native actions`() = runTest {
        val session = FakeSession()
        val number = entity(
            "input_number.sleep_timer",
            "15",
            attributes = mapOf("min" to JsonPrimitive(0.0), "max" to JsonPrimitive(90.0), "step" to JsonPrimitive(0.5)),
        )
        val select = HaEntity(
            "input_select.tv_source",
            "HDMI 1",
            JsonObject(mapOf("options" to JsonArray(listOf(JsonPrimitive("HDMI 1"), JsonPrimitive("Apps"))))),
        )
        val text = entity("input_text.guest_message", "Welcome")
        val viewModel = viewModel(session = session)
        observe(viewModel)

        viewModel.openDetails(number)
        assertEquals(15.0, assertIs<DetailState.Number>(viewModel.uiState.value.details).stagedValue)
        viewModel.stageNumberValue(-200.0)
        assertEquals(0.0, assertIs<DetailState.Number>(viewModel.uiState.value.details).stagedValue)
        viewModel.stageNumberValue(200.0)
        assertEquals(90.0, assertIs<DetailState.Number>(viewModel.uiState.value.details).stagedValue)
        viewModel.applyNumberValue()

        viewModel.openDetails(select)
        viewModel.selectOption("missing")
        assertTrue(session.actions.size == 1)
        viewModel.selectOption("Apps")

        viewModel.openDetails(text)
        viewModel.updateTextValue("Good evening")
        viewModel.applyTextValue()

        assertEquals(
            listOf<ControlAction>(
                ControlAction.SetNumberValue(number, 90.0),
                ControlAction.SelectOption(select, "Apps"),
                ControlAction.SetTextValue(text, "Good evening"),
            ),
            session.actions,
        )
        assertNull(viewModel.uiState.value.details)
    }

    @Test
    fun `editable helper details use safe defaults for unknown values`() = runTest {
        val number = entity(
            "input_number.sleep_timer",
            "unknown",
            attributes = mapOf("min" to JsonPrimitive(10.0), "max" to JsonPrimitive(90.0)),
        )
        val text = entity("input_text.guest_message", "unknown")
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.openDetails(number)
        assertEquals(10.0, assertIs<DetailState.Number>(viewModel.uiState.value.details).stagedValue)

        viewModel.openDetails(text)
        assertEquals("", assertIs<DetailState.Text>(viewModel.uiState.value.details).stagedValue)
    }

    @Test
    fun `pending feedback appears before a delayed Home Assistant command completes`() = runTest {
        val session = FakeSession().apply { pendingAction = CompletableDeferred() }
        val lamp = entity("light.den", "off")
        val viewModel = viewModel(session = session)
        observe(viewModel)

        viewModel.toggle(lamp)
        assertTrue(lamp.entityId in viewModel.uiState.value.pendingEntityIds)

        session.pendingAction?.complete(Result.success(Unit))
        runCurrent()
        assertFalse(lamp.entityId in viewModel.uiState.value.pendingEntityIds)
    }

    @Test
    fun `quick launch focus requests are explicit and acknowledged once`() = runTest {
        val lamp = entity("light.den", "off")
        val settings = FakeSettingsStore(AppSettings(tiles = listOf(TileConfiguration(lamp.entityId, 0))))
        val viewModel = viewModel(settings = settings, session = FakeSession(mapOf(lamp.entityId to lamp)))
        observe(viewModel)

        viewModel.focusEntity(lamp.entityId)

        val firstRequest = requireNotNull(viewModel.uiState.value.focusRequest)
        assertEquals(lamp.entityId, firstRequest.entityId)
        assertEquals(lamp.entityId, settings.value.lastFocusedEntityId)

        viewModel.acknowledgeFocusRequest(firstRequest.sequence)
        assertNull(viewModel.uiState.value.focusRequest)

        viewModel.focusEntity(lamp.entityId)
        assertEquals(firstRequest.sequence + 1, viewModel.uiState.value.focusRequest?.sequence)
    }

    @Test
    fun `details shortcuts focus controls without a details panel`() = runTest {
        val switch = entity("switch.bedside", "off")
        val light = entity("light.bedside", "on", attributes = mapOf("brightness" to JsonPrimitive(125)))
        val settings = FakeSettingsStore(
            AppSettings(
                tiles = listOf(
                    TileConfiguration(switch.entityId, 0),
                    TileConfiguration(light.entityId, 1),
                ),
            ),
        )
        val viewModel = viewModel(
            settings = settings,
            session = FakeSession(mapOf(switch.entityId to switch, light.entityId to light)),
        )
        observe(viewModel)

        assertFalse(viewModel.supportsDetails(switch))
        viewModel.openShortcutDetails(switch)

        assertNull(viewModel.uiState.value.details)
        assertEquals(switch.entityId, viewModel.uiState.value.focusRequest?.entityId)

        assertTrue(viewModel.supportsDetails(light))
        viewModel.openShortcutDetails(light)

        assertIs<DetailState.Level>(viewModel.uiState.value.details)
        assertNull(viewModel.uiState.value.focusRequest)
    }

    @Test
    fun `missing launcher shortcut opens recoverable control management`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.showMissingLauncherShortcut()

        assertEquals(AppScreen.Dashboard, viewModel.uiState.value.screen)
        assertNull(viewModel.uiState.value.details)
        assertNull(viewModel.uiState.value.focusRequest)
        assertEquals("This launcher shortcut is no longer available.", viewModel.uiState.value.launcherRecovery?.message)

        viewModel.openLauncherRecoveryControls()

        assertNull(viewModel.uiState.value.launcherRecovery)
        assertEquals(AppScreen.ManageTiles, viewModel.uiState.value.screen)

        viewModel.closeScreen()

        assertEquals(AppScreen.Dashboard, viewModel.uiState.value.screen)
        assertNull(viewModel.uiState.value.launcherShortcutReplacement)
    }

    @Test
    fun `stale launcher shortcut replacement adds a tile and republishes its action`() = runTest {
        val replacement = entity("light.reading", "off")
        val settings = FakeSettingsStore(
            AppSettings(
                homeShortcuts = listOf(ShortcutConfiguration("light.removed", ShortcutBehavior.TOGGLE)),
                homeChannelEnabled = true,
                channelId = 9L,
            ),
        )
        val channel = FakeChannelGateway()
        val viewModel = viewModel(
            settings = settings,
            session = FakeSession(mapOf(replacement.entityId to replacement)),
            channel = channel,
        )
        observe(viewModel)

        viewModel.showMissingLauncherShortcut("light.removed", "toggle")
        viewModel.openLauncherRecoveryControls()
        viewModel.addTile(replacement)
        runCurrent()

        assertEquals(listOf(replacement.entityId), settings.value.tiles.map(TileConfiguration::entityId))
        assertEquals(
            listOf(ShortcutConfiguration(replacement.entityId, ShortcutBehavior.TOGGLE)),
            settings.value.homeShortcuts,
        )
        assertEquals(AppScreen.Dashboard, viewModel.uiState.value.screen)
        assertEquals(replacement.entityId, viewModel.uiState.value.focusRequest?.entityId)
        assertEquals(1, channel.creates)
    }

    @Test
    fun `closing details restores focus to the originating dashboard tile`() = runTest {
        val lamp = entity("light.den", "on", attributes = mapOf("brightness" to JsonPrimitive(128)))
        val settings = FakeSettingsStore(AppSettings(tiles = listOf(TileConfiguration(lamp.entityId, 0))))
        val viewModel = viewModel(settings = settings, session = FakeSession(mapOf(lamp.entityId to lamp)))
        observe(viewModel)

        viewModel.openDetails(lamp)
        viewModel.closeDetails()

        assertNull(viewModel.uiState.value.details)
        assertEquals(lamp.entityId, viewModel.uiState.value.focusRequest?.entityId)
    }

    @Test
    fun `navigation removal focus channel refresh and errors keep state consistent`() = runTest {
        val first = entity("light.first", "off")
        val second = entity("switch.second", "off")
        val settings = FakeSettingsStore(
            AppSettings(
                tiles = listOf(TileConfiguration(first.entityId, 0), TileConfiguration(second.entityId, 1)),
                homeShortcuts = listOf(dev.haquickaccess.tv.domain.model.ShortcutConfiguration(first.entityId, ShortcutBehavior.FOCUS)),
                homeChannelEnabled = true,
                channelId = 99L,
            ),
        )
        val session = FakeSession(mapOf(first.entityId to first, second.entityId to second))
        val channel = FakeChannelGateway(channelId = 99L)
        val viewModel = viewModel(settings, session, channel)
        observe(viewModel)

        viewModel.openSettings()
        assertEquals(AppScreen.Settings, viewModel.uiState.value.screen)
        viewModel.openTileManager()
        assertEquals(AppScreen.ManageTiles, viewModel.uiState.value.screen)
        viewModel.openShortcutManager()
        assertEquals(AppScreen.ManageShortcuts, viewModel.uiState.value.screen)
        viewModel.closeScreen()
        viewModel.saveFocus(first.entityId)
        viewModel.removeTile(first.entityId)
        viewModel.removeShortcut(first.entityId)
        viewModel.refreshHomeChannel()
        viewModel.disableHomeChannel()

        assertEquals(AppScreen.Dashboard, viewModel.uiState.value.screen)
        assertEquals(first.entityId, settings.value.lastFocusedEntityId)
        assertEquals(listOf(second.entityId), settings.value.tiles.map(TileConfiguration::entityId))
        assertTrue(settings.value.homeShortcuts.isEmpty())
        assertEquals(2, channel.creates)
        assertEquals(listOf(99L), channel.removed)
        assertFalse(settings.value.homeChannelEnabled)

        session.result = Result.failure(IllegalStateException("Service unavailable"))
        viewModel.toggle(second)
        assertEquals("Service unavailable", viewModel.uiState.value.errorMessage)
        viewModel.dismissError()
        assertNull(viewModel.uiState.value.errorMessage)

        viewModel.clearConnection()
        assertEquals(AppSettings(), settings.value)
    }

    @Test
    fun `forgetting Home Assistant removes an existing Android TV Home channel`() = runTest {
        val settings = FakeSettingsStore(
            AppSettings(
                baseUrl = "https://ha.example",
                tokenEnvelope = "saved",
                homeChannelEnabled = true,
                channelId = 42L,
            ),
        )
        val session = FakeSession()
        val channel = FakeChannelGateway()
        val viewModel = viewModel(settings, session, channel)
        observe(viewModel)

        viewModel.clearConnection()
        runCurrent()

        assertEquals(listOf(42L), channel.removed)
        assertEquals(AppSettings(), settings.value)
        assertEquals(AppScreen.Dashboard, viewModel.uiState.value.screen)
    }

    @Test
    fun `unreadable saved token reports a recoverable setup error`() = runTest {
        val settings = FakeSettingsStore(AppSettings(baseUrl = "https://ha.example", tokenEnvelope = "bad"))
        settings.decryptFailure = IllegalStateException("Key invalidated")
        val session = FakeSession()
        val viewModel = viewModel(settings, session)
        observe(viewModel)

        viewModel.onForeground()

        assertTrue(viewModel.uiState.value.errorMessage!!.contains("can no longer be read"))
        assertTrue(session.starts.isEmpty())
    }

    @Test
    fun `edge actions are safe outside a matching detail panel`() = runTest {
        val session = FakeSession()
        val viewModel = viewModel(session = session)
        observe(viewModel)
        val lamp = entity("light.desk", "on", attributes = mapOf("brightness" to JsonPrimitive(1)))
        val garage = entity("cover.garage", "closed", attributes = mapOf("device_class" to JsonPrimitive("garage")))

        viewModel.applyLevel()
        viewModel.applyClimateTemperature()
        viewModel.stageLevel(10)
        viewModel.stageClimateTemperature(20.0)
        viewModel.cancelSecureCover()
        viewModel.openDetails(entity("media_player.shield", "on"))
        assertNull(viewModel.uiState.value.details)

        viewModel.openDetails(lamp)
        viewModel.stageLevel(-20)
        assertEquals(0, assertIs<DetailState.Level>(viewModel.uiState.value.details).stagedPercent)
        viewModel.closeDetails()
        viewModel.openDetails(garage)
        viewModel.coverCommand(ControlAction.CoverCommand.Command.OPEN)
        viewModel.cancelSecureCover()
        assertEquals(null, assertIs<DetailState.Cover>(viewModel.uiState.value.details).pendingCommand)
        viewModel.closeDetails()
        viewModel.armAlarm("away")

        assertTrue(session.actions.isEmpty())
    }

    @Test
    fun `unconfigured session and disabled channel do not perform background work`() = runTest {
        val session = FakeSession()
        val channel = FakeChannelGateway()
        val viewModel = viewModel(FakeSettingsStore(AppSettings(baseUrl = "https://ha.example")), session, channel)
        observe(viewModel)

        viewModel.refreshHomeChannel()
        viewModel.onForeground()
        viewModel.disableHomeChannel()

        assertTrue(session.starts.isEmpty())
        assertEquals(0, channel.creates)
        assertTrue(channel.removed.isEmpty())
    }

    @Test
    fun `failed setup validation does not persist a token and resumes the previous session`() = runTest {
        val settings = FakeSettingsStore(AppSettings(baseUrl = "https://old.example", tokenEnvelope = "saved"))
        val session = FakeSession().apply { validationResult = Result.failure(SecurityException("Token rejected")) }
        val viewModel = viewModel(settings, session)
        observe(viewModel)

        viewModel.onForeground()
        viewModel.updateSetupBaseUrl("https://new.example")
        viewModel.updateSetupToken("bad-token")
        viewModel.saveConnection()

        assertEquals("https://old.example", settings.value.baseUrl)
        assertEquals(listOf("https://new.example" to "bad-token"), session.validations)
        assertEquals(
            listOf("https://old.example" to "decrypted-token", "https://old.example" to "decrypted-token"),
            session.starts,
        )
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("Token rejected"))
    }

    @Test
    fun `alarm arm actions are limited to declared modes and require a fresh code when configured`() = runTest {
        val session = FakeSession()
        val alarm = entity(
            "alarm_control_panel.home",
            "disarmed",
            attributes = mapOf(
                "supported_features" to JsonPrimitive(1 + 4),
                "code_arm_required" to JsonPrimitive(true),
            ),
        )
        val viewModel = viewModel(session = session)
        observe(viewModel)

        viewModel.openDetails(alarm)
        viewModel.armAlarm("away")
        assertTrue(session.actions.isEmpty())
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("not supported"))

        viewModel.dismissError()
        viewModel.armAlarm("home")
        assertTrue(session.actions.isEmpty())
        assertEquals("Enter the Home Assistant alarm code", viewModel.uiState.value.errorMessage)

        viewModel.updateAlarmCode("1234")
        viewModel.armAlarm("home")
        assertEquals(ControlAction.ArmAlarm(alarm, "home", "1234"), session.actions.single())
        assertNull(viewModel.uiState.value.details)
    }

    @Test
    fun `connection setup and diagnostics are available through remote settings navigation`() = runTest {
        val viewModel = viewModel(settings = FakeSettingsStore(AppSettings(baseUrl = "https://ha.example", tokenEnvelope = "saved")))
        observe(viewModel)

        viewModel.openSettings()
        viewModel.openConnectionSetup()
        assertEquals(AppScreen.ConnectionSetup, viewModel.uiState.value.screen)
        assertEquals("https://ha.example", viewModel.uiState.value.setupBaseUrl)
        viewModel.openDiagnostics()
        assertEquals(AppScreen.Diagnostics, viewModel.uiState.value.screen)
    }

    @Test
    fun `backgrounding cancels an in progress connection validation without saving its secret`() = runTest {
        val settings = FakeSettingsStore()
        val session = FakeSession().apply { pendingValidation = CompletableDeferred() }
        val viewModel = viewModel(settings, session)
        observe(viewModel)

        viewModel.onForeground()
        viewModel.updateSetupBaseUrl("https://ha.example")
        viewModel.updateSetupToken("temporary-token")
        viewModel.saveConnection()
        assertTrue(viewModel.uiState.value.isSavingConnection)

        viewModel.onBackground()
        runCurrent()

        assertFalse(viewModel.uiState.value.isSavingConnection)
        assertEquals(AppSettings(), settings.value)
    }

    @Test
    fun `unexpected connection validation errors are shown without saving credentials`() = runTest {
        val settings = FakeSettingsStore()
        val session = FakeSession().apply { validationFailure = IllegalStateException("Socket failed") }
        val viewModel = viewModel(settings, session)
        observe(viewModel)

        viewModel.updateSetupBaseUrl("https://ha.example")
        viewModel.updateSetupToken("temporary-token")
        viewModel.saveConnection()

        assertEquals("Socket failed", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isSavingConnection)
        assertEquals(AppSettings(), settings.value)
    }

    @Test
    fun `backgrounding clears transient setup and alarm codes`() = runTest {
        val alarm = entity("alarm_control_panel.home", "armed_away")
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.updateSetupToken("temporary-token")
        viewModel.openDetails(alarm)
        viewModel.updateAlarmCode("1234")
        viewModel.onBackground()

        assertEquals("", viewModel.uiState.value.setupToken)
        assertEquals("", assertIs<DetailState.Alarm>(viewModel.uiState.value.details).disarmCode)
    }

    @Test
    fun `ui state only reports configured when both secret and URL are present`() {
        assertFalse(DashboardUiState(settings = AppSettings(baseUrl = "https://ha.example")).isConfigured)
        assertFalse(DashboardUiState(settings = AppSettings(tokenEnvelope = "saved")).isConfigured)
        assertTrue(DashboardUiState(settings = AppSettings(baseUrl = "https://ha.example", tokenEnvelope = "saved")).isConfigured)
        assertFalse(DashboardUiState().isSettingsLoaded)
    }

    @Test
    fun `dashboard retains the last live tile snapshot while reconnecting`() = runTest {
        val lamp = entity("light.den", "on")
        val session = FakeSession(mapOf(lamp.entityId to lamp))
        val settings = FakeSettingsStore(
            AppSettings(
                baseUrl = "https://ha.example",
                tokenEnvelope = "saved-token",
                tiles = listOf(TileConfiguration(lamp.entityId, 0)),
            ),
        )
        val viewModel = viewModel(settings, session)
        observe(viewModel)

        session.disconnectAndClearStates()
        runCurrent()

        assertEquals(listOf(lamp), viewModel.uiState.value.tiles)
        assertFalse(viewModel.uiState.value.areInitialStatesLoaded)
    }

    @Test
    fun `launcher compatibility state reports Projectivy when available`() = runTest {
        val viewModel = viewModel(channel = FakeChannelGateway(projectivyInstalled = true))
        observe(viewModel)

        assertTrue(viewModel.uiState.value.isProjectivyInstalled)
    }

    private fun TestScope.observe(viewModel: DashboardViewModel) {
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
    }

    private fun viewModel(
        settings: FakeSettingsStore = FakeSettingsStore(),
        session: FakeSession = FakeSession(),
        channel: FakeChannelGateway = FakeChannelGateway(),
    ) = DashboardViewModel(settings, session, channel, dispatcher)

    private fun entity(
        entityId: String,
        state: String,
        attributes: Map<String, JsonPrimitive> = emptyMap(),
    ) = HaEntity(entityId, state, JsonObject(attributes))

    private class FakeSettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
        private val mutableSettings = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()
        val value: AppSettings get() = mutableSettings.value
        var savedConnection: Pair<String, String>? = null
        var decryptFailure: Throwable? = null

        override suspend fun saveConnection(baseUrl: String, token: String) {
            savedConnection = baseUrl to token
            mutableSettings.update { it.copy(baseUrl = baseUrl, tokenEnvelope = "encrypted-token") }
        }

        override fun decryptToken(settings: AppSettings): String? {
            decryptFailure?.let { throw it }
            return settings.tokenEnvelope?.let { "decrypted-token" }
        }

        override suspend fun saveTiles(tiles: List<TileConfiguration>) {
            mutableSettings.update { it.copy(tiles = tiles) }
        }

        override suspend fun saveShortcuts(shortcuts: List<dev.haquickaccess.tv.domain.model.ShortcutConfiguration>) {
            mutableSettings.update { it.copy(homeShortcuts = shortcuts) }
        }

        override suspend fun setHomeChannel(enabled: Boolean, channelId: Long?) {
            mutableSettings.update { it.copy(homeChannelEnabled = enabled, channelId = channelId) }
        }

        override suspend fun saveLastFocusedEntity(entityId: String) {
            mutableSettings.update { it.copy(lastFocusedEntityId = entityId) }
        }

        override suspend fun clearConnection() {
            mutableSettings.value = AppSettings()
        }
    }

    private class FakeSession(initialEntities: Map<String, HaEntity> = emptyMap()) : HomeAssistantSession {
        private val mutableEntities = MutableStateFlow(initialEntities)
        private val mutableInitialStatesLoaded = MutableStateFlow(true)
        private val mutableStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
        override val entities: StateFlow<Map<String, HaEntity>> = mutableEntities.asStateFlow()
        override val initialStatesLoaded: StateFlow<Boolean> = mutableInitialStatesLoaded.asStateFlow()
        override val status: StateFlow<ConnectionStatus> = mutableStatus.asStateFlow()
        val actions = mutableListOf<ControlAction>()
        val starts = mutableListOf<Pair<String, String>>()
        val validations = mutableListOf<Pair<String, String>>()
        var stops = 0
        var result: Result<Unit> = Result.success(Unit)
        var validationResult: Result<Unit> = Result.success(Unit)
        var validationFailure: Exception? = null
        var pendingValidation: CompletableDeferred<Result<Unit>>? = null
        var pendingAction: CompletableDeferred<Result<Unit>>? = null

        override suspend fun validateConnection(baseUrl: String, token: String): Result<Unit> {
            validationFailure?.let { throw it }
            validations += baseUrl to token
            return pendingValidation?.await() ?: validationResult
        }

        override fun start(baseUrl: String, token: String) {
            starts += baseUrl to token
            mutableStatus.value = ConnectionStatus.Connected("test")
        }

        override fun stop() {
            stops += 1
            mutableStatus.value = ConnectionStatus.Disconnected
        }

        fun disconnectAndClearStates() {
            mutableEntities.value = emptyMap()
            mutableInitialStatesLoaded.value = false
            mutableStatus.value = ConnectionStatus.Connecting
        }

        override suspend fun execute(action: ControlAction): Result<Unit> {
            actions += action
            return pendingAction?.await() ?: result
        }
    }

    private class FakeChannelGateway(
        private val channelId: Long = 1L,
        private val projectivyInstalled: Boolean = false,
    ) : HomeChannelGateway {
        var creates = 0
        val removed = mutableListOf<Long>()
        var createFailure: Exception? = null
        var removeFailure: Exception? = null

        override fun createOrUpdate(settings: AppSettings, entities: Map<String, HaEntity>): Long {
            creates += 1
            createFailure?.let { throw it }
            return channelId
        }

        override fun remove(channelId: Long) {
            removeFailure?.let { throw it }
            removed += channelId
        }

        override fun isProjectivyInstalled(): Boolean = projectivyInstalled
    }
}
