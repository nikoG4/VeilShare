# Blueprint 02 — Modelo de dominio

## Value objects

Crear wrappers serializables:
- `VaultId`;
- `FileId`;
- `BlobId`;
- `FolderId`;
- `ContactId`;
- `TransferId`;
- `SessionId`;
- `ReferenceCode`;
- `Fingerprint`.

Evitar String crudo en APIs críticas.

## Estados

### Vault lifecycle
`Uninitialized -> Locked -> Unlocking -> Unlocked -> Locking -> Locked`

### File lifecycle
`Importing -> Verifying -> Committed -> Deleting -> Deleted`

### Transfer lifecycle
`Created -> Resolving -> Negotiating -> AwaitingAcceptance -> Transferring -> Verifying -> Completed`
con ramas `Paused/Failed/Cancelled`.

## Errores

Separar:
- user cancellation;
- retryable IO;
- security failure;
- unsupported;
- protocol mismatch;
- identity mismatch.

## Serialización

No serializar sealed classes críticas con nombres de clase implícitos sin descriptor versionado.
Protocol messages tienen `typeId` explícito.

## Acceptance

- tests exhaustive de transición inválida;
- IDs no aceptan vacío;
- no existen rutas filesystem en modelo.
