# iOS crypto verification

Status: `IMPLEMENTATION_PENDING_MACOS`.

The planned adapter must use a minimal Kotlin/Native cinterop definition for pinned libsodium `1.0.19`: `sodium_init`, `crypto_pwhash` with `crypto_pwhash_ALG_ARGON2ID13`, and `sodium_memzero`. It must expose no broad Libsodium API to common code.

On macOS, build the pinned static XCFramework from the official libsodium source release, verify its published checksum, point the `.def` linker options at that artifact, then run:

```bash
./gradlew :shared:core-crypto:iosSimulatorArm64Test
./gradlew :shared:core-crypto:iosX64Test
```

The tests must match the pinned Argon2 vector `07200454a4c2369a893741c6bf6355f29bf357857cc5eb8e8795104fbe70a38e` for password `password`, salt `somesalt`, memory 8192 KiB, iterations 1, parallelism 1 and 32-byte output; then run the AEAD fixture and tamper cases. Do not mark iOS ready until those tasks pass on an Apple host.
