package dev.haquickaccess.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class HaQuickAccessSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun setupScreenIsShownWithoutConfiguredConnection() {
        composeRule.onNodeWithText("Connect Home Assistant").assertIsDisplayed()
    }
}
