# VeilShare Sharing Architecture V1

Status: implemented baseline + identity/trust layer under validation

## Scope

Sharing V1 provides:

- independent local sharing identities per context;
- random rotatable ReferenceCodes;
- in-memory signaling presence/lookup/relay;
- Ed25519-authenticated handshake;
- fresh X25519 session ephemerals;
- transcript-bound HKDF key derivation;
- encrypted post-handshake peer envelopes;
- ChaCha20-Poly1305 DATA chunks;
- bounded transport fragmentation;
- transfer lifecycle limits and hostile-input validation;
- crash-safe import into the existing encrypted vault;
- verified-contact pinning and trusted session bootstrap.

V1 does not provide:

- WebRTC/STUN/TURN;
- multi-device sync;
- group sharing;
- byte-range resume;
- cloud file storage;
- iOS production crypto yet;
- persistent identity/contact stores yet.

## Trust boundaries

### Signaling server

The server is a blind routing service, not a trust authority.

It may see:

- active `ReferenceCode` registrations;
- public sharing identity material used for registration/lookup;
- SessionId used for routing;
- public pre-key handshake envelopes;
- current handshake reply ReferenceCode;
- frame lengths, timing and connection metadata.

After session keys are established, it must not see:

- TransferId;
- FileId;
- PeerMessageType;
- filename;
- MIME type;
- exact declared file size;
- DATA metadata;
- plaintext file content;
- vault metadata/keys;
- REAL/DECOY labels;
- contact aliases.

Post-handshake `PeerEnvelope` is serialized and then protected by the session ENVELOPE AEAD key before entering `RelayRequest`.

### LOOKUP

`LOOKUP` returns reachability information only:

- `SharingIdentityId`;
- Ed25519 public key.

Both fields are untrusted until compared with local pinned contact state.

A compromised signaling server must not be able to replace a contact key silently.

## Local identity architecture

Each local sharing context owns an independent:

```text
SharingContextId (local only)
  -> SharingIdentityId
  -> Ed25519 identity keypair
  -> ReferenceCode (separate random routing token)
```

None of these are derived from:

- VaultId;
- VMK;
- KEK;
- FileKey;
- blob namespace;
- device/account/hardware identifiers.

Identity and ReferenceCode lifecycles are intentionally independent.

### SharingIdentityManager

Responsibilities:

- create/load one Ed25519 identity per context;
- rotate identity explicitly;
- expose public identity;
- lend a short-lived private key copy through `SharingIdentityHandle.withKeyPair()`;
- best-effort zeroization of temporary private material.

Persistent identity storage remains pending. Private seeds must never be persisted as unprotected plaintext.

### SharingPresenceManager

Responsibilities:

- create/load one random ReferenceCode per context;
- keep it stable until rotation/deletion;
- rotate routing without rotating identity.

## Trusted contacts

A verified contact pins:

```text
ContactId
alias (local only)
SharingIdentityId
Ed25519 public key
SHA-256 public-key fingerprint
verification method
optional last-known ReferenceCode
```

Supported explicit verification methods:

- manual fingerprint;
- QR code;
- existing authenticated session.

There is no silent TOFU in the core trust manager.

### Trust decisions

`TrustedContactManager.evaluate()` produces:

- `Trusted` — identity and key exactly match a local pin;
- `NeedsVerification(NEW_PEER)` — unknown identity;
- `NeedsVerification(IDENTITY_CHANGED_FOR_ROUTING_CODE)` — known route now presents another identity;
- `KeyMismatch` — same pinned identity now presents another key.

Pinned keys are never auto-replaced.

## Signaling architecture

The implemented signaling server maintains ephemeral presence and WebSocket routing.

Responsibilities:

- REGISTER;
- UNREGISTER server handler;
- LOOKUP by ReferenceCode;
- RELAY opaque payloads;
- PING;
- connection/presence cleanup;
- rate limits including an independent RELAY message+byte budget.

Current client gap: `SignalingClient` does not yet expose `unregister()` although the server supports it. Immediate route revocation through the client is therefore a follow-up; TTL/connection teardown still removes presence.

## Trusted outbound session bootstrap

Supported flow:

```text
local context
  -> SharingIdentityManager
  -> SharingPresenceManager
  -> signaling LOOKUP(peer ReferenceCode)
  -> LookupTrustResolver
  -> TrustedContactManager
```

Only `Trusted` may continue:

```text
new SessionId
  -> signed SESSION_HELLO
  -> RELAY
```

Unknown peer, key mismatch, unavailable lookup or malformed key emits no HELLO RELAY.

## Trusted inbound bootstrap

The relay does not authenticate who sent a RELAY. An inbound HELLO is therefore checked against local contact pins.

```text
SESSION_HELLO.sharingIdentityIdHash
  -> findPinnedByIdentityHash()
  -> pinned Ed25519 public key
  -> verify identity hash + SessionId hash + Ed25519 signature
```

The public key carried by HELLO never serves as its own trust anchor.

Unknown identity hashes remain untrusted and require a separate verification flow.

## Handshake reply routing

The server forwards the opaque handshake payload and SessionId but does not add authenticated sender routing metadata.

`HandshakePeerEnvelope` therefore carries optional `replyReferenceCode`.

Properties:

- routing only;
- public pre-key metadata;
- no TransferId/file metadata;
- not a trust assertion;
- never auto-updates a contact pin/route;
- allows response to a peer that recently rotated its ReferenceCode.

Tampering with it can redirect/drop the next handshake message (DoS) but cannot forge Ed25519 signatures or derive session keys.

## Handshake sequence

### 1. HELLO

Sender sends signed:

```text
SESSION_HELLO
  sender identity hash
  sender public identity key
  SessionId hash
  protocol version
  Ed25519 signature
```

Receiver verifies using the locally pinned sender key.

### 2. CONFIRM

Receiver creates fresh X25519 keypair and sends signed:

```text
SESSION_CONFIRM
  receiver identity hash
  receiver public identity key
  SessionId hash
  receiver ephemeral X25519 public key
  Ed25519 signature
```

New orchestration uses `verifySessionConfirmFromPinnedIdentity()`.

The receiver ephemeral is accepted only after verification under the pinned receiver Ed25519 identity. The older API requiring an expected ephemeral in advance is legacy compatibility only and must not be used for new network messages.

### 3. CONFIRM_ACK

Sender creates fresh X25519 keypair and sends signed:

```text
SESSION_CONFIRM_ACK
  sender identity hash
  SessionId hash
  sender ephemeral X25519 public key
  complete transcript hash
  Ed25519 signature
```

Receiver validates the sender against its pinned identity and reconstructs the transcript.

### 4. Key derivation

Both peers perform X25519 and derive four transcript-bound HKDF keys:

```text
S2R DATA
R2S DATA
S2R ENVELOPE
R2S ENVELOPE
```

DATA and outer-envelope crypto never reuse the same key.

Ephemeral private material is single-session and closed after derivation/abort.

`EstablishedPeerSession.close()` best-effort zeroizes all four session key arrays.

## Post-handshake peer channel

All control/data messages after the handshake are typed `PeerEnvelope`s.

Before signaling relay:

```text
PeerEnvelope
  -> serialize
  -> ChaCha20-Poly1305(session ENVELOPE key)
  -> SecurePeerEnvelope
  -> RelayRequest
```

The AEAD AAD binds:

- protocol version;
- SessionId;
- traffic direction.

Direction-specific ENVELOPE keys prevent reflection/reuse across directions.

## Transfer data path

DATA additionally has its own chunk AEAD using the direction-specific DATA key.

DATA AAD binds:

- protocol version;
- direction/domain;
- transfer hash;
- file hash;
- chunk index;
- total chunk count.

A 1 MiB crypto chunk may be fragmented into bounded transport frames after encryption.
Transport fragmentation does not change DATA AEAD semantics.

Current transport bounds include:

- serialized TransferData budget: 16 KiB;
- max 128 fragments per crypto chunk;
- relay envelope payload limit: 64 KiB;
- bounded active transfers/buffer size;
- idle/absolute transfer timeouts.

## Receiver lifecycle

Receiver state includes bounded transfer lifecycle and cleanup:

- active-transfer limit;
- idle timeout;
- absolute lifetime;
- explicit abort;
- expiry sweep;
- fragment/chunk invariant checking;
- single-consumer `COMPLETE -> IMPORTING` transition;
- best-effort buffer cleanup.

A completed transfer becomes a `TransferImportSource` and feeds the existing vault import pipeline.

## Vault integration

Received plaintext is not persisted as a standalone decrypted file.

```text
Authenticated network chunks
  -> TransferImportSource
  -> existing VaultHandle.import()
  -> encrypted VBL1 blob
  -> encrypted catalog
```

The frozen vault semantics remain authoritative. Sharing does not receive VMK/FileKey access.

## V1 persistence status

Implemented in-memory abstractions:

- sharing identity store;
- sharing presence store;
- trusted contact store;
- signaling presence registry.

Still pending intentionally:

- platform-secure persistent Ed25519 identity store;
- persistent trusted-contact store;
- persistent local ReferenceCode store;
- iOS production crypto implementation.

Do not use plaintext private-key persistence as a shortcut.

## Platform status

Validated Sharing V1 baseline before identity/trust branch:

- Desktop regression: green;
- Android compile: green;
- core-transfer: 66/66 tests green;
- 1 MiB real encrypted fragmentation path: green;
- vault import/reopen E2E: green;
- signaling tests: green.

iOS status remains:

`iOS NOT VERIFIED / native crypto actuals pending macOS implementation and Xcode validation.`

The identity/trusted-session branch is still awaiting its own local Gradle validation.

## Next implementation layers

After this branch passes its build gate:

1. secure persistent identity/contact/presence stores;
2. expose client-side UNREGISTER and safe presence rotation/revocation;
3. remove the legacy circular SESSION_CONFIRM verification API;
4. session lifecycle/timeouts around pending handshake state;
5. app/UI integration for fingerprint/QR verification and share offer/accept flows;
6. iOS libsodium/native crypto implementation and macOS/Xcode validation.
