package dev.veilshare.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test

/** Run on a fresh installation in the dedicated CI emulator. Does not delete app data. */
class CalculatorEntrySmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun firstLaunchSetsUpOnceThenCalculatorStartsBeforeAuthentication() {
        waitForTag("initial_setup")
        compose.onNodeWithTag("initial_setup").assertIsDisplayed()
        compose.onNodeWithTag("calculator_screen").assertDoesNotExist()
        for ((tag, code) in listOf(
            "setup_primary" to "510123",
            "setup_primary_confirm" to "510123",
            "setup_alternate" to "520234",
            "setup_alternate_confirm" to "520234",
        )) {
            compose.onNodeWithTag(tag).performScrollTo().performTextInput(code)
        }
        compose.onNodeWithTag("setup_submit").performScrollTo().performClick()
        waitForTag("calculator_screen", 90_000)
        compose.onNodeWithTag("initial_setup").assertDoesNotExist()

        // Storage, not transient UI state, must decide what happens after recreation.
        compose.activityRule.scenario.recreate()
        waitForTag("calculator_screen")
        compose.onNodeWithTag("initial_setup").assertDoesNotExist()
        compose.onNodeWithTag("calculator_screen").assertIsDisplayed()
        for (key in listOf("2", "+", "3", "=")) {
            compose.onNodeWithTag("calc_key_$key").performClick()
        }
        compose.onNodeWithTag("calc_display").assertTextEquals("5")
        compose.onNodeWithTag("calc_menu").performClick()
        compose.onNodeWithText("Configurar espacio privado").assertDoesNotExist()
        compose.onNodeWithTag("calc_private").performClick()
        compose.onNodeWithTag("unlock_input").assertIsDisplayed()
        compose.onNodeWithTag("calc_back").assertIsDisplayed().performClick()
        compose.onNodeWithTag("calc_display").assertTextEquals("0")
        compose.onNodeWithTag("calc_menu").performClick()
        compose.onNodeWithTag("calc_private").performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForTag("calculator_screen")
        compose.onNodeWithTag("calc_display").assertTextEquals("0")
        compose.onNodeWithTag("initial_setup").assertDoesNotExist()
    }

    private fun waitForTag(tag: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
