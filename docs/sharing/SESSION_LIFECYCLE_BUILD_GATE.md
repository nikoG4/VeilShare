# VeilShare Session Lifecycle / Transfer Orchestration Build Gate

Run on branch:

`feature/sharing-session-lifecycle`

Base must be validated PR #2 HEAD `ad760d8`.

Do not merge PR #1, PR #2 or PR #3 during this gate. Fix only concrete compile/test issues.
Do not weaken crypto, trust, memory or lifecycle invariants.

## 1. Core platform / signaling client

```powershell
.\gradlew.bat :shared:core-platform:compileKotlinDesktop :shared:core-platform:compileDebugKotlinAndroid --no-daemon
.\gradlew.bat :server:signaling:test --no-daemon
```

Required:

- `SignalingClient.unregister()` exists;
- unsupported clients fail closed by default;
- `KtorSignalingClient.unregister()` sends UNREGISTER and waits for the server response;
- integration test proves REGISTER -> lookup FOUND -> UNREGISTER -> lookup NOT_FOUND immediately;
- no TTL/disconnect is required for explicit revocation.

## 2. Presence lifecycle

`SharingPresenceLifecycleTest` must pass.

Required sequence for rotation:

```text
UNREGISTER old identity routes
 -> rotate local ReferenceCode
 -> REGISTER replacement
```

Required failures:

- UNREGISTER failure => local code unchanged;
- replacement REGISTER failure => old route already revoked, replacement local code retained;
- later ensureRegistered() retries that same replacement code;
- deactivate() unregisters network presence but preserves local code.

## 3. Session registry

`TrustedSessionRegistryTest` must pass.

Defaults:

- max pending = 16;
- max established = 8;
- pending timeout = 2 minutes.

Required:

- expired pending handshake closes receiver X25519 private material;
- duplicate pending closes rejected ephemeral;
- pending capacity closes rejected ephemeral;
- established removal zeroizes all four session key arrays;
- registry shutdown closes pending + established state;
- expensive ACK/X25519/HKDF completion occurs outside registry mutex;
- if established registration fails after derivation, new session keys are zeroized.

## 4. Managed inbound admission

`ManagedTrustedSessionCoordinatorTest` must pass.

Required:

- pending registry capacity is checked BEFORE responder sends SESSION_CONFIRM;
- full registry => `RegistryCapacityRejected` + zero relay messages;
- duplicate SessionId => `RegistryDuplicate` + zero responder side effects;
- `PendingInboundHandshake` never escapes to UI when using coordinator;
- accepted pending state is owned by registry and subject to timeout.

## 5. Established session crypto binding

Compile/test `EstablishedTransferCrypto`.

For INITIATOR:

```text
outbound DATA     = S2R DATA
inbound DATA      = R2S DATA
outbound ENVELOPE = S2R ENVELOPE
inbound ENVELOPE  = R2S ENVELOPE
```

RESPONDER must use the inverse.

Required:

- DATA and ENVELOPE keys remain independent;
- wrong side/direction cannot decrypt peer traffic;
- close() zeroizes the four SensitiveBytes copies;
- original EstablishedPeerSession remains independently owned until session close.

## 6. OFFER validation / state security

`SharingTransferOrchestrationSecurityTest` must pass.

Required:

- OFFER `totalChunks` exactly matches ceil(size/chunkSize);
- offered size over local max is rejected before receiver state allocation;
- DATA before ACCEPT fails before `TransferReceiver.receive()` / decryptor side effects;
- peer/session/transfer/file binding still goes through `PeerSessionGate`;
- remote REJECT closes an unsent source exactly once.

Add/fix tests if needed for:

- COMPLETE before authenticated local DATA completion => reject;
- COMPLETE totalChunks mismatch => reject;
- wrong transfer/file/session => reject;
- CANCEL/FAILURE releases buffered receiver state.

## 7. Established-session transfer E2E

`SharingTransferOrchestrationE2ETest` must pass with production Desktop crypto.

Required path:

```text
EstablishedPeerSession pair
 -> EstablishedTransferCrypto directional mapping
 -> encrypted OFFER
 -> encrypted ACCEPT
 -> real ChaCha20-Poly1305 DATA
 -> >1 MiB file / multiple crypto chunks
 -> transport fragmentation as needed
 -> encrypted COMPLETE
 -> READY_TO_IMPORT
 -> crash-safe vault import
 -> close/reopen vault
 -> byte-identical plaintext
```

## 8. Golden full-flow E2E

`TrustedSharingFullFlowE2ETest` is the strongest gate.

It must begin BEFORE session keys exist:

```text
independent local identities
 -> verified contact pins
 -> LOOKUP trust gate
 -> signed HELLO
 -> pinned inbound HELLO verification
 -> signed CONFIRM with fresh receiver X25519
 -> signed ACK with fresh sender X25519
 -> transcript-bound HKDF
 -> four equal directional session keys
 -> encrypted OFFER
 -> encrypted ACCEPT
 -> encrypted/fragmented DATA
 -> encrypted COMPLETE
 -> durable encrypted vault import
 -> vault reopen
 -> exact byte equality
```

Do not replace production crypto with identity/fake ciphers in this test.

## 9. Core-transfer compile/test

```powershell
.\gradlew.bat :shared:core-transfer:compileKotlinDesktop :shared:core-transfer:compileDebugKotlinAndroid --no-daemon
.\gradlew.bat :shared:core-transfer:desktopTest --no-daemon
```

Report exact passed/total count.

Existing PR #1/#2 tests must remain green.

## 10. Full Desktop regression

```powershell
.\gradlew.bat :shared:core-model:desktopTest :shared:core-crypto:desktopTest :shared:core-identity:desktopTest :shared:core-contacts:desktopTest :shared:core-transfer:desktopTest :shared:core-vault:desktopTest :server:signaling:test --no-daemon
```

Expected: BUILD SUCCESSFUL.

## 11. Full Android compile

```powershell
.\gradlew.bat :shared:core-model:compileDebugKotlinAndroid :shared:core-crypto:compileDebugKotlinAndroid :shared:core-identity:compileDebugKotlinAndroid :shared:core-contacts:compileDebugKotlinAndroid :shared:core-platform:compileDebugKotlinAndroid :shared:core-transfer:compileDebugKotlinAndroid :shared:core-vault:compileDebugKotlinAndroid --no-daemon
```

Expected: all green.

## 12. CommonMain portability

```powershell
git grep -n -E "java\.|javax\.|sun\.|java\.io|java\.nio" -- "shared/core-platform/src/commonMain/**" "shared/core-transfer/src/commonMain/**"
```

Review all results. New common code must contain no JVM-only dependency.

## 13. Security / ownership review

Verify manually:

- ReferenceCode rotation revokes old route before local rotation;
- no private key or session key enters logs/strings;
- UI never selects raw session key direction;
- PendingInboundHandshake is bounded/owned by registry in managed flow;
- no CONFIRM emitted when registry cannot accept pending state;
- DATA cannot reach receiver before ACCEPT;
- source close ownership is exactly once;
- only authenticated complete transfer can enter vault import;
- sharing layer never receives VMK/FileKey;
- OFFER filename/MIME/size remain inside encrypted peer envelope;
- failure messages contain generic details, not exception dumps/paths/secrets.

## 14. Diff hygiene

```powershell
git status
git diff --check feature/sharing-identity-contacts...HEAD
git diff --stat feature/sharing-identity-contacts...HEAD
```

No build output, temp files, secrets or unrelated vault/crypto redesign.

## 15. iOS

Do not claim iOS verified.

Exact status:

`iOS NOT VERIFIED / native crypto actuals pending macOS implementation and Xcode validation.`

## Final report

```text
PR #3 SESSION LIFECYCLE BUILD VALIDATION

HEAD:

Core platform:
- Desktop compile:
- Android compile:
- unregister integration:

Presence lifecycle:
- ensure registration:
- rotate success:
- unregister failure:
- replacement register failure:
- deactivate/re-register:

Session registry:
- pending timeout:
- duplicate cleanup:
- capacity cleanup:
- established cleanup:
- crypto outside mutex:

Managed admission:
- capacity before CONFIRM:
- duplicate before responder:
- relay count on rejected admission:

Established transfer crypto:
- initiator directions:
- responder directions:
- cleanup:

Transfer state security:
- OFFER mismatch:
- oversized OFFER:
- DATA before ACCEPT:
- COMPLETE before local completion:
- wrong binding:
- source exactly-once close:

Established-session E2E:
- OFFER:
- ACCEPT:
- DATA:
- fragment/reassembly:
- COMPLETE:
- vault import:
- reopen byte equality:

Golden full-flow E2E:
- trust/pinning:
- HELLO:
- CONFIRM:
- ACK:
- X25519/HKDF:
- four key equality:
- OFFER/ACCEPT:
- DATA:
- vault durable roundtrip:

core-transfer passed/total:
server signaling tests:
full Desktop regression:
full Android compile:
CommonMain scan:
diff --check:

iOS:

Mechanical fixes made:
Commit pushed:
Remaining failures:

DO NOT MERGE PR #1, PR #2 OR PR #3.
```
