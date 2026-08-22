package dev.haquickaccess.tv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TileConfiguration(
    val entityId: String,
    val position: Int,
)

@Serializable
data class ShortcutConfiguration(
    val entityId: String,
    val behavior: ShortcutBehavior,
)

@Serializable
enum class ShortcutBehavior {
    TOGGLE,
    FOCUS,
    DETAILS,
}
