# Handshake Contract V1

Date: 2026-08-15
Author: VeilShare Agent
Version: V1

## Overview

This document specifies the security contract for sharing protocol handshake.

**V1 Approach:**
- No production handshake crypto yet
- Conceptual contract documented
- Separate identity keys from vault keys
- Transcript binding for replay resistance

## Threat Model

### T1: MITM During Session Establishment

**Threat:**
Attacker intercepts session setup, modifies keys.

**Mitigation:**
- Fresh ephemeral keys per session
- Transcript binding
- Peer identity verification
- AEAD with distinct keys

### T2: Replay Attacks

**Threat:**
Attacker replays captured session messages.

**Mitigation:**
- Session uniqueness (sessionId)
- Nonces in messages
- Signature verification
- Sequence numbers

### T3: Key Confusion

**Threat:**
Attacker convinces peer to accept wrong key.

**Mitigation:**
- Key confirmation via test message
- E2E integrity verification
- Reject unexpected key material

### T4: Key Separation Failure

**Threat:**
Transfer keys derived from vault keys.

**Mitigation:**
- Independent ephemeral key generation
- No reuse of VMK, KEK, FileKey
- Separate key hierarchy

## Key Architecture

### Long-Term Identity (Per Sharing Context)

```kotlin
data class SharingIdentityKeyPair(
    val privateKey: Ed25519PrivateKey,
    val publicKey: Ed25519PublicKey,
    val sharingIdentityId: SharingIdentityId
)
```

**Properties:**
- Ed25519 (not Ed25519 from vault)
- Independent per sharing context
- Not derived from vault keys
- Rotatable
- Stored in context-specific storage (not frozen vault)

**Key Generation:**
```kotlin
val keyPair = Ed25519KeyPair.generate() // Crypto library choice
val sharingIdentityId = SharingIdentityId(
    value = SecureRandom().nextUUID().toString()
)
```

**Storage:**
- Phase 1: In-memory per context
- Future: Encrypted file store per context
- Not frozen vault formats

### Ephemeral Session Keys

```kotlin
data class SessionKeyPair(
    val privateKey: X25519PrivateKey,
    val publicKey: X25519PublicKey
)
```

**Properties:**
- X25519 (not X25519 from vault)
- Fresh per session
- Ephemeral (TTL-based)
- Independent keys

**Generation:**
```kotlin
val sessionKeyPair = X25519KeyPair.generate() // Crypto library choice
```

### Transcript Keys

Derived from transcript hash:

```kotlin
data class TranscriptKeys(
    val transcriptHash: ByteArray,
    val sharedSecret: ByteArray,
    val senderToReceiverKey: ByteArray,
    val receiverToSenderKey: ByteArray
)
```

**Derivation:**
```
transcriptHash = HKDF-SHA-256(
    salt = "transcript-binding-v1",
    ikm = sharedSecret,
    length = 32
)

sharedSecret = ECDH(keyPairA, keyPairB)

senderToReceiverKey = HKDF-SHA-256(
    salt = "s2r-key-v1",
    ikm = sharedSecret,
    length = 32
)

receiverToSenderKey = HKDF-SHA-256(
    salt = "r2s-key-v1",
    ikm = sharedSecret,
    length = 32
)
```

### Transfer Keys (AEAD)

```kotlin
data class TransferKeyPair(
    val senderToReceiverKey: AEADKey,
    val receiverToSenderKey: AEADKey
)
```

**Properties:**
- Independent per direction
- Not vault keys
- Fresh per transfer
- AEAD (ChaCha20-Poly1305)

## Handshake Sequence

### Phase 1: Session Initiation

```
Sender → Receiver: SESSION_HELLO(
    sharingIdentityId: SHA256(sharingIdentityIdValue),
    sharingPublicKey: Base64(sharingIdentityPublicKey),
    sessionId: SHA256(sessionIdValue),
    protocolVersion: 1
)
```

**Receiver verifies:**
- protocolVersion == 1
- Valid signature over SESSION_HELLO
- sessionId valid
- sharingIdentityId not seen before (or acceptable reuse)

### Phase 2: Session Confirmation

```
Receiver → Sender: SESSION_CONFIRM(
    sharingIdentityId: SHA256(sharingIdentityIdValue),
    sharingPublicKey: Base64(sharingIdentityPublicKey),
    sessionId: SHA256(sessionIdValue),
    receiverPublicKey: Base64(receiverEphemeralPublicKey),
    protocolVersion: 1
)
```

**Sender generates ephemeral key:**
```kotlin
val ephemeralKeyPair = X25519KeyPair.generate()
```

**Sender responds:**
```
Sender → Receiver: SESSION_CONFIRM(ACK)(
    sessionId: SHA256(sessionIdValue),
    ephemeralPublicKey: Base64(ephemeralPublicKey),
    transcriptHash: SHA256(transcript)
)
```

### Phase 3: Key Agreement

**Sender derives:**
```kotlin
sharedSecret = ECDH(
    ephemeralPrivateKey,
    receiverPublicKey
)

transcriptHash = HKDF-SHA-256(
    salt = "transcript-binding-v1",
    ikm = ephemeralSharedSecret,
    length = 32
)
```

**Receiver derives:**
```kotlin
sharedSecret = ECDH(
    ephemeralPrivateKey,
    senderPublicKey
)
```

**Verify transcript match.**

### Phase 4: Transfer Key Derivation

```kotlin
senderToReceiverKey = HKDF-SHA-256(
    salt = "s2r-key-v1",
    ikm = sharedSecret,
    length = 32
)

receiverToSenderKey = HKDF-SHA-256(
    salt = "r2s-key-v1",
    ikm = sharedSecret,
    length = 32
)
```

### Phase 5: Transfer Start

```
Sender → Receiver: DATA(
    transferId: SHA256(transferIdValue),
    fileId: SHA256(fileIdValue),
    ciphertext: AENC(
        key = senderToReceiverKey,
        nonce = nonce(),
        data = plaintext
    ) || tag
)
```

## Transcript Definition

```kotlin
transcript = [
    SESSION_HELLO.protocolVersion,
    SESSION_HELLO.senderSharingIdentityIdHash,
    SESSION_HELLO.sessionIdHash,
    SESSION_CONFIRM.protocolVersion,
    SESSION_CONFIRM.receiverSharingIdentityIdHash,
    SESSION_CONFIRM.sessionIdHash,
    SESSION_CONFIRM.ephemeralPublicKeyHash,
    SESSION_CONFIRM.ACK.protocolVersion,
    SESSION_CONFIRM.ACK.sessionIdHash,
    SESSION_CONFIRM.ACK.ephemeralPublicKeyHash,
    SESSION_CONFIRM.ACK.transcriptHash
]
```

**Hash:** SHA256 or HMAC-SHA256 for MAC

## Replay Resistance

### Session Uniqueness

```kotlin
sessionId = SecureRandom().nextBytes(16) // 128 bits
```

**Properties:**
- UUID v4 or random bytes
- 2^128 possible sessions
- Collisions negligible

### Nonces

```kotlin
nonce = SecureRandom().nextBytes(12) // 96 bits per message
```

**Per-message nonces prevent replay.**

### Sequence Numbers

```kotlin
sequenceNumber = AtomicInteger(0)
```

**Monotonic per direction.**

## Key Separation

### Vault Key Independence

**NEVER reuse:**
- VMK (VaultMasterKey)
- KEK (KeyEncryptionKey)
- FileKey
- Catalog key

**Transfer keys are:**
- Fresh ephemeral
- Derived from ephemeral agreement
- Not vault keys

### Sharing Identity Key Separation

**SharingIdentityKey is:**
- Ed25519 (not vault signing key)
- Separate from vault identity
- Per sharing context
- Not derived from vault keys

### Key Hierarchy

```
Vault Keys (FROZEN)
  ├── VMK
  ├── KEK
  ├── FileKey
  └── Catalog key

Sharing Keys (Separate)
  ├── SharingIdentityKey (Ed25519)
  │   └── Per context
  ├── SessionKey (X25519)
  │   └── Per session
  ├── TranscriptKeys
  │   └── Per session
  └── TransferKeys (AEAD)
      ├── senderToReceiverKey
      └── receiverToSenderKey
```

## Security Properties

### 1. Authentication

- Sender authenticated by sharingPublicKey
- Receiver authenticated by sharingPublicKey
- Signature verification

### 2. Integrity

- All messages authenticated
- AEAD for transfer data
- Transcript binding

### 3. Forward Secrecy

- Ephemeral session keys
- Not derived from long-term keys
- Past sessions not compromised

### 4. Replay Resistance

- Session uniqueness
- Nonces
- Sequence numbers
- Transcript binding

### 5. Key Separation

- Transfer keys independent
- Not vault keys
- Per session
- Per direction

## Error Handling

### Invalid Protocol Version

```kotlin
if (message.protocolVersion != 1) {
    throw ProtocolVersionMismatch(
        expected = 1,
        actual = message.protocolVersion
    )
}
```

### Invalid Signature

```kotlin
if (!verify(message, sharingIdentityPublicKey)) {
    throw InvalidSignature()
}
```

### Duplicate Session

```kotlin
if (sessionId in seenSessions) {
    throw DuplicateSession(sessionId)
}
```

### Invalid Transcript

```kotlin
if (expectedTranscriptHash != actualTranscriptHash) {
    throw InvalidTranscript()
}
```

## Implementation Notes

### Crypto Library Choice

**Current:**
- No crypto library defined yet
- Use existing `:shared:core-crypto` if compatible
- Or add minimal crypto module

**Recommended:**
- Ed25519 for signatures
- X25519 for key agreement
- HKDF-SHA-256 for key derivation
- ChaCha20-Poly1305 for AEAD

### Implementation Phases

**Phase 1 (V1 Foundation):**
- DTOs for messages
- State machine
- No crypto implementation yet
- Conceptual contract

**Phase 2 (Implementation):**
- Crypto library integration
- Handshake implementation
- Session management

**Phase 3 (Hardening):**
- Replay attack tests
- Key rotation
- Security review

## Testing

### Handshake Tests

1. Valid handshake sequence
2. Invalid protocol version
3. Invalid signature
4. Duplicate session
5. Invalid transcript
6. Key separation verification
7. Replay attack simulation

### State Machine Tests

1. Idle → Ready → ResolvingPeer → CreatingSession → WaitingForPeer
2. WaitingForPeer → Negotiating → Transferring
3. Transferring → Verifying → Completed
4. Transferring → Cancelled
5. Transferring → Failed
6. Invalid transition detection
7. Timeout handling

---

**Next Action:** Create presence/session registry implementation.
