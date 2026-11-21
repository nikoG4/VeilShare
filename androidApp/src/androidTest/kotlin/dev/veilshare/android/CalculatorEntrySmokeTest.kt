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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import dev.veilshare.ui.features.DecoyKind
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
        waitForTag("vault_workspace", 90_000)
        compose.onNodeWithTag("vault_empty_files").assertIsDisplayed()
        // Settings switch must change the Android window flag while authenticated.
        compose.onNodeWithTag("vault_appearance").performClick()
        compose.onNodeWithTag("allow_screen_captures").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("allow_screen_captures").performClick()
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            org.junit.Assert.assertEquals(
                0,
                activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        compose.onNodeWithTag("allow_screen_captures").performClick()
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            org.junit.Assert.assertTrue(
                activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
        compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithTag("vault_nav_gallery").performClick()
        compose.onNodeWithTag("vault_empty_gallery").assertIsDisplayed()
        compose.onNodeWithTag("vault_nav_contacts").performClick()
        compose.onNodeWithTag("vault_empty_contacts").assertIsDisplayed()
        compose.onNodeWithTag("vault_lock").assertIsDisplayed()
        compose.onNodeWithTag("vault_nav_files").performClick()
        compose.onNodeWithText("Nueva carpeta").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Solo primaria")
        compose.onNodeWithText("Crear").performClick()
        waitForText("Solo primaria")
        compose.onNodeWithText("Bloquear").performClick()
        waitForTag("calculator_screen")

        enterPin("520234")
        waitForTag("vault_workspace", 90_000)
        compose.onNodeWithTag("vault_empty_files").assertIsDisplayed()
        compose.onNodeWithText("Solo primaria").assertDoesNotExist()
        compose.onNodeWithTag("vault_nav_gallery").performClick()
        compose.onNodeWithTag("vault_lock").performClick()
        waitForTag("calculator_screen")
        enterPin("051123")
        waitForText("Solo primaria", 90_000)

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForTag("calculator_screen")
        compose.onNodeWithTag("calc_display").assertTextEquals("0")
        compose.onNodeWithTag("initial_setup").assertDoesNotExist()

        // Each discreet entry point must authenticate through the existing controller.
        // Verify both vault credentials and that the primary-only folder never leaks.
        // Restore the original launcher selection even when an assertion fails.
        val store = AndroidDecoyStore(compose.activityRule.scenario.let {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        })
        val original = store.preferences.value
        try {
            for (kind in listOf(DecoyKind.Calendar, DecoyKind.Notes, DecoyKind.Clock, DecoyKind.Converter)) {
                org.junit.Assert.assertTrue("Select $kind", store.select(original.copy(kind = kind)))
                compose.activityRule.scenario.recreate()
                waitForTag("decoy_" + kind.name.lowercase())
                compose.onNodeWithTag("unlock_input").assertDoesNotExist()
                compose.onNodeWithTag("tool_title").performTouchInput { longClick() }
                waitForTag("unlock_input")
                compose.onNodeWithTag("unlock_input").performTextInput("520234")
                compose.onNodeWithTag("unlock_submit").performClick()
                waitForTag("vault_workspace", 90_000)
                compose.onNodeWithText("Solo primaria").assertDoesNotExist()
                compose.onNodeWithTag("vault_lock").performClick()
                waitForTag("decoy_" + kind.name.lowercase())
                compose.onNodeWithTag("unlock_input").assertDoesNotExist()

                compose.onNodeWithTag("tool_title").performTouchInput { longClick() }
                waitForTag("unlock_input")
                compose.onNodeWithTag("unlock_input").performTextInput("051123")
                compose.onNodeWithTag("unlock_submit").performClick()
                waitForText("Solo primaria", 90_000)
                compose.onNodeWithTag("vault_lock").performClick()
                waitForTag("decoy_" + kind.name.lowercase())
            }
        } finally {
            org.junit.Assert.assertTrue("Restore original appearance", store.select(original))
        }
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
