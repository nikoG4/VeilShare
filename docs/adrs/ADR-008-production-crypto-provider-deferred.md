# ADR-008 — Production crypto provider remains deferred

Status: Accepted

## Context

V1 requires Argon2id, CSPRNG, AEAD and a single interoperable format on Android, iOS and Desktop JVM. `cryptography-kotlin` is a credible common API for AEAD/HKDF but does not supply Argon2id. The examined `kotlin-multiplatform-libsodium` 0.9.5 binding exposes Libsodium functionality across the target set, but its own upstream README marks the wrapper experimental and explicitly advises against production use without community review.

## Decision

Do not select a production provider in this repository yet. `VEIL_CRYPTO_V1` freezes the intended protocol vocabulary: Argon2id; 32-byte keys; 16-byte salt; XChaCha20-Poly1305 with 24-byte nonce; 1 MiB chunks. It is a specification identifier, not evidence that the implementation exists.

## Release gate

No app host may create persistent user vaults or import user files until one provider passes Android, iOS and Desktop contract/vector tests and receives a reviewed ADR replacing this one.

## Fallback policy

There is no downgrade to PBKDF2, a hash, a fake cipher, or target-specific file format. Keep contracts and test fakes only.
