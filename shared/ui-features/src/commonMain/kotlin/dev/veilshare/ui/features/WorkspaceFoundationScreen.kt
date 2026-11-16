package dev.veilshare.ui.features

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.veilshare.ui.design.VeilWindowClass

private enum class VaultIcon { Gallery, Folder, Contacts, Lock, AddFolder, Import, Receive, Send, File, Play }

private enum class WorkspaceSection(val title: String, val subtitle: String, val glyph: VaultIcon) {
    Gallery("Galería", "Fotos y videos", VaultIcon.Gallery),
    Files("Archivos", "Almacenamiento local cifrado", VaultIcon.Folder),
    Contacts("Contactos", "Identidades verificadas", VaultIcon.Contacts),
}

/** Small stroke icons drawn in Compose, shared by Android/Desktop without a font or icon dependency. */
@Composable
private fun VaultGlyph(
    icon: VaultIcon,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    dimension: Dp = 24.dp,
) {
    Canvas(modifier.size(dimension)) {
        val u = size.minDimension / 24f
        fun pt(x: Float, y: Float) = Offset(x*u, y*u)
        val stroke = Stroke(width = 1.65f*u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, pt(x1,y1), pt(x2,y2), stroke.width, StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float = 2f) =
            drawRoundRect(tint, pt(x,y), Size(w*u,h*u), CornerRadius(radius*u), style = stroke)
        fun circle(x: Float, y: Float, r: Float) = drawCircle(tint, r*u, pt(x,y), style = stroke)
        fun poly(vararg points: Pair<Float,Float>, closed: Boolean = false) {
            if (points.isEmpty()) return
            val path = Path().apply {
                moveTo(points[0].first*u, points[0].second*u)
                points.drop(1).forEach { lineTo(it.first*u,it.second*u) }
                if (closed) close()
            }
            drawPath(path, tint, style = stroke)
        }
        when (icon) {
            VaultIcon.Folder, VaultIcon.AddFolder -> {
                poly(3f to 6f, 3f to 19f, 21f to 19f, 21f to 8f, 12f to 8f, 9f to 5f, 4f to 5f, 3f to 6f)
                if (icon == VaultIcon.AddFolder) {
                    line(12f,10.5f,12f,16.5f); line(9f,13.5f,15f,13.5f)
                }
            }
            VaultIcon.Gallery -> {
                rect(3f,4f,18f,16f,3f)
                circle(8f,9f,1.5f)
                poly(3.5f to 17f, 10f to 11f, 14f to 15f, 17f to 12f, 21f to 16f)
            }
            VaultIcon.Contacts -> {
                circle(9f,7f,3f); circle(17f,8f,2.5f)
                rect(3f,13f,12f,7f,3f)
                poly(17f to 13f, 19f to 13f, 21f to 15f, 21f to 19f)
            }
            VaultIcon.Lock -> {
                rect(5f,10f,14f,11f,2.5f)
                val shackle = Path().apply {
                    moveTo(8f*u,10f*u); lineTo(8f*u,6.5f*u)
                    cubicTo(8f*u,1.5f*u,16f*u,1.5f*u,16f*u,6.5f*u)
                    lineTo(16f*u,10f*u)
                }
                drawPath(shackle,tint,style=stroke); line(12f,15f,12f,17f)
            }
            VaultIcon.Import, VaultIcon.Receive -> {
                line(12f,3f,12f,15f)
                poly(7f to 10f,12f to 15f,17f to 10f)
                poly(4f to 14f,4f to 20f,20f to 20f,20f to 14f)
            }
            VaultIcon.Send -> {
                poly(3f to 10f,21f to 3f,14f to 21f,11f to 13f,3f to 10f, closed=true)
                line(11f,13f,21f,3f)
            }
            VaultIcon.File -> {
                poly(6f to 3f,14f to 3f,19f to 8f,19f to 21f,6f to 21f, closed=true)
                poly(14f to 3f,14f to 8f,19f to 8f)
                line(9f,13f,16f,13f); line(9f,17f,16f,17f)
            }
            VaultIcon.Play -> poly(9f to 5f,19f to 12f,9f to 19f, closed=true)
        }
    }
}

@Composable
private fun VaultEmptyState(icon: VaultIcon, title: String, description: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(84.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    VaultGlyph(icon, tint = MaterialTheme.colorScheme.primary, dimension = 42.dp)
                }
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun VaultAction(label: String, icon: VaultIcon, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) colors.primary else colors.surfaceVariant,
            contentColor = if (primary) colors.onPrimary else colors.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
    ) {
        VaultGlyph(icon, tint = if (primary) colors.onPrimary else colors.primary, dimension = 19.dp)
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun WorkspaceFoundationScreen(
    controller: LocalAppController,
    windowClass: VeilWindowClass,
    mediaPreview: MediaPreviewProvider = UnavailableMediaPreviewProvider,
) {
    val root by controller.state.collectAsState()
    val unlocked = root as? RootState.Unlocked
    if (unlocked == null) {
        FoundationScreen(controller, windowClass)
        return
    }
    WorkspaceScreen(unlocked.browser, controller, windowClass, mediaPreview)
}

@Composable
private fun WorkspaceScreen(
    browser: BrowserState,
    controller: LocalAppController,
    windowClass: VeilWindowClass,
    mediaPreview: MediaPreviewProvider,
) {
    var section by remember { mutableStateOf(WorkspaceSection.Files) }
    val contacts by controller.contacts.collectAsState()

    val colors = MaterialTheme.colorScheme
    val compact = windowClass == VeilWindowClass.Compact
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("vault_workspace"),
        containerColor = colors.background,
        bottomBar = {
            if (compact) {
                Surface(color = colors.surface, tonalElevation = 0.dp) {
                    Column {
                        HorizontalDivider(color = colors.outlineVariant)
                        NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
                            WorkspaceSection.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = section == item,
                                    onClick = { section = item },
                                    modifier = Modifier.testTag("vault_nav_${item.name.lowercase()}"),
                                    icon = { VaultGlyph(item.glyph, tint = if (section == item) colors.primary else colors.onSurfaceVariant) },
                                    label = { Text(item.title, fontWeight = if (section == item) FontWeight.SemiBold else FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedTextColor = colors.primary,
                                        unselectedTextColor = colors.onSurfaceVariant,
                                        indicatorColor = colors.primaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (!compact) {
                NavigationRail(containerColor = colors.surface) {
                    Spacer(Modifier.height(18.dp))
                    WorkspaceSection.entries.forEach { item ->
                        NavigationRailItem(
                            selected = section == item,
                            onClick = { section = item },
                            modifier = Modifier.testTag("vault_nav_${item.name.lowercase()}"),
                            icon = { VaultGlyph(item.glyph, tint = if (section == item) colors.primary else colors.onSurfaceVariant) },
                            label = { Text(item.title) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedTextColor = colors.primary,
                                unselectedTextColor = colors.onSurfaceVariant,
                                indicatorColor = colors.primaryContainer,
                            ),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = if (compact) 20.dp else 28.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(section.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                        Text(section.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    TextButton(onClick = { controller.lock() }, modifier = Modifier.testTag("vault_lock")) {
                        VaultGlyph(VaultIcon.Lock, tint = colors.primary, dimension = 19.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Bloquear", color = colors.primary)
                    }
                }
                WorkspaceContent(
                    section = section,
                    browser = browser,
                    contacts = contacts,
                    controller = controller,
                    windowClass = windowClass,
                    mediaPreview = mediaPreview,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
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
    mediaPreview: MediaPreviewProvider,
    modifier: Modifier,
) {
    Box(modifier) {
        when (section) {
            WorkspaceSection.Files -> WorkspaceFiles(browser, controller, windowClass)
            WorkspaceSection.Gallery -> GalleryScreen(browser.mediaItems, controller, mediaPreview)
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
    var importOptionsOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<BrowserItem?>(null) }
    var deleteTarget by remember { mutableStateOf<BrowserItem?>(null) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = if (windowClass == VeilWindowClass.Compact) 20.dp else 28.dp, vertical = 12.dp).testTag("vault_files"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (windowClass == VeilWindowClass.Compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultAction("Nueva carpeta", VaultIcon.AddFolder, { newFolderOpen = true }, Modifier.weight(1f))
                VaultAction("Importar", VaultIcon.Import, { importOptionsOpen = true }, Modifier.weight(1f), primary = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultAction("Recibir", VaultIcon.Receive, controller::startSharingReceiver, Modifier.weight(1f))
                VaultAction("Enviar", VaultIcon.Send, controller::startSharingSender, Modifier.weight(1f))
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultAction("Nueva carpeta", VaultIcon.AddFolder, { newFolderOpen = true }, Modifier.weight(1f))
                VaultAction("Importar", VaultIcon.Import, { importOptionsOpen = true }, Modifier.weight(1f), primary = true)
                VaultAction("Recibir", VaultIcon.Receive, controller::startSharingReceiver, Modifier.weight(1f))
                VaultAction("Enviar", VaultIcon.Send, controller::startSharingSender, Modifier.weight(1f))
            }
        }

        BreadcrumbRow(state, controller)
        WorkspaceOperation(state.operation, controller)
        state.message?.let { AssistChip(onClick = {}, label = { Text(it) }) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (state.items.isEmpty() && state.operation is BrowserOperation.Idle) {
            VaultEmptyState(VaultIcon.Folder, "Esta carpeta está vacía", "Importa archivos para comenzar o crea una carpeta.", Modifier.weight(1f).testTag("vault_empty_files"))
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
    if (importOptionsOpen) ImportOptionsDialog(
        onDismiss = { importOptionsOpen = false },
        onImport = { deleteOriginal ->
            controller.setDeleteOriginalAfterImport(deleteOriginal)
            controller.importFile()
            importOptionsOpen = false
        },
    )
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
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
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
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable { if (item.isDirectory) controller.enterFolder(item.id) else controller.openFile(item.id) }
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(45.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    VaultGlyph(if (item.isDirectory) VaultIcon.Folder else VaultIcon.File, tint = MaterialTheme.colorScheme.primary)
                }
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
                    if (!item.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("Compartir") },
                            onClick = { menuOpen = false; controller.shareVaultItem(item.id) },
                        )
                    }
                    DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menuOpen = false; rename() })
                    DropdownMenuItem(text = { Text("Eliminar") }, onClick = { menuOpen = false; delete() })
                }
            }
        }
    }
}

@Composable
private fun GalleryScreen(
    items: List<BrowserItem>,
    controller: LocalAppController,
    mediaPreview: MediaPreviewProvider,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp).testTag("vault_gallery"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (items.isEmpty()) {
            VaultEmptyState(VaultIcon.Gallery, "Todavía no hay fotos o videos.", "Las imágenes y los videos importados aparecerán aquí.", Modifier.weight(1f).testTag("vault_empty_gallery"))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                gridItems(items, key = { it.id }) { item ->
                    val isVideo = item.isVideoMedia()
                    val preview by produceState<ImageBitmap?>(
                        initialValue = null,
                        key1 = item.id,
                        key2 = mediaPreview,
                    ) {
                        if (!isVideo) value = mediaPreview.load(item.id, 512)
                    }
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().clickable { controller.openFile(item.id) },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Box(Modifier.fillMaxWidth().height(118.dp), contentAlignment = Alignment.Center) {
                                    if (preview != null) {
                                        Image(
                                            bitmap = preview!!,
                                            contentDescription = item.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        VaultGlyph(if (isVideo) VaultIcon.Play else VaultIcon.Gallery, tint = MaterialTheme.colorScheme.primary, dimension = 34.dp)
                                    }
                                }
                            }
                            Text(item.name, fontWeight = FontWeight.Medium, maxLines = 2)
                            item.mime?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun BrowserItem.isVideoMedia(): Boolean {
    if (mime?.startsWith("video/", ignoreCase = true) == true) return true
    return name.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "mov", "webm", "mkv", "avi")
}

@Composable
private fun ContactsScreen(contacts: List<SharingContactSummary>, controller: LocalAppController) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp).testTag("vault_contacts"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VaultAction("Recibir", VaultIcon.Receive, controller::startSharingReceiver, Modifier.weight(1f))
            VaultAction("Agregar", VaultIcon.Contacts, controller::startContactVerification, Modifier.weight(1f), primary = true)
        }
        if (contacts.isEmpty()) {
            VaultEmptyState(VaultIcon.Contacts, "No hay contactos verificados todavía.", "Agrega un contacto para compartir archivos de forma segura.", Modifier.weight(1f).testTag("vault_empty_contacts"))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(contacts, key = { it.fingerprint }) { contact ->
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(45.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(contact.alias.trim().take(2).uppercase().ifEmpty { "?" }, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                }
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
private fun ImportOptionsDialog(
    onDismiss: () -> Unit,
    onImport: (Boolean) -> Unit,
) {
    var deleteOriginal by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importar archivo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("El archivo se cifra y se guarda en la bóveda antes de intentar modificar el original.")
                Row(
                    Modifier.fillMaxWidth().clickable { deleteOriginal = !deleteOriginal },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = deleteOriginal, onCheckedChange = { deleteOriginal = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Eliminar el original después de cifrar")
                }
                if (deleteOriginal) {
                    Text(
                        "Algunos proveedores de Android no permiten borrar el documento. Si el borrado falla, la copia cifrada se conserva y el original permanece intacto.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onImport(deleteOriginal) }) { Text("Seleccionar archivo") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
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
