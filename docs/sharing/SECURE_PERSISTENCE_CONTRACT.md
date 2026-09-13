# Secure Sharing State Persistence Contract

## Purpose

Sharing identity, trusted-contact pinning and routing presence must survive process/device restarts without writing sensitive sharing state as plaintext and without deriving protection from vault keys.

This contract covers three independent logical stores:

- `sharing.identity.v1`: local `SharingIdentityId`, Ed25519 private seed and public key;
- `sharing.presence.v1`: local `ReferenceCode` by sharing context;
- `sharing.contacts.v1`: local aliases, pinned peer identity/public key/fingerprint, verification method and last authenticated route.

## Non-negotiable independence from vault crypto

The sharing persistence key/protector MUST NOT be derived from or use:

- VaultId;
- VMK;
- KEK/PIN material;
- file keys;
- vault slot descriptors;
- REAL/DECOY vault namespaces.

Sharing contexts remain random opaque application identifiers. Vault unlock/change-PIN/delete operations must not silently rotate or destroy the sharing identity unless the application explicitly performs a sharing-identity operation.

## Common protected-state boundary

`ProtectedStateStore` accepts a validated `SecureStateScope`, raw plaintext bytes and an `AtomicStateStorage` + `SecureStateProtector`.

Properties:

- scope name is bounded and path-safe;
- each scope maps to a separate `.vss` file;
- plaintext state is bounded to 2 MiB;
- protected state is bounded to 4 MiB;
- whole-store replacement is atomic at the storage boundary;
- scope is cryptographically bound as associated data / platform entropy;
- protected files from one scope must not authenticate as another scope;
- malformed, truncated, oversized, corrupted or wrongly protected state fails closed;
- decoded private-seed/plaintext buffers are cleared best-effort after use.

The binary codecs are versioned and bounded. Ed25519 private seeds are always encoded as raw 32-byte fields and must never be converted to String/Base64 for persistence.

## Android production protection

Production Android uses `AndroidKeystoreStateProtector`:

- AES-256-GCM;
- non-exportable key generated inside `AndroidKeyStore`;
- randomized encryption required;
- 128-bit GCM tag;
- scope string used as AAD;
- application does not export/persist the AES key;
- state files live beneath `Context.noBackupFilesDir`;
- file bytes are flushed and `FileDescriptor.sync()` is called before same-filesystem `Os.rename()` replacement.

Default key alias:

`dev.veilshare.sharing.state.v1`

The key is intentionally independent from vault credentials. App reinstall / keystore loss may make protected sharing state unrecoverable; that is preferable to silently falling back to plaintext.

## Windows Desktop production protection

Production Windows Desktop uses `WindowsDpapiStateProtector`:

- Windows DPAPI current-user protection;
- `CRYPTPROTECT_UI_FORBIDDEN`;
- scope AAD is supplied as DPAPI optional entropy;
- state is therefore tied to the Windows user security context and scope;
- protected bytes are written to a same-filesystem temp file, `FileChannel.force(true)` is used, then `ATOMIC_MOVE + REPLACE_EXISTING` promotes the new version.

Non-Windows Desktop currently fails closed when the production DPAPI factory is requested. Do not add a plaintext fallback or write an application encryption key beside its ciphertext.

## iOS status

iOS secure sharing-state persistence is NOT implemented or verified in this phase. A future implementation should use Keychain / appropriate Apple protected storage and must be validated together with native crypto on macOS/Xcode.

Exact status:

`iOS NOT VERIFIED / native crypto and secure-state protector pending macOS implementation and Xcode validation.`

## Store semantics

### Identity

`PersistentSharingIdentityStore` persists at most 32 local sharing contexts.

On load/replacement:

- Ed25519 seed must be exactly 32 bytes;
- Ed25519 public key must be exactly 32 bytes;
- duplicate contexts are rejected;
- temporary decoded seed copies are zeroized best-effort;
- identity rotation writes the new identity atomically;
- deleting the last identity deletes the identity-state file.

### Presence

`PersistentSharingPresenceStore` persists at most 32 context→ReferenceCode bindings.

- ReferenceCode is routing metadata, never trust;
- it is still encrypted at rest for privacy;
- identity and presence rotations remain independent;
- duplicate contexts are rejected.

### Trusted contacts

`PersistentTrustedContactStore` persists at most 1024 contacts.

- aliases are encrypted at rest;
- pinned Ed25519 key and identity are encrypted at rest;
- ReferenceCode is encrypted at rest;
- fingerprint is recomputed from the pinned key when decoding rather than trusted as persisted input;
- verification method is encoded by stable enum name, not ordinal;
- duplicate ContactId, pinned identity ownership or route ownership is rejected.

## Atomicity / crash model

A write produces a complete protected blob before the storage layer replaces the current file. The previous committed file remains authoritative until atomic rename/promotion succeeds.

This provides process/power-loss resistance at the file-replacement boundary consistent with the existing vault persistence strategy. Directory-entry fsync is not currently implemented on all platforms, so the durability claim is best-effort atomic replacement, not a formal filesystem proof for every storage stack.

## Explicitly NOT solved: rollback

Authenticated encryption / DPAPI detects modification, truncation, cross-scope swaps and random corruption, but it cannot by itself detect replacement with an **older, previously valid protected blob**.

An attacker with filesystem rollback capability could restore an old valid state containing:

- a previous local identity;
- an old routing code;
- an older contact alias/route/pin set.

Preventing this requires a trusted monotonic version/counter or equivalent platform-backed anti-rollback state. A version number stored only inside the same protected file does not solve rollback and MUST NOT be presented as doing so.

Rollback protection is a follow-up security feature.

## Explicitly NOT solved: secure deletion on flash / SSD

Deleting or replacing a file and clearing process buffers does not guarantee physical erasure from flash translation layers, filesystem snapshots, SSD wear levelling, journal/history or external forensic copies.

The design guarantees that persisted sharing state is protected cryptographically at rest; it does not claim forensic secure erase.

## Process model

Current persistent stores serialize mutations with an in-process `Mutex`. The production application is currently single-process.

Multiple OS processes mutating the same scope concurrently are not supported in this phase. If a future app adds multi-process sharing services, cross-process locking/transaction semantics must be designed explicitly rather than relying on these mutexes.

## Testing requirements

The persistence gate must demonstrate:

- real ChaCha20-Poly1305 protected-state roundtrip in common/desktop tests;
- tamper rejection;
- truncation rejection;
- cross-scope swap rejection;
- plaintext markers/private seed absent from protected bytes;
- restart stability for identity/private seed, presence and contacts;
- identity and presence rotations remain independent;
- Windows DPAPI roundtrip and wrong-scope rejection on Windows;
- Android Keystore roundtrip, raw-disk privacy, tamper and wrong-scope rejection on a device/emulator;
- full Android persistent identity/presence/contact restart on a device/emulator;
- regression of all previously validated sharing/vault tests.
