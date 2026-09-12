# VeilShare Sharing Identity / Contact Trust Contract

Status: implementation baseline

## Purpose

The authenticated sharing handshake needs an **expected peer Ed25519 public key**.
That key must not become trusted merely because the signaling server returned it from
`LOOKUP`. A compromised signaling server can replace both `SharingIdentityId` and the
public key it returns.

This contract defines where trust comes from and how local sharing identities are
isolated from vault secrets.

## Local sharing identity

Each local sharing context owns an independent:

- random `SharingContextId` (local only)
- random `SharingIdentityId`
- Ed25519 private seed
- Ed25519 public key
- SHA-256 public-key fingerprint

A sharing identity MUST NOT be derived from:

- `VaultId`
- VMK / KEK / FileKey
- blob namespace
- device ID / installation ID
- account ID
- hardware fingerprint

Rotation creates both a new `SharingIdentityId` and a new Ed25519 keypair.

`SharingIdentityHandle` owns the loaded private seed. Handshake/signing code obtains a
short-lived copy only through `withKeyPair()`; that temporary seed is closed after the
block. Storage-returned seed copies are zeroized after use.

Persistent `SharingIdentityStore` implementations are not allowed to persist the seed
as unprotected plaintext. They must use platform secure storage or a dedicated local
application encryption key independent from vault keys.

## ReferenceCode is not identity

`ReferenceCode` is a rotatable routing token. It is intentionally separate from:

- `SharingIdentityId`
- Ed25519 keys
- contact identity
- vault identity

Knowing a ReferenceCode proves nothing about who currently owns it.

A code may be updated for an existing trusted contact only after an authenticated
session with the already pinned identity, or as part of an explicit user-verified
identity replacement.

## Trusted contact pin

A trusted contact pins:

- `ContactId`
- local alias
- `SharingIdentityId`
- Ed25519 public key
- SHA-256 fingerprint of that public key
- verification method
- optional last-known ReferenceCode

The fingerprint stored in `TrustedContact` must cryptographically match the pinned
public key or the record is invalid.

## Verification methods

Current explicit verification methods:

- `MANUAL_FINGERPRINT`
- `QR_CODE`
- `EXISTING_TRUSTED_SESSION`

The core module deliberately has no silent TOFU method. UI may present an unknown peer
for user verification, but must not call `addVerified()` until the application has
performed one of the accepted verification flows.

## LOOKUP trust flow

```
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

- expected identity ID
- SHA-256 identity ID hash
- expected Ed25519 public key
- fingerprint

These values are what the handshake verifier must use.

### NeedsVerification / NEW_PEER

No pinned identity matches. The user must verify the fingerprint/QR before the peer can
be promoted to a trusted contact.

### KeyMismatch

The same pinned `SharingIdentityId` was presented with a different Ed25519 public key.

This is a hard security event. Do not:

- overwrite the pinned key
- continue the handshake as trusted
- convert it into a normal reconnect

The UI should clearly surface that the peer key changed.

### NeedsVerification / IDENTITY_CHANGED_FOR_ROUTING_CODE

The routing code is currently associated with a trusted contact, but LOOKUP returned a
different `SharingIdentityId`.

The new identity is not trusted automatically. This can represent legitimate identity
rotation, code reassignment, or an attack.

## Explicit identity change

`confirmIdentityChange()` is the only normal core operation that replaces a pinned
identity/key.

Before calling it, UI/session orchestration must have performed fresh out-of-band
verification.

The operation rejects a replacement if the candidate identity or routing code is
already pinned to another contact.

## Handshake integration

For a trusted peer:

```
PinnedPeerIdentity.sharingIdentityIdHash
    -> expectedSharingIdentityIdHash

PinnedPeerIdentity.publicKey
    -> expectedSenderPublicKey / expectedReceiverIdentityPublicKey
```

The handshake still validates signatures and transcript/key agreement. Contact pinning
provides the missing external trust anchor.

A malicious signaling server that substitutes a peer key therefore fails twice:

1. `TrustedContactManager` returns `KeyMismatch`.
2. Even if a caller ignores that result, Ed25519 handshake verification against the
   pinned key rejects a HELLO/CONFIRM signed by the substituted key.

## Privacy

Contacts and sharing identities must not encode REAL/DECOY labels into network-visible
identifiers.

Aliases are local-only and must never be sent in REGISTER, LOOKUP, handshake, OFFER, or
DATA messages.

The signaling server may see the public sharing identity used for an active presence,
but must not learn which local vault/session type selected that identity.

## Persistence still pending

This baseline includes in-memory stores and persistence interfaces. Platform-persistent
stores remain a separate implementation task because the Ed25519 private seed requires
an intentional secure-storage design.

Do not persist `StoredSharingIdentity.privateKeySeed` as plaintext merely to complete
UI work.
