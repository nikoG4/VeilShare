// CONCEPTUAL: nombres y tipos finales pueden cambiar.

interface BlobStore {
    suspend fun create(): BlobWriter
    suspend fun open(id: BlobId): BlobReader
    suspend fun delete(id: BlobId)
}

interface ImportPicker {
    suspend fun pick(request: ImportRequest): List<ImportHandle>
}

interface OriginalDeletion {
    suspend fun delete(handle: ImportHandle): DeleteOriginalResult
}

interface SecretStore {
    suspend fun createKey(alias: SecretAlias, policy: AccessPolicy): SecretHandle
    suspend fun seal(handle: SecretHandle, data: ByteArray, aad: ByteArray): ByteArray
    suspend fun unseal(handle: SecretHandle, data: ByteArray, aad: ByteArray): ByteArray
}

interface PasswordKdf {
    suspend fun derive(secret: SensitiveChars, salt: ByteArray, params: KdfParams): SensitiveBytes
}

interface PeerTransport {
    val incoming: kotlinx.coroutines.flow.Flow<TransportFrame>
    suspend fun connect(remote: PeerDescriptor)
    suspend fun send(frame: TransportFrame)
    suspend fun close()
}

interface DisguiseController {
    suspend fun supportedProfiles(): List<DisguiseProfile>
    suspend fun apply(profile: DisguiseProfile): DisguiseResult
}
