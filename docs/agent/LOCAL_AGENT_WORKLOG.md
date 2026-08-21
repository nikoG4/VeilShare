# Local Agent Worklog

## Repository
root: C:\Users\ll\Desktop\private app
branch: master
starting HEAD for recovery: 02e1d70 feat: polish local vault release readiness
current HEAD: see latest git log after final checkpoint commit

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
- Added KtorSignalingClient common implementation over an injected HttpClient.
- Added endpoint/client integration test: client A registers, client B registers, A looks up B, A relays opaque payload, B receives exact bytes, disconnect cleans presence.
- Added unit/integration tests for protocol validation, IDs, state machine, registries, rate limiting, and WebSocket relay.

## Tests / Commands Verified
- .\gradlew.bat :shared:core-model:allTests :server:signaling:test :server:signaling:build --no-daemon
  - BUILD SUCCESSFUL
- .\gradlew.bat :server:signaling:build :shared:core-model:allTests :shared:core-platform:allTests :shared:core-vault:desktopTest :shared:core-crypto:desktopTest :shared:ui-features:desktopTest :shared:app:compileDebugKotlinAndroid :shared:ui-features:testDebugUnitTest --no-daemon
  - BUILD SUCCESSFUL
  - 162 actionable tasks: 148 executed, 14 up-to-date
- .\gradlew.bat :shared:core-platform:allTests :server:signaling:test --no-daemon
  - BUILD SUCCESSFUL

## Test Count Snapshot
Relevant XML reports counted before client integration: 126 test executions, 0 failures/errors.
Includes core-model Desktop/Android debug/release, server signaling, frozen core-vault Desktop, core-crypto Desktop, and ui-features Desktop/Android unit reports.

## Bugs Found
- Compile break: core-model depended on core-crypto via CryptoIdGenerator. Fixed by using RandomBytesSource in core-model.
- Ignored source-set: server/signaling/src/commonMain was not part of the JVM module. Fixed by deleting ignored partial DTOs and placing server runtime code in src/main.
- Protocol privacy issue: prior RelayMessage exposed peer transfer/file-like fields to server. Fixed by making server relay carry opaquePayload only.

## Decisions
- Ktor 3.5.2 was checked as the latest stable release, but 3.5.2 and 3.4.3 Kotlin/Native artifacts require ABI 2.3.0 and are incompatible with the repository's Kotlin 2.2.21 toolchain. Ktor is pinned to 3.3.3 for KMP compatibility.
- Foundation DTOs live in shared/core-model so Android/Desktop can share the protocol contract.
- Registries and rate limiting live in server/signaling because they are relay runtime state.
- Common client has a Ktor implementation that compiles for KMP with injected engine/client.
- Server remains in-memory and blind; no DB and no file transfer.

## Pending
- Add authenticated E2E handshake later; no custom crypto added in this pass.
- Add file transfer only after handshake is specified and tested.

## Blockers
None.

## Next Action
Run final regression, commit client/control-plane checkpoint locally, then next phase is authenticated E2E handshake design/implementation.
