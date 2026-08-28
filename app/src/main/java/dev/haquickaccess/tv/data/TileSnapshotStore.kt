package dev.haquickaccess.tv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.tileSnapshotDataStore by preferencesDataStore(name = "ha_quick_access_tile_snapshot")

@Serializable
data class TileSnapshotEntry(
    val entityId: String,
    val name: String,
    val kind: String,
    val stateLabel: String,
    val active: Boolean,
    val unavailable: Boolean,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class TileSnapshot(
    @EncodeDefault
    val version: Int = CURRENT_VERSION,
    val capturedAtEpochMillis: Long,
    val tiles: List<TileSnapshotEntry>,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

interface TileSnapshotStore {
    val snapshot: Flow<TileSnapshot?>
    suspend fun save(snapshot: TileSnapshot)
    suspend fun clear()
}

class TileSnapshotCodec @Inject constructor(
    private val json: Json,
) {
    fun encode(snapshot: TileSnapshot): String {
        require(snapshot.version == TileSnapshot.CURRENT_VERSION) { "Unsupported tile snapshot version" }
        return json.encodeToString(snapshot)
    }

    fun decode(value: String): TileSnapshot? = runCatching {
        json.decodeFromString<TileSnapshot>(value)
    }.getOrNull()?.takeIf { it.version == TileSnapshot.CURRENT_VERSION }
}

object EmptyTileSnapshotStore : TileSnapshotStore {
    override val snapshot: Flow<TileSnapshot?> = MutableStateFlow(null)
    override suspend fun save(snapshot: TileSnapshot) = Unit
    override suspend fun clear() = Unit
}

@Singleton
class DataStoreTileSnapshotStore @Inject constructor(
    private val context: Context,
    private val codec: TileSnapshotCodec,
) : TileSnapshotStore {
    override val snapshot: Flow<TileSnapshot?> = context.tileSnapshotDataStore.data.map { preferences ->
        preferences[Keys.snapshot]?.let(codec::decode)
    }

    override suspend fun save(snapshot: TileSnapshot) {
        context.tileSnapshotDataStore.edit { preferences ->
            preferences[Keys.snapshot] = codec.encode(snapshot)
        }
    }

    override suspend fun clear() {
        context.tileSnapshotDataStore.edit { it.remove(Keys.snapshot) }
    }

    private object Keys {
        val snapshot = stringPreferencesKey("dashboard_tile_snapshot")
    }
}
