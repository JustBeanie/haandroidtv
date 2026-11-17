package dev.haquickaccess.tv.data

import dev.haquickaccess.tv.domain.HomeAssistantCommandFactory
import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val SERVICE_CALL_TIMEOUT_MS = 10_000L

interface HomeAssistantSession {
    val entities: StateFlow<Map<String, HaEntity>>
    val initialStatesLoaded: StateFlow<Boolean>
    val status: StateFlow<ConnectionStatus>
    suspend fun validateConnection(baseUrl: String, token: String): Result<Unit>
    fun start(baseUrl: String, token: String)
    fun stop()
    suspend fun execute(action: ControlAction): Result<Unit>
}

@Singleton
class HomeAssistantRepository private constructor(
    private val gateway: HomeAssistantGateway,
    private val ioDispatcher: CoroutineDispatcher,
    private val validationGatewayFactory: () -> HomeAssistantGateway,
) : HomeAssistantSession {
    @Inject
    constructor(gateway: HomeAssistantGateway, @IoDispatcher ioDispatcher: CoroutineDispatcher, client: OkHttpClient, json: Json) :
        this(gateway, ioDispatcher, { HomeAssistantWebSocket(client, json) })

    internal constructor(gateway: HomeAssistantGateway, ioDispatcher: CoroutineDispatcher) :
        this(gateway, ioDispatcher, { gateway })

    override val entities: StateFlow<Map<String, HaEntity>> = gateway.entities
    override val initialStatesLoaded: StateFlow<Boolean> = gateway.initialStatesLoaded
    override val status: StateFlow<ConnectionStatus> = gateway.status

    private var sessionScope: CoroutineScope? = null
    private var sessionJob: Job? = null

    override suspend fun validateConnection(baseUrl: String, token: String): Result<Unit> {
        val validationGateway = validationGatewayFactory()
        return try {
            validationGateway.connect(baseUrl, token)
        } finally {
            validationGateway.disconnect()
        }
    }

    @Synchronized
    override fun start(baseUrl: String, token: String) {
        val previousJob = sessionJob
        stop()
        sessionScope = CoroutineScope(SupervisorJob() + ioDispatcher).also { scope ->
            sessionJob = scope.launch {
                previousJob?.join()
                val reconnectBackoff = CappedReconnectBackoff()
                while (isActive) {
                    val connection = gateway.connect(baseUrl, token)
                    if (connection.isSuccess) {
                        reconnectBackoff.reset()
                        gateway.status.first { it !is ConnectionStatus.Connected }
                    }
                    if ((gateway.status.value as? ConnectionStatus.Failed)?.retryable == false) break
                    delay(reconnectBackoff.nextDelay())
                }
            }
        }
    }

    @Synchronized
    override fun stop() {
        sessionScope?.cancel()
        sessionScope = null
        gateway.disconnect()
    }

    override suspend fun execute(action: ControlAction): Result<Unit> = try {
        val result = withTimeoutOrNull(SERVICE_CALL_TIMEOUT_MS) {
            gateway.call(HomeAssistantCommandFactory.create(action))
        }
        if (result != null) {
            result
        } else {
            Result.failure(IllegalStateException("Home Assistant command timed out"))
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Result.failure(exception)
    }
}
