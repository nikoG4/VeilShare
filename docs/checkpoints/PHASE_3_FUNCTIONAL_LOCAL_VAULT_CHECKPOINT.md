# Phase 3 functional local vault checkpoint

## Current capabilities

Desktop has persistent opaque bootstrap slots with atomic replace and a real Argon2id/ChaCha20-Poly1305 unlock path. Two independent slots survive a process-like storage re-open; their roles are encrypted inside descriptors. The app UI/import/catalog persistence are not yet wired.

## Storage layout

`bootstrap/slots/<opaque-24-hex>.vslot` is the current implemented layout. Slot headers contain magic, versions, suite, opaque vault ID, bounded KDF parameters and salt. VMK wrapper and descriptor are authenticated ciphertext. Blob storage is independently available under the supplied `DesktopBlobStore` root.

## Verification

`./gradlew.bat :shared:core-vault:desktopTest --no-daemon` passed, including persistent real/decoy unlock after a new slot-store instance.

## Remaining critical work

Canonical encrypted catalog persistence, journal/recovery, V1 streaming blob framing, import use case, production host/UI wiring and Android runtime validation remain unfinished. Do not begin identity/transfer work.
