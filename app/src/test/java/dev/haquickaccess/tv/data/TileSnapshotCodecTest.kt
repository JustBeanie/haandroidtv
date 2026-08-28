package dev.haquickaccess.tv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class TileSnapshotCodecTest {
    private val codec = TileSnapshotCodec(Json { ignoreUnknownKeys = true; explicitNulls = false })

    @Test
    fun roundTripPreservesOnlyDisplayFields() {
        val snapshot = snapshot()

        val encoded = codec.encode(snapshot)
        val decoded = codec.decode(encoded)

        assertEquals(snapshot, decoded)
        assertTrue(encoded.contains("Kitchen light"))
        assertFalse(encoded.contains("token", ignoreCase = true))
        assertFalse(encoded.contains("https://"))
        assertFalse(encoded.contains("attributes"))
        assertFalse(encoded.contains("alarm", ignoreCase = true))
    }

    @Test
    fun corruptSnapshotFallsBackToNull() {
        assertNull(codec.decode("not-json"))
    }

    @Test
    fun unknownVersionIsIgnored() {
        val encoded = codec.encode(snapshot()).replace("\"version\":1", "\"version\":99")

        assertNull(codec.decode(encoded))
    }

    @Test
    fun unsupportedVersionCannotBeWritten() {
        assertFailsWith<IllegalArgumentException> { codec.encode(snapshot().copy(version = 2)) }
    }

    private fun snapshot() = TileSnapshot(
        capturedAtEpochMillis = 1234L,
        tiles = listOf(
            TileSnapshotEntry(
                entityId = "light.kitchen",
                name = "Kitchen light",
                kind = "LIGHT",
                stateLabel = "72%",
                active = true,
                unavailable = false,
            ),
        ),
    )
}
