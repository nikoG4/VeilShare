# Sharing V1 Freeze Gate

`SHARING_V1_BASELINE_FROZEN` may be declared only after every required item below is green on the current PR head.

## 1. Repository hygiene

```powershell
git status
git diff main...HEAD
git diff --check main...HEAD
```

Required:

- no secrets/private keys/session keys in source or fixtures;
- no generated binaries/temp vaults committed;
- no debug bypasses;
- no plaintext sensitive fixtures;
- no unresolved merge markers.

## 2. Core model

```powershell
.\gradlew.bat :shared:core-model:desktopTest --no-daemon
```

Required coverage:

- Base64 ByteArray wire serialization roundtrip;
- strict protocol-version validation;
- `TransferOffer/Accept/Reject/Failure` validation;
- explicit `SESSION_CONFIRM_ACK` peer message type.

## 3. Core crypto / handshake

```powershell
.\gradlew.bat :shared:core-crypto:desktopTest --no-daemon
.\gradlew.bat :shared:core-crypto:compileDebugKotlinAndroid --no-daemon
```

Required coverage:

- HELLO signature and trusted sender-key binding;
- CONFIRM signature and receiver ephemeral binding;
- signed CONFIRM_ACK;
- sender ephemeral replacement fails even when transcript hash is recomputed;
- wrong trusted sender identity fails ACK verification;
- canonical transcript changes when any bound field changes;
- ambiguous delimiter layouts cannot collide at canonical encoding layer;
- both peers derive identical S2R/R2S keys;
- S2R != R2S;
- same X25519 secret under different transcript derives different traffic keys.

## 4. Core transfer build

```powershell
.\gradlew.bat :shared:core-transfer:compileKotlinDesktop --no-daemon
.\gradlew.bat :shared:core-transfer:compileDebugKotlinAndroid --no-daemon
.\gradlew.bat :shared:core-transfer:desktopTest --no-daemon
```

Required coverage:

- canonical DATA AAD;
- real ChaCha20-Poly1305 negative tests;
- cross-session/cross-transfer/file gate rejection;
- 1 MiB chunk fragmentation;
- exact nested wire-size bounds;
- hostile fragment metadata;
- duplicate/out-of-order fragments;
- retry ciphertext reuse;
- cancellation without retry;
- source cleanup on success/failure/cancel/crypto error;
- receiver active-transfer and byte limits;
- idle/max-lifetime sweep;
- explicit abort;
- single-consumer import.

## 5. Signaling

```powershell
.\gradlew.bat :server:signaling:test --no-daemon
```

Required coverage:

- legitimate RELAY traffic can exceed old 60/min bug threshold;
- relay message cap;
- relay byte cap;
- independent connection budgets;
- real concurrent calls never exceed configured caps;
- WebSocket frame and signaling envelope size limits remain enforced.

## 6. Transfer -> vault E2E

`core-transfer:desktopTest` must include and pass:

- real ChaCha encrypted transfer -> receiver -> `VaultHandle.import()` -> read exact plaintext;
- persistent vault close/reopen -> same file exact;
- validated `TransferOffer` metadata applied to imported item;
- offered size mismatch rejected before consuming transfer;
- invalid filename rejected before consuming transfer.

## 7. Full authenticated-sharing E2E

Must pass a single test chain containing real production primitives:

```
Ed25519 HELLO
  -> Ed25519 CONFIRM + receiver X25519
  -> signed CONFIRM_ACK + sender X25519
  -> transcript-bound HKDF
  -> ChaCha20-Poly1305 DATA
  -> transport fragmentation/reassembly
  -> receiver
  -> validated offer
  -> crash-safe vault import
  -> persistent vault reopen/read
```

Fakes are not sufficient for this gate.

## 8. Frozen vault regression

```powershell
.\gradlew.bat :shared:core-vault:desktopTest --no-daemon
```

Also run any existing Android unit/instrumented vault suites available on the local machine.

Sharing changes must not weaken:

- REAL/DECOY isolation;
- Argon2id policy;
- VBL1 format;
- encrypted catalog;
- journal V2;
- import/delete crash safety;
- orphan GC/recovery;
- PIN rotation;
- corruption handling.

## 9. Combined gate

At minimum:

```powershell
.\gradlew.bat :shared:core-model:desktopTest :shared:core-crypto:desktopTest :shared:core-transfer:desktopTest :shared:core-vault:desktopTest :server:signaling:test --no-daemon
```

Result must be `BUILD SUCCESSFUL`.

## 10. KMP audit

Search `commonMain` for accidental JVM-only APIs:

```powershell
git grep -n -E "java\.|javax\.|sun\.|java\.io|java\.nio" -- "shared/*/src/commonMain/**"
```

Any hit must be inspected and justified/fixed.

Desktop + Android compilation is required before merge.

iOS may only be reported as:

> iOS source compatibility statically reviewed; native compilation/tests require macOS/Xcode.

until actually executed on macOS.

## 11. Remaining accepted V1 constraints

These do not by themselves block the baseline if documented:

- empty files unsupported;
- in-memory encrypted receiver buffering capped at 64 MiB per transfer;
- maximum two active received transfers by default;
- no network resume after process death;
- iOS native build pending macOS.

A trusted contact/pinning workflow must exist before product UX treats a lookup key as a trusted person. Lookup alone is not identity trust.

## 12. Freeze declaration

Only after all executable gates above are green:

```
SHARING_V1_BASELINE_FROZEN = YES
```

Create one reviewed checkpoint commit/tag according to repository release practice.

If any required gate fails:

```
SHARING_V1_BASELINE_FROZEN = NO
```

and record the exact failing task/test instead of weakening an invariant merely to make the build pass.
