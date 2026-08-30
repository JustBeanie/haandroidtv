package dev.haquickaccess.tv.data

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@OptIn(ExperimentalCoroutinesApi::class)
class HomeAssistantWebSocketTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `network failure clears entities loaded by the authenticated socket`() = runTest {
        val fixture = Fixture()
        val connection = async { fixture.gateway.connect("https://ha.example", "token") }

        runCurrent()
        fixture.authenticate()
        assertTrue(connection.await().isSuccess)
        fixture.sendStates()
        assertTrue("light.kitchen" in fixture.gateway.entities.value)

        fixture.listener.onFailure(fixture.socket, IOException("network unavailable"), null)

        assertTrue(fixture.gateway.entities.value.isEmpty())
        assertIs<ConnectionStatus.Failed>(fixture.gateway.status.value)
    }

    @Test
    fun `explicit disconnect clears loaded entities and closes the socket`() = runTest {
        val fixture = Fixture()
        val connection = async { fixture.gateway.connect("https://ha.example", "token") }

        runCurrent()
        fixture.authenticate()
        assertTrue(connection.await().isSuccess)
        fixture.sendStates()
        fixture.gateway.disconnect()

        assertTrue(fixture.gateway.entities.value.isEmpty())
        assertEquals(1000, fixture.socket.closeCode)
        assertIs<ConnectionStatus.Disconnected>(fixture.gateway.status.value)
    }

    @Test
    fun `authentication rejection closes the socket and does not retry`() = runTest {
        val fixture = Fixture()
        val connection = async { fixture.gateway.connect("https://ha.example", "bad-token") }

        runCurrent()
        fixture.listener.onMessage(fixture.socket, """{"type":"auth_required"}""")
        fixture.listener.onMessage(fixture.socket, """{"type":"auth_invalid","message":"Token rejected"}""")

        assertIs<SecurityException>(connection.await().exceptionOrNull())
        val status = assertIs<ConnectionStatus.Failed>(fixture.gateway.status.value)
        assertTrue(!status.retryable)
        assertEquals(1008, fixture.socket.closeCode)
    }

    @Test
    fun `invalid initial state payload fails fast instead of leaving loading stuck`() = runTest {
        val fixture = Fixture()
        val connection = async { fixture.gateway.connect("https://ha.example", "token") }

        runCurrent()
        fixture.authenticate()
        assertTrue(connection.await().isSuccess)
        fixture.listener.onMessage(fixture.socket, """{"id":1,"type":"result","success":true,"result":{}}""")

        assertIs<ConnectionStatus.Failed>(fixture.gateway.status.value)
        assertFalse(fixture.gateway.initialStatesLoaded.value)
        assertTrue(fixture.socket.cancelled)
    }

    @Test
    fun `oversized server messages fail closed before JSON parsing`() = runTest {
        val fixture = Fixture()
        val connection = async { fixture.gateway.connect("https://ha.example", "token") }

        runCurrent()
        fixture.authenticate()
        assertTrue(connection.await().isSuccess)

        fixture.listener.onMessage(fixture.socket, "x".repeat(1_048_577))

        assertIs<ConnectionStatus.Failed>(fixture.gateway.status.value)
        assertTrue(fixture.gateway.entities.value.isEmpty())
        assertTrue(fixture.socket.cancelled)
    }

    @Test
    fun `rejected event subscription resets the session so reconnect can restore updates`() = runTest {
        val fixture = Fixture()
        val connection = async { fixture.gateway.connect("https://ha.example", "token") }

        runCurrent()
        fixture.authenticate()
        assertTrue(connection.await().isSuccess)
        fixture.sendStates()
        fixture.listener.onMessage(
            fixture.socket,
            """{"id":2,"type":"result","success":false,"error":{"message":"subscription rejected"}}""",
        )

        assertIs<ConnectionStatus.Failed>(fixture.gateway.status.value)
        assertTrue(fixture.gateway.entities.value.isEmpty())
        assertTrue(fixture.socket.cancelled)
    }

    @Test
    fun `failed authentication send reports connection failure immediately`() = runTest {
        val fixture = Fixture()
        fixture.socket.sendResult = false
        val connection = async { fixture.gateway.connect("https://ha.example", "token") }

        runCurrent()
        fixture.listener.onMessage(fixture.socket, """{"type":"auth_required"}""")

        assertTrue(connection.await().isFailure)
        assertIs<ConnectionStatus.Failed>(fixture.gateway.status.value)
        assertTrue(fixture.socket.cancelled)
    }

    private inner class Fixture {
        lateinit var listener: WebSocketListener
        val socket = FakeWebSocket()
        val gateway = HomeAssistantWebSocket(json) { _, listener ->
            this.listener = listener
            socket
        }

        fun authenticate() {
            listener.onMessage(socket, """{"type":"auth_required"}""")
            listener.onMessage(socket, """{"type":"auth_ok","ha_version":"2026.8.0"}""")
        }

        fun sendStates() {
            listener.onMessage(
                socket,
                """{"id":1,"type":"result","success":true,"result":[{"entity_id":"light.kitchen","state":"off","attributes":{"friendly_name":"Kitchen"}}]}""",
            )
        }
    }

    private class FakeWebSocket : WebSocket {
        var closeCode: Int? = null
        var sendResult = true
        var cancelled = false

        override fun request(): Request = Request.Builder().url("https://ha.example/api/websocket").build()

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean = sendResult

        override fun send(bytes: okio.ByteString): Boolean = true

        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            return true
        }

        override fun cancel() {
            cancelled = true
        }
    }
}
