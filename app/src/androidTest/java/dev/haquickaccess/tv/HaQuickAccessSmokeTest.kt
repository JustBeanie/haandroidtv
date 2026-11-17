package dev.haquickaccess.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isDisplayed
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.haquickaccess.tv.data.SettingsRepository
import dev.haquickaccess.tv.data.TokenCipher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test

class HaQuickAccessSmokeTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun setupScreenIsShownWithoutConfiguredConnection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        runBlocking { SettingsRepository(context, TokenCipher(), Json { ignoreUnknownKeys = true }).clearConnection() }
        ActivityScenario.launch(MainActivity::class.java).use {
            // DataStore loads asynchronously and is not a Compose idling resource.
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onNodeWithText("Connect Home Assistant").isDisplayed()
            }
            composeRule.onNodeWithText("Connect Home Assistant").assertIsDisplayed()
        }
    }
}
