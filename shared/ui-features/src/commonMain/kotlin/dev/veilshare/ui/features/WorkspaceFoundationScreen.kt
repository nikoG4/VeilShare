package dev.veilshare.ui.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.veilshare.ui.design.VeilWindowClass

private enum class WorkspaceSection(val title: String, val glyph: String) {
    Gallery("Galería", "▦"),
    Files("Archivos", "▤"),
    Contacts("Contactos", "◎"),
}

/**
 * Keeps the established locked/setup/sharing flows untouched while giving the unlocked
 * vault a responsive workspace. This lets the UX evolve without duplicating trust or
 * cryptographic state in Compose.
 */
@Composable
fun WorkspaceFoundationScreen(controller: LocalAppController, windowClass: VeilWindowClass) {
    val root by controller.state.collectAsState()
    val unlocked = root as? RootState.Unlocked
    if (unlocked == null) {
        FoundationScreen(controller, windowClass)
        return
    }
    WorkspaceScreen(unlocked.browser, controller, windowClass)
}

@Composable
private fun WorkspaceScreen(
    browser: BrowserState,
    controller: LocalAppController,
    windowClass: VeilWindowClass,
) {
    var section by remember { mutableStateOf(WorkspaceSection.Files) }
    val contacts by controller.contacts.collectAsState()

    if (windowClass == VeilWindowClass.Compact) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    WorkspaceSection.entries.forEach { item ->
                        NavigationBarItem(
                            selected = section == item,
                            onClick = { section = item },
                            icon = { Text(item.glyph, style = MaterialTheme.typography.titleMedium) },
                            label = { Text(item.title) },
                        )
                    }
                }
            },
        ) { padding ->
            WorkspaceContent(
                section = section,
                browser = browser,
                contacts = contacts,
                controller = controller,
                windowClass = windowClass,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                Spacer(Modifier.height(12.dp))
                WorkspaceSection.entries.forEach { item ->
                    NavigationRailItem(
                        selected = section == item,
                        onClick = { section = item },
                        icon = { Text(item.glyph, style = MaterialTheme.typography.titleMedium) },
                        label = { Text(item.title) },
                    )
                }
            }
            WorkspaceContent(
                section = section,
                browser = browser,
                contacts = contacts,
                controller = controller,
                windowClass = windowClass,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun WorkspaceContent(
    section: WorkspaceSection,
    browser: BrowserState,
    contacts: List<SharingContactSummary>,
    controller: LocalAppController,
    windowClass: VeilWindowClass,
    modifier: Modifier,
) {
    Box(modifier) {
        when (section) {
            WorkspaceSection.Files -> WorkspaceFiles(browser, controller, windowClass)
            WorkspaceSection.Gallery -> GalleryScreen(browser.mediaItems, controller)
            WorkspaceSection.Contacts -> ContactsScreen(contacts, controller)
        }
    }
}

@Composable
private fun WorkspaceFiles(
    state: BrowserState,
    controller: LocalAppController,
    windowClass: VeilWindowClass,
) {
    var newFolderOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<BrowserItem?>(null) }
    var deleteTarget by remember { mutableStateOf<BrowserItem?>(null) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = if (windowClass == VeilWindowClass.Compact) 16.dp else 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Archivos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Bóveda cifrada local", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { controller.lock() }) { Text("Bloquear") }
        }

        if (windowClass == VeilWindowClass.Compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { newFolderOpen = true }, modifier = Modifier.weight(1f)) { Text("Nueva carpeta") }
                Button(onClick = controller::importFile, modifier = Modifier.weight(1f)) { Text("Importar") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = controller::startSharingReceiver, modifier = Modifier.weight(1f)) { Text("Recibir") }
                Button(onClick = controller::startSharingSender, modifier = Modifier.weight(1f)) { Text("Enviar") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { newFolderOpen = true }) { Text("Nueva carpeta") }
                Button(onClick = controller::importFile) { Text("Importar") }
                OutlinedButton(onClick = controller::startSharingReceiver) { Text("Recibir") }
                Button(onClick = controller::startSharingSender) { Text("Enviar") }
            }
        }

        BreadcrumbRow(state, controller)
        WorkspaceOperation(state.operation, controller)
        state.message?.let { AssistChip(onClick = {}, label = { Text(it) }) }
        HorizontalDivider()

        if (state.items.isEmpty() && state.operation is BrowserOperation.Idle) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Esta carpeta está vacía", style = MaterialTheme.typography.titleMedium)
                    Text("Importa un archivo o crea una carpeta.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    WorkspaceFileRow(
                        item = item,
                        controller = controller,
                        rename = { renameTarget = item },
                        delete = { deleteTarget = item },
                    )
                }
            }
        }
    }

    if (newFolderOpen) WorkspaceNameDialog(
        title = "Nueva carpeta",
        action = "Crear",
        onDismiss = { newFolderOpen = false },
    ) {
        controller.createFolder(it)
        newFolderOpen = false
    }
    renameTarget?.let { item ->
        WorkspaceNameDialog(
            title = "Renombrar",
            action = "Guardar",
            initial = item.name,
            onDismiss = { renameTarget = null },
        ) {
            controller.rename(item.id, it)
            renameTarget = null
        }
    }
    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Eliminar") },
            text = { Text("¿Eliminar “${item.name}”? Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.delete(item.id)
                    deleteTarget = null
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun BreadcrumbRow(state: BrowserState, controller: LocalAppController) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        state.breadcrumbs.forEachIndexed { index, crumb ->
            if (index > 0) Text("/")
            TextButton(onClick = { controller.navigateTo(crumb.id) }) { Text(crumb.label, maxLines = 1) }
        }
    }
}

@Composable
private fun WorkspaceOperation(operation: BrowserOperation, controller: LocalAppController) {
    when (operation) {
        BrowserOperation.Idle -> Unit
        is BrowserOperation.Busy -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(operation.label, style = MaterialTheme.typography.bodySmall)
        }
        is BrowserOperation.Importing -> {
            val fraction = operation.total?.takeIf { it > 0 }?.let { operation.bytes.toFloat() / it.toFloat() }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (operation.total == null) "Importando…" else "${workspaceFormatSize(operation.bytes)} de ${workspaceFormatSize(operation.total)}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = controller::cancelImport) { Text("Cancelar") }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceFileRow(
    item: BrowserItem,
    controller: LocalAppController,
    rename: () -> Unit,
    delete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable { if (item.isDirectory) controller.enterFolder(item.id) else controller.openFile(item.id) }
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(if (item.isDirectory) "▤" else "□", Modifier.padding(horizontal = 11.dp, vertical = 7.dp), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, maxLines = 2, fontWeight = FontWeight.Medium)
                Text(
                    if (item.isDirectory) "Carpeta" else workspaceFormatSize(item.size ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("Más") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menuOpen = false; rename() })
                    DropdownMenuItem(text = { Text("Eliminar") }, onClick = { menuOpen = false; delete() })
                }
            }
        }
    }
}

@Composable
private fun GalleryScreen(items: List<BrowserItem>, controller: LocalAppController) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Galería", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("Fotos y videos cifrados de toda la bóveda", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Todavía no hay fotos o videos.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                gridItems(items, key = { it.id }) { item ->
                    ElevatedCard(Modifier.fillMaxWidth().clickable { controller.openFile(item.id) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.Center) {
                                    Text(if (item.mime?.startsWith("video/") == true) "▶" else "▧", style = MaterialTheme.typography.headlineLarge)
                                }
                            }
                            Text(item.name, fontWeight = FontWeight.Medium, maxLines = 2)
                            item.mime?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsScreen(contacts: List<SharingContactSummary>, controller: LocalAppController) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Contactos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Identidades verificadas manualmente", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = controller::startSharingReceiver) { Text("Recibir") }
            Button(onClick = controller::startContactVerification) { Text("Agregar") }
        }
        if (contacts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hay contactos verificados todavía.")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(contacts, key = { it.fingerprint }) { contact ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text("◎", Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(contact.alias, fontWeight = FontWeight.SemiBold)
                                contact.referenceCode?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                Text(
                                    contact.fingerprint.uppercase().chunked(4).take(4).joinToString(" "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = controller::startSharingSender) { Text("Enviar") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceNameDialog(
    title: String,
    action: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text("Nombre") }) },
        confirmButton = {
            Button(
                onClick = { onSubmit(value.trim()) },
                enabled = value.isNotBlank(),
                modifier = Modifier.heightIn(min = 44.dp),
            ) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun workspaceFormatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
    else -> "${bytes / (1024L * 1024 * 1024)} GiB"
}
