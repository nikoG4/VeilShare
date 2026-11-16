package dev.veilshare.ui.features

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.*
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

@Composable
internal fun DecoyEntryHost(root: RootState.Locked, controller: LocalAppController, generation: Long, preferences: DecoyPreferences, store: DecoyStore) {
    var prompt by remember(generation, preferences.kind) { mutableStateOf(false) }
    val dark = when (preferences.theme) { DecoyTheme.Dark -> true; DecoyTheme.Light -> false; DecoyTheme.System -> isSystemInDarkTheme() }
    val scheme = if (dark) darkColorScheme(primary = Color(0xFFA9C3FF), background = Color(0xFF111317), surface = Color(0xFF1B1E25)) else lightColorScheme(primary = Color(0xFF315DD4), background = Color(0xFFF4F6FA))
    MaterialTheme(colorScheme = scheme) {
        if (prompt) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TextButton(onClick = { prompt = false }, enabled = !root.busy) { Text("‹  " + preferences.kind.label) }
                Box(Modifier.weight(1f)) { QuickUnlockScreen(root, controller) }
            }
        } else if (preferences.kind == DecoyKind.Calc) {
            key(generation) {
                CalculatorScreen(root.busy, { controller.unlock(it) }, { if (!root.busy) prompt = true }, preferences.theme, { store.select(preferences.copy(theme = it)) })
            }
        } else {
            Surface(Modifier.fillMaxSize().testTag("decoy_" + preferences.kind.name.lowercase()), color = scheme.background) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (preferences.kind) {
                        DecoyKind.Calendar -> CalendarTool(store) { prompt = true }
                        DecoyKind.Notes -> NotesTool(store) { prompt = true }
                        DecoyKind.Clock -> ClockTool { prompt = true }
                        DecoyKind.Converter -> ConverterTool { prompt = true }
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
internal fun DecoySettings(value: DecoyPreferences, store: DecoyStore, onClose: () -> Unit, onChanged: () -> Unit) {
    var kind by remember { mutableStateOf(value.kind) }
    var theme by remember { mutableStateOf(value.theme) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Apariencia") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            DecoyKind.entries.forEach { candidate ->
                Row { RadioButton(kind == candidate, { kind = candidate }, modifier = Modifier.testTag("select_" + candidate.name)); Text(candidate.label, Modifier.padding(top = 14.dp)) }
            }
            HorizontalDivider()
            DecoyTheme.entries.forEach { candidate ->
                Row { RadioButton(theme == candidate, { theme = candidate }); Text(when(candidate) { DecoyTheme.System -> "Sistema"; DecoyTheme.Dark -> "Oscuro"; DecoyTheme.Light -> "Claro" }, Modifier.padding(top = 14.dp)) }
            }
            if (error) Text("No se pudo guardar la apariencia.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(onClick = { if (store.select(DecoyPreferences(kind, theme))) onChanged() else error = true }) { Text("Aplicar") } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolTitle(title: String, access: () -> Unit) {
    Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("tool_title").combinedClickable(onClick = {}, onLongClick = access))
}

private fun today() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
internal fun monthDays(first: LocalDate): List<LocalDate> {
    val start = first.minus(first.dayOfWeek.ordinal, DateTimeUnit.DAY)
    return List(42) { start.plus(it, DateTimeUnit.DAY) }
}
@Composable
private fun CalendarTool(store: DecoyStore, access: () -> Unit) {
    val now = today()
    var month by remember { mutableStateOf(LocalDate(now.year, now.monthNumber, 1)) }
    var selected by remember { mutableStateOf(now) }
    var event by remember(selected) { mutableStateOf(store.read("event_" + selected.toString()).orEmpty()) }
    var saved by remember(selected) { mutableStateOf(false) }
    ToolTitle(month.month.name.lowercase().replaceFirstChar { it.uppercase() } + " " + month.year, access)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { month = month.minus(1, DateTimeUnit.MONTH) }) { Text("‹") }
        TextButton(onClick = { month = LocalDate(now.year, now.monthNumber, 1); selected = now }) { Text("Hoy") }
        TextButton(onClick = { month = month.plus(1, DateTimeUnit.MONTH) }) { Text("›") }
    }
    Row(Modifier.fillMaxWidth()) { listOf("L","M","X","J","V","S","D").forEach { Text(it, Modifier.weight(1f)) } }
    monthDays(month).chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) { week.forEach { day ->
            TextButton(onClick = { selected = day }, modifier = Modifier.weight(1f).testTag("day_" + day), contentPadding = PaddingValues(0.dp)) {
                Text(day.dayOfMonth.toString(), color = if (day == selected) MaterialTheme.colorScheme.primary else if (day.month != month.month) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, fontWeight = if (day == now || day == selected) FontWeight.Bold else FontWeight.Normal)
            }
        } }
    }
    Text(selected.toString(), style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(event, { event = it; saved = false }, label = { Text("Evento") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = { saved = store.write("event_" + selected, event) }) { Text("Guardar") }
    if (saved) Text("Guardado")
}

@Composable
private fun NotesTool(store: DecoyStore, access: () -> Unit) {
    var notes by remember { mutableStateOf((0..19).map { store.read("note_" + it).orEmpty() }) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf(false) }
    ToolTitle("Notas", access)
    Button(onClick = { val i = notes.indexOfFirst { it.isEmpty() }; if (i >= 0) { editing = i; draft = ""; failure = false } }, enabled = notes.any { it.isEmpty() }) { Text("Nueva nota") }
    notes.forEachIndexed { i, note -> if (note.isNotEmpty()) {
        Card(onClick = { editing = i; draft = note; failure = false }, modifier = Modifier.fillMaxWidth()) { Text(note, Modifier.padding(16.dp), maxLines = 4) }
    } }
    editing?.let { i -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("Nota") }, text = {
        Column { OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth()); if (failure) Text("No se pudo guardar") }
    }, confirmButton = { Button(onClick = {
        if (store.write("note_" + i, draft)) { notes = notes.toMutableList().also { it[i] = draft }; editing = null } else failure = true
    }) { Text("Guardar") } }, dismissButton = { Row {
        TextButton(onClick = { if (store.write("note_" + i, "")) { notes = notes.toMutableList().also { it[i] = "" }; editing = null } else failure = true }) { Text("Eliminar") }
        TextButton(onClick = { editing = null }) { Text("Cancelar") }
    } }) }
}

@Composable
private fun ClockTool(access: () -> Unit) {
    var now by remember { mutableStateOf(Clock.System.now()) }
    var start by remember { mutableStateOf<kotlin.time.TimeMark?>(null) }
    var elapsed by remember { mutableStateOf(0L) }
    var runningTime by remember { mutableStateOf(0L) }
    var minutes by remember { mutableStateOf("5") }
    var timer by remember { mutableStateOf<kotlin.time.TimeMark?>(null) }
    var duration by remember { mutableStateOf(0L) }
    var remaining by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) {
        now = Clock.System.now()
        runningTime = elapsed + (start?.elapsedNow()?.inWholeSeconds ?: 0)
        if (timer != null) { remaining = (duration - (timer?.elapsedNow()?.inWholeSeconds ?: 0)).coerceAtLeast(0); if (remaining == 0L) timer = null }
        delay(200)
    } }
    ToolTitle(now.toLocalDateTime(TimeZone.currentSystemDefault()).time.toString().take(8), access)
    Text("Cronómetro", style = MaterialTheme.typography.titleLarge)
    Text(secondsText(runningTime), style = MaterialTheme.typography.displayMedium)
    Row { Button(onClick = { val mark = start; if (mark == null) start = TimeSource.Monotonic.markNow() else { elapsed += mark.elapsedNow().inWholeSeconds; start = null } }) { Text(if (start == null) "Iniciar" else "Pausar") }; TextButton(onClick = { start = null; elapsed = 0; runningTime = 0 }) { Text("Reiniciar") } }
    Text("Temporizador", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, label = { Text("Minutos") })
    Text(secondsText(remaining), style = MaterialTheme.typography.displayMedium)
    Row { Button(onClick = { duration = (minutes.toLongOrNull() ?: 0) * 60; remaining = duration; timer = TimeSource.Monotonic.markNow() }) { Text("Iniciar") }; TextButton(onClick = { timer = null; remaining = 0 }) { Text("Cancelar") } }
}
internal fun secondsText(s: Long) = (s / 60).toString().padStart(2, '0') + ":" + (s % 60).toString().padStart(2, '0')

internal val conversions = listOf("km → mi" to 0.621371, "mi → km" to 1.609344, "kg → lb" to 2.20462262, "lb → kg" to 0.45359237, "L → mL" to 1000.0, "mL → L" to 0.001)
@Composable
private fun ConverterTool(access: () -> Unit) {
    var raw by remember { mutableStateOf("") }
    var index by remember { mutableStateOf(0) }
    ToolTitle("Conversor", access)
    OutlinedTextField(raw, { raw = it }, label = { Text("Valor") }, modifier = Modifier.fillMaxWidth())
    conversions.forEachIndexed { i, item -> Row { RadioButton(index == i, { index = i }); Text(item.first, Modifier.padding(top = 14.dp)) } }
    val result = raw.replace(',', '.').toDoubleOrNull()?.let { it * conversions[index].second }
    Text(result?.takeIf { it.isFinite() }?.toString() ?: "—", style = MaterialTheme.typography.displayMedium)
}
