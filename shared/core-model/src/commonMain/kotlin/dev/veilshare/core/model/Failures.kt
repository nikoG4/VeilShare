package dev.veilshare.core.model

sealed interface PlatformFailure {
    data object PermissionDenied : PlatformFailure
    data object UserCancelled : PlatformFailure
    data object Unsupported : PlatformFailure
    data object TemporarilyUnavailable : PlatformFailure
    data class Io(val code: String) : PlatformFailure
    data class Security(val code: String) : PlatformFailure
}

sealed interface DomainError { data object InvalidCredential : DomainError; data object IdentityChanged : DomainError; data object CorruptedVault : DomainError; data object UnsupportedFormat : DomainError }

sealed interface HandshakeError {
    data object ProtocolVersionMismatch : HandshakeError
    data object InvalidSignature : HandshakeError
    data object DuplicateSession : HandshakeError
    data object InvalidTranscript : HandshakeError
    data object KeyDerivationFailed : HandshakeError
    data object InvalidEphemeralKey : HandshakeError
}
