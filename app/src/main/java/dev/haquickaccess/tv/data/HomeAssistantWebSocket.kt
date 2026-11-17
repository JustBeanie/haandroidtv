package dev.haquickaccess.tv.data

import dev.haquickaccess.tv.domain.ServiceCall
import dev.haquickaccess.tv.domain.model.ControlKind
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.capabilities
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
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

/**
 * Session transitions are serialized by [lock]. Each Session is a generation
 * token, so an old callback or cancelled caller cannot finish a replacement.
 * No coroutine suspension or blocking network I/O occurs inside the lock.
 */
@Singleton
class HomeAssistantWebSocket private constructor(
    private val openWebSocket: (Request, WebSocketListener) -> WebSocket,
    private val json: Json,
) : HomeAssistantGateway {
    @Inject
    constructor(client: OkHttpClient, json: Json) : this(client::newWebSocket, json)

    internal constructor(json: Json, openWebSocket: (Request, WebSocketListener) -> WebSocket) :
        this(openWebSocket, json)

    private val lock = Any()
    private var active: Session? = null
    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    override val entities = _entities.asStateFlow()
    private val _initialStatesLoaded = MutableStateFlow(false)
    override val initialStatesLoaded = _initialStatesLoaded.asStateFlow()
    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val status = _status.asStateFlow()

    private class Session(var token: String?) {
        var socket: WebSocket? = null
        var version: String? = null
        var authenticated = false
        var nextId = 1L
        var subscriptionId: Long? = null
        var statesId: Long? = null
        val ready = CompletableDeferred<Result<Unit>>()
        val pending = mutableMapOf<Long, CompletableDeferred<Result<JsonObject>>>()
        val initialChanges = mutableMapOf<String, HaEntity?>()
    }

    override suspend fun connect(baseUrl: String, token: String): Result<Unit> {
        val session = Session(token)
        synchronized(lock) {
            disconnect()
            active = session
            _status.value = ConnectionStatus.Connecting
            try {
                val socket = openWebSocket(
                    Request.Builder().url(HomeAssistantProtocol.webSocketUrl(baseUrl)).build(),
                    Listener(session),
                )
                if (active === session) session.socket = socket else socket.cancel()
            } catch (exception: Exception) {
                finish(session, exception, "Could not connect to Home Assistant")
            }
        }
        return try {
            withTimeoutOrNull(CONNECTION_TIMEOUT_MS) { session.ready.await() }
                ?: synchronized(lock) {
                    val failure = IllegalStateException("Home Assistant synchronization timed out")
                    finish(session, failure, failure.message!!)
                    Result.failure(failure)
                }
        } catch (exception: CancellationException) {
            synchronized(lock) { finish(session, exception) }
            throw exception
        }
    }

    override suspend fun call(service: ServiceCall): Result<Unit> {
        val (session, id, result) = synchronized(lock) {
            val session = active
            if (session == null || !_initialStatesLoaded.value) {
                return Result.failure(IllegalStateException("Home Assistant is not ready"))
            }
            if (session.pending.size >= MAX_PENDING_REQUESTS) {
                return Result.failure(IllegalStateException("Too many pending commands; try again shortly"))
            }
            val (id, result) = send(session, "call_service", buildJsonObject {
                put("domain", service.domain)
                put("service", service.service)
                put("service_data", service.data)
            })
            Triple(session, id, result)
        }
        return try {
            withTimeoutOrNull(SERVICE_CALL_TIMEOUT_MS) { result.await() }?.map { Unit }
                ?: Result.failure(IllegalStateException("Home Assistant command timed out; check the device state"))
        } finally {
            synchronized(lock) { session.pending.remove(id) }
            result.cancel()
        }
    }

    override fun disconnect() = synchronized(lock) {
        active?.let { finish(it, IllegalStateException("Disconnected"), closeCode = 1000) }
        if (_status.value !is ConnectionStatus.Failed) _status.value = ConnectionStatus.Disconnected
    }

    private fun send(session: Session, type: String, extra: JsonObject = JsonObject(emptyMap())):
        Pair<Long, CompletableDeferred<Result<JsonObject>>> {
        val id = session.nextId++
        val result = CompletableDeferred<Result<JsonObject>>()
        session.pending[id] = result
        // Register before send, including transports that call back synchronously.
        if (type == "subscribe_events") session.subscriptionId = id
        if (type == "get_states") session.statesId = id
        val message = HomeAssistantProtocol.command(id, type, extra)
        if (session.socket?.send(json.encodeToString(JsonObject.serializer(), message)) != true) {
            finish(session, IllegalStateException("Connection lost"), "Home Assistant connection lost")
        }
        return id to result
    }

    private inner class Listener(private val session: Session) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.length > MAX_MESSAGE_LENGTH) {
                synchronized(lock) {
                    finish(session, IllegalArgumentException("Message too large"), "Home Assistant sent an oversized message", retryable = false)
                }
                return
            }
            val message = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
            synchronized(lock) {
                if (active !== session) return
                session.socket = webSocket
                when (message.string("type")) {
                    "auth_required" -> {
                        if (session.authenticated) return
                        if (!webSocket.send(json.encodeToString(JsonObject.serializer(), HomeAssistantProtocol.authMessage(session.token.orEmpty())))) {
                            finish(session, IllegalStateException("Authentication send failed"), "Home Assistant connection lost")
                        }
                    }
                    "auth_ok" -> {
                        if (session.authenticated) return
                        session.authenticated = true
                        session.token = null
                        session.version = message.string("ha_version")
                        // Subscribe before the snapshot and retain intervening changes.
                        send(session, "subscribe_events", buildJsonObject { put("event_type", "state_changed") })
                    }
                    "auth_invalid" -> finish(session, SecurityException("Token rejected"), "Authentication failed", false, 1008)
                    "result" -> completeRequest(session, message)
                    "event" -> {
                        if (!session.authenticated) return
                        val event = message["event"] as? JsonObject
                        if (event?.string("event_type") == "state_changed") {
                            val data = event["data"] as? JsonObject ?: return
                            val id = data.string("entity_id") ?: return
                            val entity = (data["new_state"] as? JsonObject)?.let(HomeAssistantProtocol::entityFromJson)
                                ?.takeIf { it.capabilities().kind != ControlKind.UNSUPPORTED }
                            if (!_initialStatesLoaded.value) {
                                if (id !in session.initialChanges && session.initialChanges.size >= MAX_INITIAL_CHANGES) {
                                    finish(session, IllegalStateException("Too many initial changes"), "Home Assistant is busy; reconnecting")
                                    return
                                }
                                session.initialChanges[id] = entity
                            } else updateEntity(id, entity)
                        }
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) { finish(session, t, "Home Assistant connection lost") }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) { finish(session, IllegalStateException("Connection closed"), closeCode = 1000) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) { finish(session, IllegalStateException("Connection closed")) }
        }
    }

    private fun completeRequest(session: Session, message: JsonObject) {
        val id = (message["id"] as? JsonPrimitive)?.longOrNull ?: return
        val result = session.pending.remove(id) ?: return
        if ((message["success"] as? JsonPrimitive)?.booleanOrNull != true) {
            val failure = IllegalStateException("Home Assistant rejected the request")
            result.complete(Result.failure(failure))
            if (id == session.statesId || id == session.subscriptionId) {
                finish(session, failure, "Could not initialize Home Assistant updates")
            }
            return
        }
        when (id) {
            session.subscriptionId -> {
                session.subscriptionId = null
                send(session, "get_states")
            }
            session.statesId -> {
                session.statesId = null
                val states = message["result"] as? JsonArray
                if (states == null) {
                    val failure = IllegalStateException("Invalid initial states")
                    result.complete(Result.failure(failure))
                    finish(session, failure, "Could not load Home Assistant states")
                    return
                }
                val entities = states.mapNotNull(HomeAssistantProtocol::entityFromJson)
                    .filter { it.capabilities().kind != ControlKind.UNSUPPORTED }
                    .associateBy(HaEntity::entityId).toMutableMap()
                session.initialChanges.forEach { (id, entity) -> if (entity == null) entities.remove(id) else entities[id] = entity }
                session.initialChanges.clear()
                _entities.value = entities
                _initialStatesLoaded.value = true
                _status.value = ConnectionStatus.Connected(session.version)
                session.ready.complete(Result.success(Unit))
            }
        }
        result.complete(Result.success(message))
    }

    private fun updateEntity(id: String, entity: HaEntity?) {
        val current = _entities.value
        if (current[id] == entity) return
        _entities.value = if (entity == null) current - id else current + (id to entity)
    }

    private fun finish(session: Session, failure: Throwable, message: String? = null, retryable: Boolean = true, closeCode: Int? = null) {
        if (active !== session) return
        active = null
        session.token = null
        session.initialChanges.clear()
        session.ready.complete(Result.failure(failure))
        session.pending.values.forEach { it.complete(Result.failure(failure)) }
        session.pending.clear()
        _entities.value = emptyMap()
        _initialStatesLoaded.value = false
        _status.value = if (message == null) ConnectionStatus.Disconnected else ConnectionStatus.Failed(message, retryable)
        val socket = session.socket
        session.socket = null
        if (closeCode != null) socket?.close(closeCode, "Session ended") else socket?.cancel()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 10_000L
        // get_states includes every domain, even those this client discards. A
        // real 3,500-entity installation already exceeds 1 MiB. Keep parsing
        // bounded while allowing room for larger homes and attribute payloads.
        const val MAX_MESSAGE_LENGTH = 8 * 1_048_576
        const val MAX_PENDING_REQUESTS = 64
        const val MAX_INITIAL_CHANGES = 4096
    }
}
