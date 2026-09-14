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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.veilshare.ui.design.VeilAdaptiveScaffold
import dev.veilshare.ui.design.VeilWindowClass
import dev.veilshare.ui.features.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun FoundationScreen(controller: LocalAppController, windowClass: VeilWindowClass) {
    val state by controller.state.collectAsState()
    VeilAdaptiveScaffold(windowClass) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (val value = state) {
                RootState.Initializing -> LoadingPanel(stringResource(Res.string.preparing_local))
                is RootState.FirstRun -> FirstRunScreen(value, controller)
                is RootState.Locked -> UnlockScreen(value, controller)
                is RootState.Unlocked -> BrowserScreen(value.browser, controller, windowClass)
                is RootState.Fatal -> ErrorScreen(value.message)
                is RootState.SharingSender -> SenderScreen(value.state, controller)
                is RootState.SharingReceiver -> ReceiverScreen(value.state, controller)
            }
        }
    }
}

@Composable private fun LoadingPanel(label: String) = Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
    CircularProgressIndicator(); Text(label, style = MaterialTheme.typography.bodyMedium)
}

@Composable private fun FirstRunScreen(state: RootState.FirstRun, controller: LocalAppController) {
    var primary by remember { mutableStateOf("") }; var primaryAgain by remember { mutableStateOf("") }
    var alternate by remember { mutableStateOf("") }; var alternateAgain by remember { mutableStateOf("") }
    ElevatedCard(Modifier.widthIn(max = 520.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.setup_label), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(Res.string.setup_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.setup_description))
            HorizontalDivider()
            Text(stringResource(Res.string.first_code), style = MaterialTheme.typography.titleSmall)
            SecretField(stringResource(Res.string.code), primary, { primary = it }, !state.busy, ImeAction.Next)
            SecretField(stringResource(Res.string.confirm_code), primaryAgain, { primaryAgain = it }, !state.busy, ImeAction.Next)
            Text(stringResource(Res.string.second_code), style = MaterialTheme.typography.titleSmall)
            SecretField(stringResource(Res.string.code), alternate, { alternate = it }, !state.busy, ImeAction.Next)
            SecretField(stringResource(Res.string.confirm_code), alternateAgain, { alternateAgain = it }, !state.busy, ImeAction.Done)
            state.error?.let { InlineError(it) }
            Button(
                onClick = {
                    controller.setup(primary.toCharArray(), primaryAgain.toCharArray(), alternate.toCharArray(), alternateAgain.toCharArray())
                    primary = ""; primaryAgain = ""; alternate = ""; alternateAgain = ""
                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(Res.string.create_spaces)) }
        }
    }
}

@Composable private fun UnlockScreen(state: RootState.Locked, controller: LocalAppController) {
    var credential by remember { mutableStateOf("") }; val focus = remember { FocusRequester() }
    fun submit() { if (credential.isNotEmpty() && !state.busy) { controller.unlock(credential.toCharArray()); credential = "" } }
    LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    ElevatedCard(Modifier.widthIn(max = 430.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(Res.string.files), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(Res.string.continue_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.unlock_description))
            OutlinedTextField(
                credential, { credential = it }, label = { Text(stringResource(Res.string.code)) }, singleLine = true,
                enabled = !state.busy, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("unlock_input"),
                supportingText = { Text(stringResource(Res.string.code_not_stored)) },
            )
            state.error?.let { InlineError(it) }
            Button(::submit, enabled = !state.busy && credential.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("unlock_submit")) {
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(Res.string.open_action))
            }
        }
    }
}

@Composable private fun BrowserScreen(state: BrowserState, controller: LocalAppController, windowClass: VeilWindowClass) {
    var createFolder by remember { mutableStateOf(false) }; var settings by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<BrowserItem?>(null) }; var pendingRename by remember { mutableStateOf<BrowserItem?>(null) }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BrowserHeader(windowClass, controller, { createFolder = true }, { settings = true })
            Breadcrumbs(state, controller)
            OperationPanel(state.operation, controller)
            state.message?.let { AssistChip(onClick = {}, label = { Text(it) }) }
            if (state.items.isEmpty() && state.operation is BrowserOperation.Idle) EmptyFolder()
            else LazyColumn(Modifier.fillMaxSize().testTag("browser_list"), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.items, key = { it.id }) { item -> FileRow(item, controller, { pendingRename = item }, { pendingDelete = item }) }
            }
        }
    }
    if (windowClass == VeilWindowClass.Expanded) Row(Modifier.fillMaxSize().widthIn(max = 1180.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.weight(1f)) { content() }; LocalInfoPanel(Modifier.width(260.dp).fillMaxHeight())
    } else Box(Modifier.fillMaxSize().widthIn(max = 760.dp)) { content() }

    if (createFolder) NameDialog(stringResource(Res.string.new_folder), stringResource(Res.string.create), { createFolder = false }) { controller.createFolder(it); createFolder = false }
    pendingRename?.let { item -> NameDialog(stringResource(Res.string.rename), stringResource(Res.string.save), { pendingRename = null }, item.name) { controller.rename(item.id,it);pendingRename=null } }
    pendingDelete?.let { item -> ConfirmDelete(item.name, { pendingDelete = null }) { controller.delete(item.id); pendingDelete = null } }
    if (settings) SettingsDialog({ settings = false }, controller)
}

@Composable private fun BrowserHeader(windowClass: VeilWindowClass, controller: LocalAppController, createFolder: () -> Unit, settings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(stringResource(Res.string.files), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold); Text(stringResource(Res.string.local_storage), style = MaterialTheme.typography.bodySmall) }
            TextButton(settings) { Text(stringResource(Res.string.settings)) }; TextButton({ controller.lock() }) { Text(stringResource(Res.string.lock)) }
        }
        if (windowClass == VeilWindowClass.Compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(createFolder, Modifier.weight(1f)) { Text(stringResource(Res.string.new_folder)) }; Button(controller::importFile, Modifier.weight(1f).testTag("import_action")) { Text(stringResource(Res.string.import_action)) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(createFolder) { Text(stringResource(Res.string.new_folder)) }; Button(controller::importFile, Modifier.testTag("import_action")) { Text(stringResource(Res.string.import_file)) }
        }
    }
}

@Composable private fun Breadcrumbs(state: BrowserState, controller: LocalAppController) = Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
    state.breadcrumbs.forEachIndexed { index, crumb -> if (index > 0) Text("/"); TextButton({ controller.navigateTo(crumb.id) }) { Text(crumb.label, maxLines = 1) } }
}

@Composable private fun OperationPanel(operation: BrowserOperation, controller: LocalAppController) = when (operation) {
    is BrowserOperation.Importing -> {
        val fraction = operation.total?.takeIf { it > 0 }?.let { operation.bytes.toFloat() / it }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator({ fraction.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (operation.total == null) "Importando ${formatSize(operation.bytes)}" else "${formatSize(operation.bytes)} de ${formatSize(operation.total)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(controller::cancelImport) { Text(stringResource(Res.string.cancel)) }
            }
        }
    }
    is BrowserOperation.Busy -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(operation.label, style = MaterialTheme.typography.bodySmall) }
    BrowserOperation.Idle -> Unit
}

@Composable private fun FileRow(item: BrowserItem, controller: LocalAppController, rename: () -> Unit, delete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { if (item.isDirectory) controller.enterFolder(item.id) else controller.openFile(item.id) }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) { Text(if (item.isDirectory) "C" else "A", Modifier.padding(horizontal = 11.dp, vertical = 7.dp), fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(item.name, maxLines = 2, fontWeight = FontWeight.Medium); Text(if (item.isDirectory) stringResource(Res.string.folder) else formatSize(item.size ?: 0), style = MaterialTheme.typography.bodySmall) }
            Box { TextButton({ menu = true }) { Text(stringResource(Res.string.more)) }; DropdownMenu(menu,{menu=false}) { DropdownMenuItem({Text(stringResource(Res.string.rename))},{menu=false;rename()});DropdownMenuItem({Text(stringResource(Res.string.delete))},{menu=false;delete()}) } }
        }
    }
}

@Composable private fun EmptyFolder() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(Res.string.empty_title), style = MaterialTheme.typography.titleMedium); Text(stringResource(Res.string.empty_description), style = MaterialTheme.typography.bodyMedium) }
}

@Composable private fun LocalInfoPanel(modifier: Modifier) = ElevatedCard(modifier) {
    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.this_device), style = MaterialTheme.typography.titleMedium); Text(stringResource(Res.string.local_info))
        HorizontalDivider(); Text(stringResource(Res.string.version), style = MaterialTheme.typography.labelLarge); Text(stringResource(Res.string.local_version), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun NameDialog(title: String, action: String, dismiss: () -> Unit, initial: String = "", submit: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(dismiss, title = { Text(title) }, text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.name)) }, singleLine = true, keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done), keyboardActions=KeyboardActions(onDone={if(name.isNotBlank())submit(name.trim())})) },
        confirmButton = { TextButton({ if (name.isNotBlank()) submit(name.trim()) }, enabled = name.isNotBlank()) { Text(action) } }, dismissButton = { TextButton(dismiss) { Text(stringResource(Res.string.cancel)) } })
}

@Composable private fun ConfirmDelete(name: String, dismiss: () -> Unit, confirm: () -> Unit) = AlertDialog(
    dismiss, title = { Text(stringResource(Res.string.delete)) }, text = { Text(stringResource(Res.string.delete_confirmation,name)) },
    confirmButton = { TextButton(confirm, colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text(stringResource(Res.string.delete)) } }, dismissButton = { TextButton(dismiss) { Text(stringResource(Res.string.cancel)) } },
)

@Composable private fun SettingsDialog(dismiss: () -> Unit, controller: LocalAppController) {
    var code by remember { mutableStateOf("") }; var confirmation by remember { mutableStateOf("") }
    AlertDialog(dismiss, title = { Text(stringResource(Res.string.settings)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.change_code), style=MaterialTheme.typography.titleMedium); SecretField(stringResource(Res.string.new_code), code, { code = it }, true, ImeAction.Next)
            SecretField(stringResource(Res.string.confirm_new_code), confirmation, { confirmation = it }, true, ImeAction.Done); Text(stringResource(Res.string.relock_after_change), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider();Text(stringResource(Res.string.about),style=MaterialTheme.typography.titleMedium);Text(stringResource(Res.string.about_summary),style=MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ controller.changeCredential(code.toCharArray(), confirmation.toCharArray()); code=""; confirmation=""; dismiss() },enabled=code.isNotEmpty()&&confirmation.isNotEmpty()) { Text(stringResource(Res.string.change_code)) } }, dismissButton = { TextButton(dismiss) { Text(stringResource(Res.string.close)) } })
}

@Composable private fun SecretField(label: String, value: String, change: (String) -> Unit, enabled: Boolean, imeAction: ImeAction) = OutlinedTextField(
    value, change, label = { Text(label) }, enabled = enabled, singleLine = true, visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction=imeAction), modifier = Modifier.fillMaxWidth(),
)

@Composable private fun InlineError(message: String) = Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.small) { Text(message,Modifier.fillMaxWidth().padding(12.dp),color=MaterialTheme.colorScheme.onErrorContainer) }

@Composable private fun ErrorScreen(message: String) = ElevatedCard(Modifier.widthIn(max = 500.dp)) {
    Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(Res.string.fatal_title), style = MaterialTheme.typography.headlineSmall); Text(message); Text(stringResource(Res.string.fatal_description),style=MaterialTheme.typography.bodySmall)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
    else -> "${bytes / (1024L * 1024 * 1024)} GiB"
}

@Composable
fun SenderScreen(state: SharingSenderState, controller: LocalAppController) {
    when (state) {
        is SharingSenderState.Preparing -> SenderPreparingScreen(state, controller)
        is SharingSenderState.Connecting -> SenderConnectingScreen(state, controller)
        is SharingSenderState.Sending -> SenderProgressScreen(state.progress, controller)
        is SharingSenderState.Completed -> SenderCompletedScreen(controller)
        is SharingSenderState.Error -> SenderErrorScreen(state, controller)
        is SharingSenderState.Cancelled -> SenderCancelledScreen(controller)
    }
}

@Composable
private fun SenderPreparingScreen(state: SharingSenderState.Preparing, controller: LocalAppController) {
    var referenceCode by remember { mutableStateOf(state.referenceCode ?: "") }
    var selectedFile by remember { mutableStateOf(state.selectedFile ?: "") }
    val focusRequester = remember { FocusRequester() }
    
    LaunchedEffect(Unit) { withFrameNanos { }; focusRequester.requestFocus() }
    
    ElevatedCard(Modifier.widthIn(max = 520.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.share_title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(Res.string.share_sender_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.share_sender_description))
            HorizontalDivider()
            
            // File selection
            Text(stringResource(Res.string.share_select_file), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = selectedFile,
                    onValueChange = { selectedFile = it },
                    label = { Text(stringResource(Res.string.share_file_selected)) },
                    enabled = false,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { controller.selectSharingFile() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.share_browse))
                }
            }
            
            // Reference code input
            Text(stringResource(Res.string.share_reference_code), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = referenceCode,
                onValueChange = { referenceCode = it.uppercase() },
                label = { Text(stringResource(Res.string.share_enter_code)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { 
                    controller.enterSharingReferenceCode(referenceCode)
                    controller.startSharingTransfer()
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            
            // Actions
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { controller.cancelSharing() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.cancel))
                }
                Button(
                    onClick = {
                        if (referenceCode.isNotBlank() && selectedFile.isNotBlank()) {
                            controller.enterSharingReferenceCode(referenceCode)
                            controller.startSharingTransfer()
                        }
                    },
                    enabled = referenceCode.isNotBlank() && selectedFile.isNotBlank(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text(stringResource(Res.string.share_send))
                }
            }
        }
    }
}

@Composable
private fun SenderConnectingScreen(state: SharingSenderState.Connecting, controller: LocalAppController) {
    ElevatedCard(Modifier.widthIn(max = 430.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(Res.string.share_title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(Res.string.share_connecting_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.share_connecting_description, state.referenceCode.value))
            CircularProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 40.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { controller.cancelSharing() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun SenderProgressScreen(progress: SharingProgress, controller: LocalAppController) {
    val fraction = if (progress.totalBytes > 0) progress.bytesTransferred.toFloat() / progress.totalBytes else null
    ElevatedCard(Modifier.widthIn(max = 520.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.share_title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(Res.string.share_sending_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.share_sending_description))
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(fraction.coerceIn(0f, 1f), Modifier.fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (progress.totalBytes == 0L) 
                            "Enviando ${formatSize(progress.bytesTransferred)}" 
                        else 
                            "${formatSize(progress.bytesTransferred)} de ${formatSize(progress.totalBytes)}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("${progress.currentChunk}/${progress.totalChunks}", style = MaterialTheme.typography.bodySmall)
                }
            }
            
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { controller.cancelSharing() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun SenderCompletedScreen(controller: LocalAppController) {
    ElevatedCard(Modifier.widthIn(max = 430.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("✓", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Text(stringResource(Res.string.share_complete_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.share_complete_description))
            Button(onClick = { controller.finishSharing() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.done))
            }
        }
    }
}

@Composable
private fun SenderErrorScreen(state: SharingSenderState.Error, controller: LocalAppController) {
    ElevatedCard(Modifier.widthIn(max = 500.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(Res.string.share_error_title), style = MaterialTheme.typography.headlineSmall)
            Text(state.message)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.canRetry) {
                    OutlinedButton(onClick = { controller.startSharingSender() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(Res.string.retry))
                    }
                }
                Button(onClick = { controller.finishSharing() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.close))
                }
            }
        }
    }
}

@Composable
private fun SenderCancelledScreen(controller: LocalAppController) {
    ElevatedCard(Modifier.widthIn(max = 430.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(Res.string.share_cancelled_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.share_cancelled_description))
            Button(onClick = { controller.finishSharing() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.done))
            }
        }
    }
}

@Composable
fun ReceiverScreen(state: SharingReceiverState, controller: LocalAppController) {
    when (state) {
        is SharingReceiverState.Waiting -> ReceiverWaitingScreen(state, controller)
        is SharingReceiverState.Incoming -> ReceiverIncomingScreen(state, controller)
        is SharingReceiverState.Receiving -> ReceiverProgressScreen(state.progress, controller)
        is SharingReceiverState.Completed -> ReceiverCompletedScreen(controller)
        is SharingReceiverState.Error -> ReceiverErrorScreen(state, controller)
        is SharingReceiverState.Rejected -> ReceiverRejectedScreen(controller)
        is SharingReceiverState.Cancelled -> ReceiverCancelledScreen(controller)
    }
}

@Composable
private fun ReceiverWaitingScreen(state: SharingReceiverState.Waiting, controller: LocalAppController) {
    ElevatedCard(Modifier.widthIn(max = 430.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(Res.string.share_title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(Res.string.share_receiver_waiting_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.share_receiver_waiting_description, state.referenceCode.value))
            CircularProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 40.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { controller.cancelSharing() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun ReceiverIncomingScreen(state: SharingReceiverState.Incoming, controller: LocalAppController) {
    ElevatedCard(Modifier.widthIn(max = 520.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.share_title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(Res.string.share_incoming_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.share_incoming_description, state.senderIdentity))
            
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(formatSize(state.fileSize), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { controller.rejectIncomingSharing() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(Res.string.share_reject))
                }
                Button(
                    onClick = { controller.acceptIncomingSharing() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text(stringResource(Res.string.share_accept))
                }
            }
        }
    }
}

@Composable
private fun ReceiverProgressScreen(progress: SharingProgress, controller: LocalAppController) {
    val fraction = if (progress.totalBytes > 0) progress.bytesTransferred.toFloat() / progress.totalBytes else null
    ElevatedCard(Modifier.widthIn(max = 520.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.share_title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(Res.string.share_receiving_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.share_receiving_description))
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(fraction.coerceIn(0f, 1f), Modifier.fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (progress.totalBytes == 0L) 
                            "Recibiendo ${formatSize(progress.bytesTransferred)}" 
                        else 
                            "${formatSize(progress.bytesTransferred)} de ${formatSize(progress.totalBytes)}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("${progress.currentChunk}/${progress.totalChunks}", style = MaterialTheme.typography.bodySmall)
                }
            }
            
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { controller.cancelSharing() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun ReceiverCompletedScreen(controller: LocalAppController) {
    ElevatedCard(Modifier.widthIn(max = 430.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("✓", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Text(stringResource(Res.string.share_complete_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.share_receive_complete_description))
            Button(onClick = { controller.finishSharing() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.done))
            }
        }
    }
}

@Composable
private fun ReceiverErrorScreen(state: SharingReceiverState.Error, controller: LocalAppController) {
    ElevatedCard(Modifier.widthIn(max = 500.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(Res.string.share_error_title), style = MaterialTheme.typography.headlineSmall)
            Text(state.message)
            Button(onClick = { controller.finishSharing() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.close))
            }
        }
    }
}

@Composable
private fun ReceiverRejectedScreen(controller: LocalAppController) {
    ElevatedCard(Modifier.widthIn(max = 430.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(Res.string.share_rejected_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.share_rejected_description))
            Button(onClick = { controller.finishSharing() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.done))
            }
        }
    }
}

@Composable
private fun ReceiverCancelledScreen(controller: LocalAppController) {
    ElevatedCard(Modifier.widthIn(max = 430.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(Res.string.share_cancelled_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.share_cancelled_description))
            Button(onClick = { controller.finishSharing() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.done))
            }
        }
    }
}
