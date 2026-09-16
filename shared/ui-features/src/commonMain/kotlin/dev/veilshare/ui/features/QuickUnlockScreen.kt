package dev.veilshare.ui.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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

/** Locked-state shell. No vault kind or REAL/DECOY metadata is exposed or persisted here. */
@Composable
fun QuickUnlockScreen(state: RootState.Locked, controller: LocalAppController) {
    var credential by remember { mutableStateOf("") }
    var enrollBiometric by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun submit() {
        if (credential.isEmpty() || state.busy) return
        controller.unlock(credential.toCharArray(), enrollQuickUnlock = enrollBiometric)
        credential = ""
        enrollBiometric = false
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        focus.requestFocus()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ElevatedCard(Modifier.widthIn(max = 430.dp).padding(16.dp)) {
            Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Archivos", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text("Continuar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Ingresa tu código para abrir el espacio correspondiente.")

                OutlinedTextField(
                    value = credential,
                    onValueChange = { credential = it },
                    label = { Text("Código") },
                    singleLine = true,
                    enabled = !state.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("unlock_input"),
                    supportingText = { Text("El código no se guarda en texto plano.") },
                )

                if (controller.quickUnlockAvailable && !controller.quickUnlockEnrolled) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = enrollBiometric,
                            onCheckedChange = { enrollBiometric = it },
                            enabled = !state.busy,
                        )
                        Column(Modifier.weight(1f)) {
                            Text("Usar biometría para este código")
                            Text(
                                "Se activará solo después de validar el código.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                state.error?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            message,
                            Modifier.fillMaxWidth().padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                Button(
                    onClick = ::submit,
                    enabled = !state.busy && credential.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("unlock_submit"),
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("Abrir")
                }

                if (controller.quickUnlockAvailable && controller.quickUnlockEnrolled) {
                    OutlinedButton(
                        onClick = controller::unlockWithQuickUnlock,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("unlock_biometric"),
                    ) {
                        Text("Usar biometría")
                    }
                    Text(
                        "También puedes usar tu código en cualquier momento.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
