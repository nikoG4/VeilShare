# VeilShare Secure Sharing-State Persistence Build Gate

Run on:

`feature/sharing-secure-persistence`

This branch is stacked on validated PR #3 HEAD `a0c282c`. Do not merge PR #1/#2/#3/#4 automatically.

## 1. Read contract first

Read:

- `docs/sharing/SECURE_PERSISTENCE_CONTRACT.md`
- `docs/sharing/IDENTITY_TRUST_CONTRACT.md`
- `docs/sharing/SESSION_LIFECYCLE_CONTRACT.md`

Do not weaken confidentiality/integrity or reintroduce a vault-key dependency to make tests pass.

## 2. Core secure store

```powershell
.\gradlew.bat :shared:core-secure-store:compileKotlinDesktop :shared:core-secure-store:compileDebugKotlinAndroid --no-daemon
.\gradlew.bat :shared:core-secure-store:desktopTest --no-daemon
```

Required Desktop/common behavior:

- `SecureStateScope` is path-safe and domain-separated;
- real ChaCha20-Poly1305 `AeadStateProtector` roundtrip;
- wrong scope fails authentication;
- bit tamper fails authentication;
- truncation fails closed;
- oversized protected input is rejected before unprotect;
- plaintext marker is absent from protected bytes;
- desktop atomic replace/read/delete works;
- on Windows, real DPAPI current-user roundtrip succeeds and wrong scope fails;
- non-Windows production `WindowsDpapiStateProtector` must fail closed, not store plaintext.

Do not replace DPAPI with an app key stored next to ciphertext.

## 3. Persistent identity / presence

```powershell
.\gradlew.bat :shared:core-identity:desktopTest :shared:core-identity:compileDebugKotlinAndroid --no-daemon
```

`PersistentSharingStoresTest` must prove:

- same Ed25519 identity survives manager restart;
- same private seed survives restart;
- same public key survives restart;
- same ReferenceCode survives restart;
- persisted identity blob does not contain private seed bytes;
- persisted identity blob does not expose SharingIdentityId marker;
- persisted presence blob does not expose ReferenceCode marker;
- route rotation does not rotate identity;
- identity rotation does not rotate route;
- both rotations persist across restart;
- corrupted protected identity state fails closed.

Private seed MUST remain raw bytes inside protected plaintext and must not be encoded as String/Base64 for persistence.

## 4. Persistent trusted contacts

```powershell
.\gradlew.bat :shared:core-contacts:desktopTest :shared:core-contacts:compileDebugKotlinAndroid --no-daemon
```

Required:

- verified contacts survive store/manager restart;
- alias survives restart;
- pinned identity/key/fingerprint survive restart;
- authenticated ReferenceCode updates survive restart;
- rename survives restart;
- pinned key does not silently change;
- raw protected blob does not expose alias, identity, route or pinned key;
- corrupted protected contact state fails closed;
- duplicate persisted identity ownership is rejected;
- duplicate persisted route ownership is rejected;
- fingerprint is recomputed from key during decode.

## 5. Windows production E2E

On Windows run core-contacts desktop tests and verify:

`DesktopPersistentSharingStateE2ETest`

It must use:

- `DesktopSecureStateFactory.windows(...)`;
- Windows DPAPI, not test AEAD;
- real filesystem atomic storage;
- persistent identity + private seed;
- persistent ReferenceCode;
- persistent trusted contact;
- process-style factory/manager recreation;
- identity state copied over contact scope must fail closed.

## 6. Android Keystore instrumentation

Compile first:

```powershell
.\gradlew.bat :shared:core-secure-store:compileDebugKotlinAndroid :shared:core-contacts:compileDebugKotlinAndroid --no-daemon
```

With an Android emulator/device available, run the relevant connected instrumentation tests, normally:

```powershell
.\gradlew.bat :shared:core-secure-store:connectedDebugAndroidTest :shared:core-contacts:connectedDebugAndroidTest --no-daemon
```

If Gradle exposes a slightly different connected task name, use the actual task and report it exactly.

Mandatory device/emulator tests:

- `AndroidSecureStateE2ETest`;
- `AndroidPersistentSharingStateE2ETest`.

Required Android behavior:

- Android Keystore AES-256-GCM roundtrip;
- key remains non-exported in `AndroidKeyStore`;
- state files are under `noBackupFilesDir`;
- raw disk file does not contain plaintext marker/private seed/alias/route;
- wrong scope swap fails;
- bit tamper fails;
- identity/private seed survives process-style manager recreation;
- ReferenceCode survives recreation;
- trusted contact survives recreation.

Do not mark Android persistence fully verified if instrumentation did not actually run.

## 7. Scope swap / corruption matrix

Verify these all fail closed:

```text
identity protected blob -> contacts scope
identity protected blob -> presence scope
contacts protected blob -> identity scope
bit flip in ciphertext
truncated protected file
oversized protected file
wrong protector key / wrong DPAPI entropy scope
```

No fallback to EMPTY state is allowed for an existing corrupted file. Missing file means empty store; invalid existing file means error.

## 8. Atomicity / secret handling review

Review manually:

- temp file is fully written and synced before rename;
- temp and target are on same filesystem/directory;
- Android uses `fd.sync()` + `Os.rename()`;
- Desktop uses `FileChannel.force(true)` + `ATOMIC_MOVE`;
- decoded identity seed copies are cleared best-effort;
- in-memory replaced/deleted secret records are cleared;
- no private key is logged or converted to String;
- no persistent protector depends on VMK/KEK/PIN/FileKey/VaultId;
- there is no plaintext persistence fallback.

## 9. Explicit limitations: do NOT paper over them

Report these exactly rather than claiming more than the implementation provides:

### Rollback

Older previously valid protected blobs can currently be restored by an attacker with filesystem rollback capability. AEAD/DPAPI does not detect that.

Status:

`VALID-BLOB ROLLBACK PROTECTION NOT IMPLEMENTED.`

Do not add a counter stored only inside the same file and claim rollback protection; that does not solve it.

### Secure erase

File deletion / buffer clearing does not guarantee physical erase from SSD/flash/filesystem snapshots.

Status:

`FORENSIC SECURE ERASE NOT GUARANTEED.`

### Multi-process

Stores are serialized by in-process mutexes. Cross-process concurrent mutation is not supported.

### iOS

Exact status:

`iOS NOT VERIFIED / native crypto and secure-state protector pending macOS implementation and Xcode validation.`

## 10. Regression

Run:

```powershell
.\gradlew.bat :shared:core-model:desktopTest :shared:core-crypto:desktopTest :shared:core-secure-store:desktopTest :shared:core-identity:desktopTest :shared:core-contacts:desktopTest :shared:core-platform:compileKotlinDesktop :shared:core-transfer:desktopTest :shared:core-vault:desktopTest :server:signaling:test --no-daemon
```

Android compile regression:

```powershell
.\gradlew.bat :shared:core-model:compileDebugKotlinAndroid :shared:core-crypto:compileDebugKotlinAndroid :shared:core-secure-store:compileDebugKotlinAndroid :shared:core-identity:compileDebugKotlinAndroid :shared:core-contacts:compileDebugKotlinAndroid :shared:core-platform:compileDebugKotlinAndroid :shared:core-transfer:compileDebugKotlinAndroid :shared:core-vault:compileDebugKotlinAndroid --no-daemon
```

PR #3 baseline must remain green, including core-transfer 66/66 and the golden trust→vault E2E.

## 11. CommonMain scan

```powershell
git grep -n -E "java\.|javax\.|sun\.|android\.|com\.sun\.jna" -- "shared/core-secure-store/src/commonMain/**" "shared/core-identity/src/commonMain/**" "shared/core-contacts/src/commonMain/**"
```

Platform dependencies belong only in Android/Desktop source sets.

## 12. Diff hygiene

```powershell
git status
git diff --check feature/sharing-session-lifecycle...HEAD
git diff --stat feature/sharing-session-lifecycle...HEAD
```

No generated artifacts, secrets, temporary keys or accidental UI changes.

## Final report

Return:

```text
PR #4 SECURE PERSISTENCE BUILD VALIDATION

HEAD:

Core secure store:
- Desktop compile:
- Android compile:
- Desktop tests:
- AEAD scope separation:
- tamper:
- truncation:
- oversized file:
- atomic replace:
- Windows DPAPI:

Persistent identity/presence:
- Desktop tests:
- Android compile:
- same identity after restart:
- same private seed after restart:
- same ReferenceCode after restart:
- identity/route independent rotation:
- plaintext seed absent:
- corruption failure:

Persistent contacts:
- Desktop tests:
- Android compile:
- restart:
- rename/route update:
- pin preserved:
- plaintext metadata absent:
- corruption failure:

Windows production E2E:
- DPAPI:
- filesystem persistence:
- cross-scope swap:

Android instrumentation:
- task actually executed:
- AndroidSecureStateE2ETest:
- AndroidPersistentSharingStateE2ETest:
- Keystore:
- noBackupFilesDir:
- raw-disk privacy:

Sharing regression:
- core-model:
- core-crypto:
- core-secure-store:
- core-identity:
- core-contacts:
- core-transfer passed/total:
- core-vault:
- signaling:

Android full compile:
CommonMain scan:
diff --check:

Rollback protection:
VALID-BLOB ROLLBACK PROTECTION NOT IMPLEMENTED.

Secure erase:
FORENSIC SECURE ERASE NOT GUARANTEED.

iOS:
iOS NOT VERIFIED / native crypto and secure-state protector pending macOS implementation and Xcode validation.

Mechanical fixes:
Commit pushed:
Remaining failures:

DO NOT MERGE PR #1/#2/#3/#4.
```
