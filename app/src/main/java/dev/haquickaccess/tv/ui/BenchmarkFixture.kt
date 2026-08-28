package dev.haquickaccess.tv.ui

import dev.haquickaccess.tv.data.AppSettings
import dev.haquickaccess.tv.data.ConnectionStatus
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.TileConfiguration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object BenchmarkFixture {
    fun dashboardState(tileCount: Int = 30): DashboardUiState {
        val entities = (1..tileCount).associate { index ->
            val domain = when (index % 5) {
                0 -> "switch"
                1 -> "light"
                2 -> "fan"
                3 -> "scene"
                else -> "input_boolean"
            }
            val entityId = "$domain.benchmark_$index"
            val attributes = buildMap {
                put("friendly_name", JsonPrimitive("Benchmark control $index"))
                if (domain == "light") put("brightness", JsonPrimitive((index * 17) % 255))
                if (domain == "fan") put("percentage", JsonPrimitive((index * 10) % 100))
            }
            entityId to HaEntity(
                entityId = entityId,
                state = if (index % 2 == 0) "on" else "off",
                attributes = JsonObject(attributes),
            )
        }
        return DashboardUiState(
            settings = AppSettings(
                baseUrl = "https://benchmark.invalid",
                tokenEnvelope = "benchmark-only",
                tiles = entities.keys.mapIndexed { index, entityId -> TileConfiguration(entityId, index) },
            ),
            isSettingsLoaded = true,
            entities = entities,
            areInitialStatesLoaded = true,
            connectionStatus = ConnectionStatus.Connected("benchmark"),
        )
    }
}
