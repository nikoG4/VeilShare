package dev.veilshare.ui.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.veilshare.ui.design.VeilAdaptiveScaffold
import dev.veilshare.ui.design.VeilWindowClass

@Composable
fun FoundationScreen(controller: LocalAppController, windowClass: VeilWindowClass) {
    val state by controller.state.collectAsState()
    VeilAdaptiveScaffold(windowClass) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (val value = state) {
                RootState.Initializing -> CircularProgressIndicator()
                is RootState.FirstRun -> FirstRunScreen(value, controller)
                is RootState.Locked -> UnlockScreen(value, controller)
                is RootState.Unlocked -> BrowserScreen(value.browser, controller, windowClass)
                is RootState.Fatal -> ErrorScreen(value.message)
            }
        }
    }
}

@Composable private fun FirstRunScreen(state: RootState.FirstRun, controller: LocalAppController) {
    var primary by remember { mutableStateOf("") }; var primaryAgain by remember { mutableStateOf("") }
    var alternate by remember { mutableStateOf("") }; var alternateAgain by remember { mutableStateOf("") }
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.large) {
        Column(Modifier.widthIn(max = 480.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Configura tus archivos", style = MaterialTheme.typography.headlineMedium)
            Text("Crea dos códigos diferentes. Cada código abre un espacio independiente con la misma interfaz.")
            SecretField("Código principal", primary, { primary = it }, !state.busy)
            SecretField("Confirmar código principal", primaryAgain, { primaryAgain = it }, !state.busy)
            SecretField("Código alternativo", alternate, { alternate = it }, !state.busy)
            SecretField("Confirmar código alternativo", alternateAgain, { alternateAgain = it }, !state.busy)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    controller.setup(primary.toCharArray(), primaryAgain.toCharArray(), alternate.toCharArray(), alternateAgain.toCharArray())
                    primary = ""; primaryAgain = ""; alternate = ""; alternateAgain = ""
                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
            ) { if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Crear y bloquear") }
        }
    }
}

@Composable private fun UnlockScreen(state: RootState.Locked, controller: LocalAppController) {
    var credential by remember { mutableStateOf("") }
    fun submit() { if (credential.isNotEmpty()) { controller.unlock(credential.toCharArray()); credential = "" } }
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.large) {
        Column(Modifier.widthIn(max = 420.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Continuar", style = MaterialTheme.typography.headlineMedium)
            Text("Ingresa tu código.")
            OutlinedTextField(
                credential, { credential = it }, label = { Text("Código") }, singleLine = true,
                enabled = !state.busy, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }), modifier = Modifier.fillMaxWidth().testTag("unlock_input"),
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(::submit, enabled = !state.busy && credential.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("unlock_submit")) {
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Abrir")
            }
        }
    }
}

@Composable private fun BrowserScreen(state: BrowserState, controller: LocalAppController, windowClass: VeilWindowClass) {
    var createFolder by remember { mutableStateOf(false) }; var settings by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<BrowserItem?>(null) }
    Column(Modifier.fillMaxSize().widthIn(max = if (windowClass == VeilWindowClass.Expanded) 1100.dp else 720.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Archivos", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton({ createFolder = true }) { Text("Nueva carpeta") }
            Button(controller::importFile, modifier = Modifier.testTag("import_action")) { Text("Importar") }
            TextButton({ settings = true }) { Text("Ajustes") }
            TextButton({ controller.lock() }) { Text("Bloquear") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            state.breadcrumbs.forEach { crumb -> TextButton({ controller.navigateTo(crumb.id) }) { Text(crumb.label) } }
        }
        when (val operation = state.operation) {
            is BrowserOperation.Importing -> {
                val fraction = operation.total?.takeIf { it > 0 }?.let { operation.bytes.toFloat() / it }
                if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator({ fraction.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                TextButton(controller::cancelImport) { Text("Cancelar importación") }
            }
            is BrowserOperation.Busy -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(operation.label) }
            BrowserOperation.Idle -> Unit
        }
        state.message?.let { Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary) }
        if (state.items.isEmpty() && state.operation is BrowserOperation.Idle) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Esta carpeta está vacía.") }
        } else LazyColumn(Modifier.fillMaxSize().testTag("browser_list"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.items, key = { it.id }) { item ->
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { if (item.isDirectory) controller.enterFolder(item.id) else controller.openFile(item.id) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (item.isDirectory) "Carpeta" else "Archivo", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(70.dp))
                        Column(Modifier.weight(1f)) { Text(item.name); if (!item.isDirectory) Text(formatSize(item.size ?: 0), style = MaterialTheme.typography.bodySmall) }
                        TextButton({ pendingDelete = item }) { Text("Eliminar") }
                    }
                }
            }
        }
    }
    if (createFolder) NameDialog("Nueva carpeta", "Crear", { createFolder = false }) { controller.createFolder(it); createFolder = false }
    pendingDelete?.let { item -> ConfirmDelete(item.name, { pendingDelete = null }) { controller.delete(item.id); pendingDelete = null } }
    if (settings) SettingsDialog({ settings = false }, controller)
}

@Composable private fun NameDialog(title: String, action: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(dismiss, title = { Text(title) }, text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true) },
        confirmButton = { TextButton({ if (name.isNotBlank()) submit(name) }, enabled = name.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(dismiss) { Text("Cancelar") } })
}

@Composable private fun ConfirmDelete(name: String, dismiss: () -> Unit, confirm: () -> Unit) = AlertDialog(
    dismiss, title = { Text("Eliminar archivo") }, text = { Text("¿Quieres eliminar “$name”? Esta acción no puede deshacerse.") },
    confirmButton = { TextButton(confirm) { Text("Eliminar") } }, dismissButton = { TextButton(dismiss) { Text("Cancelar") } },
)

@Composable private fun SettingsDialog(dismiss: () -> Unit, controller: LocalAppController) {
    var code by remember { mutableStateOf("") }; var confirmation by remember { mutableStateOf("") }
    AlertDialog(dismiss, title = { Text("Cambiar código") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecretField("Nuevo código", code, { code = it }, true)
            SecretField("Confirmar nuevo código", confirmation, { confirmation = it }, true)
            Text("Después del cambio tendrás que abrir de nuevo.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ controller.changeCredential(code.toCharArray(), confirmation.toCharArray()); code=""; confirmation=""; dismiss() }) { Text("Cambiar") } }, dismissButton = { TextButton(dismiss) { Text("Cancelar") } })
}

@Composable private fun SecretField(label: String, value: String, change: (String) -> Unit, enabled: Boolean) = OutlinedTextField(
    value, change, label = { Text(label) }, enabled = enabled, singleLine = true, visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
)

@Composable private fun ErrorScreen(message: String) = Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
    Column(Modifier.widthIn(max = 480.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("No se pudo continuar", style = MaterialTheme.typography.headlineSmall); Text(message); Text("Reinicia la aplicación. No se realizará limpieza automática.")
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    else -> "${bytes / (1024 * 1024)} MiB"
}
