package dev.veilshare.ui.features

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

internal data class Calculation(val expression: String, val result: String)

/** Pure calculator state; never receives a vault credential or persists history. */
internal data class CalculatorState(
    val tokens: List<String> = emptyList(),
    val entry: String = "0",
    val completed: Boolean = false,
    val lastExpression: String? = null,
    val error: String? = null,
    val history: List<Calculation> = emptyList(),
) {
    val expression: String get() = lastExpression ?: (tokens + entry.takeIf { it.isNotEmpty() }.orEmpty()).joinToString(" ").trim()
    val display: String get() = error ?: entry.ifEmpty { tokens.dropLast(1).lastOrNull() ?: "0" }

    fun press(key: String): CalculatorState = when (key) {
        "AC" -> CalculatorState(history = history)
        "⌫" -> if (error != null || completed || entry.contains('E', true)) CalculatorState(history = history)
            else if (entry.isEmpty() && tokens.size >= 2) copy(tokens = tokens.dropLast(2), entry = tokens[tokens.size - 2])
            else copy(entry = entry.dropLast(1).let { if (it.isEmpty() || it == "-") "0" else it })
        "+", "−", "×", "÷" -> operator(key)
        "+/−" -> if (error != null || entry.isEmpty() || entry == "0") this else copy(entry = if (entry.startsWith('-')) entry.drop(1) else "-$entry", lastExpression = null, completed = false)
        "%" -> attempt {
            if (entry.isEmpty()) return@attempt this
            val value = number(entry) / 100.0
            val relative = if (tokens.lastOrNull() in listOf("+", "−")) evaluate(tokens.dropLast(1)) * value else value
            copy(entry = format(relative), completed = false, lastExpression = null)
        }
        "=" -> attempt {
            if (completed || entry.isEmpty()) return@attempt this
            val all = tokens + entry
            val result = format(evaluate(all))
            val previous = all.joinToString(" ")
            copy(tokens = emptyList(), entry = result, completed = true, lastExpression = "$previous =", history = (listOf(Calculation(previous, result)) + history).take(20))
        }
        else -> if (key == "." || (key.length == 1 && key[0] in '0'..'9')) digit(key) else this
    }

    fun recall(result: String): CalculatorState = CalculatorState(entry = result, history = history, completed = true)

    private fun digit(key: String): CalculatorState {
        val base = if (completed || error != null) CalculatorState(history = history) else this
        val old = base.entry
        if (old.length >= 16 || old.contains('E', true) || (key == "." && old.contains('.'))) return base
        val next = when {
            key == "." && old.isEmpty() -> "0."
            key != "." && old == "0" -> key
            else -> old + key
        }
        return base.copy(entry = next, lastExpression = null)
    }

    private fun operator(key: String): CalculatorState {
        if (error != null) return this
        if (entry.isEmpty()) return if (tokens.isEmpty()) this else copy(tokens = tokens.dropLast(1) + key)
        if (tokens.size >= 62) return this
        return copy(tokens = tokens + entry + key, entry = "", completed = false, lastExpression = null)
    }

    private inline fun attempt(block: () -> CalculatorState): CalculatorState {
        if (error != null) return this
        return try { block() } catch (_: IllegalArgumentException) { copy(error = "Error", lastExpression = null) }
    }
}

private fun number(value: String): Double = requireNotNull(value.toDoubleOrNull()).also { require(it.isFinite()) }

private fun evaluate(tokens: List<String>): Double {
    require(tokens.isNotEmpty() && tokens.size % 2 == 1)
    var total = 0.0
    var term = number(tokens.first())
    var sign = 1.0
    for (i in 1 until tokens.size step 2) {
        val right = number(tokens[i + 1])
        when (tokens[i]) {
            "×" -> term *= right
            "÷" -> { require(right != 0.0); term /= right }
            "+", "−" -> { total += sign * term; sign = if (tokens[i] == "+") 1.0 else -1.0; term = right }
            else -> throw IllegalArgumentException("Invalid operator")
        }
        require(total.isFinite() && term.isFinite())
    }
    return (total + sign * term).also { require(it.isFinite()) }
}

private fun format(value: Double): String {
    require(value.isFinite())
    if (value == 0.0) return "0"
    val magnitude = floor(log10(abs(value)))
    val scale = 10.0.pow(11.0 - magnitude)
    val rounded = if (scale.isFinite() && scale != 0.0 && (value * scale).isFinite()) round(value * scale) / scale else value
    return rounded.toString().removeSuffix(".0").replace(".0E", "E")
}
