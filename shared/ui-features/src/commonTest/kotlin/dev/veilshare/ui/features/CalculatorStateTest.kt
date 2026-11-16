package dev.veilshare.ui.features

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalculatorStateTest {
    private fun run(vararg keys: String) = keys.fold(CalculatorState()) { s, k -> s.press(k) }

    @Test fun precedenceAndChain() {
        assertEquals("14", run("2", "+", "3", "×", "4", "=").display)
        assertEquals("20", run("2", "+", "3", "=", "×", "4", "=").display)
        assertEquals("6", run("2", "+", "×", "3", "=").display)
    }

    @Test fun decimalsAndSigns() {
        assertEquals("0.3", run("0", ".", "1", "+", "0", ".", "2", "=").display)
        assertEquals("-10", run("5", "+/−", "×", "2", "=").display)
        assertEquals("1.2", run("1", ".", ".", "2").display)
    }

    @Test fun percentages() {
        assertEquals("220", run("2", "0", "0", "+", "1", "0", "%", "=").display)
        assertEquals("180", run("2", "0", "0", "−", "1", "0", "%", "=").display)
        assertEquals("20", run("2", "0", "0", "×", "1", "0", "%", "=").display)
    }

    @Test fun recoverFromInvalidOperation() {
        assertEquals("Error", run("1", "÷", "0", "=").error)
        val recovered = run("1", "÷", "0", "=", "7")
        assertEquals("7", recovered.display)
        assertNull(recovered.error)
    }

    @Test fun editingAndHistory() {
        assertEquals("1", run("1", "2", "⌫").display)
        assertEquals("9", run("2", "+", "3", "=", "9").display)
        val state = run("2", "+", "3", "=", "AC")
        assertEquals("0", state.display)
        assertEquals(listOf(Calculation("2 + 3", "5")), state.history)
        assertEquals("5", state.recall("5").display)
    }

    @Test fun historyBoundedToSession() {
        var state = CalculatorState()
        repeat(30) { state = state.press("1").press("=").press("AC") }
        assertEquals(20, state.history.size)
        assertEquals(emptyList(), CalculatorState().history)
    }
}
