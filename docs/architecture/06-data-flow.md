# Flujos de datos

## Importación

```mermaid
flowchart LR
    P[Picker nativo] --> H[ImportHandle]
    H --> S[Stream common]
    S --> C[Chunk Encryptor]
    C --> B[BlobStore cifrado]
    C --> M[ManifestBuilder]
    M --> E[Manifest cifrado]
    E --> V[Verificación]
    V --> I[Commit índice]
    I --> Q{¿Eliminar original?}
    Q -->|sí| D[Platform OriginalDeletion]
```

### Invariante
No se solicita borrar original antes de commit + verificación.

## Apertura

```mermaid
flowchart LR
    P[PIN] --> K[KDF]
    K --> U[Unwrap VaultKey]
    U --> M[Decrypt manifest]
    M --> S[Session state]
    S --> R[Render UI]
```

## Transferencia

```mermaid
sequenceDiagram
    participant A as Sender
    participant S as Signaling
    participant B as Receiver
    A->>S: presence + signed identity proof
    B->>S: presence + signed identity proof
    A->>S: offer to reference B
    S->>B: forward offer
    B->>S: answer
    S->>A: forward answer
    A->>B: P2P transport
    A->>B: signed E2E session hello
    B->>A: signed session response
    A->>B: encrypted manifest + chunks
    B->>A: ACK bitmap/ranges
```

## Receptor

El receptor no necesita escribir plaintext:
`wire ciphertext -> E2E decrypt chunk -> vault re-encrypt with receiver FileKey -> receiver BlobStore`.

Se acepta un buffer plaintext acotado en memoria durante el pipeline; debe limpiarse best-effort.
