package dev.haquickaccess.tv.data

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdbCommandContractTest {
    @Test
    fun `accepts a complete configuration`() {
        AdbConfigurationValidator.validate(
            AdbConfiguration(
                baseUrl = "https://ha.example",
                token = "long-lived-token",
                tiles = listOf("light.kitchen", "switch.tv"),
                shortcuts = listOf(AdbShortcut("light.kitchen", "toggle")),
                homeChannelEnabled = true,
            ),
        )
    }

    @Test
    fun `allows partial non-credential updates and explicit empty lists`() {
        AdbConfigurationValidator.validate(AdbConfiguration(tiles = emptyList(), shortcuts = emptyList()))
        AdbConfigurationValidator.validate(AdbConfiguration(homeChannelEnabled = false))
    }

    @Test
    fun `allows enabling the channel in the pure contract when shortcuts are supplied separately`() {
        AdbConfigurationValidator.validate(
            AdbConfiguration(shortcuts = listOf(AdbShortcut("light.kitchen", "toggle")), homeChannelEnabled = true),
        )
    }

    @Test
    fun `requires credentials as a pair`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            AdbConfigurationValidator.validate(AdbConfiguration(baseUrl = "https://ha.example"))
        }

        assertTrue(exception.message.orEmpty().contains("together"))
    }

    @Test
    fun `rejects unsafe or ambiguous control lists`() {
        assertFailsWith<IllegalArgumentException> {
            AdbConfigurationValidator.validate(AdbConfiguration(tiles = listOf("light-kitchen")))
        }
        assertFailsWith<IllegalArgumentException> {
            AdbConfigurationValidator.validate(
                AdbConfiguration(shortcuts = listOf(AdbShortcut("light.kitchen", "arm"))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AdbConfigurationValidator.validate(
                AdbConfiguration(tiles = listOf("light.kitchen", "light.kitchen")),
            )
        }
    }

    @Test
    fun `rejects empty configuration`() {
        assertFailsWith<IllegalArgumentException> {
            AdbConfigurationValidator.validate(AdbConfiguration())
        }
    }
}
