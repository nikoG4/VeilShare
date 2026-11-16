package dev.veilshare.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class CalculatorEntrySmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun calculatorStartsAndCalculatesBeforeAuthentication() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("calculator_screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("calculator_screen").assertIsDisplayed()
        for (key in listOf("2", "+", "3", "=")) {
            compose.onNodeWithTag("calc_key_$key").performClick()
        }
        compose.onNodeWithTag("calc_display").assertTextEquals("5")
        compose.onNodeWithTag("calc_menu").performClick()
        compose.onNodeWithTag("calc_private").performClick()
        compose.onNodeWithTag("calc_back").assertIsDisplayed().performClick()
        compose.onNodeWithTag("calc_display").assertTextEquals("0")
    }
}
