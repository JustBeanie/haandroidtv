package dev.haquickaccess.tv.data

import dev.haquickaccess.tv.domain.ServiceCall
import dev.haquickaccess.tv.domain.model.ControlKind
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.capabilities
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object Connecting : ConnectionStatus
    data class Connected(val version: String?) : ConnectionStatus
    data class Failed(val message: String, val retryable: Boolean = true) : ConnectionStatus
}

interface HomeAssistantGateway {
    val entities: StateFlow<Map<String, HaEntity>>
    val initialStatesLoaded: StateFlow<Boolean>
    val status: StateFlow<ConnectionStatus>
    suspend fun connect(baseUrl: String, token: String): Result<Unit>
    suspend fun call(service: ServiceCall): Result<Unit>
    fun disconnect()
}

@Singleton
class HomeAssistantWebSocket private constructor(
    private val openWebSocket: (Request, WebSocketListener) -> WebSocket,
    private val json: Json,
) : HomeAssistantGateway {
    @Inject
    constructor(client: OkHttpClient, json: Json) : this(client::newWebSocket, json)

    internal constructor(
        json: Json,
        openWebSocket: (Request, WebSocketListener) -> WebSocket,
    ) : this(openWebSocket, json)

    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    override val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    private val _initialStatesLoaded = MutableStateFlow(false)
    override val initialStatesLoaded: StateFlow<Boolean> = _initialStatesLoaded.asStateFlow()

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Result<JsonObject>>>()
    private var socket: WebSocket? = null
    private var authResult: CompletableDeferred<Result<Unit>>? = null
    private var currentToken: String? = null
    private var initialStatesRequestId: Long? = null
    private var stateSubscriptionRequestId: Long? = null

    override suspend fun connect(baseUrl: String, token: String): Result<Unit> {
        disconnect()
        _status.value = ConnectionStatus.Connecting
        currentToken = token
        val result = CompletableDeferred<Result<Unit>>()
        authResult = result
        socket = openWebSocket(
            Request.Builder().url(HomeAssistantProtocol.webSocketUrl(baseUrl)).build(),
            Listener(),
        )
        return withTimeoutOrNull(CONNECTION_TIMEOUT_MS) { result.await() }
            ?: Result.failure<Unit>(IllegalStateException("Home Assistant connection timed out")).also { disconnect() }
    }

    override suspend fun call(service: ServiceCall): Result<Unit> {
        val result = sendCommand(
            type = "call_service",
            extra = buildJsonObject {
                put("domain", JsonPrimitive(service.domain))
                put("service", JsonPrimitive(service.service))
                put("service_data", service.data)
            },
        )
        return result.await().fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(it) },
        )
    }

    override fun disconnect() {
        val previousSocket = socket
        socket = null
        currentToken = null
        initialStatesRequestId = null
        stateSubscriptionRequestId = null
        _entities.value = emptyMap()
        _initialStatesLoaded.value = false
        authResult?.complete(Result.failure(IllegalStateException("Disconnected")))
        authResult = null
        pending.values.forEach { it.complete(Result.failure(IllegalStateException("Disconnected"))) }
        pending.clear()
        if (_status.value !is ConnectionStatus.Failed) _status.value = ConnectionStatus.Disconnected
        previousSocket?.close(1000, "App moved to background")
    }

    private fun sendInitialRequests() {
        initialStatesRequestId = nextId.get()
        sendCommand("get_states")
        if (socket == null) return
        stateSubscriptionRequestId = nextId.get()
        sendCommand(
            type = "subscribe_events",
            extra = buildJsonObject { put("event_type", JsonPrimitive("state_changed")) },
        )
    }

    private fun sendCommand(type: String, extra: JsonObject = JsonObject(emptyMap())): CompletableDeferred<Result<JsonObject>> {
        val id = nextId.getAndIncrement()
        val result = CompletableDeferred<Result<JsonObject>>()
        val message = HomeAssistantProtocol.command(id, type, extra)
        pending[id] = result
        val activeSocket = socket
        if (activeSocket?.send(json.encodeToString(JsonObject.serializer(), message)) != true) {
            val failure = IllegalStateException("Home Assistant is not connected")
            pending.remove(id)
            result.complete(Result.failure(failure))
            if (activeSocket != null) {
                failActiveSocket(activeSocket, failure, "Home Assistant connection lost")
                activeSocket.cancel()
            }
        }
        return result
    }

    private fun replaceStates(states: JsonArray) {
        _entities.value = states
            .mapNotNull(::entityFromJson)
            .filter(::isSupportedControl)
            .associateBy(HaEntity::entityId)
        _initialStatesLoaded.value = true
    }

    private fun updateState(state: JsonObject?) {
        val entity = state?.let(::entityFromJson) ?: return
        val currentEntities = _entities.value
        val updatedEntities = if (isSupportedControl(entity)) {
            if (currentEntities[entity.entityId] == entity) return
            currentEntities + (entity.entityId to entity)
        } else {
            if (entity.entityId !in currentEntities) return
            currentEntities - entity.entityId
        }
        _entities.value = updatedEntities
    }

    private fun applyStateChange(data: JsonObject?) {
        val entityId = (data?.get("entity_id") as? JsonPrimitive)?.contentOrNull ?: return
        val newState = data["new_state"] as? JsonObject
        if (newState == null) {
            _entities.value = _entities.value - entityId
        } else {
            updateState(newState)
        }
    }

    private fun entityFromJson(value: kotlinx.serialization.json.JsonElement): HaEntity? =
        HomeAssistantProtocol.entityFromJson(value)

    private fun isSupportedControl(entity: HaEntity): Boolean =
        entity.capabilities().kind != ControlKind.UNSUPPORTED

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when ((message["type"] as? JsonPrimitive)?.contentOrNull) {
                "auth_required" -> {
                    val sent = webSocket.send(
                        json.encodeToString(JsonObject.serializer(), HomeAssistantProtocol.authMessage(currentToken.orEmpty())),
                    )
                    if (!sent) {
                        val failure = IllegalStateException("Could not authenticate with Home Assistant")
                        failActiveSocket(webSocket, failure, "Home Assistant connection lost")
                        webSocket.cancel()
                    }
                }
                "auth_ok" -> {
                    _status.value = ConnectionStatus.Connected((message["ha_version"] as? JsonPrimitive)?.contentOrNull)
                    authResult?.complete(Result.success(Unit))
                    authResult = null
                    sendInitialRequests()
                }
                "auth_invalid" -> {
                    val exception = SecurityException((message["message"] as? JsonPrimitive)?.contentOrNull ?: "Token rejected")
                    failActiveSocket(webSocket, exception, "Authentication failed", retryable = false)
                    webSocket.close(1008, "Authentication failed")
                }
                "result" -> completeRequest(message)
                "event" -> {
                    val event = message["event"] as? JsonObject
                    if ((event?.get("event_type") as? JsonPrimitive)?.contentOrNull == "state_changed") {
                        applyStateChange(event["data"] as? JsonObject)
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val message = t.message ?: "Network connection failed"
            failActiveSocket(webSocket, t, message)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket !== webSocket) return
            socket = null
            currentToken = null
            initialStatesRequestId = null
            stateSubscriptionRequestId = null
            _entities.value = emptyMap()
            _initialStatesLoaded.value = false
            val failure = IllegalStateException(reason.ifBlank { "Home Assistant closed the connection" })
            authResult?.complete(Result.failure(failure))
            authResult = null
            pending.values.forEach { it.complete(Result.failure(failure)) }
            pending.clear()
            if (_status.value !is ConnectionStatus.Failed) _status.value = ConnectionStatus.Disconnected
        }
    }

    private fun completeRequest(message: JsonObject) {
        val id = (message["id"] as? JsonPrimitive)?.longOrNull ?: return
        val result = pending.remove(id) ?: return
        if ((message["success"] as? JsonPrimitive)?.booleanOrNull == true) {
            if (id == initialStatesRequestId) {
                val states = message["result"] as? JsonArray
                if (states == null) {
                    val failure = IllegalStateException("Home Assistant returned invalid initial states")
                    result.complete(Result.failure(failure))
                    socket?.let { activeSocket ->
                        failActiveSocket(activeSocket, failure, "Could not load Home Assistant states")
                        activeSocket.cancel()
                    }
                    return
                }
                initialStatesRequestId = null
                replaceStates(states)
            } else if (id == stateSubscriptionRequestId) {
                stateSubscriptionRequestId = null
            }
            result.complete(Result.success(message))
        } else {
            val failure = IllegalStateException(message["error"]?.toString() ?: "Home Assistant rejected command")
            result.complete(Result.failure(failure))
            if (id == initialStatesRequestId || id == stateSubscriptionRequestId) {
                socket?.let { activeSocket ->
                    failActiveSocket(activeSocket, failure, "Could not initialize Home Assistant updates")
                    activeSocket.cancel()
                }
            }
        }
    }

    private fun failActiveSocket(
        webSocket: WebSocket,
        failure: Throwable,
        message: String,
        retryable: Boolean = true,
    ) {
        if (socket !== webSocket) return
        socket = null
        currentToken = null
        initialStatesRequestId = null
        stateSubscriptionRequestId = null
        _entities.value = emptyMap()
        _initialStatesLoaded.value = false
        _status.value = ConnectionStatus.Failed(message, retryable)
        authResult?.complete(Result.failure(failure))
        authResult = null
        pending.values.forEach { it.complete(Result.failure(failure)) }
        pending.clear()
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 10_000L
    }
}
