package dev.haquickaccess.tv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TileSnapshotStoreInstrumentedTest {
    @Test
    fun snapshot_round_trips_in_private_storage_and_can_be_cleared() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val store = DataStoreTileSnapshotStore(
            context,
            TileSnapshotCodec(Json { ignoreUnknownKeys = true; explicitNulls = false }),
        )
        val expected = TileSnapshot(
            capturedAtEpochMillis = 1234L,
            tiles = listOf(
                TileSnapshotEntry(
                    entityId = "light.den",
                    name = "Den",
                    kind = "LIGHT",
                    stateLabel = "On",
                    active = true,
                    unavailable = false,
                ),
            ),
        )

        store.clear()
        store.save(expected)
        assertEquals(expected, store.snapshot.first())

        store.clear()
        assertNull(store.snapshot.first())
    }
}
