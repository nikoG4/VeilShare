# Blueprint 13 — Error model

## Layers

### DomainError
- InvalidCredential
- IdentityChanged
- CorruptedVault
- UnsupportedFormat
- TransferRejected
- PeerOffline

### InfraError
- Io
- Permission
- Network
- ProviderUnavailable
- DiskFull

### SecurityError
- AuthenticationTagInvalid
- SignatureInvalid
- ProtocolDowngrade
- KdfParamsInvalid

## UX mapping

Security errors nunca muestran detalles cripto internos a casual UI.
Logs release usan code, no secret material.

## Retry

Annotate:
- retryable;
- requires user action;
- fatal until repair.

## Acceptance

No catch-all que convierta `SecurityError` en “retry”.
