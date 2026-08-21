# REAL/DECOY Privacy in Sharing V1 (Corrected)

Date: 2026-08-15
Author: VeilShare Agent
Version: V1 CORRECTED

## Principle

Sharing V1 must **never reveal** whether a peer is using a REAL or DECOY vault, nor should it reveal how many vaults a peer has.

**Critical Correction:** Each sharing context uses completely independent identities. No correlation possible.

## Threat Model

### T1: Attacker Correlates Contexts via Common Identity

**Attack:**
- Attacker observes sharing sessions from both contexts
- Correlates via common public identity/hardware ID
- Infers vault relationships

**Mitigation V1:**
- **Separate SharingIdentityId per context**
- No common network-visible installation ID
- No hardware fingerprinting
- Each context: independent identity generation

**V1 Implementation:**
```kotlin
// Context A (REAL vault)
SharingIdentityId(contextA) = randomUUID()
sharingIdentityKeyA = Ed25519.generate()

// Context B (DECOY vault)
SharingIdentityId(contextB) = randomUUID()
sharingIdentityKeyB = Ed25519.generate()
```

**Result:** Server sees two unrelated identities. No correlation.

### T2: Server Reveals Vault Type

**Attack:**
- Signaling server stores vault type metadata
- Reveals REAL/DECOY mapping

**Mitigation:**
- Server never stores vault type
- Server state in-memory only
- No DB persistence
- No vault context in protocol

**Out of scope:**
- Server restart loses all presence data

### T3: Attacker Infers from Transfer Volume

**Attack:**
- DECOY vault used for decoy files
- REAL vault used for important files
- Volume differences reveal usage

**Mitigation:**
- Volume visible to attacker (documented leakage)
- Can't prevent: attacker sees timing and sizes
- Accept limitation: document, don't over-engineer

**Out of scope:**
- Perfect secrecy impossible

### T4: Reference Code Derivation

**Previous Design Flaw (Fixed):**
ReferenceCode derived from public identity.

**Mitigation:**
- ReferenceCode = random token
- Not derived from identity
- Rotatable per context
- No identity leakage through code

### T5: Contact Store Reveals Vault Count

**Attack:**
- Contact list shows one vault per contact
- Reveals peer has multiple vaults

**Mitigation:**
- Single contact entry per reference code
- No vault count in contact store
- Alias is local only
- No correlation across contacts

### T6: Multi-Device Correlation

**Threat:**
- Same user on multiple devices
- Same identity on all devices
- Correlates user via identity

**Mitigation V1 (Out of scope):**
- V1: single context/device
- V2: may address multi-device users
- Separate identities per device/user context

## Protocol Design

### Identity Separation

**Critical Design Change:**

```
Context A:
  SharingIdentityId → random UUID
  sharingIdentityKey → Ed25519 private key
  referenceCode → random token (A)

Context B:
  SharingIdentityId → random UUID
  sharingIdentityKey → Ed25519 private key
  referenceCode → random token (B)
```

**No:**
- Common installation UUID
- Common hardware ID
- Common fingerprint

**Result:**
- Server cannot correlate contexts
- Same physical device = unrelated identities
- No hardware fingerprinting

### SharingIdentity

```kotlin
data class SharingIdentityId(
    val value: String,        // Random UUID v4
    val expiryAt: Long        // Optional, for rotation
)
```

**Generation:**
```kotlin
val sharingIdentityId = SharingIdentityId(
    value = SecureRandom().nextUUID().toString(),
    expiryAt = currentTimeMillis() + sessionTTL
)
```

### ReferenceCode

**Critical Correction:**
ReferenceCode is **random**, not derived from identity.

```kotlin
data class ReferenceCode(
    val code: String,         // Base32 80+ bits + checksum
    val checksum: String,     // Optional Luhn
    val issuer: String?       // Optional, not derived
)
```

**Generation:**
```kotlin
val referenceCode = ReferenceCode(
    code = generateRandomCode(), // 32 chars ~80 bits
    checksum = calculateChecksum(code), // Optional
    issuer = null // Not derived from identity
)
```

**Properties:**
- Human-readable
- Rotatable
- Revocable
- Not identity-derived

### Session State

**State per session:**
```kotlin
data class TransferSession(
    val transferId: TransferId,      // Unique per transfer
    val sessionId: SessionId,        // Unique per session
    val senderConnectionId: ConnectionId,
    val receiverConnectionId: ConnectionId,
    val createdAt: Long,
    val expiresAt: Long,              // TTL-based
    val state: TransferState
)
```

**No vault metadata:**
- No vault type field
- No REAL/DECOY field
- No vault count
- No sharing identity in session

### Import Behavior

**Receiver import:**
- Import into currently unlocked vault
- Same pipeline for any context
- No distinction in import flow
- Vault selection by user (not protocol)

**Catalog:**
- Entries go to catalog of unlocked vault
- Catalog doesn't know sharing context
- Same catalog operations

### Contact Store

**Entry format:**
```kotlin
data class Contact(
    val referenceCode: ReferenceCode,
    val sharingIdentityId: SharingIdentityId, // Separate per context
    val alias: String,           // Local plaintext only
    val note: String?            // Local plaintext only
)
```

**Storage:**
- Alias/note in local store only
- Not shared with server
- No vault count
- Pinning for verification only

**Location:**
- Encrypted: in vault settings (recommended)
- Unencrypted: app preferences (acceptable)

### UI Design

**Sharing Screen (Sender):**
- Select file
- Choose from contacts/favorites
- "Send" button
- Progress indicator

**No vault indicators:**
- No REAL/DECOY labels
- No vault count shown
- Neutral terminology

**Receiver UI:**
- "Incoming transfer" notification
- Show sender alias
- Accept/Reject buttons
- Progress indicator
- Destination folder (optional)

**No vault indicators:**
- No REAL/DECOY labels
- No vault count shown
- Same screen for any context

### Contact Management

**Add Contact:**
- Scan QR or enter reference code
- Compute alias locally
- Verify fingerprint
- Save encrypted

**Contact List:**
- Shows pinned contacts
- Shows alias locally
- No vault information

## Failure Handling

### Cancel by Sender

**Behavior:**
- Sender cancels, receiver discards
- No REAL/DECOY revealed
- Normal cancel flow

### Cancel by Receiver

**Before commit:**
- Cancel, no entry created
- No REAL/DECOY revealed

**After commit:**
- Entry exists, ignore cancel
- Normal cancel flow

### Verification Failure

**Mismatch:**
- fingerprint doesn't match code
- Hard block contact
- Log error (coarse only)

## Privacy Audit

### Server Sees

- Reference codes
- SharingIdentityIds (not vault type)
- Encrypted frames
- Timing and sizes
- Routing IDs

**Does NOT see:**
- Vault type
- REAL/DECOY labels
- File contents
- FileKeys
- File names
- FileKey
- SharingIdentity key material
- Connection correlation

### Attacker Sees (Documented)

- Network traffic (TLS protected)
- Timing
- Sizes (coarse)
- Reference codes
- SharingIdentityIds (from server)
- Public identity (Ed25519)

**Cannot get:**
- Plaintext without keys
- Keys without device
- Vault type from protocol
- Sharing context from identity
- Multi-device correlation

### Receiver Sees

- Incoming transfers
- Sender identity/code
- Local contact alias
- Other sessions (if in same context)

**Cannot infer:**
- REAL vs DECOY context
- Other contexts
- Vault relationships

## Design Decisions

| Decision | Rationale |
|------|---------|
| Separate identities per context | Prevents correlation |
| No common network-visible ID | Hides device relationships |
| No hardware fingerprinting | Independent per context |
| Random reference codes | Rotatable, revocable |
| No identity derivation | Independent per context |
| Encrypted contact store | Prevents alias leaks |
| Blind relay server | No vault metadata |
| No DB persistence | Stateless server |
| Alias local only | No external alias |
| Single contact entry per code | No vault count |

## Future Enhancements

**Phase V2 Considerations:**

1. **Multi-device users:**
   - Same user, multiple devices
   - Separate identities per device
   - Or shared identity with device fingerprint

2. **Device pairing:**
   - Trusted devices
   - Shared identity pool (optional)
   - Design first

3. **Advanced privacy:**
   - Always-TURN for privacy
   - Frame padding
   - Relay privacy mode

**Current Status:**
- V1 accepts documented leakage
- Separate identities enforced
- No correlation possible
- No over-engineering
- Simple protocol

---

## Checklist

- [ ] Separate identities per context
- [ ] No common installation ID
- [ ] No hardware fingerprinting
- [ ] Random reference codes
- [ ] No identity derivation
- [ ] Server doesn't store vault metadata
- [ ] No REAL/DECOY in protocol
- [ ] Cancel works for any context
- [ ] Contact alias local only
- [ ] No vault count in UI
- [ ] Timing/size leakage documented

---

**Status:** Privacy boundaries documented and corrected. V1 design enforces independent identities per sharing context.

**Next Action:** Update HANDSHAKE_CONTRACT.md with independent identity keys.
