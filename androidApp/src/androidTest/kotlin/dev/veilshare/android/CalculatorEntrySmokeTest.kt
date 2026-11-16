package dev.veilshare.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
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
            "setup_primary" to "051123",
            "setup_primary_confirm" to "051123",
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
        compose.onNodeWithText("Espacio privado").assertDoesNotExist()
        compose.onNodeWithText("Tema del sistema").performClick()

        // Wrong PIN stays in Calc; it never becomes history or a visible vault prompt.
        enterPin("999999")
        compose.waitUntil(60_000) {
            compose.onAllNodesWithTag("calculator_screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("calc_display").assertTextEquals("0")
        compose.onNodeWithTag("unlock_input").assertDoesNotExist()

        // Retry after the KDF has finished. Use the same controller for both credentials.
        enterPin("051123")
        waitForText("Nueva carpeta", 90_000)
        compose.onNodeWithText("Nueva carpeta").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Solo primaria")
        compose.onNodeWithText("Crear").performClick()
        waitForText("Solo primaria")
        compose.onNodeWithText("Bloquear").performClick()
        waitForTag("calculator_screen")

        enterPin("520234")
        waitForText("Nueva carpeta", 90_000)
        compose.onNodeWithText("Solo primaria").assertDoesNotExist()
        compose.onNodeWithText("Bloquear").performClick()
        waitForTag("calculator_screen")
        enterPin("051123")
        waitForText("Solo primaria", 90_000)

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForTag("calculator_screen")
        compose.onNodeWithTag("calc_display").assertTextEquals("0")
        compose.onNodeWithTag("initial_setup").assertDoesNotExist()
    }

    private fun enterPin(pin: String) {
        compose.onNodeWithTag("calc_key_AC").performClick()
        for (digit in pin) compose.onNodeWithTag("calc_key_$digit").performClick()
        compose.onNodeWithTag("calc_key_=").performClick()
    }

    private fun waitForText(text: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTag(tag: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
