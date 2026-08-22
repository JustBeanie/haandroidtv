package dev.haquickaccess.tv.domain.model

/**
 * Keeps discovery independent from the UI so every entity picker presents the
 * same predictable, TV-friendly results.
 */
object ControlBrowser {
    fun domains(entities: Collection<HaEntity>): List<String> =
        entities.asSequence()
            .map(HaEntity::domain)
            .distinct()
            .sortedBy(::domainLabel)
            .toList()

    fun filter(
        entities: Collection<HaEntity>,
        query: String,
        domain: String? = null,
    ): List<HaEntity> {
        val normalizedQuery = query.trim().lowercase()
        return entities.asSequence()
            .filter { domain == null || it.domain == domain }
            .filter { entity ->
                normalizedQuery.isBlank() ||
                    entity.name.lowercase().contains(normalizedQuery) ||
                    entity.entityId.lowercase().contains(normalizedQuery)
            }
            .sortedBy(HaEntity::name)
            .toList()
    }

    fun domainLabel(domain: String): String = when (domain) {
        "input_boolean" -> "Helpers"
        "alarm_control_panel" -> "Security"
        else -> domain
            .replace('_', ' ')
            .replaceFirstChar(Char::uppercase)
    }
}
