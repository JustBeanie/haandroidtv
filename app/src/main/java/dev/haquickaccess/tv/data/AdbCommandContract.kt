package dev.haquickaccess.tv.data

import dev.haquickaccess.tv.domain.model.homeAssistantEntityIdPattern
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Public command names and payloads for the device-local ADB integration. */
object AdbCommandContract {
    const val ACTION_CONFIGURE = "dev.haquickaccess.tv.action.CONFIGURE"
    const val ACTION_CLEAR_CONFIGURATION = "dev.haquickaccess.tv.action.CLEAR_CONFIGURATION"
    const val ACTION_QUERY = "dev.haquickaccess.tv.action.QUERY"
    const val ACTION_CONTROL = "dev.haquickaccess.tv.action.CONTROL"

    const val EXTRA_CONFIG_FILE = "dev.haquickaccess.tv.extra.CONFIG_FILE"
    const val EXTRA_ENTITY_ID = "dev.haquickaccess.tv.extra.ENTITY_ID"
    const val EXTRA_BEHAVIOR = "dev.haquickaccess.tv.extra.BEHAVIOR"

    const val CONFIG_FILE_NAME = "ha-quick-access-config.json"
    const val RESULT_OK = 0
    const val RESULT_INVALID_COMMAND = 2
    const val RESULT_FAILED = 1
}

/**
 * A file-import payload. Nullable fields mean "leave this setting unchanged";
 * an empty list explicitly replaces the corresponding list with no entries.
 */
@Serializable
data class AdbConfiguration(
    @SerialName("base_url") val baseUrl: String? = null,
    val token: String? = null,
    val tiles: List<String>? = null,
    val shortcuts: List<AdbShortcut>? = null,
    @SerialName("home_channel_enabled") val homeChannelEnabled: Boolean? = null,
)

@Serializable
data class AdbShortcut(
    @SerialName("entity_id") val entityId: String,
    val behavior: String,
)

@Serializable
data class AdbQueryResponse(
    @SerialName("base_url") val baseUrl: String?,
    @SerialName("token_configured") val tokenConfigured: Boolean,
    val tiles: List<String>,
    val shortcuts: List<AdbShortcut>,
    @SerialName("home_channel_enabled") val homeChannelEnabled: Boolean,
)

/** Validation shared by the receiver and the repository before any write. */
object AdbConfigurationValidator {
    private val allowedBehaviors = setOf("toggle", "focus", "details")

    fun validate(configuration: AdbConfiguration) {
        require(configuration.hasChanges()) { "Configuration file contains no settings" }
        require((configuration.baseUrl == null) == (configuration.token == null)) {
            "base_url and token must be supplied together"
        }
        configuration.token?.let { token ->
            require(token.isNotBlank()) { "Token cannot be blank" }
            require(token.length <= MAX_TOKEN_LENGTH) { "Token is too long" }
        }
        configuration.tiles?.let { tiles ->
            require(tiles.size <= MAX_TILES) { "Too many dashboard tiles" }
            require(tiles.distinct().size == tiles.size) { "Dashboard tiles must be unique" }
            tiles.forEach(::requireEntityId)
        }
        configuration.shortcuts?.let { shortcuts ->
            require(shortcuts.size <= MAX_SHORTCUTS) { "Too many Home screen shortcuts" }
            require(shortcuts.map(AdbShortcut::entityId).distinct().size == shortcuts.size) {
                "Home screen shortcuts must be unique"
            }
            shortcuts.forEach { shortcut ->
                requireEntityId(shortcut.entityId)
                require(shortcut.behavior.lowercase(Locale.ROOT) in allowedBehaviors) {
                    "Unsupported shortcut behavior"
                }
            }
        }
    }

    private fun requireEntityId(entityId: String) {
        require(homeAssistantEntityIdPattern.matches(entityId)) { "Invalid Home Assistant entity ID" }
    }

    private fun AdbConfiguration.hasChanges(): Boolean =
        baseUrl != null || token != null || tiles != null || shortcuts != null || homeChannelEnabled != null

    private const val MAX_TOKEN_LENGTH = 4_096
    private const val MAX_TILES = 100
    private const val MAX_SHORTCUTS = 4
}
