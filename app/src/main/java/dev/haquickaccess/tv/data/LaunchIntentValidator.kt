package dev.haquickaccess.tv.data

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import dev.haquickaccess.tv.domain.model.homeAssistantEntityIdPattern

data class LaunchRequest(
    val entityId: String,
    val behavior: String,
)

/** Validates launcher/provider input before it reaches the view model. */
object LaunchIntentValidator {
    private val allowedBehaviors = setOf("toggle", "focus", "details")

    fun parse(rawUri: String?): LaunchRequest? {
        val uri = runCatching { rawUri?.let(::URI) }.getOrNull() ?: return null
        if (uri.scheme != "haquickaccess" || uri.host != "control" || uri.userInfo != null) return null
        if (uri.rawPath?.startsWith("/") != true) return null
        val pathSegments = uri.rawPath.removePrefix("/").split('/')
        if (pathSegments.size != 1 || pathSegments.single().isBlank()) return null
        val entityId = decode(pathSegments.single())
        if (!homeAssistantEntityIdPattern.matches(entityId)) return null
        if (uri.rawFragment != null) return null
        val behavior = queryParameter(uri.rawQuery, "behavior")?.lowercase() ?: "details"
        if (behavior !in allowedBehaviors) return null
        return LaunchRequest(entityId, behavior)
    }

    private fun queryParameter(query: String?, name: String): String? = query
        ?.split('&')
        ?.asSequence()
        ?.map { it.split('=', limit = 2) }
        ?.firstOrNull { it.firstOrNull() == name }
        ?.getOrNull(1)
        ?.let(::decode)

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}
