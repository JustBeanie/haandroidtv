package dev.haquickaccess.tv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CappedReconnectBackoffTest {
    @Test
    fun `delays double and cap before a successful session resets them`() {
        val backoff = CappedReconnectBackoff(initialDelayMs = 1_000, maximumDelayMs = 4_000)

        assertEquals(1_000, backoff.nextDelay())
        assertEquals(2_000, backoff.nextDelay())
        assertEquals(4_000, backoff.nextDelay())
        assertEquals(4_000, backoff.nextDelay())

        backoff.reset()

        assertEquals(1_000, backoff.nextDelay())
    }

    @Test
    fun `rejects unsafe reconnect configurations`() {
        assertFailsWith<IllegalArgumentException> { CappedReconnectBackoff(initialDelayMs = 0) }
        assertFailsWith<IllegalArgumentException> { CappedReconnectBackoff(initialDelayMs = 2_000, maximumDelayMs = 1_000) }
    }
}
