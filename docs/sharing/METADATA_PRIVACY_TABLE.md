# Metadata Privacy Table V1

Date: 2026-08-15
Author: VeilShare Agent
Version: V1

## Overview

This table documents what metadata is visible to server vs peer for sharing protocol V1.

## Full Table

| Field | Server Sees? | Peer Sees? | Notes | Leakage Risk |
|-------|-----:|---:|-------|---|
| **Signaling Fields** |
| ReferenceCode | ✅ Yes (routing) | ✅ Yes (to lookup) | Medium - routing info |
| SharingIdentityId | ✅ Yes (routing) | ✅ Yes (public) | High - identity exposure |
| SharingIdentityPublicKey | ✅ Yes (routing) | ✅ Yes (verification) | Medium - public key |
| SessionId | ✅ Yes (routing) | ✅ Yes (to verify) | Low - session ID |
| MessageId | ✅ Yes (routing) | ✅ Yes (to verify) | Low - dedup |
| ProtocolVersion | ✅ Yes | ✅ Yes | None - version |
| TransferId | ❌ No (E2E) | ✅ Yes (to verify) | Low - dedup |
| Timestamp | ✅ Yes | ✅ Yes | Low - timing |
| **E2E Payload Fields** |
| Filename | ❌ No | ✅ Yes | Medium - filename |
| FileSize | ❌ No (traffic only) | ✅ Yes | Medium - approximate |
| MIME Type | ❌ No | ✅ Yes | Low - type info |
| FileDigest | ❌ No | ✅ Yes | Medium - integrity |
| FileContent | ❌ No | ✅ Yes (sender) | High - content |
| AEAD Tag | ❌ No | ✅ Yes | None - integrity |
| **Metadata Fields** |
| VaultType | ❌ No | N/A | **Forbidden** |
| REAL/DECOY Label | ❌ No | N/A | **Forbidden** |
| VaultId | ❌ No | ❌ No | **Forbidden** |
| FileKey | ❌ No | ❌ No | **Forbidden** |
| SharingIdentityPrivateKey | ❌ No | ❌ No | **Forbidden** |
| Alias | ❌ No | ✅ Yes (local only) | Low - local only |
| DestinationFolder | ❌ No | ✅ Yes | Low - user info |
| **Timing/Stats** |
| Frame Size | ✅ Yes | ❌ No | Medium - approximate |
| Timing | ✅ Yes | ✅ Yes | Medium - correlation |
| Connection Duration | ✅ Yes | ✅ Yes | Low - correlation |

## Leakage Analysis

### High Leakage (Acceptable)

| Field | Why Acceptable | Mitigation |
|-------|----------------|------------|
| FileContent | Expected - sender sends file | E2E encryption |
| Filename | Expected - sender names file | No server storage |
| FileSize | Expected - traffic analysis | Traffic analysis inherent |
| Timing | Expected - network reality | Documented limitation |
| Connection Duration | Expected - handshake | Cannot hide |

### Medium Leakage (Acceptable)

| Field | Why Acceptable | Mitigation |
|-------|----------------|------------|
| ReferenceCode | Required for lookup | Random, rotatable |
| SharingIdentityId | Required for routing | Separate per context |
| SharingIdentityPublicKey | Required for verification | Public key, no private |
| AEAD Tag | Required for integrity | AEAD standard |
| Alias (local) | Optional - UI display | Local only |
| DestinationFolder | Optional - user choice | Local only |

### Low Leakage (Acceptable)

| Field | Why Acceptable | Mitigation |
|-------|----------------|------------|
| SessionId | Required for dedup | Random UUID |
| MessageId | Required for dedup | Random UUID |
| MIME Type | Optional - user info | Optional field |
| FileDigest | Optional - integrity | Optional field |

### Forbidden Leakage (Never)

| Field | Why Forbidden | Detection |
|-------|---------------|-----------|
| VaultType | Reveals REAL/DECOY | Protocol design |
| REAL/DECOY Label | Reveals context | Protocol design |
| VaultId | Correlates vaults | Protocol design |
| FileKey | Exposes vault key | Never in protocol |
| SharingIdentityPrivateKey | Compromises identity | Never transmitted |

## Server Visibility

### Server Sees (Envelope Only)

```kotlin
data class EnvelopeFields(
    val protocolVersion: Int,
    val messageId: String,
    val sessionId: String?,
    val sharingIdentityId: SharingIdentityId,
    val sharingIdentityPublicKey: Ed25519PublicKey,
    val referenceCode: ReferenceCode,
    val timestamp: Long
) {
    // All fields are routing/verification only
    // No E2E payload inspection
}
```

### Server Does NOT See (E2E)

```kotlin
// E2E payload:
- Filename
- FileSize  
- MIME Type
- FileDigest
- FileContent
- AEAD Tag

// Vault metadata:
- VaultType
- REAL/DECOY Label
- VaultId
- FileKey
```

### Server Can Infer (Documented)

```kotlin
// From traffic analysis:
- Approximate file sizes (sum of chunks)
- Transfer duration
- Number of transfers (from log)
- Connection patterns (timing)
```

## Peer Visibility

### Receiver Sees (Complete E2E)

```kotlin
data class ReceiverVisible(
    val transferId: TransferId,
    val sessionId: SessionId,
    val senderSharingIdentityId: SharingIdentityId,
    val senderPublicKey: Ed25519PublicKey,
    val filename: String,
    val fileSize: Long,
    val mimeType: String?,
    val digest: ByteArray?,
    val ciphertext: ByteArray,
    val authTag: ByteArray
)
```

### Receiver Cannot Infer

```kotlin
// Not visible to receiver:
- Other sharing contexts (if any)
- Other devices of sender (if any)
- Sender's REAL/DECOY status
- Sender's vault structure
```

## Timing Analysis

### Observable Timing

| Event | Visibility | Can Correlate? |
|-------|------------|----------------|
| SESSION_HELLO sent | Server, Receiver, Sender | ✅ All parties |
| SESSION_ACCEPT sent | Server, Receiver, Sender | ✅ All parties |
| DATA chunks sent | Server, Receiver, Sender | ✅ All parties |
| COMPLETE sent | Server, Receiver, Sender | ✅ All parties |
| Connection closed | Server, Receiver, Sender | ✅ All parties |

### Timing Attack Mitigations

**Not implemented in V1 (by design):**
- Frame padding (adds complexity)
- Delayed responses (adds latency)
- Always-TURN (adds cost)

**Acceptable:**
- Documented timing leakage
- Timing attack requires active monitoring
- Standard traffic analysis

## Contact Alias Privacy

### Alias Storage

| Location | Storage Type | Lifetime | Visibility |
|----------|--------------|----------|------------|
| Local (app settings) | Plaintext or encrypted | Until delete | User only |
| Server | ❌ Not stored | N/A | N/A |
| E2E Channel | ❌ Not sent | Session only | Receiver only |

### Alias Forwarding

- Not sent in REGISTER message
- Not sent in LOOKUP message
- Only displayed to receiver
- No alias in protocol

### Alias Rotation

- Alias can change over time
- New alias: re-register with new code
- Old alias: no longer resolves
- No alias-to-identity binding

## Rate Limiting Impact

### Lookup Rate Limiting

```kotlin
maxLookupsPerMinute = 10
maxLookupsPerIdentityPerDay = 100
```

**Impact:**
- Legitimate lookups: allowed
- Spam: rejected with rate limit
- False positives: documented limitation

### Session Creation Rate Limiting

```kotlin
maxSessionsPerConnection = 10
maxSessionsPerMinute = 5
```

**Impact:**
- Normal usage: fine
- Rapid connections: rate limited
- Documented limitation

## Conclusion

**V1 Privacy Model:**
- Server blind to E2E payload
- Server blind to vault metadata
- Separate identities per context
- Random reference codes
- No correlation possible

**Limitations (Documented):**
- Timing observable
- Reference codes visible
- Approximate file sizes from traffic
- Connection patterns visible

**Mitigations (V2):**
- Frame padding
- Always-TURN for privacy
- Delayed responses
- Advanced rate limiting

---

**Next Action:** Create failure model document.
