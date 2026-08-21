# Sharing / Signaling Architecture

Date: 2026-08-15
Author: VeilShare Agent
Version: V1 CORRECTED

## Repository State

- Branch: master
- Commit: 02e1d70 "feat: polish local vault release readiness"
- Working tree: untracked docs/agent/, docs/sharing/

## Design Corrections (V1 Foundation)

### Correction 1: Independent Sharing Identities

**Previous Design Flaw:**
Same public identity for both REAL and DECOY vaults on same device.

**Problem:**
Allows correlation of activity between contexts, leaking vault relationships.

**Corrected Design:**
- Each sharing context has independent `SharingIdentityId`
- No common network-visible installation ID
- No hardware fingerprinting for sharing identity
- Server cannot correlate sessions across contexts

```
Context A (vault A):
  SharingIdentityId → ephemeral identity
  ReferenceCode → random token

Context B (vault B):
  SharingIdentityId → ephemeral identity
  ReferenceCode → random token
```

### Correction 2: Random Reference Codes

**Previous Design Flaw:**
ReferenceCode derived from public identity via hash.

**Problem:**
Code must be static, cannot be rotated/revoked without identity change.

**Corrected Design:**
- ReferenceCode = random high-entropy routing token
- >= 80 bits real entropy
- Human-readable Base32 with checksum
- Rotatable and revocable
- Not derived from identity key

### Correction 3: WebSocket Relay V1

**Previous Design Flaw:**
V1 proposed Android=WebRTC, Desktop=local socket.

**Corrected Design:**
- V1 uses Ktor WebSocket relay for both platforms
- Server receives opaque frames, routes, forwards
- No platform-specific transport in V1
- V2 may evaluate WebRTC, P2P, STUN, TURN

### Correction 4: Encrypted Metadata

**Previous Design Flaw:**
Server needed to see filename, MIME, digest.

**Corrected Design:**
- All metadata inside E2E channel
- Server sees only: timing, lookup IDs, routing IDs
- Server never parses payload

### Correction 5: No Chunk ACK in V1

**Previous Design Flaw:**
V1 proposed per-chunk ACKs.

**Corrected Design:**
- WebSocket/TCP guarantees order (no ACKs)
- Full restart on disconnect
- Idempotent commit via TransferId
- Resume from byte offset deferred to V2

### Correction 6: Handshake Contract

**Previous Design Flaw:**
Vague "signature verification" for MITM.

**Corrected Design:**
- Separate long-term identity (Ed25519)
- Fresh ephemeral keys per session (X25519)
- Transcript binding with HKDF-SHA-256
- AEAD with distinct keys
- No reuse of vault keys (VMK, KEK, FileKey)

## Existing Signaling Server Audit

### Current Implementation

The `server/signaling` module is a placeholder reserved for Phase 5.

File: `server/signaling/src/main/kotlin/dev/veilshare/signaling/Main.kt`

```kotlin
package dev.veilshare.signaling

/** Phase 0 deliberately has no network endpoint: the server module is reserved for Phase 5. */
fun main() = println("VeilShare signaling server is not implemented in Foundation.")
```

### Build Configuration

File: `server/signaling/build.gradle.kts`

```kotlin
plugins { alias(libs.plugins.kotlin.jvm); application }
kotlin { jvmToolchain(17) }
application { mainClass.set("dev.veilshare.signaling.MainKt") }
```

### Component Inventory

| Component | Status V1 | Notes |
|-----------|--------|-------|
| Main entry point | Placeholder | No network in V1 |
| Ktor application | Reserved | Created in Phase 2 |
| WebSocket endpoint | Reserved | Created in Phase 2 |
| Presence registry | In-memory (Phase 1) | Ephemeral, TTL-based |
| Session registry | In-memory (Phase 1) | Stateless routing |
| TURN credentials | Reserved | Not in V1 |
| Rate limiting | Basic (Phase 1) | Lookup/session limits |
| HTTP health endpoint | Reserved | For monitoring |

### Existing Design Documentation (Not Implemented)

- `docs/network/03-signaling-server.md` - Server design
- `docs/network/05-transfer-protocol.md` - Transfer protocol (conceptual)
- `docs/network/02-reference-code-qr.md` - Identity/coding scheme (updated)
- `docs/network/09-metadata-privacy.md` - Privacy boundaries (updated)
- `docs/network/07-nat-stun-turn.md` - NAT traversal (V2)
- `docs/server/protocol-websocket.md` - WebSocket message format (updated)
- `docs/server/ktor-api.md` - API routes and auth (reserved)
- `docs/server/abuse-rate-limits.md` - Abuse prevention (basic in V1)

### Missing Infrastructure V1

- No Gradle dependencies for Ktor declared
- No Kotlin P2P library bindings
- No platform-specific transport adapters
- No identity/signing key infrastructure (uses separate keys)

## Sharing V1 Scope

### In Scope for V1

1. **WebSocket relay only**
   - Single sender → single receiver
   - Server relays opaque frames
   - In-memory state only

2. **Signaling server**
   - Challenge-response authentication
   - Reference code lookup (random token)
   - Forward messages only

3. **Transfer**
   - Encrypted stream chunks
   - Full restart on disconnect (no byte-range resume)
   - ACK not required per chunk

4. **Identity**
   - Independent sharing identity per context
   - Random reference code
   - Basic fingerprint verification

### Out of Scope for V1

1. Multi-device sync
2. Group transfers
3. Cloud library
4. Social graph
5. Remote thumbnails/previews
6. Infinite history
7. Complex contact management
8. WebRTC
9. STUN/TURN
10. Byte-range resume
11. Production handshake crypto

### Module Proposal (Phased)

| Phase | Module | Justification |
|-------|--------|-------------|
| V1 | `:shared:core-model` | DTOs, message types |
| V1 | `:shared:core-identity` | SharingIdentity, ReferenceCode |
| V1 | `:shared:core-transfer` | State machine |
| V1 | `:shared:core-platform` | Transport contract (reserved) |
| V1 | `:server:signaling` | In-memory presence server |
| V2 | `:shared:core-crypto` | Session key derivation |
| V2 | P2P library integration | WebRTC evaluation |

### Dependency Rules (Existing)

From `docs/architecture/05-dependency-rules.md`:

1. No new cryptographic dependencies without ADR
2. `core-model` → no UI, filesystem, network
3. `core-crypto` → no UI dependencies
4. `core-transfer` → depends on `core-crypto`
5. `core-contacts` → depends on `core-identity`
6. `shared:app` → depends on all contracts
7. Ktor client only for signaling
8. No "encrypted shared preferences" for files

## Security Invariants (Preserved)

- **Authenticated catalog = logical source of truth**
- **Corruption ≠ cleanup authorization**
- **REAL/DECOY isolation maintained**
- **No plaintext keys in logs**
- **No filenames in signaling**
- **FileKey/VaultKey never shared**
- **Independent sharing identities**
- **Random rotatable reference codes**

## Signaling Server Responsibilities

1. Challenge-response authentication
2. Presence registration/unregistration
3. Lookup by reference code
4. Forward signals between authenticated peers
5. Rate limiting
6. TURN credential issuance (reserved)

**Server never learns:**
- Plaintext file contents
- File names
- FileKeys/VaultKeys
- Vault type
- REAL/DECOY labels
- Contact aliases
- File keys or digest

## Client Responsibilities

1. `PeerTransport` - Platform-specific (reserved)
2. `SignalingClient` - Ktor HTTP client for signaling (reserved)
3. `SecurePeerSession` - E2E session management (reserved)
4. `ReferenceCodeResolver` - Lookup and verification (Phase 1)

## Transfer Import Integration

The receiver must use existing import pipeline:

```
Decrypted network stream
  ↓
ImportSource adapter
  ↓
ImportCoordinator (shared:core-vault)
  ↓
Blob store write
  ↓
Catalog commit
  ↓
Durable (FileChannel.force + AtomicMove)
```

No bypass allowed. All transferred files must go through existing `ImportSource` interface.

## Server State

**V1: In-memory only**

- `Map<ConnectionId, PresenceInfo>` for connection tracking
- `Map<ReferenceCode, ConnectionId>` for lookup
- `Map<SessionId, TransferSession>` for active transfers
- TTL-based cleanup (30s-5m configurable)
- No database required

**Future (Phase V2):**
- Redis pub/sub for multi-instance presence
- No file storage in server

## Client Responsibilities Summary

Android/Desktop clients implement (V2):

1. `PeerTransport` - Platform-specific (WebRTC, local socket, etc.)
2. `SignalingClient` - Ktor HTTP client for signaling server
3. `SecurePeerSession` - E2E session management
4. `ReferenceCodeResolver` - Lookup and verification

## Signaling Server Responsibilities Summary

1. Challenge-response authentication
2. Presence registration/unregistration
3. Lookup by reference code
4. Forward signals between authenticated peers
5. Rate limiting
6. TURN credential issuance (if relay needed, reserved)

## Client Responsibilities (No Server Knowledge)

Server **never** learns:

- Plaintext file contents
- File names
- FileKeys/VaultKeys
- Vault type
- REAL/DECOY labels
- Contact aliases (only full identity/code)

---

**Next Action:** Create HANDSHAKE_CONTRACT.md, then implement Phase 1 DTOs.
