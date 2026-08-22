package dev.haquickaccess.tv.data

import dev.haquickaccess.tv.domain.HomeAssistantCommandFactory
import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val SERVICE_CALL_TIMEOUT_MS = 10_000L

interface HomeAssistantSession {
    val entities: StateFlow<Map<String, HaEntity>>
    val status: StateFlow<ConnectionStatus>
    suspend fun validateConnection(baseUrl: String, token: String): Result<Unit>
    fun start(baseUrl: String, token: String)
    fun stop()
    suspend fun execute(action: ControlAction): Result<Unit>
}

@Singleton
class HomeAssistantRepository @Inject constructor(
    private val gateway: HomeAssistantGateway,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : HomeAssistantSession {
    override val entities: StateFlow<Map<String, HaEntity>> = gateway.entities
    override val status: StateFlow<ConnectionStatus> = gateway.status

    private var sessionScope: CoroutineScope? = null

    override suspend fun validateConnection(baseUrl: String, token: String): Result<Unit> = try {
        gateway.connect(baseUrl, token)
    } finally {
        gateway.disconnect()
    }

    override fun start(baseUrl: String, token: String) {
        stop()
        sessionScope = CoroutineScope(SupervisorJob() + ioDispatcher).also { scope ->
            scope.launch {
                val reconnectBackoff = CappedReconnectBackoff()
                while (true) {
                    val connection = gateway.connect(baseUrl, token)
                    if (connection.isSuccess) reconnectBackoff.reset()
                    while (gateway.status.value is ConnectionStatus.Connected) delay(1_000)
                    if ((gateway.status.value as? ConnectionStatus.Failed)?.retryable == false) break
                    delay(reconnectBackoff.nextDelay())
                }
            }
        }
    }

    override fun stop() {
        sessionScope?.cancel()
        sessionScope = null
        gateway.disconnect()
    }

    override suspend fun execute(action: ControlAction): Result<Unit> {
        val result = withTimeoutOrNull(SERVICE_CALL_TIMEOUT_MS) {
            gateway.call(HomeAssistantCommandFactory.create(action))
        }
        if (result != null) return result

        gateway.disconnect()
        return Result.failure(IllegalStateException("Home Assistant command timed out"))
    }
}
