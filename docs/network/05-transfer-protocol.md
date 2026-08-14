# Protocolo de transferencia v1

## Capas

```text
Transport encryption (WebRTC/TLS)
        +
App E2E session
        +
File chunk AEAD/integrity
```

## Handshake

1. A y B ya conocen public identities.
2. Crear ephemeral agreement keys.
3. intercambiar `SESSION_HELLO`.
4. firmar transcript con identity signing key.
5. derivar shared secret.
6. HKDF con transcript hash.
7. producir `sessionTxKey/sessionRxKey`.

No inventar detalles criptográficos sin revisión; suite final se congela en ADR.

## Mensajes

- `SESSION_HELLO`
- `SESSION_ACCEPT`
- `TRANSFER_OFFER`
- `TRANSFER_ACCEPT`
- `TRANSFER_REJECT`
- `FILE_META`
- `CHUNK`
- `ACK`
- `RESUME_REQUEST`
- `RESUME_STATE`
- `TRANSFER_COMPLETE`
- `TRANSFER_CANCEL`
- `ERROR`

## Offer

Revela al receptor, dentro de E2E:
- sender;
- total files;
- total bytes;
- optional display names/previews según policy.

No enviar esa metadata por signaling server.

## Chunk

```text
transferId
fileId
chunkIndex
nonce
ciphertext
authTag
```

## Receiver pipeline

```text
CHUNK
 ↓
verify/decrypt E2E/file transfer layer
 ↓
bounded plaintext buffer
 ↓
encrypt using receiver vault FileKey
 ↓
persist receiver vault chunk
 ↓
ACK after durable write
```

ACK antes de durable write produce corrupción tras crash; prohibido.

## Idempotencia

`(transferId, fileId, chunkIndex)` único.
Chunk repetido:
- validar;
- descartar si ya durable;
- ACK nuevamente.
