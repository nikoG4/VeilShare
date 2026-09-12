# VeilShare Identity / Contacts Build Gate

Run this gate on branch:

`feature/sharing-identity-contacts`

This branch is stacked on top of the current Sharing V1 hardening branch. Do not merge it
before the underlying sharing branch is validated/merged or the stack is rebased cleanly.

## 1. Core identity

```powershell
.\gradlew.bat :shared:core-identity:desktopTest --no-daemon
.\gradlew.bat :shared:core-identity:compileDebugKotlinAndroid --no-daemon
```

Required behavior:

- same `SharingContextId` returns stable identity
- distinct contexts use distinct identity IDs and Ed25519 keys
- rotation changes both identity ID and keypair
- delete followed by create produces a fresh identity
- `withKeyPair()` works and handle close invalidates future use
- store returns defensive copies

## 2. Core contacts

```powershell
.\gradlew.bat :shared:core-contacts:desktopTest --no-daemon
.\gradlew.bat :shared:core-contacts:compileDebugKotlinAndroid --no-daemon
```

Required behavior:

- unknown LOOKUP result -> `NeedsVerification`
- pinned identity + exact pinned key -> `Trusted`
- pinned identity + changed key -> `KeyMismatch`
- same ReferenceCode + different identity -> `NeedsVerification`
- ReferenceCode never upgrades trust
- explicit identity replacement is required for key rotation
- duplicate pinned identity/reference-code ownership is rejected
- malformed Ed25519 key from LOOKUP fails closed

## 3. Real handshake integration

`TrustedHandshakeIntegrationTest` must pass with production Desktop crypto.

Required path:

```text
local sharing identity
 -> verified contact pin
 -> LOOKUP candidate evaluation
 -> PinnedPeerIdentity
 -> expected identity hash + Ed25519 public key
 -> SESSION_HELLO verification
```

A substituted signaling key must fail both trust evaluation and handshake verification.

## 4. Existing Sharing V1 regression

The stacked branch must not regress the sharing baseline:

```powershell
.\gradlew.bat :shared:core-model:desktopTest :shared:core-crypto:desktopTest :shared:core-transfer:desktopTest :shared:core-vault:desktopTest :server:signaling:test --no-daemon
```

Also compile Android:

```powershell
.\gradlew.bat :shared:core-model:compileDebugKotlinAndroid :shared:core-crypto:compileDebugKotlinAndroid :shared:core-transfer:compileDebugKotlinAndroid :shared:core-vault:compileDebugKotlinAndroid --no-daemon
```

## 5. Common source scan

```powershell
git grep -n -E "java\.|javax\.|sun\.|java\.io|java\.nio" -- "shared/core-identity/src/commonMain/**" "shared/core-contacts/src/commonMain/**"
```

Expected: no JVM-only dependencies in these common source sets.

## 6. Security review

Verify manually:

- no vault keys imported into `core-identity` or `core-contacts`
- no VaultId used to derive `SharingContextId` or `SharingIdentityId`
- aliases never enter network DTOs
- `LookupTrustResolver` treats LOOKUP as untrusted input
- `TrustedContactManager` never auto-replaces a pinned key
- fingerprint equals SHA-256 of pinned Ed25519 public key
- temporary private seed copies are zeroized/closed best effort
- private key material is not logged or converted to String

## 7. iOS status

Do not claim iOS verified.

The underlying `core-crypto` native implementation is still pending. Exact status:

`iOS NOT VERIFIED / native crypto actuals pending macOS implementation and Xcode validation.`

## Final report

Return:

```text
IDENTITY / CONTACTS BUILD VALIDATION

HEAD:

Core identity:
- Desktop tests:
- Android compile:
- context isolation:
- rotation:
- secret cleanup:

Core contacts:
- Desktop tests:
- Android compile:
- new peer decision:
- key mismatch:
- routing-code identity mismatch:
- explicit identity replacement:

Lookup -> handshake:
- trusted binding:
- substituted key rejection:

Sharing regression:
- core-model:
- core-crypto:
- core-transfer:
- core-vault:
- signaling:

CommonMain scan:

iOS:

Remaining failures:

Commit pushed:
```
