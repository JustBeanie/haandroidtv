package dev.haquickaccess.tv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaunchIntentValidatorTest {
    @Test
    fun `accepts only the app control deep link contract`() {
        val request = LaunchIntentValidator.parse(
            "haquickaccess://control/light.kitchen?behavior=toggle",
        )

        assertEquals(LaunchRequest("light.kitchen", "toggle"), request)
    }

    @Test
    fun `defaults missing behavior to details`() {
        assertEquals(
            LaunchRequest("cover.garage", "details"),
            LaunchIntentValidator.parse("haquickaccess://control/cover.garage"),
        )
    }

    @Test
    fun `rejects spoofed hosts paths entities and behaviors`() {
        val invalidUris = listOf(
            "https://control/light.kitchen?behavior=toggle",
            "haquickaccess://attacker/light.kitchen?behavior=toggle",
            "haquickaccess://control/light.kitchen/extra?behavior=toggle",
            "haquickaccess://control/../light.kitchen?behavior=toggle",
            "haquickaccess://control/light.kitchen?behavior=arm",
            "haquickaccess://control/light-kitchen?behavior=toggle",
        )

        invalidUris.forEach { assertNull(LaunchIntentValidator.parse(it), it) }
    }
}
