# Local Agent Worklog

## Repository
root: C:\Users\ll\Desktop\private app
branch: master
current HEAD: 590d8ac feat: add signaling websocket client baseline

## Latest Stable Baseline
LOCAL_PRODUCT_RELEASE_READINESS completed before this recovery pass.
Frozen local vault core remains green.

## Current Objective
Sharing V1 — implement authenticated E2E handshake (handshake layer between signaling relay and file transfer).
TransferReceiver concurrency fix complete — all tests green on Desktop and Android.

## Security Invariants
- Independent sharing identity per logical context.
- ReferenceCode is random, high entropy, rotatable, and not identity-derived.
- Signaling V1 is Ktor WebSocket relay, not WebRTC/STUN/TURN/P2P.
- Server-visible DTOs must not contain filename, MIME, vault type, VMK, KEK, FileKey, catalog key, local alias, or plaintext digest.
- Peer payload remains opaque to the server.
- No per-chunk ACK in V1; disconnect means restart full transfer.
- Network/sharing keys remain separate from all vault keys.
- Receiver import path remains future decrypted stream -> ImportSource -> ImportCoordinator.

## Recovery Audit (historical)
Qwen completed design docs under docs/sharing and started protocol foundation code.
Issues found and fixed: core-model→core-crypto dependency break, expect/actual mismatch, ignored commonMain source-set, DTO duplication, server-facing RelayMessage privacy leak.

## Completed

### Phase: Sharing Protocol V1 Foundation (f569efa) + Client Baseline (590d8ac)
- Repaired core-model identifier/protocol foundation.
- Added typed opaque IDs for SharingIdentityId, ReferenceCode, ConnectionId, SessionId, TransferId, MessageId.
- Added ReferenceCodes parser/normalizer/generator with injected entropy and 80-bit Base32 code.
- Added fail-closed SignalingEnvelope and peer-envelope DTOs.
- Added pure SharingStateMachine for sender/receiver flows.
- Moved effective server-visible protocol model into shared/core-model.
- Removed ignored server/signaling/src/commonMain DTO files.
- Added server in-memory PresenceRegistry, SessionRegistry, fixed-window RateLimiter, limits, and injectable clock.
- Added Ktor 3.3.3 WebSocket endpoint /v1/ws.
- Endpoint supports REGISTER, UNREGISTER, LOOKUP, RELAY, PING, ERROR responses.
- Added common SignalingClient contract in core-platform.
- Added KtorSignalingClient common implementation over an injected HttpClient.
- Added endpoint/client integration test: client A registers, client B registers, A looks up B, A relays opaque payload, B receives exact bytes, disconnect cleans presence.
- Added unit/integration tests for protocol validation, IDs, state machine, registries, rate limiting, and WebSocket relay.
- 10 design docs written under docs/sharing/ (architecture, protocol, state machine, handshake contract, threat model, failure model, receiver import contract, metadata privacy table, REAL/decoy privacy, implementation plan).

### Phase: TransferReceiver Concurrency Fix (current session)
- Fixed TransferReceiver.kt compilation errors on Desktop and Android.
- Added proper Mutex factory function (`newMutex()`) in Mutex.kt using kotlinx.coroutines.sync.Mutex.
- Replaced `Mutex()` constructor calls with `newMutex()` factory.
- Made `getImportSource` suspend in TransferReceiver interface to support Mutex.withLock.
- Made TransferState methods suspend where they use mutex (`getChunk`, `isComplete`, `getTotalBytes`, `cancel`, `fail`, `isCancelled`, `isFailed`, `isCompleted`).
- Added non-suspend `getTotalBytesSync()` for property accessors that need sizeHint.
- Fixed `receive()` to use `mapMutex.withLock()` instead of manual lock/unlock.
- Fixed `TransferImportSourceImpl` to use `state.totalChunks` instead of bare `totalChunks`.

## Tests / Commands Verified
### This session (2026-09-08)
- `.\gradlew.bat :shared:core-platform:allTests :server:signaling:test --no-daemon` → BUILD SUCCESSFUL (60 tasks, 8 executed)
- `.\gradlew.bat :shared:core-model:allTests :server:signaling:build :shared:core-vault:desktopTest :shared:core-crypto:desktopTest --no-daemon` → BUILD SUCCESSFUL (62 tasks, 8 executed)
- `.\gradlew.bat :shared:core-transfer:compileKotlinDesktop :shared:core-transfer:compileDebugKotlinAndroid --no-daemon` → BUILD SUCCESSFUL
- `.\gradlew.bat :shared:core-transfer:desktopTest --no-daemon` → BUILD SUCCESSFUL
- `.\gradlew.bat :shared:core-model:desktopTest :shared:core-crypto:desktopTest :shared:core-transfer:desktopTest :shared:core-vault:desktopTest :server:signaling:test --no-daemon` → BUILD SUCCESSFUL

### Historical regressions (prior session)
- `.\gradlew.bat :shared:core-model:allTests :server:signaling:test :server:signaling:build --no-daemon` → BUILD SUCCESSFUL
- Full regression (`:server:signaling:build :shared:core-model:allTests :shared:core-platform:allTests :shared:core-vault:desktopTest :shared:core-crypto:desktopTest :shared:ui-features:desktopTest :shared:app:compileDebugKotlinAndroid :shared:ui-features:testDebugUnitTest --no-daemon`) → BUILD SUCCESSFUL, 162 actionable tasks: 148 executed, 14 up-to-date
- `.\gradlew.bat :shared:core-platform:allTests :server:signaling:test --no-daemon` → BUILD SUCCESSFUL

## Test Count Snapshot
~126 test executions across core-model Desktop/Android debug/release, server signaling, frozen core-vault Desktop, core-crypto Desktop. 0 failures/errors.

## Bugs Found (resolved during foundation phase)
- Compile break: core-model depended on core-crypto via CryptoIdGenerator. Fixed by using RandomBytesSource in core-model.
- Ignored source-set: server/signaling/src/commonMain was not part of the JVM module. Fixed by deleting ignored partial DTOs and placing server runtime code in src/main.
- Protocol privacy issue: prior RelayMessage exposed peer transfer/file-like fields to server. Fixed by making server relay carry opaquePayload only.

## Decisions
- Ktor pinned to 3.3.3 for Kotlin/Native ABI compatibility with Kotlin 2.2.21.
- Foundation DTOs live in shared/core-model so Android/Desktop can share the protocol contract.
- Registries and rate limiting live in server/signaling because they are relay runtime state.
- Common client has a Ktor implementation that compiles for KMP with injected engine/client.
- Server remains in-memory and blind; no DB and no file transfer.

## Pending
- [HANDSHAKE] Design and implement authenticated E2E handshake per docs/sharing/HANDSHAKE_CONTRACT.md.
- [FILE TRANSFER] Implement file transport after handshake is specified and tested.

## Blockers
None.

## Next Action
Read docs/sharing/HANDSHAKE_CONTRACT.md and IMPLEMENTATION_PLAN.md to begin handshake design/implementation phase.
Verify all module tests pass with full regression.
