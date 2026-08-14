# Phase 1 vault foundation checkpoint

## What exists

- KMP module graph and Android/Desktop builds.
- Common vault domain, opaque blob contract, journal contract, catalog/folder invariants, dual-slot bootstrap and explicit lock session lifecycle.
- Desktop filesystem `BlobStore` uses opaque `.vblob` IDs, temp files and atomic move where supported.
- Android SDK location is local-only in `local.properties`; all library targets compile.

## Crypto status

Only contracts and test orchestration exist. No production KDF/cipher/provider is enabled. Do not treat the current app host as a usable secure vault. Select an audited cross-platform Argon2id provider first (P1), then implement provider contract tests and a canonical slot codec.

## Commands

```powershell
.\gradlew.bat :shared:core-vault:desktopTest :shared:core-vault:compileDebugKotlinAndroid --no-daemon
.\gradlew.bat :shared:app:compileDebugKotlinAndroid :shared:app:lintDebug --no-daemon
```

## Next work

1. ADR for crypto suite/provider and cross-platform vectors.
2. Persist canonical bootstrap/catalog formats behind authenticated encryption.
3. Implement Android/iOS/desktop platform storage roots and secret stores.
4. Build streaming encryption/import only after the authenticated cipher is real.
