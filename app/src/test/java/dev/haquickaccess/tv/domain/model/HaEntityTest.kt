package dev.haquickaccess.tv.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

class HaEntityTest {
    @Test
    fun `light with brightness is dimmable and exposes percentage`() {
        val entity = HaEntity(
            "light.living_room",
            "on",
            JsonObject(mapOf("brightness" to JsonPrimitive(128), "friendly_name" to JsonPrimitive("Living room"))),
        )

        assertEquals(ControlKind.LIGHT, entity.capabilities().kind)
        assertTrue(entity.capabilities().canSetLevel)
        assertEquals(50, entity.levelPercent())
        assertEquals("Living room", entity.name)
    }

    @Test
    fun `fan without percentage stays simple toggle`() {
        val entity = HaEntity("fan.tv", "off")

        assertTrue(entity.capabilities().canToggle)
        assertFalse(entity.capabilities().canSetLevel)
    }

    @Test
    fun `secure cover requires confirmation before opening`() {
        val entity = HaEntity("cover.garage", "closed", JsonObject(mapOf("device_class" to JsonPrimitive("garage"))))

        assertTrue(entity.capabilities().requiresSecureCoverConfirmation)
    }

    @Test
    fun `climate exposes declared temperature bounds and modes`() {
        val entity = HaEntity(
            "climate.hall",
            "heat",
            JsonObject(
                mapOf(
                    "temperature" to JsonPrimitive(20.0),
                    "min_temp" to JsonPrimitive(10.0),
                    "max_temp" to JsonPrimitive(28.0),
                    "target_temp_step" to JsonPrimitive(0.5),
                    "hvac_modes" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("off"), JsonPrimitive("heat"))),
                ),
            ),
        )

        assertTrue(entity.capabilities().canSetClimate)
        assertEquals(20.0, entity.climateTarget())
        assertEquals(10.0, entity.climateMinimum())
        assertEquals(28.0, entity.climateMaximum())
        assertEquals(0.5, entity.climateStep())
        assertEquals(listOf("off", "heat"), entity.hvacModes())
    }

    @Test
    fun `unavailable state cannot be treated as on`() {
        val entity = HaEntity("switch.coffee", "unavailable")

        assertTrue(entity.unavailable)
        assertFalse(entity.isOn)
    }

    @Test
    fun `domains route to their expected capabilities`() {
        assertTrue(HaEntity("switch.coffee", "off").capabilities().canToggle)
        assertTrue(HaEntity("input_boolean.guest_mode", "off").capabilities().canToggle)
        val alarm = HaEntity(
            "alarm_control_panel.home",
            "disarmed",
            JsonObject(mapOf("supported_features" to JsonPrimitive(2))),
        )
        assertTrue(alarm.capabilities().canArm)
        assertTrue(alarm.capabilities().alarmCodeRequired)
        assertFalse(
            HaEntity(
                "alarm_control_panel.home",
                "disarmed",
                JsonObject(mapOf("code_arm_required" to JsonPrimitive(false))),
            ).capabilities().alarmCodeRequired,
        )
        assertEquals(ControlKind.UNSUPPORTED, HaEntity("media_player.shield", "on").capabilities().kind)
    }

    @Test
    fun `scenes scripts groups and buttons expose their intended actions`() {
        val scene = HaEntity("scene.movie_time", "unknown")
        val script = HaEntity("script.goodnight", "off")
        val group = HaEntity("group.downstairs", "on")
        val button = HaEntity("button.refresh", "unknown")
        val inputButton = HaEntity("input_button.test", "unknown")

        assertTrue(scene.capabilities().canActivate)
        assertFalse(scene.unavailable)
        assertEquals("Activate", scene.actionLabel())
        assertTrue(script.capabilities().canRun)
        assertEquals("Run", script.actionLabel())
        assertTrue(group.capabilities().canToggle)
        assertEquals(ControlKind.GROUP, group.capabilities().kind)
        assertTrue(button.capabilities().canPress)
        assertTrue(inputButton.capabilities().canPress)
        assertFalse(button.unavailable)
        assertEquals("Press", inputButton.actionLabel())
    }

    @Test
    fun `number select and text helpers expose bounded editable controls`() {
        val number = HaEntity(
            "input_number.sleep_timer",
            "15.5",
            JsonObject(mapOf("min" to JsonPrimitive(0.0), "max" to JsonPrimitive(90.0), "step" to JsonPrimitive(0.5))),
        )
        val select = HaEntity(
            "input_select.tv_source",
            "HDMI 1",
            JsonObject(mapOf("options" to JsonArray(listOf(JsonPrimitive("HDMI 1"), JsonPrimitive("Apps"))))),
        )
        val text = HaEntity("input_text.guest_message", "Welcome")

        assertEquals(ControlKind.NUMBER, number.capabilities().kind)
        assertTrue(number.capabilities().canSetNumber)
        assertEquals(0.0, number.numberMinimum())
        assertEquals(90.0, number.numberMaximum())
        assertEquals(0.5, number.numberStep())
        assertEquals(ControlKind.SELECT, select.capabilities().kind)
        assertTrue(select.capabilities().canSelectOption)
        assertEquals(listOf("HDMI 1", "Apps"), select.selectOptions())
        assertTrue(text.capabilities().canSetText)
        assertFalse(HaEntity("input_select.empty", "unknown").capabilities().canSelectOption)
    }

    @Test
    fun `editable helper defaults remain safe when Home Assistant omits or rejects values`() {
        val unboundedNumber = HaEntity("number.volume_limit", "unknown")
        val invalidStep = HaEntity(
            "input_number.sleep_timer",
            "15",
            JsonObject(mapOf("step" to JsonPrimitive(0))),
        )
        val nativeSelect = HaEntity(
            "select.input_source",
            "TV",
            JsonObject(mapOf("options" to JsonArray(listOf(JsonPrimitive("TV"))))),
        )
        val nativeText = HaEntity("text.status_message", "Ready")

        assertTrue(unboundedNumber.capabilities().canSetNumber)
        assertEquals(0.0, unboundedNumber.numberMinimum())
        assertEquals(100.0, unboundedNumber.numberMaximum())
        assertEquals(1.0, unboundedNumber.numberStep())
        assertEquals(1.0, invalidStep.numberStep())
        assertTrue(nativeSelect.capabilities().canSelectOption)
        assertTrue(nativeText.capabilities().canSetText)
    }

    @Test
    fun `alarm only exposes declared arm services and retains code format`() {
        val alarm = HaEntity(
            "alarm_control_panel.home",
            "disarmed",
            JsonObject(
                mapOf(
                    "supported_features" to JsonPrimitive(1 + 4 + 32),
                    "code_format" to JsonPrimitive("number"),
                ),
            ),
        )

        assertEquals(listOf("home", "night", "vacation"), alarm.alarmArmModes())
        assertTrue(alarm.capabilities().canArm)
        assertTrue(alarm.alarmCodeIsNumeric())
        assertFalse(HaEntity("alarm_control_panel.none", "disarmed").capabilities().canArm)
    }

    @Test
    fun `level and climate helpers clamp values and use safe defaults`() {
        assertEquals(100, HaEntity("fan.tv", "on", JsonObject(mapOf("percentage" to JsonPrimitive(140)))).levelPercent())
        assertEquals(0, HaEntity("cover.blinds", "closed", JsonObject(mapOf("current_position" to JsonPrimitive(-10)))).levelPercent())
        assertEquals(null, HaEntity("switch.coffee", "off").levelPercent())

        val climate = HaEntity("climate.hall", "off")
        assertEquals(1.0, climate.climateStep())
        assertEquals(7.0, climate.climateMinimum())
        assertEquals(35.0, climate.climateMaximum())
        assertEquals(emptyList(), climate.hvacModes())
    }

    @Test
    fun `unknown state and entity naming fallbacks remain safe`() {
        val entity = HaEntity("light.kitchen_ceiling", "unknown")

        assertTrue(entity.unavailable)
        assertEquals("light", entity.domain)
        assertEquals("kitchen_ceiling", entity.name)
        assertEquals(null, entity.deviceClass)
        assertEquals(null, entity.string("missing"))
        assertEquals(null, entity.number("missing"))
    }

    @Test
    fun `nullable and partial attribute payloads do not create invalid controls`() {
        val attributes = JsonObject(
            mapOf(
                "friendly_name" to JsonNull,
                "device_class" to JsonNull,
                "brightness" to JsonNull,
                "hvac_modes" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("heat"), JsonNull)),
            ),
        )
        val light = HaEntity("light.desk", "off", attributes)

        assertEquals("desk", light.name)
        assertEquals(null, light.deviceClass)
        assertEquals(null, light.levelPercent())
        assertFalse(light.capabilities().canSetLevel)
        assertEquals(listOf("heat"), light.hvacModes())
        assertEquals(null, HaEntity("cover.blinds", "closed").levelPercent())
    }
}
