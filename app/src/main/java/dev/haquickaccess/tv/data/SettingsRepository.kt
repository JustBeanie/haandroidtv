package dev.haquickaccess.tv.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.haquickaccess.tv.domain.model.ShortcutConfiguration
import dev.haquickaccess.tv.domain.model.ShortcutBehavior
import dev.haquickaccess.tv.domain.model.TileConfiguration
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(name = "ha_quick_access")

data class AppSettings(
    val baseUrl: String? = null,
    val tokenEnvelope: String? = null,
    val tiles: List<TileConfiguration> = emptyList(),
    val homeShortcuts: List<ShortcutConfiguration> = emptyList(),
    val homeChannelEnabled: Boolean = false,
    val channelId: Long? = null,
    val lastFocusedEntityId: String? = null,
)

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun saveConnection(baseUrl: String, token: String)
    fun decryptToken(settings: AppSettings): String?
    suspend fun saveTiles(tiles: List<TileConfiguration>)
    suspend fun saveShortcuts(shortcuts: List<ShortcutConfiguration>)
    suspend fun setHomeChannel(enabled: Boolean, channelId: Long? = null)
    suspend fun saveLastFocusedEntity(entityId: String)
    suspend fun clearConnection()
}

@Singleton
class SettingsRepository @Inject constructor(
    private val context: Context,
    private val cipher: TokenCipher,
    private val json: Json,
) : SettingsStore {
    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            baseUrl = preferences[Keys.baseUrl],
            tokenEnvelope = preferences[Keys.tokenEnvelope],
            tiles = preferences[Keys.tiles]?.let { decodeTiles(it) }.orEmpty(),
            homeShortcuts = preferences[Keys.shortcuts]?.let { decodeShortcuts(it) }.orEmpty(),
            homeChannelEnabled = preferences[Keys.homeChannelEnabled] ?: false,
            channelId = preferences[Keys.channelId],
            lastFocusedEntityId = preferences[Keys.lastFocusedEntityId],
        )
    }

    override suspend fun saveConnection(baseUrl: String, token: String) {
        val normalizedUrl = UrlValidator.normalize(baseUrl).getOrThrow()
        require(token.isNotBlank()) { "Home Assistant token cannot be blank" }
        require(token.length <= MAX_TOKEN_LENGTH) { "Home Assistant token is too long" }
        val encryptedToken = cipher.encrypt(token)
        context.settingsDataStore.edit {
            it[Keys.baseUrl] = normalizedUrl
            it[Keys.tokenEnvelope] = encryptedToken
        }
    }

    suspend fun applyConfiguration(configuration: AdbConfiguration) {
        AdbConfigurationValidator.validate(configuration)
        val normalizedUrl = configuration.baseUrl?.let { UrlValidator.normalize(it).getOrThrow() }
        val encryptedToken = configuration.token?.let { token ->
            require(token.isNotBlank()) { "Home Assistant token cannot be blank" }
            require(token.length <= MAX_TOKEN_LENGTH) { "Home Assistant token is too long" }
            cipher.encrypt(token)
        }
        val tiles = configuration.tiles?.mapIndexed { position, entityId ->
            TileConfiguration(entityId, position)
        }
        val shortcuts = configuration.shortcuts?.map { shortcut ->
            ShortcutConfiguration(
                entityId = shortcut.entityId,
                behavior = ShortcutBehavior.valueOf(shortcut.behavior.uppercase(Locale.ROOT)),
            )
        }

        context.settingsDataStore.edit {
            if (normalizedUrl != null) it[Keys.baseUrl] = normalizedUrl
            if (encryptedToken != null) it[Keys.tokenEnvelope] = encryptedToken
            if (tiles != null) it[Keys.tiles] = json.encodeToString(tiles)
            if (shortcuts != null) it[Keys.shortcuts] = json.encodeToString(shortcuts)
            configuration.homeChannelEnabled?.let { enabled ->
                it[Keys.homeChannelEnabled] = enabled
                if (!enabled) it.remove(Keys.channelId)
            }
        }
    }

    override fun decryptToken(settings: AppSettings): String? = settings.tokenEnvelope?.let(cipher::decrypt)

    override suspend fun saveTiles(tiles: List<TileConfiguration>) {
        context.settingsDataStore.edit { it[Keys.tiles] = json.encodeToString(tiles.sortedBy(TileConfiguration::position)) }
    }

    override suspend fun saveShortcuts(shortcuts: List<ShortcutConfiguration>) {
        context.settingsDataStore.edit { it[Keys.shortcuts] = json.encodeToString(shortcuts.take(4)) }
    }

    override suspend fun setHomeChannel(enabled: Boolean, channelId: Long?) {
        context.settingsDataStore.edit {
            it[Keys.homeChannelEnabled] = enabled
            if (channelId == null) it.remove(Keys.channelId) else it[Keys.channelId] = channelId
        }
    }

    override suspend fun saveLastFocusedEntity(entityId: String) {
        context.settingsDataStore.edit { it[Keys.lastFocusedEntityId] = entityId }
    }

    override suspend fun clearConnection() {
        cipher.clear()
        context.settingsDataStore.edit { it.clear() }
    }

    private fun decodeTiles(value: String): List<TileConfiguration> = runCatching {
        json.decodeFromString<List<TileConfiguration>>(value)
    }.getOrDefault(emptyList())

    private fun decodeShortcuts(value: String): List<ShortcutConfiguration> = runCatching {
        json.decodeFromString<List<ShortcutConfiguration>>(value)
    }.getOrDefault(emptyList())

    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val tokenEnvelope = stringPreferencesKey("token_envelope")
        val tiles = stringPreferencesKey("tiles")
        val shortcuts = stringPreferencesKey("shortcuts")
        val homeChannelEnabled = booleanPreferencesKey("home_channel_enabled")
        val channelId = longPreferencesKey("home_channel_id")
        val lastFocusedEntityId = stringPreferencesKey("last_focused_entity")
    }

    private companion object {
        const val MAX_TOKEN_LENGTH = 4096
    }
}
