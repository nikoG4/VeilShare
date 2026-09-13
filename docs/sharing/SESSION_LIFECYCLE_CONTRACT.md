# VeilShare Sharing Session / Transfer Lifecycle Contract

Status: PR #3 implementation baseline under build validation

## Purpose

PR #1 froze the encrypted transfer/data path. PR #2 added trusted identity/contact pinning
and authenticated session establishment. This contract defines the next layer: how a live
client owns presence, pending handshakes, established session keys, offers, transfer
resources and durable vault import without exposing those lifetimes to UI code.

## Presence lifecycle

A local sharing context owns two independent values:

```text
SharingIdentityId + Ed25519 keypair   (identity/trust)
ReferenceCode                         (routing only)
```

`SharingPresenceLifecycle` serializes network presence operations.

### ensureRegistered(context)

```text
load/create identity
load/create current ReferenceCode
REGISTER(identity, code)
```

It does not rotate either value.

### rotateAndRegister(context)

Rotation is fail-closed:

```text
UNREGISTER current SharingIdentityId
        |
        | must succeed
        v
rotate local ReferenceCode
        |
        v
REGISTER(identity, replacement code)
```

Rules:

- if UNREGISTER fails, local ReferenceCode MUST NOT change;
- if replacement REGISTER fails, the old server route is already revoked;
- the newly generated local ReferenceCode remains current and a later
  `ensureRegistered()` retries that same code;
- temporary offline state is preferable to silently keeping two active routes;
- identity does not rotate when ReferenceCode rotates.

The server UNREGISTER operation removes all routes for that identity owned by the current
connection. `KtorSignalingClient.unregister()` waits for the server response.

### deactivate(context)

UNREGISTERs network presence but preserves the current local ReferenceCode so the same
route may be restored later.

## Pending handshake ownership

A valid inbound HELLO eventually creates a receiver X25519 ephemeral private key.
UI must never own that key.

`ManagedTrustedSessionCoordinator` + `TrustedSessionRegistry` own it.

Default limits:

- maximum pending handshakes: 16;
- pending timeout: 2 minutes;
- maximum established sessions: 8.

Before `SESSION_CONFIRM` is generated/sent, the coordinator performs a registry admission
check for:

- duplicate SessionId;
- pending capacity.

`beginInbound()` is serialized across the admission + responder + registration sequence.
Therefore a registry-capacity rejection sends no CONFIRM and allocates no long-lived
pending ephemeral.

The replay guard from PR #2 remains a separate, authenticated SessionId replay boundary.

### Pending cleanup

A `PendingInboundHandshake` closes its receiver X25519 private material when:

- ACK completes successfully;
- ACK verification/derivation fails;
- pending handshake times out;
- caller explicitly cancels pending state;
- duplicate/capacity registration rejects it;
- registry shuts down.

## Established session ownership

`EstablishedPeerSession` owns the four transcript-bound key arrays:

```text
S2R DATA
R2S DATA
S2R ENVELOPE
R2S ENVELOPE
```

`TrustedSessionRegistry.removeEstablished()` and registry shutdown call
`EstablishedPeerSession.close()`, which zeroizes all four arrays.

Expensive handshake completion (signature verification, X25519, HKDF) occurs outside the
registry mutex. Only pending claim and established registration are performed under lock.
If the registry closes/fills between those stages, the newly derived session is closed and
its keys are zeroized before the failure is returned.

## Per-transfer crypto adapter

`EstablishedTransferCrypto.open()` converts one established peer session into a transfer
bundle with the correct direction automatically.

Initiator:

```text
outbound DATA     = S2R DATA
inbound DATA      = R2S DATA
outbound ENVELOPE = S2R ENVELOPE
inbound ENVELOPE  = R2S ENVELOPE
```

Responder uses the inverse direction.

The adapter creates `SensitiveBytes` copies and owns them until close. UI must not select
raw directional key arrays manually.

DATA keys and ENVELOPE keys remain independent.

## OFFER validation

`TransferOffer` is E2E encrypted inside the secure peer envelope.

Before allocating receiver transfer state, `validateTransferOffer()` requires:

- `sizeBytes <= maxTransferBytes`;
- `totalChunks <= maxChunks`;
- `totalChunks == ceil(sizeBytes / configuredChunkSize)`.

A peer cannot claim a small size with a larger chunk count or vice versa.

## Sender state machine

```text
NEW
  -> OFFERED
       -> ACCEPTED -> SENDING -> COMPLETE
       -> REJECTED
       -> CANCELLED
       -> FAILED
```

DATA cannot begin before ACCEPT.

### TransferSource ownership

Before DATA starts, `OutgoingSharingTransfer` owns the source and closes it on:

- REJECT;
- CANCEL;
- FAILURE;
- explicit `abandonBeforeSend()`.

Once `sendAccepted()` begins, `DefaultTransferSender` becomes the exactly-once source
close owner.

Ciphertext/nonce wire buffers are not mutated after handoff to the transport.

## Receiver state machine

```text
OFFERED
  -> ACCEPTED
       -> RECEIVING
            -> READY_TO_IMPORT
                 -> IMPORTING -> COMPLETE
       -> CANCELLED
       -> FAILED
  -> REJECTED
```

State is checked before DATA reaches `TransferReceiver.receive()`.
DATA-before-ACCEPT must therefore have zero decryptor/receiver side effects.

Remote COMPLETE is accepted only when:

- state is ACCEPTED/RECEIVING;
- COMPLETE totalChunks equals OFFER totalChunks;
- local authenticated DATA processing has already reached `TransferComplete`.

Only then does state become READY_TO_IMPORT.

## Vault import boundary

`importIntoVault()` is legal only in READY_TO_IMPORT.

It delegates to `ReceivedTransferVaultImporter`, which checks authenticated transfer size
against OFFER and then calls the existing crash-safe encrypted `VaultHandle.import()`.

The sharing layer never receives VMK/FileKey.

Expected golden path:

```text
verified contacts
 -> Ed25519/X25519 handshake
 -> four transcript-bound keys
 -> encrypted OFFER
 -> encrypted ACCEPT
 -> ChaCha20-Poly1305 DATA
 -> bounded transport fragmentation
 -> authenticated COMPLETE
 -> crash-safe Vault import
 -> close Vault
 -> reopen Vault
 -> byte-identical plaintext read
```

## Cancellation and failure

CANCEL and FAILURE are post-handshake encrypted control messages.

Receiver CANCEL/FAILURE releases buffered transfer state.
Sender cancellation after DATA started delegates source cleanup to `TransferSender` and
sends CANCEL best-effort from a NonCancellable cleanup context.

Internal failure details sent to a peer are intentionally generic. Secrets, paths and
exception dumps must not cross the peer channel or enter logs.

## Privacy

The signaling server may still infer traffic timing and approximate sizes. It must not
learn post-handshake:

- PeerMessageType;
- TransferId/FileId;
- filename;
- MIME;
- exact declared size;
- DATA metadata/content;
- vault IDs/keys;
- aliases;
- REAL/DECOY labels.

## Persistence status

Identity/contact/presence persistence remains intentionally outside this PR. In-memory
stores are still used by the current baseline. Do not persist Ed25519 private seeds as
plaintext as a shortcut.

## Platform status

Desktop and Android must pass the PR #3 build gate.

iOS remains:

`iOS NOT VERIFIED / native crypto actuals pending macOS implementation and Xcode validation.`
