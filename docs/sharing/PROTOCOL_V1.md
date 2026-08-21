# Sharing / Signaling Protocol V1 (Corrected)

Date: 2026-08-15
Author: VeilShare Agent
Version: V1 CORRECTED

## Overview

Protocol V1 enables file sharing between two VeilShare clients via a signaling server. The server coordinates rendezvous but does not store content or keys.

**Critical Corrections:**
- Separate `SharingIdentityId` per sharing context (not common identity)
- Random `ReferenceCode` (not derived from identity)
- WebSocket relay only (no WebRTC in V1)
- All metadata inside E2E channel (server blind)
- No per-chunk ACKs (full restart on disconnect)

## Protocol Version

```
protocolVersion = "1"
```

Clients and server reject incompatible versions with clear error message.

## Identity Architecture

### SharingIdentityId (V1 Critical)

Each sharing context has independent identity:

```kotlin
data class SharingIdentityId(
    val value: String,        // UUID v4 string
    val expiryAt: Long        // Optional, for rotation
)
```

**Generation:**
```kotlin
val sharingIdentityId = SharingIdentityId(
    value = SecureRandom().nextUUID().toString(),
    expiryAt = currentTimeMillis() + sessionTTL
)
```

**No derivation from:**
- Public identity
- Device ID
- Installation ID
- Hardware fingerprint

### SharingIdentityKey

Long-term identity key per context:

```kotlin
data class SharingIdentityKey(
    val privateKey: Ed25519PrivateKey,
    val publicKey: Ed25519PublicKey
)
```

**Key separation:**
- Independent from vault keys (VMK, KEK, FileKey)
- Independent per sharing context
- Not stored in frozen vault formats

### ReferenceCode (V1 Critical)

Random routing token, not identity-derived:

```kotlin
data class ReferenceCode(
    val code: String,         // Base32 80+ bits + checksum
    val checksum: String      // Optional Luhn checksum
)
```

**Generation:**
```kotlin
val referenceCode = ReferenceCode(
    code = randomBase32(highEntropyString), // ~80 bits
    checksum = calculateChecksum(code) // Optional
)
```

**Properties:**
- Human-readable Base32
- Checksum for typo detection
- Rotatable and revocable
- Not derived from identity

### PublicIdentity (Separate)

```kotlin
data class PublicIdentity(
    val id: String,              // Base64-encoded Ed25519 key
    val fingerprint: String      // SHA-256(id) as hex
)
```

**Use:**
- Verification of sender authenticity
- Optional pinning
- Not used for routing

## Message Structure

### Frame Envelope (Server-visible)

```json
{
  "v": 1,                              // protocolVersion
  "id": "msg-uuid",                    // MessageId
  "type": "REGISTER|UNREGISTER|LOOKUP|RELAY|PING|ERROR",
  "sessionId": "session-uuid",         // Optional
  "payload": {}                       // Opaque bytes
}
```

**Envelope fields (server sees):**
- `protocolVersion`: version check
- `messageId`: dedup
- `sessionId`: routing
- `type`: routing

**Envelope does NOT include:**
- Filename
- MIME
- File size (explicit)
- Digest
- Vault type
- REAL/DECOY label

### E2E Payload (Peer-only)

Encrypted/authenticated, server blind:

```json
{
  "version": 1,
  "transferId": "transfer-uuid",       // Unique per transfer
  "messageType": "SESSION_HELLO|OFFER|ACCEPT|DATA|COMPLETE|CANCEL",
  "filename": "example.txt",           // E2E only
  "mimeType": "text/plain",            // E2E only
  "size": 123456,                      // E2E only
  "digest": "sha256-hash",             // E2E only
  "ciphertext": "base64-encrypted",    // E2E only
  "authTag": "base64-auth-tag"         // E2E only
}
```

**Payload encryption:**
- Ephemeral session keys (X25519)
- Transcript binding
- AEAD
- Independent from vault keys

## Signaling Messages

### REGISTER

Client registers presence:

```json
{
  "v": 1,
  "id": "register-uuid",
  "type": "REGISTER",
  "payload": {
    "sharingIdentityId": "uuid-string",
    "referenceCode": "M7QK-...",
    "sharingPublicKey": "base64-ed25519",
    "protocolVersion": 1
  }
}
```

### UNREGISTER

Client unregisters:

```json
{
  "v": 1,
  "id": "unregister-uuid",
  "type": "UNREGISTER",
  "payload": {
    "sharingIdentityId": "uuid-string"
  }
}
```

### LOOKUP

Request peer by reference code:

```json
{
  "v": 1,
  "id": "lookup-uuid",
  "type": "LOOKUP",
  "payload": {
    "referenceCode": "M7QK-2P9D-V4TX-H8CN",
    "requestorSharingIdentityId": "requester-uuid"
  }
}
```

**Response:**

```json
{
  "v": 1,
  "id": "lookup-uuid",
  "type": "FOUND|NOT_FOUND|INVALID",
  "payload": {
    "sharingIdentityId": "target-uuid",   // if FOUND
    "publicIdentity": "base64-key",       // if FOUND
    "status": "offline"                   // if NOT_FOUND
  }
}
```

### RELAY

Forwarded message between peers:

```json
{
  "v": 1,
  "id": "relay-uuid",
  "type": "RELAY",
  "payload": {
    "to": "targetReferenceCode",
    "sessionId": "session-uuid",
    "transferId": "transfer-uuid",
    "envelope": {
      "type": "SESSION_HELLO|OFFER|ACCEPT|DATA|COMPLETE|CANCEL",
      "payload": "opaque-base64"
    }
  }
}
```

**Server behavior:**
- Forward only if both endpoints authenticated
- Do not inspect payload
- Do not persist (ephemeral)
- Blind relay

### PING

Keepalive:

```json
{
  "v": 1,
  "id": "ping-uuid",
  "type": "PING",
  "payload": {
    "timestamp": "epoch-ms"
  }
}
```

### ERROR

Error response:

```json
{
  "v": 1,
  "id": "error-uuid",
  "type": "ERROR",
  "payload": {
    "errorCode": "PROTOCOL_VERSION_MISMATCH|INVALID_MESSAGE|RATE_LIMITED",
    "details": "error-description"
  }
}
```

## Peer E2E Messages (Conceptual)

### SESSION_HELLO

Initiate transfer:

```json
{
  "version": 1,
  "transferId": "transfer-uuid",
  "sessionId": "session-uuid",
  "senderSharingIdentityId": "sender-uuid",
  "senderPublicIdentity": "base64-key",
  "totalFiles": 1,
  "totalBytes": 123456,
  "protocolVersion": 1
}
```

### SESSION_CONFIRM

Receiver confirms session setup:

```json
{
  "version": 1,
  "transferId": "transfer-uuid",
  "sessionId": "session-uuid",
  "type": "CONFIRM",
  "protocolVersion": 1
}
```

### OFFER

File offer:

```json
{
  "version": 1,
  "transferId": "transfer-uuid",
  "sessionId": "session-uuid",
  "type": "OFFER",
  "files": [
    {
      "fileId": "file-uuid",
      "displayName": "example.txt",
      "mimeType": "text/plain",
      "size": 123456
    }
  ],
  "protocolVersion": 1
}
```

### ACCEPT / REJECT

Receiver response:

```json
{
  "version": 1,
  "transferId": "transfer-uuid",
  "sessionId": "session-uuid",
  "type": "ACCEPT|REJECT",
  "reason": "user-accepted|unsupported" // if REJECT
}
```

### DATA

File data chunk:

```json
{
  "version": 1,
  "transferId": "transfer-uuid",
  "sessionId": "session-uuid",
  "fileId": "file-uuid",
  "chunkIndex": 0,
  "nonce": "ephemeral-nonce",
  "ciphertext": "base64-encrypted-chunk",
  "authTag": "base64-auth-tag",
  "protocolVersion": 1
}
```

**Note:** No per-chunk ACK. Full restart on disconnect.

### COMPLETE

Transfer complete:

```json
{
  "version": 1,
  "transferId": "transfer-uuid",
  "sessionId": "session-uuid",
  "type": "COMPLETE",
  "receivedFiles": 1,
  "protocolVersion": 1
}
```

### CANCEL

Cancel transfer:

```json
{
  "version": 1,
  "transferId": "transfer-uuid",
  "sessionId": "session-uuid",
  "type": "CANCEL",
  "protocolVersion": 1
}
```

### FAILURE

Error:

```json
{
  "version": 1,
  "transferId": "transfer-uuid",
  "sessionId": "session-uuid",
  "type": "FAILURE",
  "errorCode": "DECORPORATED|TIMEOUT|ERROR",
  "protocolVersion": 1
}
```

## Versioning

Current: `protocolVersion = 1`

Breaking changes require incrementing version:
- Message format changes
- New message types
- Security enhancements

Clients reject incompatible versions with:

```json
{
  "v": 1,
  "id": "error-uuid",
  "type": "ERROR",
  "payload": {
    "errorCode": "PROTOCOL_VERSION_MISMATCH",
    "details": "Your client uses version 1.0, this server requires 1.0"
  }
}
```

## Handshake Contract (Conceptual)

### Long-Term Identity

- Ed25519 per sharing context
- Independent per context
- Not derived from vault keys
- Rotatable

### Ephemeral Session Keys

- X25519 per session
- Transcript binding with HKDF-SHA-256
- AEAD per direction
- Independent keys

### Transcript Binding

Binds:
- protocolVersion
- sender identity
- receiver identity
- SessionId
- TransferId
- ephemeral keys

### Replay Resistance

- Session uniqueness
- Nonces/signatures
- Monotonic sequence numbers

### Key Separation

- Transfer traffic keys independent
- Not VMK, KEK, FileKey
- Not catalog keys
- Separate per session

## Idempotency

- **TransferId** unique per transfer attempt
- **(transferId, fileId, chunkIndex)** unique triple for chunks
- Duplicate chunk: validate → discard if durable → ACK
- Duplicate SESSION_HELLO: sender cancels or ignores (race)
- Receiver cancel after catalog commit: file exists, ignore cancel

## Security Boundaries

### Server Sees (Envelope Only)

- Encrypted frames (via WebSocket)
- Frame sizes
- Timing
- Reference codes (routing)
- SharingIdentityIds (routing)
- Coarse transfer sizes

### Server Does NOT See (E2E)

- Plaintext file contents
- File names (in E2E channel)
- FileKeys
- VaultKeys
- E2E session keys
- Vault type
- REAL/DECOY labels
- SharingIdentity key material

### Peer-to-Peer Channel

- All transfer messages E2E encrypted
- Session keys derived per-transfer
- Transcript hash provides forward secrecy
- Signature over transcript authenticates transcript

---

## Message Flow

### Initiate Transfer

```
Sender → Signaling: REGISTER(sharingIdentity, referenceCode)
  ↓
Signaling: LOOKUP(referenceCode)
  ↓
Signaling → Sender: FOUND(targetSharingIdentity)
  ↓
Sender → Signaling: SESSION_HELLO
  ↓
Signaling → Receiver: RELAY(SESSION_HELLO)
  ↓
Receiver → Signaling: SESSION_ACCEPT
  ↓
Signaling → Sender: RELAY(SESSION_ACCEPT)
  ↓
Sender → Receiver: DATA*
  ↓
Sender → Receiver: COMPLETE
  ↓
Receiver → Sender: COMPLETE
```

### Cancel Flow

```
Sender → Receiver: CANCEL
  ↓
Receiver: discard pending data
  ↓
Sender: stop sending
```

---

**Next Action:** Create HANDSHAKE_CONTRACT.md for precise security specifications.
