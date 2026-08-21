# Failure Model V1

Date: 2026-08-15
Author: VeilShare Agent
Version: V1

## Overview

This document describes failure handling for sharing protocol V1.

**V1 Constraint:** No byte-range resume. Full restart on disconnect.

## Failure Categories

### A. Network Disconnect

| Event | Sender State | Receiver State | Cleanup | Retry |
|-------|--------------|----------------|---------|-------|
| Disconnect during transfer | Transferring | Transferring | Remove partial chunks | ✅ Yes (restart) |
| Disconnect after COMPLETE | Completed | Completed | No action | ❌ No |
| Disconnect before COMPLETE | Verifying | Verifying | Discard session | ✅ Yes (restart) |

### B. Signaling Server Issues

| Event | Action | Cleanup |
|-------|--------|----------|
| Signaling restart | All sessions invalidated | Remove all sessions |
| Server crash | Sessions lost on restart | No state (ephemeral) |
| Server restart | Sessions invalidated | Clean on restart |

### C. User Cancel

| Event | Initiator | Cleanup | Duplicate Prevention |
|-------|--------|----------|---|
| Cancel sender | Sender | Remove chunks from sender | ✅ Idempotent |
| Cancel receiver (pre-commit) | Receiver | No entry created | ✅ Idempotent |
| Cancel receiver (post-commit) | Receiver | Entry exists | ✅ Ignore cancel |

### D. Protocol Errors

| Event | Error Type | Cleanup | Retry |
|-------|-----------|----------|------|
| Invalid protocol version | PROTOCOL_VERSION_MISMATCH | Discard message | ❌ No (restart) |
| Invalid signature | INVALID_SIGNATURE | Discard message | ✅ Yes (retry) |
| Duplicate session | DUPLICATE_SESSION | Ignore | ✅ Yes (idempotent) |
| Corrupted payload | PAYMENT_CORRUPTED | Discard chunk | ✅ Yes (resend) |
| Invalid transferId | INVALID_TRANSFER_ID | Discard message | ❌ No (abort) |

### E. Timeout

| Event | Timeout Type | Cleanup | Retry |
|-------|-----------|----------|------|
| Offer timeout | OFFER_TTL | Discard offer | ✅ Yes (restart) |
| Session timeout | SESSION_TTL | Discard session | ❌ No (restart) |
| Lookup timeout | LOOKUP_TTL | Discard lookup | ✅ Yes (retry) |
| Inactivity timeout | IDLE_TTL | Discard transfer | ✅ Yes (restart) |

### F. Peer Offline

| Event | Detection | Cleanup | Retry |
|-------|---------|----------|------|
| Peer not found | NOT_FOUND | Discard lookup | ✅ Yes (retry later) |
| Peer offline | No response | Session expired | ✅ Yes (retry) |
| Peer busy | RATE_LIMITED | Discard request | ✅ Yes (later) |

## State-Specific Failures

### Idle State

**Failures:**
- `createSession()` throws exception
- `cancel()` called before `createSession()`

**Cleanup:**
- Discard any partial state
- No session created

**Retry:**
- ✅ Yes (user retries)

### Ready State

**Failures:**
- Peer not found in lookup
- Invalid reference code

**Cleanup:**
- Discard lookup request
- No session

**Retry:**
- ✅ Yes (retry lookup)

### ResolvingPeer State

**Failures:**
- Lookup timeout
- Peer not found
- Protocol error

**Cleanup:**
- Discard lookup state
- Remove from presence map

**Retry:**
- ✅ Yes (retry lookup)

### CreatingSession State

**Failures:**
- Session creation exception
- Invalid ephemeral key
- Transcript hash mismatch

**Cleanup:**
- Discard session keys
- No session established

**Retry:**
- ✅ Yes (restart session)

### WaitingForPeer State

**Failures:**
- Offer timeout
- Peer reject offer
- Protocol error

**Cleanup:**
- Discard offer
- Remove session

**Retry:**
- ✅ Yes (restart offer)

### AwaitingDecision State

**Failures:**
- Timeout waiting for decision
- Invalid decision
- Protocol error

**Cleanup:**
- Discard session
- Remove session

**Retry:**
- ✅ Yes (restart session)

### Negotiating State

**Failures:**
- Metadata exchange error
- Protocol error

**Cleanup:**
- Discard session
- Remove session

**Retry:**
- ✅ Yes (restart transfer)

### Transferring State

**Failures:**
- Network disconnect
- Corrupted payload
- Protocol error

**Cleanup:**
- Remove partial chunks
- Discard session
- Log error

**Retry:**
- ✅ Yes (restart transfer)

### Verifying State

**Failures:**
- Integrity verification fail
- Missing chunks
- Protocol error

**Cleanup:**
- Remove partial file
- Discard session
- Log error

**Retry:**
- ❌ No (file corrupted)

### Completed State

**Failures:**
- None (terminal state)
- If error after completion: `COMPLETE` still sent

**Cleanup:**
- Discard session keys
- File in catalog

**Retry:**
- ❌ No (completed)

### Failed State

**Failures:**
- None (terminal state)

**Cleanup:**
- Remove partial state
- Log failure reason

**Retry:**
- ✅ Yes (user retries)

### Cancelled State

**Failures:**
- None (terminal state)

**Cleanup:**
- Remove session
- Discard partial state

**Retry:**
- ❌ No (cancelled)

### Rejected State

**Failures:**
- None (terminal state)

**Cleanup:**
- Discard session
- Discard offer

**Retry:**
- ✅ Yes (user retries)

### Expired State

**Failures:**
- None (terminal state)

**Cleanup:**
- Remove session
- Discard offer

**Retry:**
- ✅ Yes (new offer)

## Error Types

### Protocol Errors

```kotlin
sealed class ProtocolError : Exception() {
    data class InvalidVersion(
        val expected: Int,
        val actual: Int
    ) : ProtocolError()
    
    data class InvalidMessage(val expectedType: MessageType, val actualType: MessageType) : ProtocolError()
    
    data class UnsupportedMessageType(val messageType: MessageType) : ProtocolError()
    
    data class MalformedPayload(val payload: ByteArray) : ProtocolError()
    
    data class InvalidReferenceCode(val code: String) : ProtocolError()
}
```

### Auth Errors

```kotlin
sealed class AuthError : Exception() {
    data class InvalidSignature : AuthError()
    
    data class InvalidIdentity(val sharingIdentityId: SharingIdentityId) : AuthError()
    
    data class DuplicateSession(val sessionId: SessionId) : AuthError()
    
    data class ExpiredSession(val sessionId: SessionId) : AuthError()
}
```

### Network Errors

```kotlin
sealed class NetworkError : Exception() {
    data class Disconnect : NetworkError()
    
    data class Timeout(val timeout: Long) : NetworkError()
    
    data class InvalidData(val data: ByteArray) : NetworkError()
}
```

### Internal Errors

```kotlin
sealed class InternalError : Exception() {
    data class SessionNotFound(val sessionId: SessionId) : InternalError()
    
    data class InvalidState(val state: TransferState) : InternalError()
    
    data class InvalidTransition(val from: TransferState, val to: TransferState) : InternalError()
}
```

## Cleanup Operations

### On Failure

```kotlin
fun cleanupSession(session: TransferSession, reason: String) {
    // Remove from session registry
    sessionRegistry.remove(session.sessionId)
    
    // Remove from presence map if needed
    presenceRegistry.remove(session.senderConnectionId)
    
    // Remove partial chunks
    val chunks = getPartialChunks(session.transferId)
    if (!chunks.isEmpty()) {
        // Mark chunks as partial
        val orphanedFiles = chunks.map { (fileId, chunkIndex) ->
            FileId(transferId, fileId)
        }
        // Don't delete (corruption != cleanup authorization)
        log.warn("Session $reason: $orphanedFiles orphaned")
    }
    
    // Log failure
    log.error("Transfer session failed: $reason", e)
}
```

### On Cancel

```kotlin
fun cancelSession(session: TransferSession, initiator: Initiator) {
    // If committed, ignore cancel
    if (session.state == Completed) return
    
    // Remove from session registry
    sessionRegistry.remove(session.sessionId)
    
    // Remove from presence map
    presenceRegistry.remove(session.senderConnectionId)
    
    // Discard partial chunks
    val chunks = getPartialChunks(session.transferId)
    chunks.forEach { (fileId, chunkIndex) ->
        deleteChunk(session.transferId, fileId, chunkIndex)
    }
    
    // Log cancel
    log.info("Transfer cancelled by $initiator")
}
```

### On Timeout

```kotlin
fun cleanupTimeout(session: TransferSession) {
    // Remove from session registry
    sessionRegistry.remove(session.sessionId)
    
    // Remove from presence map
    presenceRegistry.remove(session.senderConnectionId)
    
    // Discard partial chunks
    val chunks = getPartialChunks(session.transferId)
    chunks.forEach { (fileId, chunkIndex) ->
        deleteChunk(session.transferId, fileId, chunkIndex)
    }
    
    // Log timeout
    log.info("Transfer session timed out")
}
```

## Idempotency

### TransferId Dedup

```kotlin
fun isDuplicateDelivery(transferId: TransferId, fileId: FileId, chunkIndex: Int): Boolean {
    val existingFile = catalog.get(transferId, fileId)
    return existingFile != null
}
```

### SessionId Dedup

```kotlin
fun isDuplicateSession(sessionId: SessionId, senderId: SharingIdentityId): Boolean {
    return sessionRegistry.containsKey(sessionId)
}
```

## State Machine Tests

Test cases:

1. Idle → Ready → ResolvingPeer → Failed (peer not found)
2. ResolvingPeer → Expired (lookup timeout)
3. CreatingSession → Failed (key agreement fail)
4. WaitingForPeer → Rejected (offer rejected)
5. WaitingForPeer → Expired (offer timeout)
6. Negotiating → Transferring → Cancelled (sender cancel)
7. Transferring → Cancelled (receiver cancel)
8. Transferring → Verifying → Failed (corruption)
9. Verifying → Completed → No failure
10. Completed → No failure (terminal)
11. Failed → No failure (terminal)
12. Invalid transition detection

## Summary

**V1 Failure Model:**
- Full restart on disconnect
- No byte-range resume
- Idempotent delivery via TransferId
- Corrupted files: no retry (preserve existing)
- Cancel before commit: no entry
- Cancel after commit: entry exists

**Limitations:**
- Large files may take long to restart
- No partial resume
- Network errors: full restart

---

**Next Action:** Implement common DTOs.
