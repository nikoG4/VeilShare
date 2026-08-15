# Local product release-readiness baseline

Date: 2026-08-14
Scope: local Android/Desktop product only; networking and iOS unchanged.

## Toolchain

- Gradle 8.13, JDK/JVM toolchain 17.
- Kotlin 2.2.21 (from 2.1.21).
- Android Gradle Plugin 8.11.1 (from 8.7.3).
- Compose Multiplatform 1.8.2.
- compileSdk/targetSdk 36, minSdk 23.
- Coroutines 1.10.2, serialization 1.9.0, Bouncy Castle 1.80.

This is the smallest alignment that removes the Kotlin metadata diagnostics and gives AGP-supported compileSdk 36 without migrating the UI framework. Lint moved from 42 warnings in the previous checkpoint (15 immediately before release cleanup) to 0 errors and 0 warnings.

## Product surface

- Polished adaptive first-run, unlock, browser, empty/loading/error, import progress, rename, delete, settings/change-code, and About surfaces.
- Compact actions no longer share one overflowing toolbar; expanded Desktop adds a local-status details panel.
- Neutral terminology is shared across both spaces.
- Desktop default/minimum window sizes are 1100x760 and 720x520.

## Android release posture

- Manifest disables backup/device transfer, cleartext traffic, and provider export.
- FileProvider exposes only `cache/open-4f16a9/`.
- No broad storage/media/phone permissions survive manifest merge; instrumentation checks the installed package.
- `FLAG_SECURE` is asserted by instrumentation.
- `release` is minified/shrunk and intentionally unsigned.
- `releaseCheck` is minified, non-debuggable, and debug-key signed solely for local installability testing. It is not a production-signed artifact.
- API 35 emulator: clean install, setup, unlock, background lock, real system-picker cancel and real TXT selection/import passed.
- External open reached the Android resolver; rendering by an installed external viewer remains pending.

## Plaintext temporary policy

Decrypted open copies exist only under dedicated app-owned temp roots with opaque basenames and a validated extension when useful:

- Android: `cache/open-4f16a9/`
- Desktop: `%TEMP%/vs-open-4f16a9/`

Startup and lock retry recursive best-effort cleanup. Cleanup failure never invalidates the encrypted vault or prevents lock. An external viewer may retain plaintext, and Windows can defer deletion of open files; this boundary is documented rather than described as secure erase.

## Desktop release posture

- `installDist` is the supported artifact for this checkpoint.
- A copied distribution in a clean path containing spaces launched from an unrelated working directory and stayed alive for 8 seconds.
- Interactive picker and viewer association checks remain manual.
- Native MSI/EXE packaging and code signing are not configured.

## Tests and gates

- Frozen core Desktop/Android suites retained.
- Added controller lock/cleanup, Desktop owned-temp isolation, Android owned-temp isolation, FileProvider rejection, manifest permission, and `FLAG_SECURE` coverage.
- Android API 35 core instrumentation: 15/15.
- Android app instrumentation: 4/4 after the added platform tests.
- 91 distinct tests are green (98 target executions when the seven shared UI tests run on both Desktop and Android).
- Android lint: `No issues found.`

## Final regression

The combined Gradle gate completed with `BUILD SUCCESSFUL` (713 tasks) for Desktop crypto/vault/UI tests, Desktop app tests and distributions, Android crypto/vault/UI unit tests, Android debug/release/releaseCheck APKs, Android lint, and the signaling-server regression build. The API 35 emulator then completed 15 core instrumentation tests and 4 host-app instrumentation tests with zero failures.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `androidApp-debug.apk` | 17,788,903 | `23CA6AC59A0287B3974E92A86A13850B75D59A2290EE9C7671D078665EFBEB7C` |
| `androidApp-release-unsigned.apk` | 2,412,275 | `980496E2E979C0A634E6F11B55746CD37D87A05A62AA93C2C6316EB4F247669B` |
| `androidApp-releaseCheck.apk` | 2,429,219 | `1C1858735B9D6E69DD4FD96C7B8C203922BEBB31A80BF9D4D1FECA34F27BDA39` |
| `desktopApp.zip` | 40,341,863 | `30567F2E7A9BD575A88C1395223455FA81B9BFF9A421A4527BD930C0F2B2CA08` |

The final `releaseCheck` was installed cleanly on the API 35 emulator. It launched into first-run, `run-as` rejected it as non-debuggable, and `dumpsys package` showed no broad storage/media/phone permissions. The final Desktop distribution contained 50 files (43,222,785 bytes), was copied to a clean path containing spaces, launched from an unrelated working directory, and remained alive for the eight-second smoke window.

## Manual status

See:

- `docs/qa/ANDROID_SYSTEM_PICKER_OPEN_SMOKE.md`
- `docs/qa/DESKTOP_PICKER_OPEN_SMOKE.md`
- `docs/qa/LOCAL_RELEASE_CHECKLIST.md`

## Decision boundary

The local product is now a green technical release-readiness baseline. It is not a production-signed release, and external-viewer/manual device coverage remains explicit follow-up QA.
