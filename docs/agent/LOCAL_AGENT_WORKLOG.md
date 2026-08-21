# Local Agent Worklog

## Repository
root: C:\Users\ll\Desktop\private app
branch: master
starting HEAD for recovery: 02e1d70 feat: polish local vault release readiness
current HEAD before commit: 02e1d70

## Latest Stable Baseline
LOCAL_PRODUCT_RELEASE_READINESS completed before this recovery pass.
Frozen local vault core remains green.

## Current Objective
Recover Qwen partial Sharing/Signaling V1 work from the real repository, finish protocol foundation, and continue to the first material server/client control-plane baseline if foundation is green.

## Security Invariants
- Independent sharing identity per logical context.
- ReferenceCode is random, high entropy, rotatable, and not identity-derived.
- Signaling V1 is Ktor WebSocket relay, not WebRTC/STUN/TURN/P2P.
- Server-visible DTOs must not contain filename, MIME, vault type, VMK, KEK, FileKey, catalog key, local alias, or plaintext digest.
- Peer payload remains opaque to the server.
- No per-chunk ACK in V1; disconnect means restart full transfer.
- Network/sharing keys remain separate from all vault keys.
- Receiver import path remains future decrypted stream -> ImportSource -> ImportCoordinator.

## Recovery Audit
Qwen completed design docs under docs/sharing and started protocol foundation code.
Partial/invalid state found:
- shared/core-model Messaging.kt imported core-crypto, breaking dependency direction.
- Identifiers.kt declared expect generateId() without matching actuals in the same package.
- CryptoIdGenerator partial files existed in core-crypto and would not cover iOS.
- server/signaling/src/commonMain existed under a JVM-only module and was ignored by Gradle.
- DTO concepts were duplicated between server/signaling and shared/core-model.

## Completed
- Repaired core-model identifier/protocol foundation.
- Added typed opaque IDs for SharingIdentityId, ReferenceCode, ConnectionId, SessionId, TransferId, MessageId.
- Added ReferenceCodes parser/normalizer/generator with injected entropy and 80-bit Base32 code.
- Added fail-closed SignalingEnvelope and peer-envelope DTOs.
- Added pure SharingStateMachine for sender/receiver flows.
- Moved effective server-visible protocol model into shared/core-model.
- Removed ignored server/signaling/src/commonMain DTO files.
- Added server in-memory PresenceRegistry, SessionRegistry, fixed-window RateLimiter, limits, and injectable clock.
- Added Ktor 3.5.2 WebSocket endpoint /v1/ws.
- Endpoint supports REGISTER, UNREGISTER, LOOKUP, RELAY, PING, ERROR responses.
- Added common SignalingClient contract in core-platform.
- Added unit/integration tests for protocol validation, IDs, state machine, registries, rate limiting, and WebSocket relay.

## Tests / Commands Verified
- .\gradlew.bat :shared:core-model:allTests :server:signaling:test :server:signaling:build --no-daemon
  - BUILD SUCCESSFUL
- .\gradlew.bat :server:signaling:build :shared:core-model:allTests :shared:core-platform:allTests :shared:core-vault:desktopTest :shared:core-crypto:desktopTest :shared:ui-features:desktopTest :shared:app:compileDebugKotlinAndroid :shared:ui-features:testDebugUnitTest --no-daemon
  - BUILD SUCCESSFUL
  - 162 actionable tasks: 148 executed, 14 up-to-date

## Test Count Snapshot
Relevant XML reports counted: 126 test executions, 0 failures/errors.
Includes core-model Desktop/Android debug/release, server signaling, frozen core-vault Desktop, core-crypto Desktop, and ui-features Desktop/Android unit reports.

## Bugs Found
- Compile break: core-model depended on core-crypto via CryptoIdGenerator. Fixed by using RandomBytesSource in core-model.
- Ignored source-set: server/signaling/src/commonMain was not part of the JVM module. Fixed by deleting ignored partial DTOs and placing server runtime code in src/main.
- Protocol privacy issue: prior RelayMessage exposed peer transfer/file-like fields to server. Fixed by making server relay carry opaquePayload only.

## Decisions
- Ktor version pinned to 3.5.2 based on official Ktor release docs/Maven metadata checked on 2026-08-21.
- Foundation DTOs live in shared/core-model so Android/Desktop can share the protocol contract.
- Registries and rate limiting live in server/signaling because they are relay runtime state.
- Common client is currently an interface only; Ktor client implementation is next.
- Server remains in-memory and blind; no DB and no file transfer.

## Pending
- Implement real common/JVM signaling client over Ktor WebSocket.
- Add endpoint/client integration using that production client abstraction.
- Add authenticated E2E handshake later; no custom crypto added in this pass.
- Add file transfer only after handshake is specified and tested.

## Blockers
None.

## Next Action
Commit this checkpoint locally, then continue with production client implementation and expanded endpoint/client integration tests.
