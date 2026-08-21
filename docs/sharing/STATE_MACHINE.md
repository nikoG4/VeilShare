# Transfer State Machine V1 (Corrected)

Date: 2026-08-15
Author: VeilShare Agent
Version: V1 CORRECTED

## Overview

State machine for a single transfer session. Each side (sender/receiver) maintains its own state.

**V1 Corrections:**
- Simplified states (no byte-range resume)
- Full restart on disconnect
- No per-chunk ACKs
- Idempotent completion only

## State Diagram

```
              ┌───────────────────┐
              │     Idle          │
              └────────┬──────────┘
                       │
           ┌───────────┼───────────┐
           │           │           │
           ▼           ▼           ▼
    ┌──────┐    ┌───────────┐  ┌────┐
    │      │    │ Resolving │  │     │
    │      │    │ Peer      │  │     │
    │      │    └───────────┘  │     │
    │      │                   └────┘
    │      │
    ▼      ▼
┌─────────┐    ┌───────────┐
│ Ready   │    │Creating    │
│         │    │Session    │
│         │    └───────────┘
└───┬─────┘        │
    │              │
    │     ┌────────┴───────────┐
    │     │ WaitingForPeer     │
    │     └────────┬───────────┘
    │              │
    ▼              ▼
┌──────────────────┐  ┌─────────────────┐
│  AwaitingDecision│  │Negotiating      │
└──────────┬────────┘  └───────┬────────┘
           │                  │
           │                  │
    ┌───────┴───────┐    ┌────┴──────┐
    │     Transferring    │    │      │
    └───────┬────────────┘    │      │
            │                 │      │
    ┌───────┴───────┐    ┌────┴──────┴────┐
    │  Verifying    │    │  Failed         │
    └───────┬───────┘    └──────────────────┘
            │
    ┌────────┴────────┐
    │ Completed       │
    └──────────────────┘

    Failure states (terminal):
    - Failed
    - Cancelled
    - Rejected
    - Expired
```

## States

### Idle

**Description:** No active transfer.

**Invariants:**
- No pending chunks
- No in-flight operations

**Transitions:**
- `createSession()` → `ResolvingPeer`
- `cancel()` → `Cancelled`
- Error during creation → `Failed`

### Ready

**Description:** Identity verified, ready for transfer.

**Invariants:**
- `SharingIdentityId` validated
- `ReferenceCode` verified (if lookup)

**Transitions:**
- `startTransfer()` → `ResolvingPeer`
- `error()` → `Failed`

### ResolvingPeer

**Description:** Looking up peer reference code.

**Invariants:**
- Lookup request sent
- Timer running (optional)

**Transitions:**
- `lookupSuccess()` → `Ready`
- `lookupNotFound()` → `Failed`
- `timeout()` → `Expired`

### CreatingSession

**Description:** Session creation in progress.

**Invariants:**
- Generating `sessionId`
- Session key agreement (conceptual)

**Transitions:**
- `sessionEstablished()` → `Ready`
- `error()` → `Failed`

### WaitingForPeer

**Description:** Sender waiting for peer acceptance.

**Invariants:**
- Offer sent
- Timer running (configurable, default: 30s)
- Peer not yet accepting

**Transitions:**
- `receiveAccept()` → `Transferring`
- `timeout()` → `Expired`
- `cancel()` → `Cancelled`
- `receiveReject()` → `Rejected`

### AwaitingDecision

**Description:** Receiver awaiting sender's decision.

**Invariants:**
- Session established
- Waiting for offer/decision
- Timer running

**Transitions:**
- `acceptOffer()` → `Transferring`
- `rejectOffer()` → `Rejected`
- `timeout()` → `Expired`

### Negotiating

**Description:** Both sides negotiating file metadata.

**Invariants:**
- Session keys exchanged
- File metadata exchanged
- Ready to transfer

**Transitions:**
- `startTransfer()` → `Transferring`
- `cancel()` → `Cancelled`
- `error()` → `Failed`

### Transferring

**Description:** Active data transfer.

**Invariants:**
- Chunks being sent/received
- Progress tracked
- No per-chunk ACKs

**Sub-states:**
- `SendingChunks`
- `ReceivingChunks`
- `BothSides`

**Transitions:**
- `sendChunk()` → `Transferring` (sub-state change)
- `receiveChunk()` → `Transferring` (sub-state change)
- `sendComplete()` → `Verifying`
- `receiveComplete()` → `Verifying`
- `cancel()` → `Cancelled`
- `error()` → `Failed`

### Verifying

**Description:** Transfer complete, verifying integrity.

**Invariants:**
- All chunks received
- Integrity checks pending
- Not yet catalog committed

**Transitions:**
- `verifyComplete()` → `Completed`
- `verifyFailed()` → `Failed`
- `cancel()` → `Cancelled`
- `error()` → `Failed`

### Completed

**Description:** Transfer successfully completed.

**Invariants:**
- All chunks durable
- Catalog entries exist
- Session keys can be discarded

**Transitions:**
- None (terminal state)

### Failed

**Description:** Transfer failed.

**Invariants:**
- Error logged
- Session cleanup initiated
- Partial chunks removed (if any)

**Transitions:**
- None (terminal state)

### Cancelled

**Description:** Transfer cancelled by sender or receiver.

**Invariants:**
- Cancel signal received
- Cleanup in progress
- No new chunks accepted

**Transitions:**
- None (terminal state)

### Rejected

**Description:** Offer rejected by receiver.

**Caused by:**
- User rejected
- Unsupported protocol version
- Invalid fingerprint

**Cleanup:**
- Discard session
- Session ends

### Expired

**Description:** Session or offer expired.

**Caused by:**
- Session TTL exceeded
- Offer timeout
- Inactivity timeout

**Cleanup:**
- Remove session
- Remove pending chunks

## Timeout Configuration

| Timeout | Default | Configurable | Notes |
|---------|---------|--------------|-------|
| Offer TTL | 30s | Yes | Max time offer valid |
| Session TTL | 5m | Yes | Max session lifetime |
| Lookup Timeout | 10s | Yes | LOOKUP response |
| Inactivity Timeout | 300s | Yes | For transfer phase |

## Failure Matrix

| Event | Sender State | Receiver State | Cleanup | Retry |
|-------|--------------|----------------|---------|-------|
| sender disconnect pre-accept | WaitingForPeer | AwaitingDecision | Discard offer | Auto on reconnect |
| receiver disconnect pre-accept | WaitingForPeer | - | Discard chunks | Auto on reconnect |
| disconnect during transfer | Transferring | Transferring | Remove partial | **No** (restart full) |
| signaling restart | Any | Any | Session invalid | Re-initiate |
| cancel sender | Any | Any | Remove chunks | No |
| cancel receiver | Any | Any | Keep catalog if committed | No |
| duplicate offer | Any | AwaitingDecision | Ignore, forward | Yes, idempotent |
| duplicate complete | Transferring | Transferring | Ignore | Yes, idempotent |
| timeout (offer) | WaitingForPeer | AwaitingDecision | Session ends | Re-initiate |
| timeout (transfer) | Transferring | Transferring | Session ends | Re-initiate |
| corrupted payload | Transferring | Transferring | Discard chunk | Yes, resend |
| verification fail | Verifying | Verifying | Discard session | No |
| protocol error | Any | Any | Session ends | Re-initiate |
| invalid transition | Any | Any | Fail state | No |

## Implementation Notes

### Sender Responsibilities

1. Generate `transferId` (UUID v4)
2. Generate `sessionId` (UUID v4)
3. Create session keys (ephemeral)
4. Send `SESSION_HELLO`
5. Track sent chunks
6. Send `COMPLETE` when finished
7. Cancel on error

### Receiver Responsibilities

1. Receive offer, verify signature
2. Check if accepting
3. Send `ACCEPT` or `REJECT`
4. Receive chunks, verify integrity
5. Write to vault (durable)
6. Send `COMPLETE` when done
7. Cancel if user rejects

### Idempotency

- **Chunks:** `(transferId, fileId, chunkIndex)` unique
- Duplicate chunk: validate → discard if durable
- Sender tracks sent chunks, can resend unacked
- Receiver idempotent on chunk receipt
- **Completion:** Idempotent via `transferId`

### Catalog Commit

- Commit happens after durable write
- ACK sent after durable write
- Crash during write → recovery on restart
- `exception ≠ rollback` per existing semantics

### Restart Behavior

- **Sender:** discard session, generate new `transferId` and `sessionId`
- **Receiver:** scan catalog for existing entries
- If file exists, send `COMPLETE` immediately
- Don't duplicate files

## V1 Constraints

1. **No byte-range resume:** Full restart on disconnect
2. **No per-chunk ACKs:** WebSocket/TCP guarantees order
3. **Full restart:** On any disconnect during transfer
4. **Idempotent completion:** Via `transferId`

## State Machine Tests

Test cases to verify:

1. Idle → Ready → ResolvingPeer → CreatingSession → WaitingForPeer → Negotiating → Transferring → Verifying → Completed
2. Idle → Ready → Failed (error during creation)
3. Ready → ResolvingPeer → Failed (lookup failure)
4. Ready → ResolvingPeer → Expired (timeout)
5. WaitingForPeer → Failed (sender error)
6. WaitingForPeer → Cancelled (sender cancels)
7. WaitingForPeer → Rejected (receiver rejects)
8. WaitingForPeer → Expired (timeout)
9. WaitingForPeer → Negotiating (peer accepts)
10. Transferring → Cancelled (either side cancels)
11. Transferring → Failed (corrupted payload)
12. Transferring → Completed (normal completion)
13. Duplicate offer handling
14. Duplicate complete handling
15. Invalid transition detection

---

**Next Action:** Create HANDSHAKE_CONTRACT.md for precise security specifications.
