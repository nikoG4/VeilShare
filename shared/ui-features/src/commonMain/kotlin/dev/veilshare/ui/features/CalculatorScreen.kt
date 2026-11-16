package dev.veilshare.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Calculator is the public workspace after initial setup. Authentication is separate. */
@Composable
internal fun CalculatorEntryHost(
    root: RootState.Locked,
    controller: LocalAppController,
    entryResetGeneration: Long,
) {
    var privateOpen by remember(entryResetGeneration) { mutableStateOf(false) }
    val busy = root.busy
    if (privateOpen) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TextButton(
                onClick = { privateOpen = false },
                enabled = !busy,
                modifier = Modifier.padding(horizontal = 12.dp).testTag("calc_back"),
            ) { Text("‹  Calculadora") }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                QuickUnlockScreen(root, controller)
            }
        }
    } else {
        CalculatorScreen(onOpenPrivate = { privateOpen = true })
    }
}

private data class CalcPalette(
    val background: Color,
    val display: Color,
    val key: Color,
    val utility: Color,
    val operator: Color,
    val accent: Color,
    val accentText: Color,
    val primary: Color,
    val secondary: Color,
)

private val Night = CalcPalette(
    Color(0xFF111317), Color(0xFF1B1E25), Color(0xFF252932), Color(0xFF333944),
    Color(0xFF313D55), Color(0xFFA9C3FF), Color(0xFF14284D), Color(0xFFF3F5FA), Color(0xFFB2BAC9),
)
private val Day = CalcPalette(
    Color(0xFFF4F6FA), Color(0xFFE7EBF3), Color(0xFFFFFFFF), Color(0xFFDCE2EC),
    Color(0xFFDCE8FF), Color(0xFF315DD4), Color.White, Color(0xFF172035), Color(0xFF505E74),
)

private val Keys = listOf(
    listOf("AC", "+/−", "%", "÷"),
    listOf("7", "8", "9", "×"),
    listOf("4", "5", "6", "−"),
    listOf("1", "2", "3", "+"),
    listOf("⌫", "0", ".", "="),
)

@Composable
internal fun CalculatorScreen(onOpenPrivate: () -> Unit) {
    // Do not save this state across process death or the authentication boundary.
    var state by remember { mutableStateOf(CalculatorState()) }
    var appearance by remember { mutableStateOf<Boolean?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    val dark = appearance ?: isSystemInDarkTheme()
    val p = if (dark) Night else Day
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.accentText, surface = p.display, onSurface = p.primary,
        onSurfaceVariant = p.secondary, background = p.background, onBackground = p.primary,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.accentText, surface = p.display, onSurface = p.primary,
        onSurfaceVariant = p.secondary, background = p.background, onBackground = p.primary,
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(color = p.background, modifier = Modifier.fillMaxSize().testTag("calculator_screen")) {
            BoxWithConstraints(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val wide = maxWidth >= 720.dp
                val keyHeight = ((maxHeight - if (wide) 72.dp else 225.dp) / 5).coerceIn(48.dp, 86.dp)
                Column(
                    Modifier.widthIn(max = if (wide) 980.dp else 500.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Calc", color = p.primary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                            Text("Calculadora", color = p.secondary, fontSize = 13.sp)
                        }
                        TextButton(
                            onClick = { historyOpen = true },
                            modifier = Modifier.testTag("calc_history"),
                        ) { Text("Historial", color = p.secondary) }
                        Box {
                            TextButton(
                                onClick = { menuOpen = true },
                                modifier = Modifier.testTag("calc_menu").semantics { contentDescription = "Más opciones" },
                            ) { Text("⋮", color = p.primary, fontSize = 24.sp) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (dark) "Tema claro" else "Tema oscuro") },
                                    onClick = { appearance = !dark; menuOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Tema del sistema") },
                                    onClick = { appearance = null; menuOpen = false },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Espacio privado") },
                                    onClick = {
                                        menuOpen = false
                                        state = CalculatorState()
                                        onOpenPrivate()
                                    },
                                    modifier = Modifier.testTag("calc_private"),
                                )
                            }
                        }
                    }
                    if (wide) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            CalcDisplay(state, p, Modifier.weight(1f).heightIn(min = 220.dp))
                            CalcKeypad(p, keyHeight, { state = state.press(it) }, Modifier.weight(1f))
                        }
                    } else {
                        CalcDisplay(state, p, Modifier.fillMaxWidth().heightIn(min = 146.dp))
                        CalcKeypad(p, keyHeight, { state = state.press(it) }, Modifier.fillMaxWidth())
                    }
                }
            }
        }

        if (historyOpen) {
            AlertDialog(
                onDismissRequest = { historyOpen = false },
                title = { Text("Historial") },
                text = {
                    if (state.history.isEmpty()) {
                        Text("Tus operaciones aparecerán aquí durante esta sesión.")
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                            items(state.history) { item ->
                                TextButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { state = state.recall(item.result); historyOpen = false },
                                ) {
                                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                                        Text(item.expression, color = p.secondary, textAlign = TextAlign.End)
                                        Text(item.result, color = p.primary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { historyOpen = false }) { Text("Cerrar") } },
                dismissButton = {
                    if (state.history.isNotEmpty()) {
                        TextButton(onClick = { state = state.copy(history = emptyList()) }) { Text("Borrar todo") }
                    }
                },
            )
        }
    }
}

@Composable
private fun CalcDisplay(state: CalculatorState, p: CalcPalette, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = p.display) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                if (state.expression == "0") " " else state.expression,
                color = p.secondary, fontSize = 17.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().testTag("calc_expression"),
            )
            Spacer(Modifier.height(10.dp))
            val length = state.display.length
            val resultSize = when {
                length > 18 -> 25.sp
                length > 13 -> 32.sp
                length > 9 -> 40.sp
                else -> 56.sp
            }
            Text(
                state.display, color = if (state.error == null) p.primary else MaterialTheme.colorScheme.error,
                fontSize = resultSize, fontWeight = FontWeight.Light, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().testTag("calc_display"),
            )
            if (state.error != null) Text("Operación no válida", color = p.secondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CalcKeypad(p: CalcPalette, keyHeight: Dp, onKey: (String) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Keys.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { key ->
                    val op = key in listOf("+", "−", "×", "÷")
                    val utility = key in listOf("AC", "+/−", "%", "⌫")
                    val background = when {
                        key == "=" -> p.accent
                        op -> p.operator
                        utility -> p.utility
                        else -> p.key
                    }
                    val description = when (key) {
                        "AC" -> "Limpiar"
                        "+/−" -> "Cambiar signo"
                        "%" -> "Porcentaje"
                        "÷" -> "Dividir"
                        "×" -> "Multiplicar"
                        "−" -> "Restar"
                        "+" -> "Sumar"
                        "=" -> "Igual"
                        "⌫" -> "Borrar último dígito"
                        "." -> "Punto decimal"
                        else -> key
                    }
                    Button(
                        onClick = { onKey(key) },
                        modifier = Modifier.weight(1f).height(keyHeight).testTag("calc_key_$key")
                            .semantics { contentDescription = description },
                        shape = RoundedCornerShape(21.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = background,
                            contentColor = if (key == "=") p.accentText else p.primary,
                        ),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(key, fontSize = if (utility) 21.sp else 29.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
