# Sharing / Signaling Threat Model

Date: 2026-08-15
Author: VeilShare Agent

## Assets

1. **User files** - Protected by E2E encryption
2. **FileKeys** - Per-file encryption keys (never leave device)
3. **VaultKeys** - Vault-level wrapping keys
4. **User identity** - Public identity, fingerprint, reference code
5. **Session keys** - Ephemeral per-transfer
6. **Transfer in-flight data** - In progress
7. **Signaling server** - Trust boundary

## Threats and Mitigations

### T1: Signaling Server Curiousity

**Risk:** Server logs/memorize sensitive data
**Mitigation V1:**
- Logs only: aggregate counts, error codes, coarse latency
- Never log: full reference codes, complete public keys, ICE payloads
- Server state in memory only, cleared on restart
- No plaintext storage of file contents or metadata

**Out of scope for server to know:**
- File names (except in E2E channel)
- FileKeys/VaultKeys
- Vault type
- REAL/DECOY labels

### T2: Signaling Server Compromised

**Risk:** Attacker reads server memory, dumps files

**Mitigation V1:**
- No plaintext in server memory
- Server doesn't parse transferred chunks
- Server only forwards encrypted frames
- Reference codes derive from public identity (not secret)
- Public identity can be rotated (future)

**Out of scope:**
- Server cannot decrypt E2E transfers
- Server cannot derive keys without private key
- Server cannot enumerate active users beyond presence map

### T3: Attacker Guesses Reference Codes

**Risk:** Brute-force or dictionary attack on reference code

**Mitigation V1:**
- Code length: ~80 bits before checksum (Phase 5 design)
- Base32 encoding with visual separation
- Checksum detects typo
- Rate limiting on lookup requests
- Invalid code attempts rate-limited per IP

**Out of scope for V1:**
- QR code scanning
- Fingerprint pinning verification

**Calculated risk:** 2^80 operations before collision

### T4: Replay Attack

**Risk:** Attacker captures and replays handshake message

**Mitigation V1:**
- Ephemeral transcript hash per session
- Nonce in challenge response
- Monotonic sequence numbers per direction
- Session key tied to transcript hash
- Signature over transcript

**Out of scope:**
- Perfect forward secrecy for old sessions (by design)

### T5: MITM on Signaling

**Risk:** Attacker modifies signaling messages

**Mitigation V1:**
- All messages signed with identity key
- Signature verification on both ends
- Protocol version in all frames
- Invalid signature rejects entire transfer

**Out of scope:**
- MITM can't read encrypted chunks without E2E keys
- MITM can't derive keys without transcript hash

### T6: Malicious Peer

**Risk:** Peer A sends to Peer B, Peer B forwards to Peer C

**Mitigation V1:**
- Transfer authenticated by both peers
- Peer C must accept/reject explicitly
- Transfer session per peer pair
- No broadcast

**Out of scope:**
- Peer C still responsible for accepting
- Peer C can cancel before commit

### T7: Peer Disconnected During Transfer

**Risk:** Transfer incomplete, sender thinks succeeded

**Mitigation V1:**
- Sender tracks ACKs
- Retry: resend unacked chunks
- Idempotent commit on receiver side
- Cancel on sender side stops transfer
- Receiver cancel before commit: no entry

**Out of scope for V1:**
- Byte-range resume (restart full transfer)

### T8: Partial Transfer (Disk Space Insufficient)

**Risk:** Transfer fails mid-way

**Mitigation V1:**
- Import pipeline: durable write per chunk
- ACK before durable write
- Cancel on insufficient space
- Receiver catalog reflects durable state

**Out of scope:**
- Partial file on disk (by design)
- Rollback not possible after commit

### T9: Very Large Files

**Risk:** Long transfers, memory pressure

**Mitigation V1:**
- Chunked transfer with configurable chunk size
- Inactivity timeout (configurable)
- Sender cancels after timeout

**Out of scope for V1:**
- Adaptive chunk sizing
- Compression

### T10: Spam / Flood

**Risk:** Attacker sends many offers

**Mitigation V1:**
- Rate limit lookup requests
- Rate limit offers per IP/identity
- Connection limits per IP
- Session TTL cleanup
- Max frame size validation
- Invalid frame rejection

**Out of scope:**
- Advanced abuse detection
- CAPTCHA integration

### T11: Metadata Leakage

**Risk:** Server/relay observes transfer timing, sizes

**Mitigation V1:**
- Timing: observable (documented)
- Size: visible in logs (coarse, not per-file)
- No padding (yet)
- Batch framing not implemented in V1

**Out of scope for V1:**
- Always-TURN for privacy
- Frame padding
- Relay privacy mode

### T12: Duplicate Delivery

**Risk:** Same file delivered twice

**Mitigation V1:**
- TransferId unique per transfer
- Idempotent commit: `(transferId, fileId, chunkIndex)` unique
- Duplicate chunks discarded if already durable

**Out of scope:**
- Resume from byte offset in V1
- Parallel chunk download

### T13: REAL/DECOY Leakage

**Risk:** Transfer reveals whether peer has real/fake vault

**Mitigation V1:**
- Sharing operates on currently unlocked session
- No reveal: whether sender has primary/alternate vault
- No reveal: whether receiver has multiple vaults
- Contact/favorite belongs to context or common settings
- No REAL/DECOY labels in protocol

**Out of scope:**
- REAL/DECOY indicators in UI
- Separate contact stores

---

## Assumptions

1. **Identity key pair** exists per device (Phase 5)
2. **Public identity** is immutable (or rotation protocol exists)
3. **Reference code** derives deterministically from public identity
4. **Signaling server** operates over TLS (WSS)
5. **Peer clients** run signed/app store releases

## What's Not Protected

1. **Network traffic** - Protected by TLS (signaling), E2E (transfer)
2. **Malware on device** - Can exfiltrate any data
3. **Physical access** - Can bypass all protections
4. **Phishing** - User can reveal keys to attacker
5. **Supply chain** - Compromised libraries

---

## Security Boundaries

```
┌─────────────────────────────────────────────────┐
│              Signaling Server (Trust Boundary)    │
│  ┌────────────────────────────────────────────┐  │
│  │  Presence Map (ephemeral)                   │  │
│  │  └── referenceCode → Session                │  │
│  └────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────┐  │
│  │  Signal Forwarder (blind relay)             │  │
│  └────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘

┌─────────────────┐         ┌─────────────────┐
│   Peer A        │         │   Peer B        │
│ ┌─────────────┐ │         │ ┌─────────────┐ │
│ │ Signaling   │ │ ◄───►  │ │ Signaling   │ │
│ │ Client      │ │         │ │ Client      │ │
│ └──────┬──────┘ │         │ └──────┬──────┘ │
│        │        │         │            │
│ ┌──────▼──────┐ │         │ ┌──────▼──────┐ │
│ │ Secure Channel │ ◄───► │ │ Secure Channel │
│ │ (encrypted)  │ │         │ │ (encrypted)  │
│ └──────┬──────┘ │         │ └──────┬──────┘ │
│        │        │         │            │
│ ┌──────▼──────┐ │         │ ┌──────▼──────┐ │
│ │ Import       │ │         │ │ Import      │ │
│ │ Coordinator  │ │         │ │ Coordinator │ │
│ └─────────────┘ │         │ └─────────────┘ │
│                 │         │                 │
│  [Local Vault A]         │   [Local Vault B]
└─────────────────┘         └─────────────────┘
```

**Signaling server:**
- Sees: encrypted frames, timing, sizes
- Does NOT see: plaintext, keys, vault structure

**Peer clients:**
- E2E session: A → B
- Each has own vault, own keys

---

## Final Assessment

**Trust model:** Signaling server is a blind relay for encrypted frames. It cannot decrypt or inspect transfer content.

**Attack surface:**
- Signaling: MITM, timing, rate limits
- Transfer: E2E encrypted, key derivation
- Import: Existing vault security

**V1 readiness:** Threat model complete. Ready for implementation with documented boundaries.
