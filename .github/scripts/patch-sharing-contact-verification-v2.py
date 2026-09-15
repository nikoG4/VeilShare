from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    s = p.read_text()
    assert s.count(old) == 1, (path, s.count(old), old[:80])
    p.write_text(s.replace(old, new, 1))


path = 'shared/ui-features/src/commonMain/kotlin/dev/veilshare/ui/features/AppState.kt'
replace_once(path,
'''    data class SharingSender(val state: SharingSenderState) : RootState
    data class SharingReceiver(val state: SharingReceiverState) : RootState
}''',
'''    data class SharingSender(val state: SharingSenderState) : RootState
    data class SharingReceiver(val state: SharingReceiverState) : RootState
    data class SharingContactVerification(val state: SharingContactVerificationState) : RootState
}''')
replace_once(path,
'''sealed interface SharingReceiverState {
    data class Waiting(val referenceCode: ReferenceCode) : SharingReceiverState
    data class Incoming(val senderIdentity: String, val fileName: String, val fileSize: Long) : SharingReceiverState
    data class Receiving(val progress: SharingProgress) : SharingReceiverState
    data object Completed : SharingReceiverState
    data class Error(val message: String) : SharingReceiverState
    data object Rejected : SharingReceiverState
    data object Cancelled : SharingReceiverState
}

data class SharingProgress(''',
'''sealed interface SharingReceiverState {
    data class Waiting(val referenceCode: ReferenceCode) : SharingReceiverState
    data class Incoming(val senderIdentity: String, val fileName: String, val fileSize: Long) : SharingReceiverState
    data class Receiving(val progress: SharingProgress) : SharingReceiverState
    data object Completed : SharingReceiverState
    data class Error(val message: String) : SharingReceiverState
    data object Rejected : SharingReceiverState
    data object Cancelled : SharingReceiverState
}

sealed interface SharingContactVerificationState {
    data class Entering(
        val referenceCode: String = "",
        val busy: Boolean = false,
        val error: String? = null,
    ) : SharingContactVerificationState

    data class VerificationRequired(
        val referenceCode: ReferenceCode,
        val fingerprint: String,
        val reason: SharingVerificationReason,
        val existingAlias: String? = null,
        val busy: Boolean = false,
        val error: String? = null,
    ) : SharingContactVerificationState

    data class Completed(val alias: String) : SharingContactVerificationState
}

sealed interface SharingPeerLookupResult {
    data class Trusted(val alias: String) : SharingPeerLookupResult
    data class NeedsVerification(
        val fingerprint: String,
        val reason: SharingVerificationReason,
        val existingAlias: String? = null,
    ) : SharingPeerLookupResult
    data class KeyMismatch(val expectedFingerprint: String, val presentedFingerprint: String) : SharingPeerLookupResult
    data class Unavailable(val reason: String? = null) : SharingPeerLookupResult
    data class Failed(val reason: String? = null) : SharingPeerLookupResult
}

data class SharingProgress(''')
replace_once(path,
'''    suspend fun activate(personaId: LocalPersonaId, vault: VaultHandle): SharingRuntimeActivation

    /** Takes ownership of [file] immediately and must close it exactly once. */''',
'''    suspend fun activate(personaId: LocalPersonaId, vault: VaultHandle): SharingRuntimeActivation

    /** Performs only LOOKUP + trust evaluation. It never starts a handshake or transfer. */
    suspend fun inspectPeer(referenceCode: ReferenceCode): SharingPeerLookupResult

    /** Takes ownership of [file] immediately and must close it exactly once. */''')
replace_once(path,
'''    override suspend fun activate(personaId: LocalPersonaId, vault: VaultHandle): SharingRuntimeActivation =
        SharingRuntimeActivation.Unavailable("Sharing runtime is not configured")

    override suspend fun send(''',
'''    override suspend fun activate(personaId: LocalPersonaId, vault: VaultHandle): SharingRuntimeActivation =
        SharingRuntimeActivation.Unavailable("Sharing runtime is not configured")

    override suspend fun inspectPeer(referenceCode: ReferenceCode): SharingPeerLookupResult =
        SharingPeerLookupResult.Unavailable("Sharing runtime is not configured")

    override suspend fun send(''')

path = 'shared/app/src/commonMain/kotlin/dev/veilshare/app/DefaultSharingRuntime.kt'
replace_once(path,
'import dev.veilshare.core.contacts.LookupTrustResolver\n',
'import dev.veilshare.core.contacts.LookupTrustResolver\nimport dev.veilshare.core.contacts.LookupTrustResult\n')
replace_once(path,
'import dev.veilshare.core.model.LocalPersonaId\n',
'import dev.veilshare.core.model.LocalPersonaId\nimport dev.veilshare.core.model.LookupRequest\n')
replace_once(path,
'import dev.veilshare.ui.features.SharingPickedFile\n',
'import dev.veilshare.ui.features.SharingPeerLookupResult\nimport dev.veilshare.ui.features.SharingPickedFile\n')
replace_once(path,
'''    override suspend fun send(
        referenceCode: ReferenceCode,
        file: SharingPickedFile,
        onProgress: suspend (SharingProgress) -> Unit,
    ): SharingSendResult = sendMutex.withLock {''',
'''    override suspend fun inspectPeer(referenceCode: ReferenceCode): SharingPeerLookupResult {
        val contextId = stateMutex.withLock {
            pendingVerification = null
            activeContext
        } ?: return SharingPeerLookupResult.Unavailable("Compartir no está activo.")
        val localIdentity = identities.publicIdentity(contextId)
            ?: return SharingPeerLookupResult.Unavailable("No se encontró la identidad local de compartir.")
        return try {
            val response = signalingClient.lookup(
                LookupRequest(
                    referenceCode = referenceCode,
                    requestorSharingIdentityId = localIdentity.identityId,
                ),
            )
            when (val resolved = LookupTrustResolver(contacts).resolve(referenceCode, response)) {
                is LookupTrustResult.Unavailable -> {
                    stateMutex.withLock { pendingVerification = null }
                    SharingPeerLookupResult.Unavailable("No hay un dispositivo disponible con ese código.")
                }
                is LookupTrustResult.Peer -> when (val decision = resolved.decision) {
                    is PeerTrustDecision.Trusted -> {
                        stateMutex.withLock { pendingVerification = null }
                        SharingPeerLookupResult.Trusted(decision.contact.alias)
                    }
                    is PeerTrustDecision.NeedsVerification -> {
                        stateMutex.withLock { pendingVerification = decision }
                        SharingPeerLookupResult.NeedsVerification(
                            fingerprint = decision.candidate.fingerprint.value,
                            reason = when (decision.reason) {
                                VerificationReason.NEW_PEER -> SharingVerificationReason.NEW_PEER
                                VerificationReason.IDENTITY_CHANGED_FOR_ROUTING_CODE -> SharingVerificationReason.IDENTITY_CHANGED
                            },
                            existingAlias = decision.existingContact?.alias,
                        )
                    }
                    is PeerTrustDecision.KeyMismatch -> {
                        stateMutex.withLock { pendingVerification = null }
                        SharingPeerLookupResult.KeyMismatch(
                            expectedFingerprint = decision.contact.fingerprint.value,
                            presentedFingerprint = decision.presentedFingerprint.value,
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            stateMutex.withLock { pendingVerification = null }
            SharingPeerLookupResult.Failed("No se pudo comprobar la identidad del contacto.")
        }
    }

    override suspend fun send(
        referenceCode: ReferenceCode,
        file: SharingPickedFile,
        onProgress: suspend (SharingProgress) -> Unit,
    ): SharingSendResult = sendMutex.withLock {''')

path = 'shared/ui-features/src/commonMain/kotlin/dev/veilshare/ui/features/LocalAppController.kt'
replace_once(path,
'''    fun cancelSharing() {
''',
'''    fun startContactVerification() {
        if (mutableState.value !is RootState.Unlocked) return
        if (ownSharingReferenceCode == null) {
            mutableState.value = RootState.SharingContactVerification(
                SharingContactVerificationState.Entering(error = "Compartir no está disponible en esta sesión."),
            )
            return
        }
        mutableState.value = RootState.SharingContactVerification(SharingContactVerificationState.Entering())
    }

    fun enterContactVerificationReferenceCode(referenceCode: String) {
        val entering = (mutableState.value as? RootState.SharingContactVerification)?.state as? SharingContactVerificationState.Entering ?: return
        if (entering.busy) return
        mutableState.value = RootState.SharingContactVerification(
            entering.copy(referenceCode = referenceCode, error = null),
        )
    }

    fun lookupContactForVerification() {
        val entering = (mutableState.value as? RootState.SharingContactVerification)?.state as? SharingContactVerificationState.Entering ?: return
        if (entering.busy || sharingJob?.isActive == true) return
        val referenceCode = try {
            ReferenceCodes.parse(entering.referenceCode)
        } catch (_: IllegalArgumentException) {
            mutableState.value = RootState.SharingContactVerification(
                entering.copy(error = "El código de referencia no es válido."),
            )
            return
        }
        mutableState.value = RootState.SharingContactVerification(entering.copy(busy = true, error = null))
        sharingJob = scope.launch {
            try {
                mutableState.value = when (val result = withContext(workDispatcher) { sharingRuntime.inspectPeer(referenceCode) }) {
                    is SharingPeerLookupResult.Trusted -> RootState.SharingContactVerification(
                        SharingContactVerificationState.Completed(result.alias),
                    )
                    is SharingPeerLookupResult.NeedsVerification -> RootState.SharingContactVerification(
                        SharingContactVerificationState.VerificationRequired(
                            referenceCode = referenceCode,
                            fingerprint = result.fingerprint,
                            reason = result.reason,
                            existingAlias = result.existingAlias,
                        ),
                    )
                    is SharingPeerLookupResult.KeyMismatch -> RootState.SharingContactVerification(
                        SharingContactVerificationState.Entering(
                            referenceCode = referenceCode.value,
                            error = "La clave presentada no coincide con la identidad ya guardada. No se modificó la confianza.",
                        ),
                    )
                    is SharingPeerLookupResult.Unavailable -> RootState.SharingContactVerification(
                        SharingContactVerificationState.Entering(referenceCode.value, error = result.reason ?: "El contacto no está disponible."),
                    )
                    is SharingPeerLookupResult.Failed -> RootState.SharingContactVerification(
                        SharingContactVerificationState.Entering(referenceCode.value, error = result.reason ?: "No se pudo comprobar el contacto."),
                    )
                }
            } catch (_: Exception) {
                mutableState.value = RootState.SharingContactVerification(
                    SharingContactVerificationState.Entering(referenceCode.value, error = "No se pudo comprobar el contacto."),
                )
            } finally {
                sharingJob = null
            }
        }
    }

    fun confirmContactVerification(alias: String) {
        val verification = (mutableState.value as? RootState.SharingContactVerification)?.state as? SharingContactVerificationState.VerificationRequired ?: return
        if (verification.busy || sharingJob?.isActive == true) return
        val safeAlias = when (verification.reason) {
            SharingVerificationReason.NEW_PEER -> alias.trim()
            SharingVerificationReason.IDENTITY_CHANGED -> verification.existingAlias ?: alias.trim()
        }
        if (safeAlias.isBlank()) {
            mutableState.value = RootState.SharingContactVerification(
                verification.copy(error = "Escribe un nombre para este contacto."),
            )
            return
        }
        mutableState.value = RootState.SharingContactVerification(verification.copy(busy = true, error = null))
        sharingJob = scope.launch {
            try {
                when (val result = withContext(workDispatcher) { sharingRuntime.confirmPendingPeer(safeAlias) }) {
                    SharingVerificationResult.Verified -> mutableState.value = RootState.SharingContactVerification(
                        SharingContactVerificationState.Completed(safeAlias),
                    )
                    is SharingVerificationResult.Failed -> mutableState.value = RootState.SharingContactVerification(
                        verification.copy(busy = false, error = result.reason ?: "No se pudo guardar la verificación."),
                    )
                }
            } catch (_: Exception) {
                mutableState.value = RootState.SharingContactVerification(
                    verification.copy(busy = false, error = "No se pudo guardar la verificación."),
                )
            } finally {
                sharingJob = null
            }
        }
    }

    fun dismissContactVerification() {
        val verification = (mutableState.value as? RootState.SharingContactVerification)?.state as? SharingContactVerificationState.VerificationRequired ?: return
        if (verification.busy) return
        scope.launch { withContext(NonCancellable + workDispatcher) { runCatching { sharingRuntime.dismissPendingPeerVerification() } } }
        mutableState.value = RootState.SharingContactVerification(
            SharingContactVerificationState.Entering(referenceCode = verification.referenceCode.value),
        )
    }

    fun finishContactVerification() {
        sharingJob?.cancel(); sharingJob = null
        scope.launch { withContext(NonCancellable + workDispatcher) { runCatching { sharingRuntime.dismissPendingPeerVerification() } } }
        returnToBrowser()
    }

    fun cancelSharing() {
''')

path = 'shared/ui-features/src/commonMain/kotlin/dev/veilshare/ui/features/FoundationScreen.kt'
replace_once(path,
'''                is RootState.SharingSender -> SenderScreen(value.state, controller)
                is RootState.SharingReceiver -> ReceiverScreen(value.state, controller)
''',
'''                is RootState.SharingSender -> SenderScreen(value.state, controller)
                is RootState.SharingReceiver -> ReceiverScreen(value.state, controller)
                is RootState.SharingContactVerification -> ContactVerificationScreen(value.state, controller)
''')
replace_once(path,
'''            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = controller::startSharingReceiver,
                    modifier = Modifier.weight(1f).testTag("share_receive_action"),
                ) { Text(stringResource(Res.string.share_receive)) }
                Button(
                    onClick = controller::startSharingSender,
                    modifier = Modifier.weight(1f).testTag("share_send_action"),
                ) { Text(stringResource(Res.string.share_sender_title)) }
            }
        } else {''',
'''            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = controller::startSharingReceiver,
                    modifier = Modifier.weight(1f).testTag("share_receive_action"),
                ) { Text(stringResource(Res.string.share_receive)) }
                Button(
                    onClick = controller::startSharingSender,
                    modifier = Modifier.weight(1f).testTag("share_send_action"),
                ) { Text(stringResource(Res.string.share_sender_title)) }
            }
            OutlinedButton(
                onClick = controller::startContactVerification,
                modifier = Modifier.fillMaxWidth().testTag("share_verify_contact_action"),
            ) { Text(stringResource(Res.string.share_verify_contact)) }
        } else {''')
replace_once(path,
'''                OutlinedButton(
                    onClick = controller::startSharingReceiver,
                    modifier = Modifier.testTag("share_receive_action"),
                ) { Text(stringResource(Res.string.share_receive)) }
                Button(''',
'''                OutlinedButton(
                    onClick = controller::startContactVerification,
                    modifier = Modifier.testTag("share_verify_contact_action"),
                ) { Text(stringResource(Res.string.share_verify_contact)) }
                OutlinedButton(
                    onClick = controller::startSharingReceiver,
                    modifier = Modifier.testTag("share_receive_action"),
                ) { Text(stringResource(Res.string.share_receive)) }
                Button(''')
replace_once(path,
'''@Composable
private fun SenderConnectingScreen(state: SharingSenderState.Connecting, controller: LocalAppController) {''',
'''@Composable
private fun ContactVerificationScreen(state: SharingContactVerificationState, controller: LocalAppController) {
    when (state) {
        is SharingContactVerificationState.Entering -> ElevatedCard(Modifier.widthIn(max = 520.dp).testTag("share_contact_verification_entry")) {
            Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.share_title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(stringResource(Res.string.share_verify_contact), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(Res.string.share_verify_contact_description))
                OutlinedTextField(
                    value = state.referenceCode,
                    onValueChange = controller::enterContactVerificationReferenceCode,
                    enabled = !state.busy,
                    singleLine = true,
                    label = { Text(stringResource(Res.string.share_reference_code)) },
                    supportingText = { Text(stringResource(Res.string.share_reference_is_not_identity)) },
                    modifier = Modifier.fillMaxWidth().testTag("share_contact_verification_code"),
                )
                state.error?.let { InlineError(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = controller::finishContactVerification, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(Res.string.back)) }
                    Button(onClick = controller::lookupContactForVerification, enabled = !state.busy && state.referenceCode.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("share_contact_verification_lookup")) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(Res.string.share_check_identity))
                    }
                }
            }
        }
        is SharingContactVerificationState.VerificationRequired -> {
            var alias by remember(state.fingerprint) { mutableStateOf(state.existingAlias.orEmpty()) }
            val isNew = state.reason == SharingVerificationReason.NEW_PEER
            ElevatedCard(Modifier.widthIn(max = 560.dp).testTag("share_contact_verification_fingerprint")) {
                Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(Res.string.share_title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(Res.string.share_verify_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(if (isNew) Res.string.share_verify_new_description else Res.string.share_verify_changed_description), color = if (isNew) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(Res.string.share_fingerprint), style = MaterialTheme.typography.labelLarge)
                            Text(formatFingerprint(state.fingerprint), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text(stringResource(Res.string.share_verify_out_of_band), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(Res.string.share_reference_is_not_identity), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isNew) OutlinedTextField(value = alias, onValueChange = { alias = it }, enabled = !state.busy, singleLine = true, label = { Text(stringResource(Res.string.share_contact_name)) }, modifier = Modifier.fillMaxWidth().testTag("share_contact_verification_alias"))
                    else state.existingAlias?.let { Text(stringResource(Res.string.share_existing_contact, it)) }
                    state.error?.let { InlineError(it) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = controller::dismissContactVerification, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(Res.string.back)) }
                        Button(onClick = { controller.confirmContactVerification(alias) }, enabled = !state.busy && (!isNew || alias.isNotBlank()), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("share_contact_verification_confirm")) {
                            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(Res.string.share_confirm_identity))
                        }
                    }
                }
            }
        }
        is SharingContactVerificationState.Completed -> ElevatedCard(Modifier.widthIn(max = 430.dp).testTag("share_contact_verification_complete")) {
            Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("✓", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(Res.string.share_contact_verified_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(Res.string.share_contact_verified_description, state.alias))
                Button(onClick = controller::finishContactVerification, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(Res.string.done)) }
            }
        }
    }
}

@Composable
private fun SenderConnectingScreen(state: SharingSenderState.Connecting, controller: LocalAppController) {''')

path = 'shared/ui-features/src/commonMain/composeResources/values/strings.xml'
replace_once(path,
'''    <string name="share_receive">Recibir archivo</string>
''',
'''    <string name="share_receive">Recibir archivo</string>
    <string name="share_verify_contact">Verificar contacto</string>
    <string name="share_verify_contact_description">Comprueba un contacto por su código antes de enviar o recibir archivos. El código sólo localiza el dispositivo; debes comparar el fingerprint por otro canal.</string>
    <string name="share_check_identity">Comprobar</string>
    <string name="share_contact_verified_title">Contacto verificado</string>
    <string name="share_contact_verified_description">%1$s quedó guardado como contacto de confianza para este espacio.</string>
''')

path = 'shared/ui-features/src/commonTest/kotlin/dev/veilshare/ui/features/LocalAppControllerTest.kt'
replace_once(path,
'''    @Test fun cancelWhileSendingPropagatesAndLeavesTerminalCancelledState() = runTest {''',
'''    @Test fun contactCanBeVerifiedBeforeAnyFileTransfer() = runTest {
        val runtime = RecordingSharingRuntime(
            peerLookupResult = SharingPeerLookupResult.NeedsVerification(
                fingerprint = "12".repeat(32),
                reason = SharingVerificationReason.NEW_PEER,
            ),
        )
        val controller = controller(FakeService(LocalStorageState.READY), runtime)
        controller.initialize(); controller.unlock("1111".toCharArray()); advanceUntilIdle()
        controller.startContactVerification()
        controller.enterContactVerificationReferenceCode(TEST_REFERENCE.value)
        controller.lookupContactForVerification(); advanceUntilIdle()
        val verification = assertIs<SharingContactVerificationState.VerificationRequired>(assertIs<RootState.SharingContactVerification>(controller.state.value).state)
        assertEquals(TEST_REFERENCE, verification.referenceCode)
        assertEquals(1, runtime.inspectCalls)
        assertEquals(0, runtime.sendCalls)
        controller.confirmContactVerification("Alice"); advanceUntilIdle()
        assertEquals(1, runtime.confirmCalls)
        val completed = assertIs<SharingContactVerificationState.Completed>(assertIs<RootState.SharingContactVerification>(controller.state.value).state)
        assertEquals("Alice", completed.alias)
        controller.finishContactVerification(); advanceUntilIdle()
        assertIs<RootState.Unlocked>(controller.state.value)
        assertEquals(0, runtime.sendCalls)
    }

    @Test fun cancelWhileSendingPropagatesAndLeavesTerminalCancelledState() = runTest {''')
replace_once(path,
'''    private val verificationResult: SharingVerificationResult = SharingVerificationResult.Verified,
    private val suspendSend: Boolean = false,
''',
'''    private val verificationResult: SharingVerificationResult = SharingVerificationResult.Verified,
    private val peerLookupResult: SharingPeerLookupResult = SharingPeerLookupResult.Trusted("Known contact"),
    private val suspendSend: Boolean = false,
''')
replace_once(path,
'''    var activateCalls = 0
    var sendCalls = 0
''',
'''    var activateCalls = 0
    var inspectCalls = 0
    var sendCalls = 0
''')
replace_once(path,
'''    override suspend fun send(
''',
'''    override suspend fun inspectPeer(referenceCode: ReferenceCode): SharingPeerLookupResult {
        inspectCalls++
        lastReferenceCode = referenceCode
        return peerLookupResult
    }

    override suspend fun send(
''')
