package dev.veilshare.ui.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Ephemeral UI-only queue for sending several already-encrypted vault files to the same
 * destination. Every file still uses the normal authenticated Sharing V1 transfer and is
 * reopened from the vault only when its turn starts; no plaintext batch/archive is created.
 */
private object SharingBatchSession {
    var remainingIds by mutableStateOf<List<String>>(emptyList())
        private set
    var totalFiles by mutableStateOf(0)
        private set
    var completedFiles by mutableStateOf(0)
        private set
    var referenceCode by mutableStateOf<String?>(null)

    val active: Boolean get() = totalFiles > 1

    fun begin(ids: List<String>) {
        val unique = ids.distinct()
        totalFiles = unique.size
        completedFiles = 0
        remainingIds = unique.drop(1)
        referenceCode = null
    }

    fun takeNext(): String? {
        val next = remainingIds.firstOrNull() ?: return null
        remainingIds = remainingIds.drop(1)
        return next
    }

    fun markCompleted() {
        if (totalFiles > 0) completedFiles = (completedFiles + 1).coerceAtMost(totalFiles)
    }

    fun clear() {
        remainingIds = emptyList()
        totalFiles = 0
        completedFiles = 0
        referenceCode = null
    }
}

/**
 * Adds an explicit multi-selection entry point without changing vault/catalog formats.
 * Selection is limited to files in the current folder; directories are intentionally not
 * sent recursively in this beta flow.
 */
@Composable
fun BatchShareLauncherOverlay(root: RootState, controller: LocalAppController) {
    val unlocked = root as? RootState.Unlocked ?: return
    val files = unlocked.browser.items.filterNot { it.isDirectory }
    if (files.size < 2) return

    var open by remember(unlocked.browser.currentFolderId) { mutableStateOf(false) }
    var selected by remember(unlocked.browser.currentFolderId) { mutableStateOf<Set<String>>(emptySet()) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.padding(20.dp).testTag("share_multi_select_action"),
        ) {
            Text("Enviar varios")
        }
    }

    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Seleccionar archivos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Marca los archivos que querés enviar al mismo destinatario.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { selected = files.map { it.id }.toSet() }) { Text("Todos") }
                    TextButton(onClick = { selected = emptySet() }) { Text("Ninguno") }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(files, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = item.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + item.id else selected - item.id
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(item.name, maxLines = 2, fontWeight = FontWeight.Medium)
                                item.size?.let { Text(batchFormatSize(it), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                Text("${selected.size} seleccionado(s)", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val ordered = files.map { it.id }.filter { it in selected }
                    if (ordered.isNotEmpty()) {
                        SharingBatchSession.begin(ordered)
                        open = false
                        selected = emptySet()
                        controller.shareVaultItem(ordered.first())
                    }
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.testTag("share_multi_select_confirm"),
            ) { Text("Continuar") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } },
    )
}

/**
 * Sender host with destination selection. Existing verified contacts are first-class choices,
 * while the manual code field keeps the new-contact verification path available.
 */
@Composable
fun EnhancedSenderScreen(state: SharingSenderState, controller: LocalAppController) {
    val contacts by controller.contacts.collectAsState()

    LaunchedEffect(state) {
        when (state) {
            SharingSenderState.Completed -> {
                if (SharingBatchSession.active) {
                    SharingBatchSession.markCompleted()
                    val nextId = SharingBatchSession.takeNext()
                    val code = SharingBatchSession.referenceCode
                    if (nextId != null && !code.isNullOrBlank()) {
                        // Completed is published just before LocalAppController releases its
                        // previous sharingJob. Move to the next item immediately, then retry the
                        // public start action for a bounded period while that old job finishes.
                        controller.finishSharing()
                        controller.shareVaultItem(nextId)
                        controller.enterSharingReferenceCode(code)
                        for (attempt in 0 until 40) {
                            controller.startSharingTransfer()
                            val current = controller.state.value
                            val stillPreparing =
                                current is RootState.SharingSender && current.state is SharingSenderState.Preparing
                            if (!stillPreparing) break
                            delay(50)
                        }
                    } else if (nextId == null) {
                        SharingBatchSession.clear()
                    }
                }
            }
            SharingSenderState.Cancelled,
            is SharingSenderState.Error -> SharingBatchSession.clear()
            else -> Unit
        }
    }

    when (state) {
        is SharingSenderState.Preparing -> EnhancedSenderPreparing(state, contacts, controller)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SenderScreen(state, controller)
        }
    }
}

@Composable
private fun EnhancedSenderPreparing(
    state: SharingSenderState.Preparing,
    contacts: List<SharingContactSummary>,
    controller: LocalAppController,
) {
    var referenceCode by remember(state.referenceCode) {
        mutableStateOf(state.referenceCode ?: SharingBatchSession.referenceCode.orEmpty())
    }
    val routableContacts = contacts.filter { !it.referenceCode.isNullOrBlank() }
    val selectedFile = state.selectedFile.orEmpty()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ElevatedCard(Modifier.widthIn(max = 580.dp).padding(16.dp)) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Compartir", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text("Enviar archivos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

                if (SharingBatchSession.active) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${SharingBatchSession.totalFiles} archivos seleccionados", fontWeight = FontWeight.SemiBold)
                            if (SharingBatchSession.completedFiles > 0) {
                                Text(
                                    "${SharingBatchSession.completedFiles} de ${SharingBatchSession.totalFiles} enviados",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (selectedFile.isNotBlank()) Text("Actual: $selectedFile", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Se enviarán al mismo destinatario, uno tras otro y cada uno conservará su nombre.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    Text(if (selectedFile.isBlank()) "Todavía no seleccionaste un archivo." else "Archivo: $selectedFile")
                    OutlinedButton(onClick = controller::selectSharingFile, modifier = Modifier.fillMaxWidth()) {
                        Text(if (selectedFile.isBlank()) "Elegir archivo" else "Cambiar archivo")
                    }
                }

                HorizontalDivider()
                Text("Destinatario", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                if (routableContacts.isNotEmpty()) {
                    Text("Contactos verificados", style = MaterialTheme.typography.labelLarge)
                    LazyColumn(Modifier.heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(routableContacts, key = { "${it.alias}:${it.referenceCode}" }) { contact ->
                            val code = contact.referenceCode.orEmpty()
                            OutlinedButton(
                                onClick = {
                                    referenceCode = code
                                    SharingBatchSession.referenceCode = code
                                    controller.enterSharingReferenceCode(code)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(contact.alias, fontWeight = FontWeight.SemiBold)
                                    Text(code, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Text("O ingresá un código nuevo", style = MaterialTheme.typography.labelLarge)
                } else {
                    Text("Ingresá el código del destinatario. Si es nuevo, la app pedirá verificar su identidad antes de enviar.")
                }

                OutlinedTextField(
                    value = referenceCode,
                    onValueChange = { value ->
                        referenceCode = value
                        SharingBatchSession.referenceCode = value
                        controller.enterSharingReferenceCode(value)
                    },
                    singleLine = true,
                    label = { Text("Código del destinatario") },
                    modifier = Modifier.fillMaxWidth().testTag("share_reference_code"),
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            SharingBatchSession.clear()
                            controller.cancelSharing()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancelar") }
                    Button(
                        onClick = {
                            SharingBatchSession.referenceCode = referenceCode.trim()
                            controller.enterSharingReferenceCode(referenceCode)
                            controller.startSharingTransfer()
                        },
                        enabled = selectedFile.isNotBlank() && referenceCode.isNotBlank(),
                        modifier = Modifier.weight(1f).testTag("share_start_transfer"),
                    ) { Text(if (SharingBatchSession.active) "Enviar ${SharingBatchSession.totalFiles}" else "Enviar") }
                }
            }
        }
    }
}

private fun batchFormatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    else -> "${bytes / (1024L * 1024L * 1024L)} GB"
}
