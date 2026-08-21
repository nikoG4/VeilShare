# Sharing / Signaling V1 Implementation Plan

Date: 2026-08-15
Author: VeilShare Agent

## Overview

This plan outlines the phased implementation of Sharing V1. Each phase is small, testable, and preserves the frozen core baseline.

## Phases

### Phase 1: Common Protocol DTOs

**Objective:** Define shared message DTOs for transfer protocol.

**Milestones:**

1. Create `shared/core-model` DTOs:
   - `TransferId`, `FileMeta`, `Chunk`, `Ack`
   - `ProtocolMessage` sealed interface
   - Protocol version constants

2. Protocol version handling:
   - `protocolVersion` field in all messages
   - Version mismatch error handling

3. Serialization:
   - Kotlin serialization
   - JSON fallback for debugging

**Modules:**
- `:shared:core-model` (new subpackage)

**Tests:**
- Message serialization/deserialization
- Protocol version handling
- Invalid message rejection
- Duplicate transferId handling

**Done criteria:**
- All DTOs compile
- Serialization verified
- 10+ tests passing

---

### Phase 2: Signaling Server Session Registry

**Objective:** In-memory presence and session registry.

**Milestones:**

1. Presence map:
   - `Map<String, PresenceInfo>` (reference code → identity)
   - TTL-based cleanup (30s default)
   - Thread-safe (ConcurrentHashMap)

2. Session registry:
   - `Map<TransferId, TransferSession>`
   - Sender/receiver identities
   - State tracking
   - Cleanup on TTL/timeout

3. Challenge generation:
   - Per-connection nonces
   - Timestamp windows

4. Signal forwarding:
   - Blind relay between authenticated peers
   - No payload inspection

**Infrastructure:**
- Ktor application
- WebSocket endpoint `/v1/ws`
- Challenge endpoint `/v1/auth`
- Health endpoint `/v1/health`

**Tests:**
- Presence registration/unregistration
- Session lifecycle
- TTL cleanup
- Signal forwarding
- Rate limiting (basic)

**Done criteria:**
- Server starts
- Presence working
- 10+ tests passing

---

### Phase 3: Client Connection

**Objective:** Implement `SignalingClient` and platform transport.

**Milestones:**

1. Ktor client:
   - Connect to `/v1/ws`
   - Authenticate with challenge response
   - Reconnect on disconnect

2. Platform adapters:
   - Android: `AndroidPeerTransport`
   - Desktop: `DesktopPeerTransport`
   - Common: `PeerTransport` interface

3. SecureChannel:
   - Session key derivation
   - E2E encrypted channel
   - Message framing

**Modules:**
- `:shared:core-transfer`
- `:shared:core-platform` (transport contract)
- Platform-specific source sets

**Tests:**
- Client authentication
- Challenge response
- Reconnect behavior
- Transport interface

**Done criteria:**
- Client connects
- Authentication verified
- 10+ tests passing

---

### Phase 4: Offer/Accept Handshake

**Objective:** Implement offer/accept flow.

**Milestones:**

1. SESSION_HELLO message:
   - Send file count, total size
   - Verify signature

2. SESSION_ACCEPT/REJECT:
   - Accept/reject decision
   - Reason codes

3. State machine transitions:
   - Idle → Creating → Offering → Negotiating
   - Error handling
   - Timeout handling

**Integration:**
- Signaling client messages
- State machine implementation

**Tests:**
- Offer sent/accepted
- Offer rejected
- Timeout behavior
- Signature verification

**Done criteria:**
- Handshake verified
- State transitions valid
- 10+ tests passing

---

### Phase 5: Encrypted Transfer Stream

**Objective:** Implement chunk transfer with integrity.

**Milestones:**

1. Chunk framing:
   - Nonce per chunk
   - AEAD encryption
   - Auth tag

2. Receiver pipeline:
   - Verify/decrypt chunk
   - Write bounded buffer
   - Encrypt with FileKey
   - Persist chunk
   - Send ACK after durable

3. Sender pipeline:
   - Read file
   - Chunk into pieces
   - Sign with ephemeral key
   - Send chunks
   - Send ACKs

4. Integrity:
   - Verify ciphertext
   - Reject corrupted chunks
   - Retry unacked chunks

**Modules:**
- `:shared:core-transfer`
- Reuse `:shared:core-crypto` primitives
- ImportCoordinator integration

**Tests:**
- Chunk send/receive
- Integrity verification
- Duplicate chunk handling
- Corrupted chunk rejection
- Progress tracking

**Done criteria:**
- Transfer verified
- Integrity working
- 15+ tests passing

---

### Phase 6: Receiver Import Integration

**Objective:** Integrate received chunks with existing vault.

**Milestones:**

1. ImportCoordinator:
   - Accept chunk stream as `ImportSource`
   - Write to blob store
   - Catalog commit
   - Durable semantics

2. Chunk → File:
   - Track `(fileId, chunkIndex)`
   - Assemble file
   - Commit catalog entry

3. Cancel handling:
   - Cancel before commit → no entry
   - Cancel after commit → entry exists

4. Verification:
   - Complete transfer before completion
   - Handle verification failure

**Integration:**
- `:shared:core-vault`
- Existing import pipeline

**Tests:**
- Import from network stream
- Catalog entry creation
- Cancel semantics
- Resume behavior (if implemented)

**Done criteria:**
- Import verified
- Semantics preserved
- 15+ tests passing

---

### Phase 7: Basic UI

**Objective:** Minimal UI for sharing.

**Milestones:**

1. Sender UI:
   - File selection
   - Enter reference code
   - Send button
   - Progress indicator
   - Complete/cancel buttons

2. Receiver UI:
   - Incoming notification
   - Accept/reject
   - Destination folder (optional)
   - Progress indicator
   - Complete/cancel buttons

3. Error handling:
   - Offer rejected
   - Connection failed
   - Cancel requested

**Platforms:**
- Android: Compose screen
- Desktop: Compose Desktop view

**Tests:**
- UI smoke tests
- Error states
- Cancel behavior

**Done criteria:**
- UI flows working
- Smoke tests passing

---

### Phase 8: Hardening and Tests

**Objective:** Security hardening and comprehensive tests.

**Milestones:**

1. Security review:
   - No plaintext in logs
   - Proper key handling
   - Rate limiting
   - Invalid code rejection

2. Fault injection:
   - Network disconnect during transfer
   - Corrupted payload
   - Cancel mid-transfer
   - Signaling restart

3. Full regression:
   - Local product regression
   - Existing tests still passing
   - No core regression

4. Performance:
   - Large file transfer
   - Many concurrent transfers
   - Memory usage

**Tests:**
- Security audit tests
- Fault injection tests
- Performance tests
- Full regression

**Done criteria:**
- All tests passing
- Security review passed
- Performance acceptable

---

## Backlog

### P0 (Required for V1)

- [ ] Protocol DTOs
- [ ] Signaling server registry
- [ ] Client connection
- [ ] Offer/accept handshake
- [ ] Encrypted transfer
- [ ] Import integration
- [ ] Basic sender/receiver UI

### P1 (Nice to have)

- [ ] Contact favorites
- [ ] Retry logic
- [ ] Better error messages
- [ ] Cancel UI polish

### P2 (Future)

- [ ] Resume transfer
- [ ] QR code support
- [ ] Multi-file polish
- [ ] Thumbnail sharing

### P3 (Out of scope V1)

- [ ] Group transfers
- [ ] Multi-device sync
- [ ] Cloud library

---

## Dependencies

| Phase | Depends On |
|-------|-------------|
| 1 | core-model existing |
| 2 | Ktor existing, Phase 1 |
| 3 | core-transfer existing, Phase 1/2 |
| 4 | Phase 3 |
| 5 | Phase 4, core-crypto |
| 6 | Phase 5, core-vault |
| 7 | Phase 6 |
| 8 | All phases |

---

## Risk Assessment

| Risk | Mitigation |
|------|-------------|
| Breaking core | Phase 1-2 isolated, no core changes |
| Network instability | Idempotent chunks, retry |
| Large files | Chunked transfer, timeouts |
| Key compromise | Per-transfer ephemeral keys |
| Server compromise | Blind relay, no plaintext |

---

## Success Criteria

1. **Local product baseline preserved:**
   - All existing tests still passing
   - No core modifications
   - No security regressions

2. **V1 functional:**
   - Sender can transfer file
   - Receiver can accept and receive
   - Cancel works
   - UI smoke tests pass

3. **Security verified:**
   - No plaintext leaks
   - Proper key handling
   - Signature verification

4. **Performance acceptable:**
   - File transfer completes
   - Memory within bounds
   - No resource leaks

---

**Next Action:** Begin Phase 1: Define protocol DTOs and state machine tests.
