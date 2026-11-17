package dev.haquickaccess.tv.data

import dev.haquickaccess.tv.domain.ServiceCall
import java.io.IOException
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeAssistantWebSocketTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val service = ServiceCall("light", "turn_on", JsonObject(emptyMap()))

    @Test fun synchronizationRequiresSubscriptionAndSnapshot() = runTest {
        val fixture = Fixture()
        val connection = async { fixture.gateway.connect("https://ha.example", "token") }
        runCurrent()
        fixture.auth()
        assertFalse(connection.isCompleted)
        assertFalse(fixture.gateway.initialStatesLoaded.value)
        fixture.subscription()
        fixture.states()
        assertTrue(connection.await().isSuccess)
        assertTrue(fixture.gateway.initialStatesLoaded.value)
        assertEquals("off", fixture.gateway.entities.value["light.kitchen"]?.state)
    }

    @Test fun networkFailureReleasesState() = runTest {
        val fixture = connected()
        fixture.listener.onFailure(fixture.socket, IOException("network unavailable"), null)
        assertTrue(fixture.gateway.entities.value.isEmpty())
        assertIs<ConnectionStatus.Failed>(fixture.gateway.status.value)
        assertTrue(fixture.socket.cancelled)
    }

    @Test fun explicitDisconnectClosesSocket() = runTest {
        val fixture = connected()
        fixture.gateway.disconnect()
        assertTrue(fixture.gateway.entities.value.isEmpty())
        assertEquals(1000, fixture.socket.closeCode)
        assertIs<ConnectionStatus.Disconnected>(fixture.gateway.status.value)
    }

    @Test fun rejectedAuthenticationIsNotRetryable() = runTest {
        val fixture = Fixture()
        val connection = async { fixture.gateway.connect("https://ha.example", "bad-token") }
        runCurrent()
        fixture.message("""{"type":"auth_invalid","message":"secret server detail"}""")
        assertIs<SecurityException>(connection.await().exceptionOrNull())
        assertFalse(assertIs<ConnectionStatus.Failed>(fixture.gateway.status.value).retryable)
        assertEquals(1008, fixture.socket.closeCode)
    }

    @Test fun invalidSnapshotFailsInitialization() = runTest {
        val fixture = Fixture()
        val connection = async { fixture.gateway.connect("https://ha.example", "token") }
        runCurrent()
        fixture.auth()
        fixture.subscription()
        fixture.message("""{"id":2,"type":"result","success":true,"result":{}}""")
        assertTrue(connection.await().isFailure)
        assertFalse(fixture.gateway.initialStatesLoaded.value)
        assertTrue(fixture.socket.cancelled)
    }

    @Test fun missingInitializationResponseTimesOut() = runTest {
        val fixture = Fixture()
        val connection = async { fixture.gateway.connect("https://ha.example", "token") }
        runCurrent()
        fixture.auth()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(connection.await().isFailure)
        assertTrue(fixture.socket.cancelled)
    }

    @Test fun rejectedSubscriptionFailsInitialization() = runTest {
        val fixture = Fixture()
        val connection = async { fixture.gateway.connect("https://ha.example", "token") }
        runCurrent()
        fixture.auth()
        fixture.message("""{"id":1,"type":"result","success":false}""")
        assertTrue(connection.await().isFailure)
        assertTrue(fixture.socket.cancelled)
    }

    @Test fun oversizeAndFailedSendsReleaseSocket() = runTest {
        val fixture = connected()
        fixture.message("x".repeat(1_048_577))
        assertTrue(fixture.socket.cancelled)
        assertTrue(fixture.gateway.entities.value.isEmpty())
        val other = Fixture()
        val connection = async { other.gateway.connect("https://ha.example", "token") }
        runCurrent()
        other.socket.sendResult = false
        other.message("""{"type":"auth_required"}""")
        assertTrue(connection.await().isFailure)
        assertTrue(other.socket.cancelled)
    }

    @Test fun cancelledConnectionCannotDisconnectItsReplacement() = runTest {
        val fixture = Fixture()
        val first = async { fixture.gateway.connect("https://first.example", "old-token") }
        runCurrent()
        val oldSocket = fixture.socket
        val oldListener = fixture.listener
        val second = async { fixture.gateway.connect("https://second.example", "new-token") }
        runCurrent()
        first.cancelAndJoin()
        oldListener.onFailure(oldSocket, IOException("late failure"), null)
        oldListener.onMessage(oldSocket, """{"type":"auth_invalid"}""")
        fixture.ready()
        assertTrue(second.await().isSuccess)
        assertIs<ConnectionStatus.Connected>(fixture.gateway.status.value)
        assertFalse(fixture.socket.cancelled)
        fixture.gateway.disconnect()
    }

    @Test fun cancelledCallsDoNotExhaustPendingCapacity() = runTest {
        val fixture = connected()
        repeat(100) {
            val request = async { fixture.gateway.call(service) }
            runCurrent()
            request.cancelAndJoin()
        }
        val request = async { fixture.gateway.call(service) }
        runCurrent()
        val id = fixture.lastCommandId()
        fixture.message("""{"id":$id,"type":"result","success":true}""")
        assertTrue(request.await().isSuccess)
        fixture.gateway.disconnect()
    }

    @Test fun timeoutDoesNotDisconnectOtherCommandsOrReplay() = runTest {
        val fixture = connected()
        val request = async { fixture.gateway.call(service) }
        runCurrent()
        val sentCount = fixture.socket.sent.size
        advanceTimeBy(SERVICE_CALL_TIMEOUT_MS)
        runCurrent()
        assertTrue(request.await().isFailure)
        assertEquals(sentCount, fixture.socket.sent.size)
        assertIs<ConnectionStatus.Connected>(fixture.gateway.status.value)
        fixture.gateway.disconnect()
    }

    @Test fun stateChangesDuringInitialSnapshotArePreserved() = runTest {
        val fixture = Fixture()
        val connection = async { fixture.gateway.connect("https://ha.example", "token") }
        runCurrent()
        fixture.auth()
        fixture.subscription()
        fixture.changed("light.kitchen", "on")
        fixture.states()
        assertTrue(connection.await().isSuccess)
        assertEquals("on", fixture.gateway.entities.value["light.kitchen"]?.state)
        fixture.gateway.disconnect()
    }

    @Test fun concurrentCallbacksDoNotLoseEntityUpdates() = runTest {
        val fixture = connected()
        val barrier = CyclicBarrier(3)
        val workers = (0..1).map { worker -> thread {
            barrier.await()
            repeat(50) { fixture.changed("light.test_" + worker + "_" + it, "on") }
        } }
        barrier.await()
        workers.forEach { it.join() }
        assertEquals(101, fixture.gateway.entities.value.size)
        fixture.gateway.disconnect()
    }

    @Test fun callbacksDuringOpenAreAccepted() = runTest {
        val socket = FakeWebSocket()
        val gateway = HomeAssistantWebSocket(json) { _, listener ->
            listener.onMessage(socket, """{"type":"auth_required"}""")
            socket
        }
        val connection = async { gateway.connect("https://ha.example", "token") }
        runCurrent()
        assertTrue(socket.sent.any { it.contains("access_token") })
        connection.cancelAndJoin()
        assertTrue(socket.cancelled)
    }

    private suspend fun TestScope.connected(): Fixture {
        val fixture = Fixture()
        val connection = async { fixture.gateway.connect("https://ha.example", "token") }
        runCurrent()
        fixture.ready()
        assertTrue(connection.await().isSuccess)
        return fixture
    }

    private inner class Fixture {
        lateinit var listener: WebSocketListener
        lateinit var socket: FakeWebSocket
        val gateway = HomeAssistantWebSocket(json) { _, listener ->
            this.listener = listener
            FakeWebSocket().also { socket = it }
        }
        fun message(text: String) = listener.onMessage(socket, text)
        fun auth() {
            message("""{"type":"auth_required"}""")
            message("""{"type":"auth_ok","ha_version":"test"}""")
        }
        fun subscription() = message("""{"id":1,"type":"result","success":true}""")
        fun states() = message("""{"id":2,"type":"result","success":true,"result":[{"entity_id":"light.kitchen","state":"off","attributes":{"friendly_name":"Kitchen"}}]}""")
        fun ready() { auth(); subscription(); states() }
        fun changed(id: String, value: String) = message("""{"type":"event","event":{"event_type":"state_changed","data":{"entity_id":"$id","new_state":{"entity_id":"$id","state":"$value","attributes":{}}}}}""")
        fun lastCommandId() = json.parseToJsonElement(socket.sent.last()).jsonObject["id"]!!.jsonPrimitive.long
    }

    private class FakeWebSocket : WebSocket {
        var closeCode: Int? = null
        var sendResult = true
        var cancelled = false
        val sent = mutableListOf<String>()
        override fun request(): Request = Request.Builder().url("https://ha.example/api/websocket").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent += text; return sendResult }
        override fun send(bytes: okio.ByteString) = true
        override fun close(code: Int, reason: String?): Boolean { closeCode = code; return true }
        override fun cancel() { cancelled = true }
    }
}
