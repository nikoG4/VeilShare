# Progress Log

## 2026-08-09 — Persistent production slot milestone

- Added `ProductionCryptoComponents` and a Desktop production component factory; app/domain need not construct Bouncy Castle classes.
- Implemented Desktop persistent opaque slot files with bounded binary parsing and atomic replace where supported.
- Added restart-style production test: creates two slots using Argon2id/ChaCha20-Poly1305, instantiates a fresh store, and unlocks real/decoy credentials independently while invalid credential fails neutrally.
- Verified `:shared:core-vault:desktopTest` — BUILD SUCCESSFUL.
- Catalog persistence, journal persistence/recovery, V1 stream writer/reader, import and UI remain unfinished.

## 2026-08-09 — P1 reopened as composed providers

- Added ADR-009, superseding ADR-008's global gate: Bouncy Castle 1.80 now supplies raw Argon2id v1.3 and ChaCha20-Poly1305 for Desktop and Android adapters.
- Revised V1 from XChaCha20-Poly1305 to IETF ChaCha20-Poly1305 (12-byte structured nonce), before enabling any persistent V1 user data.
- Added a pinned JVM Argon2id vector: `07200454a4c2369a893741c6bf6355f29bf357857cc5eb8e8795104fbe70a38e` for password `password`, salt `somesalt`, m=8192 KiB, t=1, p=1, output=32.
- Added a pinned ChaCha20-Poly1305 fixture and tamper/AAD rejection spike tests. Desktop passed; Android adapter compiles but needs device/emulator execution for READY status.
- iOS planned provider is official libsodium 1.0.19 cinterop, not the experimental Kotlin wrapper; macOS verification instructions are in `platforms/IOS_CRYPTO_VERIFICATION.md`.

## 2026-08-09 — Production crypto gate review

- Re-ran the relevant Desktop/Android compilation and vault test tasks successfully.
- Evaluated cryptography-kotlin as a portable AEAD API candidate and kotlin-multiplatform-libsodium 0.9.5 for Argon2id. The latter supports the target set but its upstream README labels the binding experimental and advises against production use without community review.
- Added `CryptoSuiteId.VEIL_CRYPTO_V1`, ADR-008, and a normative but disabled V1 binary-format specification.
- P1 remains unresolved. No production KDF/cipher, persistent encrypted catalog, import pipeline or user-facing vault setup was enabled, because doing so would violate the no-crypto-fallback rule.
- Verified: `:shared:core-crypto:desktopTest :shared:core-vault:desktopTest :shared:app:compileDebugKotlinAndroid` — BUILD SUCCESSFUL.

## 2026-08-09 — Vault foundation marathon checkpoint

- Confirmed Android SDK at `C:/Users/ll/AppData/Local/Android/Sdk`; installed platforms are android-33 through android-36 and build-tools 34.0.0 through 36.0.0. Added untracked-machine local configuration `local.properties`.
- Verified `:shared:app:compileDebugKotlinAndroid` and `:shared:core-vault:compileDebugKotlinAndroid` successfully.
- Added common vault domain, dual independent bootstrap slots, lockable session, crypto contracts, transaction journal contract, garbage collector, catalog/folder validation and desktop opaque BlobStore.
- Added tests for real/decoy orchestration, wrong PIN rejection, folder-cycle/path traversal rejection and Desktop streamed opaque blob writes.
- Added ADR-007 and Phase 1 checkpoint. Production crypto is explicitly not implemented: P1 Argon2id cross-platform provider selection is a hard release gate.
- `:shared:core-vault:desktopTest`, `:shared:core-vault:lintDebug` and `:shared:app:lintDebug` completed successfully. AGP 8.7.3 warns that compileSdk 36 is newer than its tested level; it does not fail the build.
- iOS targets remain declared but unverified on Windows; no macOS result is claimed.

## 2026-08-09 — Phase 0 Foundation

- Repositorio inicial: documentación solamente; no había código ni repositorio Git.
- Se creó un build KMP modular con targets Android, iOS y Desktop JVM.
- `commonMain` contiene IDs/versiones, errores, capabilities, fakes, estado raíz y UI inicial compartida.
- AdaptiveKt está aislado en `:shared:ui-design`; las features no lo importan.
- No se implementó criptografía, bovedas, importación ni WebRTC en esta fase, por diseño.
- Android queda sin verificar por ausencia de SDK; iOS requiere macOS/Xcode.
- Verificado en Windows/JDK 17: `./gradlew.bat :shared:core-model:desktopTest :shared:ui-features:desktopTest :desktopApp:compileKotlin :server:signaling:build --no-daemon` terminó con `BUILD SUCCESSFUL`.
- Próximo bloque: completar Phase 0 con host Android/iOS y CI, o iniciar Phase 1 (formato de vault, BlobStore y journal) sin seleccionar aún primitivas criptográficas.

## 2026-08-09 — documentación inicial

- Especificación maestra creada.
- Arquitectura KMP commonMain-first definida.
- AdaptiveKt encapsulado por wrapper.
- Bóveda real/señuelo especificada como separación criptográfica.
- Cifrado de archivo y protocolo P2P detallados.
- Fronteras Android/iOS/Desktop definidas.
- Servidor Ktor mínimo especificado.
- Test plan y roadmap creados.

### Estado de código
`NOT STARTED`

### Próximo hito
Phase 0: esqueleto compilable + contratos + tests de serialización.
