# VeilShare Sharing Protocol V1

Status: active implementation overview.

This file is the top-level index for Sharing V1. Detailed normative behavior is split into:

- `HANDSHAKE_CONTRACT.md` — authenticated Ed25519/X25519 handshake, canonical transcript and transcript-bound HKDF.
- `TRANSFER_PROTOCOL_V1.md` — OFFER/ACCEPT/REJECT/DATA/COMPLETE/CANCEL/FAILURE, fragmentation, AAD, signaling limits, receiver lifecycle and vault import.
- `THREAT_MODEL.md` — security assumptions and attacker model.
- `METADATA_PRIVACY_TABLE.md` — server-visible vs E2E-only metadata.
- `FAILURE_MODEL.md` — failure/recovery expectations.
- `REAL_DECOY_PRIVACY.md` — REAL/DECOY privacy boundary.

The detailed contract files win if an older design note conflicts with this overview.

## Goals

Sharing V1 transfers one file at a time between VeilShare peers while keeping content and sensitive file metadata end-to-end protected from the signaling server.

The server provides ephemeral presence/lookup and blind WebSocket relay. It is not trusted with:

- plaintext files;
- filenames or MIME metadata;
- vault keys;
- transfer session keys;
- REAL/DECOY vault labels;
- decrypted vault state.

## Identity and routing

### SharingIdentityId

A sharing identity is separate from vault identity/key material.

It has an Ed25519 key pair used to authenticate handshake messages.

### ReferenceCode

A `ReferenceCode` is a random human-readable routing token. It is not derived from a vault key, device ID, hardware fingerprint, or public identity.

Lookup returns the peer sharing identity/public key required by the higher-level trust/contact workflow.

**Security rule:** a lookup result alone must not silently become a trusted/pinned contact key. The handshake verifies against the expected key supplied by the contact/trust layer.

## Wire layers

```
WebSocket text frame
  SignalingEnvelope
    payload (Base64 ByteArray)
      RelayRequest
        opaquePayload (Base64 ByteArray)
          PeerEnvelope
            payload (Base64 ByteArray)
              typed peer message
```

Binary fields use the common `Base64ByteArraySerializer` to avoid platform-dependent JSON arrays and uncontrolled nested JSON expansion.

## Signaling messages

Server-visible message types:

- `REGISTER`
- `UNREGISTER`
- `LOOKUP`
- `RELAY`
- `PING`
- `ERROR`

The signaling envelope exposes routing/timing/size information. It must not expose E2E file metadata.

Current signaling envelope raw payload limit is 64 KiB.

RELAY has its own per-connection fixed-window message+byte budget; it does not consume the session-creation limiter.

## Peer messages

Peer envelope message types:

1. `SESSION_HELLO`
2. `SESSION_CONFIRM`
3. `SESSION_CONFIRM_ACK`
4. `OFFER`
5. `ACCEPT`
6. `REJECT`
7. `DATA`
8. `COMPLETE`
9. `CANCEL`
10. `FAILURE`

`PeerMessageCodec` is the canonical typed JSON codec for these payloads.

`PeerSessionGate` verifies envelope session/transfer identity and inner transfer/file hashes before DATA/control reaches the receiver.

## Handshake summary

The long-term sharing identity uses Ed25519. Each session uses fresh X25519 ephemeral keys.

All three handshake stages are authenticated:

- HELLO signed by sender identity;
- CONFIRM signed by receiver identity and binds receiver ephemeral;
- CONFIRM_ACK signed by sender identity and binds sender ephemeral + complete transcript hash.

The canonical transcript contains both sharing identity hashes, session hash and both ephemeral public keys.

Directional traffic keys are derived with HKDF-SHA-256 from the X25519 shared secret and canonical transcript hash.

See `HANDSHAKE_CONTRACT.md` for exact bytes/domain separation.

## Offer/accept summary

`TransferOffer` carries E2E-only validated metadata:

- FileId;
- display name;
- optional MIME hint;
- byte size;
- total crypto chunks.

V1 rejects empty files.

The receiver can accept or reject the offered file. File metadata is bounded/canonical before it can reach the vault/UI boundary.

## DATA summary

Default plaintext crypto chunk size is 1 MiB.

Each logical chunk is encrypted with ChaCha20-Poly1305 under a transcript-bound per-session directional key.

Canonical AAD binds:

- protocol version;
- direction;
- transfer-id hash;
- file-id hash;
- chunk index;
- total chunks.

Transport fragmentation occurs after encryption. A crypto chunk may be split into multiple smaller `TransferData` frames and is reassembled before AEAD verification.

Current target serialized TransferData frame size is 16 KiB; maximum fragments per crypto chunk is 128.

## Retry model

No per-chunk ACK protocol is required in V1.

Transient send failure retries the same already-encrypted logical data/frame. Cancellation and security/protocol failures are not retried as transport faults.

On process death or lost session, V1 may restart the transfer rather than resume partially persisted network state.

## Receiver and vault

Receiver state is deliberately bounded while whole-transfer encrypted buffering remains in use:

- at most 2 active transfers;
- at most 64 MiB buffered ciphertext per transfer;
- idle and absolute lifetime cleanup;
- explicit abort;
- single-consumer `COMPLETE -> IMPORTING` transition.

The receiver does not retain an entire plaintext file.

`ReceivedTransferVaultImporter` bridges a completed transfer into the existing crash-safe vault import path. The authenticated received size must match `TransferOffer.sizeBytes` before consumption.

Vault import then owns VBL1 encryption, journal durability, encrypted catalog commit and recovery semantics.

## REAL / DECOY boundary

Sharing must never reveal which local vault is REAL versus DECOY to the signaling server or peer protocol.

The application chooses the currently unlocked vault locally and passes only its `VaultHandle` to the import boundary.

No vault slot descriptor or vault type travels through sharing messages.

## Versioning

`SharingProtocol.VERSION = 1`.

Because V1 has not yet been frozen/released, incompatible wire corrections may still be made on the hardening branch. Once `SHARING_V1_BASELINE_FROZEN` is declared, any incompatible message/crypto encoding change requires a protocol-version strategy rather than silent mutation.

## Current freeze blockers

Before declaring the V1 baseline frozen:

- latest branch must compile/test on Desktop and Android;
- handshake negative tests must pass;
- real handshake-derived-key -> encrypted transfer -> durable vault E2E must pass;
- signaling relay size/rate-limit tests must pass;
- receiver lifecycle/abort/single-import tests must pass;
- full frozen vault regression must remain green;
- iOS native compilation remains a separate macOS/Xcode verification item.

Do not claim `SHARING_V1_BASELINE_FROZEN` from static review alone.
