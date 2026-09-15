# Sharing App Runtime Contract

## Purpose

This layer connects the validated sharing core (trust, handshake, established sessions, transfer orchestration and secure persistence) to the application/UI without exposing raw cryptographic state to Compose controllers.

PR #5 is stacked on PR #4. PR #4 Android connected instrumentation remains a prerequisite for calling the persistence stack freeze-ready.

## UI boundary

UI/controller code MUST NOT receive or choose:

- Ed25519 private seeds;
- X25519 private keys;
- `HandshakeKeys`;
- directional DATA/ENVELOPE keys;
- `PendingInboundHandshake`;
- raw `SecurePeerChannel` instances;
- VMK/KEK/FileKey material.

The application talks to a high-level sharing runtime using opaque local persona/context IDs, contacts, routing codes, transfer metadata, progress and terminal results.

## Local vault persona -> sharing context

REAL and DECOY vault sessions require independent sharing contexts, but `SharingContextId` MUST remain random and MUST NOT be derived directly from:

- PIN/credential;
- REAL/DECOY labels;
- `VaultId`;
- VMK/KEK;
- blob namespace.

Production unlock may expose a non-displayable `LocalPersonaId` used only as a local binding key. Current implementation derives this opaque binding token from the authenticated random `VaultId` with a domain-separated SHA-256 hash. This token is NOT a sharing identity and MUST never be transmitted to the signaling server or peer.

A separately protected binding store maps:

`LocalPersonaId -> random SharingContextId`

The mapped `SharingContextId` is generated from fresh randomness on first use and persisted inside the secure sharing-state protector. Credential rotation leaves both `LocalPersonaId` and `SharingContextId` stable. Different vault personas resolve to different sharing contexts.

## Lifecycle

After a vault is successfully unlocked:

1. resolve/create its random `SharingContextId` from the protected binding store;
2. connect signaling;
3. load/create persistent sharing identity and presence for that context;
4. register current presence;
5. expose only high-level sharing state to the app.

On lock/close:

1. cancel active sharing transfer/handshake work;
2. close transfer crypto/session keys;
3. unregister presence best-effort while the signaling connection is alive;
4. close signaling;
5. clear the active local persona/context from the controller.

A failure to initialize sharing MUST NOT prevent access to the local encrypted vault. Sharing should report unavailable/fail closed while vault operations remain usable.

## Outbound

The UI supplies a validated reference code and a selected transfer source. The runtime performs:

`LOOKUP -> pinned trust decision -> HELLO -> CONFIRM -> ACK -> established session -> OFFER -> ACCEPT -> DATA -> COMPLETE`

Unknown identity or changed pinned key never silently proceeds. The UI receives a verification-required/key-mismatch result instead.

## Inbound

The runtime owns the signaling event loop. It routes handshake messages to `ManagedTrustedSessionCoordinator`, established encrypted messages to the matching transfer/session, and exposes an incoming OFFER only after authenticated session establishment and OFFER validation.

The user must explicitly ACCEPT before DATA reaches the receiver state machine. REJECT/CANCEL release transfer state.

## Vault import

Incoming data is imported only through the validated `IncomingSharingTransfer.importIntoVault()` path after authenticated DATA completion plus coherent COMPLETE. The UI cannot mark a transfer complete or write peer bytes directly to the vault.

## Contacts

ReferenceCode remains routing only. Contacts are trusted only through their pinned identity/public key. Updating a stored route is allowed only after an authenticated session with the already pinned identity.

## Failure policy

- corrupted persistent sharing state: fail sharing closed; do not silently create a replacement store;
- unavailable signaling: local vault remains usable, sharing becomes unavailable;
- unknown peer: require verification;
- pinned-key mismatch: block handshake;
- malformed/replayed handshake: reject;
- transfer authentication/protocol failure: fail transfer and release state;
- application lock: cancel/close all sharing runtime state.

## Explicit non-goals of this PR

- changing validated handshake cryptography;
- changing Sharing V1 wire format;
- rollback protection for old valid persistence blobs;
- forensic secure erase;
- iOS native secure-state/crypto validation;
- WebRTC/P2P direct transport.

DO NOT MERGE PR #1/#2/#3/#4/#5 automatically.
