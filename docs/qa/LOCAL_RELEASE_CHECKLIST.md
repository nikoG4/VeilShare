# Local release checklist

Status values: PASS, PENDING MANUAL, or NOT APPLICABLE.

## Android

- [x] Clean install and cold launch of minified non-debuggable `releaseCheck`.
- [x] First-run creates two independent spaces and finishes locked.
- [x] Generic unlock and neutral invalid-code error.
- [x] Folder creation, rename, import, delete, change code, lock, restart.
- [x] REAL/DECOY-equivalent isolation through neutral UI and core E2E.
- [x] Real system picker cancellation and TXT selection/import on API 35.
- [ ] Image/PDF/provider/multichunk picker matrix on a physical device.
- [x] FileProvider path least privilege (allowed root plus sibling rejection test).
- [ ] External viewer receives and renders content (resolver reached; no viewer selected in emulator).
- [x] `FLAG_SECURE`, no broad storage permissions, app-private vault root.
- [x] Home/background locks; picker cancel exemption preserves the active flow.
- [x] Owned plaintext temp cleanup on startup/lock, isolated from sibling cache.
- [x] R8/minify/shrink build, lint, unit, and API 35 instrumentation.
- [ ] Production signing, Play integrity/distribution, and physical-device recents check.

## Desktop

- [x] `installDist`, path-with-spaces launch, unrelated working directory.
- [x] First-run/unlock/import/restart/delete/change-code core composition E2E.
- [x] Dedicated opaque temp root with startup/lock cleanup unit coverage.
- [x] Neutral title, 1100x760 default, 720x520 minimum, adaptive content.
- [ ] Interactive picker matrix and external viewer matrix.
- [ ] Native MSI/EXE signing and installer UX (not configured).

## Security/release

- [x] Frozen catalog/journal/blob/slot formats unchanged.
- [x] No networking feature work in this checkpoint.
- [x] No release signing credentials or secrets committed.
- [x] No intentional REAL/DECOY semantic labels in production UI/layout.
- [x] No VMK/FileKey/PIN logging found.
- [x] Backup/device-transfer exclusion configured for Android.
- [x] Artifact sizes and SHA-256 recorded in the release-readiness checkpoint.
