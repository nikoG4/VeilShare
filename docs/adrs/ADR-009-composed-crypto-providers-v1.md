# ADR-009 — Composed crypto providers for V1

Status: Accepted; supersedes the global production gate in ADR-008.

## Context

The earlier decision rejected a single experimental Kotlin libsodium wrapper. It incorrectly treated a single all-target library as necessary. VeilShare already owns portable format, AAD, nonce, key hierarchy and contracts; providers can differ while producing the same bytes.

## Decision

`VEIL_CRYPTO_V1` uses Argon2id v1.3 raw output (32 bytes), 16-byte random salts, random 32-byte VMK/FileKey, and ChaCha20-Poly1305 IETF (32-byte key, 12-byte nonce, 16-byte tag). Android and Desktop use pinned Bouncy Castle 1.80 for Argon2id and AEAD. iOS will use a minimal, pinned official-libsodium cinterop adapter for raw Argon2id; its native verification is pending macOS. The file format, nonce construction and AAD are common and canonical.

## Rationale

Bouncy Castle provides maintained implementations and raw Argon2 parameter control for JVM/Android. ChaCha20-Poly1305 is a standard AEAD and is portable; a future cryptography-kotlin adapter may replace the AEAD implementation only after byte-vector equivalence. The implementation never makes provider defaults part of the format.

## Upgrade/fallback

Parameters live in each slot. Unknown suite/version fails closed. There is no fallback to PBKDF2, hashes or fake crypto. iOS may not ship until its cinterop provider passes the shared vectors.
