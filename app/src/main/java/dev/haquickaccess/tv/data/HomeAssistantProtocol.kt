package dev.haquickaccess.tv.data

import dev.haquickaccess.tv.domain.model.HaEntity
import java.net.URI
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pure Home Assistant WebSocket payload and entity mapping functions. */
object HomeAssistantProtocol {
    fun webSocketUrl(baseUrl: String): String {
        val base = URI(baseUrl)
        val path = base.path.orEmpty().trimEnd('/')
        return URI("wss", null, base.host, base.port, "$path/api/websocket", null, null).toString()
    }

    fun authMessage(token: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("auth"))
        put("access_token", JsonPrimitive(token))
    }

    fun command(id: Long, type: String, extra: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("type", JsonPrimitive(type))
        extra.forEach { (key, value) -> put(key, value) }
    }

    fun entityFromJson(value: JsonElement): HaEntity? {
        val objectValue = value as? JsonObject ?: return null
        val id = objectValue["entity_id"]?.jsonPrimitive?.content ?: return null
        val state = objectValue["state"]?.jsonPrimitive?.content ?: return null
        return HaEntity(id, state, objectValue["attributes"] as? JsonObject ?: JsonObject(emptyMap()))
    }
}
