# Transfer Protocol V1 — Implemented Contract

Status: implementation contract for the current sharing hardening branch.

This document describes the code paths in `core-model`, `core-transfer`, `core-platform`, and `server:signaling`.

## 1. Layering

```
validated peer/contact identity
        ↓
authenticated Ed25519/X25519 handshake
        ↓
transcript-bound directional session key
        ↓
TransferOffer / Accept / Reject
        ↓
crypto chunk (default 1 MiB plaintext)
        ↓
ChaCha20-Poly1305 + canonical DATA AAD
        ↓
transport fragmentation (if required)
        ↓
PeerEnvelope
        ↓
RelayRequest
        ↓
SignalingEnvelope / WebSocket
        ↓
receiver reassembly + AEAD verification
        ↓
bounded encrypted transfer state
        ↓
TransferImportSource
        ↓
VaultHandle.import()
        ↓
VBL1 + encrypted catalog/journal durability
```

The signaling server is a blind relay. It does not receive vault keys, session keys, plaintext file data, file names, MIME metadata, or REAL/DECOY state.

## 2. Peer message types

`PeerMessageType` V1:

- `SESSION_HELLO`
- `SESSION_CONFIRM`
- `SESSION_CONFIRM_ACK`
- `OFFER`
- `ACCEPT`
- `REJECT`
- `DATA`
- `COMPLETE`
- `CANCEL`
- `FAILURE`

`PeerMessageCodec` is the canonical typed encode/decode gateway. Higher layers should not manually deserialize arbitrary payload JSON.

`PeerSessionGate` additionally binds a decoded envelope to the expected:

- `SessionId`
- `TransferId`
- optional `FileId`
- transfer-id hash
- file-id hash

DATA/CANCEL/FAILURE may then be passed through `ReceiverPeerDispatcher`.

## 3. Control messages

### OFFER

`TransferOffer` contains:

- `FileId`
- canonical `displayName`
- optional canonical `mimeHint`
- positive `sizeBytes`
- positive `totalChunks`
- protocol version

V1 does not support empty files.

Display name limits:

- non-blank;
- no surrounding whitespace;
- maximum 255 characters;
- no `/` or `\\`;
- no ASCII control characters.

MIME hint limits:

- null or non-blank canonical text;
- maximum 255 characters;
- no control characters.

### ACCEPT / REJECT

`TransferAccept` identifies the accepted `FileId`.

`TransferReject` identifies the rejected `FileId` and a canonical reason up to 512 characters.

### FAILURE

`TransferFailure` contains a transfer-id hash, bounded details, and one of:

- `PROTOCOL_ERROR`
- `AUTHENTICATION_FAILED`
- `DECRYPTION_FAILED`
- `LIMIT_EXCEEDED`
- `TRANSFER_EXPIRED`
- `CANCELLED`
- `IO_ERROR`
- `INTERNAL_ERROR`

Failure messages are diagnostics/control. They do not expose private exception stacks or key material.

## 4. Crypto chunk

Default plaintext crypto chunk:

```
1_048_576 bytes (1 MiB)
```

Each logical crypto chunk is encrypted once with ChaCha20-Poly1305 using a fresh 96-bit nonce.

Retries retransmit the same already-produced logical ciphertext/frame. They do not modify plaintext/AAD while reusing a key+nonce.

## 5. DATA AAD

Canonical DATA AAD domain:

```
VEILSHARE-DATA-AAD-V1
```

AAD binds:

- protocol version;
- sender-to-receiver direction marker;
- transfer-id hash;
- file-id hash;
- chunk index;
- total chunks.

Fields use explicit big-endian lengths/canonical integer encodings.

Session binding is supplied by the per-session key derived from the authenticated handshake transcript.

Therefore moving ciphertext across sessions, transfers, files, or chunk positions must fail authentication or session gating.

## 6. Transport fragmentation

Fragmentation occurs **after** AEAD encryption of the complete crypto chunk.

Current limits:

- target maximum serialized `TransferData`: 16 KiB;
- maximum fragments per crypto chunk: 128;
- fragment index: `0 <= fragmentIndex < fragmentCount`;
- all fragments of one crypto chunk must carry identical nonce and fragmentCount.

The sender finds a fragment count whose largest serialized `TransferData` fits the configured frame budget. The required count is never silently clamped.

The receiver:

1. validates fragment bounds before state growth;
2. rejects duplicate fragment indices;
3. rejects nonce changes inside a chunk;
4. rejects fragmentCount changes inside a chunk;
5. bounds aggregate ciphertext before allocating the reassembled chunk;
6. orders fragments by index;
7. requires a complete contiguous sequence;
8. reassembles ciphertext;
9. verifies AEAD over the original crypto chunk;
10. zeroizes obsolete fragment copies best-effort.

Fragment fields are transport metadata, not part of the crypto-chunk AAD. Ambiguity is prevented by receiver invariants and final AEAD verification of the reassembled ciphertext.

## 7. Wire encoding

Opaque `ByteArray` fields use `Base64ByteArraySerializer` rather than JSON integer arrays.

This applies to the nested wire path including:

- `TransferData.ciphertext`
- `TransferData.nonce`
- `PeerEnvelope.payload`
- `RelayRequest.opaquePayload`
- `SignalingEnvelope.payload`

The purpose is deterministic cross-platform representation and bounded expansion across nested JSON envelopes.

## 8. Signaling size checks

The sender checks actual serialized sizes, not only arithmetic estimates.

A peer frame must fit through:

```
TransferData JSON
    ↓
PeerEnvelope JSON
    ↓
RelayRequest JSON
    ↓
SignalingEnvelope JSON / WebSocket text frame
```

The core protocol payload ceiling remains 64 KiB for signaling envelopes. Do not increase it merely to avoid fragmentation.

## 9. Signaling relay rate limits

RELAY traffic has a dedicated budget. It must not consume the session-creation limiter.

Default relay window:

- maximum 8192 messages;
- maximum 128 MiB decoded request payload;
- per connection;
- per one-minute window.

Both event count and byte-cost updates are synchronized. Concurrency must not allow the configured budget to be exceeded.

## 10. Receiver memory/lifecycle

Temporary V1 receiver safety limits:

- maximum active transfers: 2;
- maximum buffered ciphertext per transfer: 64 MiB;
- maximum chunk count: 65,536;
- maximum fragment count per crypto chunk: 128.

The receiver retains encrypted chunks, not the full plaintext file. During vault import at most the currently decrypted chunk/read slice is held in plaintext.

Incomplete/completed-but-unconsumed transfer state is bounded by lifecycle limits:

- idle timeout: 2 minutes;
- absolute lifetime: 30 minutes.

`receive()` opportunistically sweeps expired state. Applications may also call `sweepExpired()` explicitly.

`abort(transferIdHash, reason)` releases a non-importing transfer and zeroizes buffered ciphertext/nonces best-effort.

A transfer in `IMPORTING` is owned by its vault import read handle and is not swept/aborted underneath that reader.

## 11. Single-consumer import

A completed transfer may transition:

```
COMPLETE -> IMPORTING
```

exactly once.

Only one `TransferImportReadHandle` can consume the buffered transfer. This prevents two concurrent vault imports from duplicating the same accepted transfer.

Closing or exhausting the read handle releases receiver state and zeroizes buffered transfer ciphertext/nonces best-effort.

## 12. Vault integration

`ReceivedTransferVaultImporter` is the integration boundary.

Preferred path:

```
importCompleted(transferId, validatedTransferOffer, vault, ...)
```

Before opening the transfer read handle it verifies that authenticated received plaintext size equals `TransferOffer.sizeBytes`.

Then the existing crash-safe `VaultHandle.import()` owns durability:

- streaming VBL1 encryption;
- journal semantics;
- encrypted catalog commit;
- recovery/orphan behavior.

The transfer module does not receive or derive VMK/FileKey material.

## 13. Retry/cancellation

Retry is for transient transport failure only.

No retry for:

- `CancellationException`;
- `SecurityException`;
- `IllegalArgumentException`;
- non-I/O `TransferException`.

Transfer source cleanup is executed from a `NonCancellable` `finally` block.

If cancellation happens while the final fragment is being authenticated, that uncommitted fragment is removed so transport retry can submit it again safely.

## 14. Process death / restart

Current V1 does not promise remote transfer resume after process death.

Transfer state is in memory. On restart an incomplete remote transfer is abandoned and the sender must restart it with a new transfer/session as required by the session layer.

Vault durability is separate: once `VaultHandle.import()` has reached its durable phases, normal vault recovery/journal guarantees apply after restart.

No plaintext transfer staging file is part of the V1 contract.

## 15. Required security/integration tests

Keep coverage for:

- real ChaCha20-Poly1305 DATA;
- wrong transfer/file/chunk/count AAD;
- ciphertext/nonce corruption;
- fragmentation and reassembly;
- hostile fragment metadata;
- cross-session/cross-transfer gate rejection;
- cancellation/retry;
- retry ciphertext reuse;
- exact wire-size nesting;
- Base64 roundtrips;
- receiver lifecycle expiry/abort;
- single-consumer import;
- real concurrency in relay rate limiting;
- real handshake-derived traffic key -> transfer -> persistent vault -> reopen.

## 16. Known V1 constraints

- Maximum received file size is temporarily constrained by the 64 MiB encrypted receiver buffer. This is deliberate until a durable encrypted spool/stream-to-vault design replaces whole-transfer ciphertext buffering.
- Empty files are rejected.
- iOS must not be called verified until native compilation/tests run on macOS/Xcode.
- A trusted contact/pinning store is still required above the handshake so expected peer Ed25519 keys come from an authenticated user decision, not an untrusted lookup alone.
