# VeilShare next-agent handoff

## Read first

`docs/AGENT_START_HERE.md`, `docs/NON_NEGOTIABLES.md`, `docs/adrs/ADR-009-composed-crypto-providers-v1.md`, then the Phase 3 checkpoint.

## Current state

Foundation compiles on Desktop and Android. Desktop has production-crypto-backed persistent opaque slots and dual-vault restart tests. Encrypted catalog/file persistence and import are not implemented; keep working on the local vault before networking.

## Do not change

- Real/decoy must remain independent slots and keys.
- Keep sensitive metadata encrypted and platform types out of common domain.
- Do not substitute a fast KDF for Argon2id.

## Known blockers

- Android runtime validation and iOS native verification require an emulator/device and macOS/Xcode respectively.

## Next 10 tasks

Implement canonical encrypted catalog persistence; persistent journal/recovery; streaming blob format; import E2E; then host/UI and Android storage adapters.

## Commands

`./gradlew.bat :shared:core-vault:desktopTest :shared:app:compileDebugKotlinAndroid --no-daemon`
