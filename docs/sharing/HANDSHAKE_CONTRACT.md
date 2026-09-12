# Handshake Contract V1

Status: implementation contract for the current `security/critical-sharing-fixes` branch.

This document describes what the code actually does. It is not a future design sketch.

## Security boundary

The handshake establishes authenticated per-session traffic keys for sharing. It is independent from all vault key material.

Primitives:

- Ed25519: long-term sharing-identity authentication.
- X25519: fresh ephemeral key agreement per session.
- SHA-256: identity/session/transcript hashes.
- HKDF-SHA-256: directional traffic-key derivation.
- ChaCha20-Poly1305: DATA protection after the handshake.

Never reuse VMK, KEK, FileKey, catalog keys, slot keys, or vault-derived identity material as sharing keys.

## Trusted identity requirement

A signature is useful only when verified against the public key already expected for the peer.

Therefore:

- `SESSION_HELLO` is verified against the expected sender Ed25519 public key.
- `SESSION_CONFIRM` is verified against the expected receiver Ed25519 public key.
- `SESSION_CONFIRM_ACK` is verified against the expected sender Ed25519 public key.

A public key carried inside a message is checked for equality with the expected trusted key; it is never accepted merely because it verifies its own message.

How a contact becomes trusted/pinned is outside this handshake primitive and must be handled by the contact/discovery layer.

## Canonical encoding

Handshake signatures and transcript hashes never use delimiter-based string concatenation.

The canonical encoder is:

```
length(domain) || domain
protocolVersion:u32-be
length(field1) || field1
length(field2) || field2
...
```

All lengths are unsigned-style 32-bit big-endian integer encodings of byte length. Text fields are UTF-8.

Domain separation strings:

- `VEILSHARE/HANDSHAKE/SESSION_HELLO/V1`
- `VEILSHARE/HANDSHAKE/SESSION_CONFIRM/V1`
- `VEILSHARE/HANDSHAKE/SESSION_CONFIRM_ACK/V1`
- `VEILSHARE/HANDSHAKE/TRANSCRIPT/V1`
- `VEILSHARE/HANDSHAKE/HKDF/V1`

This prevents ambiguity between field boundaries and prevents signatures from one handshake phase being reinterpreted as another phase.

## Message sequence

### 1. SESSION_HELLO — Sender -> Receiver

Payload:

- `sharingIdentityIdHash = SHA256(senderSharingIdentityId.value)`
- sender Ed25519 public key, Base64 encoded
- `sessionIdHash = SHA256(sessionId.value)`
- protocol version
- Ed25519 signature

Signed canonical fields:

```
domain = SESSION_HELLO
protocolVersion
senderIdentityIdHash
sessionIdHash
```

Receiver must already know:

- expected sender identity-id hash;
- expected session-id hash;
- expected sender Ed25519 public key.

The message is rejected on any mismatch or invalid signature.

### 2. SESSION_CONFIRM — Receiver -> Sender

Receiver generates a fresh X25519 key pair.

Payload:

- receiver sharing-identity-id hash
- receiver Ed25519 public key
- session-id hash
- receiver X25519 ephemeral public key, Base64 encoded
- protocol version
- Ed25519 signature

Signed canonical fields:

```
domain = SESSION_CONFIRM
protocolVersion
receiverIdentityIdHash
sessionIdHash
receiverEphemeralPublicKey
```

Sender verifies the signature using the already expected receiver Ed25519 key and separately verifies that both the carried identity key and receiver ephemeral key equal the expected values for the active handshake.

### 3. SESSION_CONFIRM_ACK — Sender -> Receiver

`PeerMessageType.SESSION_CONFIRM_ACK` is an explicit wire type.

Sender generates a fresh X25519 key pair and constructs the canonical transcript:

```
protocolVersion
senderIdentityIdHash
receiverIdentityIdHash
sessionIdHash
senderEphemeralPublicKey
receiverEphemeralPublicKey
```

The transcript hash is:

```
SHA256(canonicalTranscriptBytes)
```

ACK payload:

- sender identity-id hash
- session-id hash
- sender X25519 ephemeral public key
- transcript hash
- protocol version
- Ed25519 signature

ACK signature covers:

```
domain = SESSION_CONFIRM_ACK
protocolVersion
senderIdentityIdHash
sessionIdHash
senderEphemeralPublicKey
transcriptHash
```

The receiver obtains the sender ephemeral key from the ACK, rebuilds the expected transcript using its already-known receiver ephemeral key and both expected peer identities, recomputes the transcript hash, and verifies the ACK signature with the already trusted sender Ed25519 public key.

Therefore an attacker cannot substitute the sender ephemeral key and simply recompute the public transcript hash: the replacement would also require a valid sender Ed25519 signature.

## Key derivation

After ACK verification both peers possess the same authenticated transcript and opposite halves of the same X25519 exchange.

Each peer computes:

```
sharedSecret = X25519(localEphemeralPrivateKey, peerEphemeralPublicKey)
transcriptHash = SHA256(canonicalTranscriptBytes)
```

HKDF info is transcript-bound and direction-separated:

```
S2R info = canonical(
    domain = VEILSHARE/HANDSHAKE/HKDF/V1,
    protocolVersion = 1,
    "SENDER_TO_RECEIVER",
    transcriptHash
)

R2S info = canonical(
    domain = VEILSHARE/HANDSHAKE/HKDF/V1,
    protocolVersion = 1,
    "RECEIVER_TO_SENDER",
    transcriptHash
)
```

Then:

```
senderToReceiverKey = HKDF-SHA256(sharedSecret, S2R info, 32)
receiverToSenderKey = HKDF-SHA256(sharedSecret, R2S info, 32)
```

The same X25519 secret under a different authenticated transcript therefore produces different traffic keys.

The implementation best-effort zeroizes/closes the shared secret and temporary derived sensitive buffers after copying the final traffic-key bytes into `HandshakeKeys`.

## DATA binding after handshake

DATA encryption additionally authenticates canonical AAD containing:

- protocol version;
- direction;
- transfer-id hash;
- file-id hash;
- chunk index;
- total chunks.

Session separation comes from the transcript-bound per-session traffic key.

Transport fragmentation occurs after crypto-chunk encryption. Fragments are reassembled before AEAD verification and cannot alter the authenticated crypto-chunk metadata.

## Replay model

Handshake messages are bound to `sessionIdHash`. The session/control layer must reject replay/reuse of an already consumed or invalid session identifier.

V1 does **not** claim a generic monotonic sequence number for every handshake message. Do not add that claim to documentation unless the implementation actually adds it.

DATA retries retransmit the same already-encrypted logical frame/fragment. They do not re-encrypt modified content with the same key/nonce.

## Failure behavior

Any of the following fail closed:

- unsupported protocol version;
- identity-id mismatch;
- session-id mismatch;
- carried identity key != expected peer key;
- invalid Ed25519 signature;
- receiver ephemeral mismatch in `SESSION_CONFIRM`;
- transcript mismatch in ACK;
- sender ephemeral substitution in ACK;
- malformed Base64/key material;
- HKDF/X25519 failure.

No session traffic key should be considered authenticated until `SESSION_CONFIRM_ACK` verification succeeds.

## Required tests

The baseline must keep tests for:

- HELLO roundtrip and invalid signature;
- peer identity key substitution;
- CONFIRM roundtrip and receiver ephemeral mutation;
- signed ACK roundtrip;
- sender ephemeral replacement with recomputed transcript hash;
- wrong trusted sender identity key;
- transcript mutation;
- canonical field-boundary ambiguity;
- sender/receiver X25519 commutativity;
- identical directional keys on both peers for the same transcript;
- different keys for the same X25519 secret under a different transcript;
- Desktop and Android compilation.

Native iOS verification still requires macOS/Xcode. Source compatibility review is not equivalent to a native iOS build.
