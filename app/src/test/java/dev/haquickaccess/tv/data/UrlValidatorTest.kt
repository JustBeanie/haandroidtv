package dev.haquickaccess.tv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlValidatorTest {
    @Test
    fun `normalizes secure base url`() {
        val result = UrlValidator.normalize("https://home.example.com/ha/")

        assertTrue(result.isSuccess)
        assertEquals("https://home.example.com/ha", result.getOrThrow())
    }

    @Test
    fun `rejects clear text connection`() {
        assertFalse(UrlValidator.normalize("http://192.168.1.20:8123").isSuccess)
    }

    @Test
    fun `rejects url with embedded credentials`() {
        assertFalse(UrlValidator.normalize("https://token@example.com").isSuccess)
    }

    @Test
    fun `rejects blank host and fragments while preserving a strict HTTPS boundary`() {
        assertFalse(UrlValidator.normalize(" ").isSuccess)
        assertFalse(UrlValidator.normalize("https:///dashboard").isSuccess)
        assertFalse(UrlValidator.normalize("https://home.example/#fragment").isSuccess)
    }
}
