package dev.veilshare.ui.features

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Quick actions layered over the waiting receiver screen. */
@Composable
fun ReceiverCodeActionsOverlay(root: RootState) {
    val waiting = (root as? RootState.SharingReceiver)?.state as? SharingReceiverState.Waiting ?: return
    val code = waiting.referenceCode.value
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    var qrOpen by remember(code) { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Código para recibir", style = MaterialTheme.typography.labelLarge)
                Text(code, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (copied) "Copiado" else "Copiar")
                    }
                    Button(
                        onClick = { qrOpen = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Mostrar QR")
                    }
                }
            }
        }
    }

    if (qrOpen) ReceiverQrDialog(code = code, onDismiss = { qrOpen = false })
}

@Composable
private fun ReceiverQrDialog(code: String, onDismiss: () -> Unit) {
    val provider = LocalReferenceCodeQrProvider.current
    val qr by produceState<ImageBitmap?>(initialValue = null, code, provider) {
        value = if (provider.available) provider.render(code, 768) else null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.padding(24.dp).widthIn(max = 420.dp),
        ) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Escanear código", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("El QR contiene únicamente el código de referencia temporal.", style = MaterialTheme.typography.bodySmall)
                Surface(color = Color.White, shape = MaterialTheme.shapes.medium) {
                    Box(Modifier.size(300.dp).padding(12.dp), contentAlignment = Alignment.Center) {
                        val bitmap = qr
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "QR $code",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
                Text(code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        }
    }
}
