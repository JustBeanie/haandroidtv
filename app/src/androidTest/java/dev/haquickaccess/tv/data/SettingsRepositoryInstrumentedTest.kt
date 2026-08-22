package dev.haquickaccess.tv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryInstrumentedTest {
    @Test
    fun token_is_encrypted_at_rest_and_removed_with_the_connection() = runBlocking {
        val repository = SettingsRepository(
            context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            cipher = TokenCipher(),
            json = Json { ignoreUnknownKeys = true; explicitNulls = false },
        )
        repository.clearConnection()

        repository.saveConnection("https://ha.example/", "long-lived-token")
        val saved = repository.settings.first()

        assertEquals("https://ha.example", saved.baseUrl)
        assertNotEquals("long-lived-token", saved.tokenEnvelope)
        assertEquals("long-lived-token", repository.decryptToken(saved))

        repository.clearConnection()
        val cleared = repository.settings.first()
        assertNull(cleared.baseUrl)
        assertNull(cleared.tokenEnvelope)
    }
}
