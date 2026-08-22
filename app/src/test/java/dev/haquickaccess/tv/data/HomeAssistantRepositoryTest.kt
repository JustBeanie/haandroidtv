package dev.haquickaccess.tv.data

import dev.haquickaccess.tv.domain.ServiceCall
import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.HaEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalCoroutinesApi::class)
class HomeAssistantRepositoryTest {
    @Test
    fun `retries transient failures with capped backoff then keeps one connected session`() = runTest {
        val gateway = FakeGateway(
            results = ArrayDeque(
                listOf(
                    Result.failure(IllegalStateException("network unavailable")),
                    Result.success(Unit),
                ),
            ),
        )
        val repository = HomeAssistantRepository(gateway, StandardTestDispatcher(testScheduler))

        repository.start("https://ha.example", "token")
        runCurrent()
        assertEquals(1, gateway.connectCalls)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, gateway.connectCalls)
        assertTrue(repository.status.value is ConnectionStatus.Connected)

        repository.stop()
        assertEquals(2, gateway.disconnectCalls)
    }

    @Test
    fun `does not retry authentication failures`() = runTest {
        val gateway = FakeGateway(
            results = ArrayDeque(listOf(Result.failure(SecurityException("token rejected")))),
            failureRetryable = false,
        )
        val repository = HomeAssistantRepository(gateway, StandardTestDispatcher(testScheduler))

        repository.start("https://ha.example", "bad-token")
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(1, gateway.connectCalls)
        repository.stop()
    }

    @Test
    fun `validation disconnects the temporary authenticated socket`() = runTest {
        val gateway = FakeGateway(results = ArrayDeque(listOf(Result.success(Unit))))
        val repository = HomeAssistantRepository(gateway, StandardTestDispatcher(testScheduler))

        assertTrue(repository.validateConnection("https://ha.example", "token").isSuccess)
        assertEquals(1, gateway.connectCalls)
        assertEquals(1, gateway.disconnectCalls)
    }

    @Test
    fun `an unacknowledged service call times out and resets the session`() = runTest {
        val gateway = FakeGateway(results = ArrayDeque()).apply {
            pendingCall = CompletableDeferred()
        }
        val repository = HomeAssistantRepository(gateway, StandardTestDispatcher(testScheduler))
        val action = ControlAction.Toggle(HaEntity("switch.coffee", "off"))

        val result = async { repository.execute(action) }
        runCurrent()
        advanceTimeBy(SERVICE_CALL_TIMEOUT_MS)
        runCurrent()

        assertTrue(result.await().isFailure)
        assertEquals("Home Assistant command timed out", result.await().exceptionOrNull()?.message)
        assertEquals(1, gateway.disconnectCalls)
    }

    private class FakeGateway(
        private val results: ArrayDeque<Result<Unit>>,
        private val failureRetryable: Boolean = true,
    ) : HomeAssistantGateway {
        private val mutableEntities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
        private val mutableStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
        override val entities: StateFlow<Map<String, HaEntity>> = mutableEntities.asStateFlow()
        override val status: StateFlow<ConnectionStatus> = mutableStatus.asStateFlow()
        var connectCalls = 0
        var disconnectCalls = 0
        var pendingCall: CompletableDeferred<Result<Unit>>? = null

        override suspend fun connect(baseUrl: String, token: String): Result<Unit> {
            connectCalls += 1
            val result = results.removeFirstOrNull() ?: Result.failure(IllegalStateException("no test result"))
            mutableStatus.value = if (result.isSuccess) {
                ConnectionStatus.Connected("test")
            } else {
                ConnectionStatus.Failed("connection failed", failureRetryable)
            }
            return result
        }

        override suspend fun call(service: ServiceCall): Result<Unit> = pendingCall?.await() ?: Result.success(Unit)

        override fun disconnect() {
            disconnectCalls += 1
            mutableStatus.value = ConnectionStatus.Disconnected
        }
    }
}
