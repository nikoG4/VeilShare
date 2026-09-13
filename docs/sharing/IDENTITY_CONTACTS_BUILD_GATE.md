# VeilShare Identity / Contacts / Trusted Session Build Gate

Run this gate on branch:

`feature/sharing-identity-contacts`

This is a stacked draft on top of the validated Sharing V1 branch. Do not merge it until
all commands below are green. Do not weaken trust or crypto invariants to make tests pass.

## 1. Core identity

```powershell
.\gradlew.bat :shared:core-identity:desktopTest --no-daemon
.\gradlew.bat :shared:core-identity:compileDebugKotlinAndroid --no-daemon
```

Required behavior:

- same `SharingContextId` returns stable identity;
- distinct contexts use distinct identity IDs and Ed25519 keys;
- identity rotation changes both identity ID and keypair;
- identity delete followed by create produces a fresh identity;
- `withKeyPair()` works and handle close invalidates future use;
- storage-returned private seed copies are cleaned best-effort;
- same context gets a stable random ReferenceCode;
- distinct contexts get unrelated ReferenceCodes;
- ReferenceCode rotation changes routing without rotating identity;
- presence delete followed by create produces a fresh code.

## 2. Core contacts

```powershell
.\gradlew.bat :shared:core-contacts:desktopTest --no-daemon
.\gradlew.bat :shared:core-contacts:compileDebugKotlinAndroid --no-daemon
```

Required behavior:

- unknown LOOKUP result -> `NeedsVerification`;
- pinned identity + exact pinned key -> `Trusted`;
- pinned identity + changed key -> `KeyMismatch`;
- same ReferenceCode + different identity -> `NeedsVerification`;
- ReferenceCode never upgrades trust;
- explicit identity replacement is required for key rotation;
- duplicate pinned identity/reference-code ownership is rejected;
- malformed Ed25519 key from LOOKUP fails closed;
- `findPinnedByIdentityHash()` resolves only already-pinned contacts;
- fingerprint equals SHA-256 of the pinned Ed25519 public key.

## 3. Safe SESSION_CONFIRM verification

```powershell
.\gradlew.bat :shared:core-crypto:desktopTest --no-daemon
.\gradlew.bat :shared:core-crypto:compileDebugKotlinAndroid --no-daemon
```

`VerifiedSessionConfirmTest` must prove:

- a correctly signed `SESSION_CONFIRM` returns the authenticated receiver X25519 key;
- changing the receiver ephemeral invalidates the Ed25519 signature;
- an attacker Ed25519 identity cannot self-authenticate for a pinned receiver;
- replay into another SessionId fails.

New orchestration MUST use:

`verifySessionConfirmFromPinnedIdentity(...)`

It MUST NOT use the legacy `verifySessionConfirm(... expectedReceiverEphemeralPublicKey ...)`
for newly received network messages. The legacy member remains temporarily for baseline
source compatibility only.

## 4. Outbound trust gate

Run core-transfer tests and verify `TrustedSessionBootstrapTest`.

Required behavior:

```text
LOOKUP -> trust resolver -> pinned identity -> signed SESSION_HELLO
```

Only a `Trusted` decision may emit a RELAY.

These cases must emit zero RELAYs:

- unknown peer;
- key mismatch;
- unavailable peer;
- malformed LOOKUP public key.

For a trusted peer:

- new SessionId is created;
- HELLO is signed by the local sharing identity;
- the current local ReferenceCode is included as `replyReferenceCode`;
- the peer routing code remains routing-only and is not treated as trust.

## 5. Inbound trust gate

`TrustedInboundHandshakeTest` must prove:

- known HELLO identity hash resolves to a local contact pin;
- Ed25519 verification uses the pinned key, not the key carried by HELLO;
- unknown identity hash is blocked as `UnknownIdentity`;
- same identity signed by a substituted key fails;
- tampered signature fails;
- HELLO replayed into a different SessionId fails.

Unknown inbound peers are NOT silently promoted to contacts.

## 6. Handshake reply routing

`HandshakePeerEnvelope.replyReferenceCode` is public routing metadata only.

Required properties:

- it carries no TransferId/file metadata;
- receiver can obtain it with `HandshakeSignalingInbox.decodeRoutedRelay()`;
- it allows CONFIRM/ACK routing after a peer rotated its code;
- modifying it must not grant identity trust;
- it must never auto-update a pinned contact route;
- persistent route changes still require authenticated-session policy.

A relay modification of this value may cause denial of service but must not permit
signature/key impersonation.

## 7. Full trusted handshake orchestration

Run:

```powershell
.\gradlew.bat :shared:core-transfer:compileKotlinDesktop --no-daemon
.\gradlew.bat :shared:core-transfer:compileDebugKotlinAndroid --no-daemon
.\gradlew.bat :shared:core-transfer:desktopTest --no-daemon
```

`TrustedHandshakeOrchestrationE2ETest` must cover two real Desktop peers:

```text
verified contact pins
 -> LOOKUP
 -> signed HELLO + reply route
 -> inbound pinned HELLO verification
 -> fresh receiver X25519
 -> signed CONFIRM + receiver reply route
 -> safe CONFIRM verification from pinned Ed25519 identity
 -> fresh sender X25519
 -> signed ACK
 -> transcript verification
 -> X25519
 -> transcript-bound HKDF
 -> EstablishedPeerSession on both peers
```

Both peers must derive byte-identical:

- sender -> receiver DATA key;
- receiver -> sender DATA key;
- sender -> receiver ENVELOPE key;
- receiver -> sender ENVELOPE key;
- transcript hash.

Also required:

- rotating the local sender identity between HELLO and CONFIRM aborts completion;
- `PendingInboundHandshake` is single-use;
- receiver ephemeral private material is closed on completion/failure/abandon;
- sender ephemeral private material is closed after derivation;
- `EstablishedPeerSession.close()` zeroizes all four key arrays.

## 8. Existing Sharing V1 regression

The stacked branch must not regress the validated sharing baseline:

```powershell
.\gradlew.bat :shared:core-model:desktopTest :shared:core-crypto:desktopTest :shared:core-identity:desktopTest :shared:core-contacts:desktopTest :shared:core-transfer:desktopTest :shared:core-vault:desktopTest :server:signaling:test --no-daemon
```

Android compile gate:

```powershell
.\gradlew.bat :shared:core-model:compileDebugKotlinAndroid :shared:core-crypto:compileDebugKotlinAndroid :shared:core-identity:compileDebugKotlinAndroid :shared:core-contacts:compileDebugKotlinAndroid :shared:core-transfer:compileDebugKotlinAndroid :shared:core-vault:compileDebugKotlinAndroid --no-daemon
```

PR #1 baseline expectation remains:

- core-transfer 66/66 tests green before this branch;
- real 1 MiB fragmentation path green;
- vault E2E green;
- signaling limiter tests green.

## 9. Common-source scan

```powershell
git grep -n -E "java\.|javax\.|sun\.|java\.io|java\.nio" -- "shared/core-identity/src/commonMain/**" "shared/core-contacts/src/commonMain/**" "shared/core-transfer/src/commonMain/**"
```

Review every result. New identity/contact/session code must contain no JVM-only dependency.

## 10. Security review

Verify manually:

- no vault keys imported into `core-identity` or `core-contacts`;
- no VaultId used to derive context/identity/routing identifiers;
- contact aliases never enter network DTOs;
- LOOKUP remains untrusted input;
- HELLO-carried public key is never its own trust anchor;
- CONFIRM ephemeral is accepted only after pinned Ed25519 verification;
- ReferenceCode/replyReferenceCode never grants trust;
- trusted contact manager never auto-replaces a pinned key;
- temporary private seed/X25519 material is closed best-effort;
- session key arrays are zeroized on `EstablishedPeerSession.close()`;
- private key material is not logged or converted to String.

## 11. Known persistence/network gaps

Do not “fix” these with insecure shortcuts during this gate:

- identity/contact/presence persistent stores are still pending;
- Ed25519 private seed must never be persisted as plaintext;
- `SignalingClient` still lacks an explicit `unregister()` method even though the server
  supports UNREGISTER; immediate server-side ReferenceCode revocation remains a follow-up;
- the legacy circular `HandshakeProtocol.verifySessionConfirm` member remains temporarily
  for source compatibility; new orchestration already avoids it.

## 12. iOS status

Do not claim iOS verified.

Exact status:

`iOS NOT VERIFIED / native crypto actuals pending macOS implementation and Xcode validation.`

## 13. Diff hygiene

```powershell
git status
git diff --check security/critical-sharing-fixes...HEAD
git diff --stat security/critical-sharing-fixes...HEAD
```

Only identity/contact/trusted-session scope should be present, aside from the synchronized
baseline test cleanup inherited from PR #1.

## Final report

Return:

```text
IDENTITY / TRUSTED SESSION BUILD VALIDATION

HEAD:

Core identity:
- Desktop tests:
- Android compile:
- identity context isolation:
- identity rotation:
- ReferenceCode stability:
- ReferenceCode rotation:
- secret cleanup:

Core contacts:
- Desktop tests:
- Android compile:
- new peer decision:
- key mismatch:
- routing-code identity mismatch:
- identity-hash pin lookup:

Safe CONFIRM:
- Desktop:
- Android:
- ephemeral substitution:
- wrong identity:
- replayed session:

Outbound bootstrap:
- trusted HELLO:
- unknown peer no-relay:
- key mismatch no-relay:
- local reply route:

Inbound bootstrap:
- pinned HELLO:
- unknown identity:
- substituted key:
- tampered signature:
- session replay:

Full trusted handshake E2E:
- HELLO:
- CONFIRM:
- ACK:
- reply routing:
- four key equality:
- identity-rotation abort:
- ephemeral cleanup:
- session-key cleanup:

Sharing regression:
- core-model:
- core-crypto:
- core-identity:
- core-contacts:
- core-transfer passed/total:
- core-vault:
- signaling:

Android full compile:

CommonMain scan:

diff --check:

iOS:

Mechanical fixes made:

Commit pushed:

Remaining failures:

DO NOT MERGE PR #1 OR PR #2.
```
