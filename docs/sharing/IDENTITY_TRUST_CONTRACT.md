# VeilShare Sharing Identity / Contact Trust Contract

Status: implementation baseline — build validation pending

## Purpose

The authenticated sharing handshake needs an **expected peer Ed25519 public key**.
That key must never become trusted merely because the signaling server returned it from
`LOOKUP`, or because a handshake message carried a public key inside itself.

This contract defines:

- independent local sharing identities;
- random/rotatable routing codes;
- trusted-contact pinning;
- outbound and inbound trust gates;
- handshake reply routing;
- safe `SESSION_CONFIRM` ephemeral authentication;
- established-session key ownership.

## Local sharing identity

Each local sharing context owns an independent:

- random `SharingContextId` (local only);
- random `SharingIdentityId`;
- Ed25519 private seed;
- Ed25519 public key;
- SHA-256 public-key fingerprint.

A sharing identity MUST NOT be derived from:

- `VaultId`;
- VMK / KEK / FileKey;
- blob namespace;
- device ID / installation ID;
- account ID;
- hardware fingerprint.

Rotation creates both a new `SharingIdentityId` and a new Ed25519 keypair.

`SharingIdentityHandle` owns the loaded private seed. Handshake/signing code obtains a
short-lived copy only through `withKeyPair()`; that temporary seed is closed after the
block. Storage-returned seed copies are zeroized after use.

Persistent `SharingIdentityStore` implementations are not allowed to persist the seed
as unprotected plaintext. They must use platform secure storage or a dedicated local
application encryption key independent from vault keys.

## ReferenceCode lifecycle

`SharingPresenceManager` owns the random routing token for each sharing context.

Properties:

- stable until explicit rotation/deletion;
- independent per context;
- random and not derived from identity/key material;
- rotatable without identity rotation;
- identity rotation does not implicitly rotate routing;
- local persistence is abstracted behind `SharingPresenceStore`.

`ReferenceCode` is routing, **not identity**.

Knowing a ReferenceCode proves nothing about who currently owns it.

A code may be persisted for convenience, but callers must treat possession/discovery of
a code only as reachability information.

## Trusted contact pin

A trusted contact pins:

- `ContactId`;
- local alias;
- `SharingIdentityId`;
- Ed25519 public key;
- SHA-256 fingerprint of that public key;
- verification method;
- optional last-known ReferenceCode.

The fingerprint stored in `TrustedContact` must cryptographically match the pinned
public key or the record is invalid.

The manager rejects accidental ownership collisions where another contact already owns
the candidate identity or routing code.

## Verification methods

Current explicit verification methods:

- `MANUAL_FINGERPRINT`;
- `QR_CODE`;
- `EXISTING_TRUSTED_SESSION`.

The core module deliberately has no silent TOFU method. UI may present an unknown peer
for user verification, but must not call `addVerified()` until the application has
performed one of the accepted verification flows.

## LOOKUP trust flow

```text
ReferenceCode
    |
    v
signaling LOOKUP
    |
    |  UNTRUSTED: SharingIdentityId + Ed25519 public key
    v
LookupTrustResolver
    |
    v
TrustedContactManager.evaluate(candidate)
```

Possible results:

### Trusted

The presented `SharingIdentityId` already exists and the presented Ed25519 key exactly
matches the pinned key.

The result includes `PinnedPeerIdentity`:

- expected identity ID;
- SHA-256 identity ID hash;
- expected Ed25519 public key;
- fingerprint.

These values are the trust anchor for handshake verification.

### NeedsVerification / NEW_PEER

No pinned identity matches. The user must verify the fingerprint/QR before the peer can
be promoted to a trusted contact.

### KeyMismatch

The same pinned `SharingIdentityId` was presented with a different Ed25519 public key.

This is a hard security event. Do not:

- overwrite the pinned key;
- continue the handshake as trusted;
- convert it into a normal reconnect.

### NeedsVerification / IDENTITY_CHANGED_FOR_ROUTING_CODE

The routing code is currently associated with a trusted contact, but LOOKUP returned a
different `SharingIdentityId`.

The new identity is not trusted automatically. This can represent legitimate identity
rotation, code reassignment, or an attack.

## Outbound trust gate

`TrustedOutboundSessionStarter` is the supported outbound entry point.

It performs:

```text
local context
  -> load/create local sharing identity
  -> load/create current local ReferenceCode
  -> LOOKUP peer ReferenceCode
  -> LookupTrustResolver
  -> TrustedContactManager
```

Only `PeerTrustDecision.Trusted` may continue to:

```text
new SessionId
  -> signed SESSION_HELLO
  -> RELAY
```

For:

- unknown peer;
- identity change;
- key mismatch;
- unavailable lookup;
- malformed lookup key;

no handshake RELAY is emitted.

The starter returns a `Started` object binding:

- SessionId;
- local context/identity;
- local reply ReferenceCode;
- pinned peer identity;
- peer routing code;
- exact HELLO sent.

## Inbound trust gate

The signaling server does not authenticate a sender identity when it forwards a RELAY.
Therefore an inbound HELLO is resolved only against local contact pins.

`TrustedInboundHelloVerifier`:

1. reads `SESSION_HELLO.sharingIdentityIdHash`;
2. resolves that hash against locally pinned contacts;
3. uses the pinned Ed25519 key as `expectedSenderPublicKey`;
4. verifies identity hash, SessionId hash and Ed25519 signature.

The public key carried by HELLO is never used as its own trust anchor.

If the identity hash is unknown, V1 returns `UnknownIdentity`. It does not silently add a
contact because the relay does not provide an authenticated raw `SharingIdentityId` for
that sender.

## Handshake reply routing

Pre-key handshake envelopes now carry optional `replyReferenceCode`.

This field is intentionally:

- public;
- routing-only;
- not a trust assertion;
- not allowed to replace a pinned identity/key;
- not automatically persisted as a contact route.

Why it exists: the relay previously forwarded only `sessionId + opaquePayload`, so the
receiver had no reliable current route to send `SESSION_CONFIRM` after the sender
rotated its ReferenceCode.

The current-session responder may use `replyReferenceCode` to send the next handshake
message. A malicious relay altering it can cause denial of service, but cannot forge the
Ed25519-authenticated peer or derive session keys.

Persistent contact route changes still require the explicit authenticated-session policy
(`updateReferenceCodeFromAuthenticatedSession`) or a freshly verified identity change.

## SESSION_CONFIRM ephemeral authentication

The validated PR #1 baseline still contains a legacy `HandshakeProtocol.verifySessionConfirm`
overload that accepts `expectedReceiverEphemeralPublicKey`. That shape is unsuitable for
new session orchestration because a sender should not need to know the receiver ephemeral
key before receiving `SESSION_CONFIRM`.

New code must use:

`verifySessionConfirmFromPinnedIdentity(...)`

It:

1. verifies the receiver identity hash;
2. verifies the SessionId hash;
3. compares the included Ed25519 identity key against the pinned key;
4. verifies the Ed25519 signature over the included X25519 ephemeral;
5. validates the ephemeral key length;
6. returns the authenticated X25519 public key.

Ephemeral substitution therefore invalidates the receiver signature.

The legacy API is retained temporarily only for source compatibility with the already
validated sharing baseline and should be removed after this branch is build-validated.

## Complete trusted handshake orchestration

### Sender side

```text
TrustedOutboundSessionStarter
  -> SESSION_HELLO
  <- SESSION_CONFIRM
  -> verifySessionConfirmFromPinnedIdentity
  -> fresh sender X25519 ephemeral
  -> signed SESSION_CONFIRM_ACK
  -> transcript-bound HKDF
  -> EstablishedPeerSession
```

### Receiver side

```text
routed SESSION_HELLO
  -> TrustedInboundHelloVerifier
  -> fresh receiver X25519 ephemeral
  -> signed SESSION_CONFIRM
  <- signed SESSION_CONFIRM_ACK
  -> verify sender identity + transcript
  -> transcript-bound HKDF
  -> EstablishedPeerSession
```

`PendingInboundHandshake` owns the receiver ephemeral private key between CONFIRM and
ACK. It is single-use and closes/zeroizes the private material on success, failure or
explicit close.

The sender ephemeral private key is closed after ACK/key derivation.

## Established keys

Each successful session owns four independent transcript-bound subkeys:

- sender -> receiver DATA;
- receiver -> sender DATA;
- sender -> receiver outer ENVELOPE;
- receiver -> sender outer ENVELOPE.

`EstablishedPeerSession.close()` best-effort zeroizes all four arrays.

DATA and outer peer-envelope encryption must never share the same key.

## Explicit identity change

`confirmIdentityChange()` is the only normal core operation that replaces a pinned
identity/key.

Before calling it, UI/session orchestration must have performed fresh out-of-band
verification.

The operation rejects a replacement if the candidate identity or routing code is
already pinned to another contact.

## Privacy

Contacts and sharing identities must not encode REAL/DECOY labels into network-visible
identifiers.

Aliases are local-only and must never be sent in REGISTER, LOOKUP, handshake, OFFER, or
DATA messages.

The signaling server can already associate a connected registration with its own
ReferenceCode. Exposing that same current code as handshake reply-routing metadata does
not grant trust and does not expose vault state.

Post-handshake `PeerEnvelope` remains AEAD encrypted; TransferId, file metadata, peer
message type and DATA metadata remain hidden from the relay.

## Persistence still pending

This branch includes in-memory stores and persistence interfaces. Platform-persistent
stores remain a separate implementation task because the Ed25519 private seed requires
an intentional secure-storage design.

Do not persist `StoredSharingIdentity.privateKeySeed` as plaintext merely to complete
UI work.

Reference-code/contact persistence is also not yet implemented on disk.

## Known transport API gap

The signaling server supports `UNREGISTER`, but the shared `SignalingClient` contract
currently exposes only register/lookup/relay. Therefore reference-code rotation is fully
modeled locally but explicit immediate server-side revocation still needs a client
`unregister()` adapter. Until that adapter is added, server presence naturally expires
by TTL/connection teardown.
