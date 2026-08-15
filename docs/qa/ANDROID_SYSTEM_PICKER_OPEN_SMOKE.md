# Android system picker and opener smoke

## Automated and controlled evidence

Environment: Pixel 9 Pro XL API 35 emulator, Android 15, `releaseCheck` (minified, non-debuggable, debug-key signed).

- Fresh install and cold launch: PASS.
- First-run with two distinct fixture codes: PASS.
- Generic unlock: PASS.
- Home/background and return: PASS; the app returned locked.
- Real `ACTION_OPEN_DOCUMENT` launch: PASS; `DocumentsUI/PickActivity` owned focus.
- Picker cancel: PASS; the unlocked session remained available because picker transition exemption was active.
- Real document selection from Downloads: PASS (`veil-release-smoke.txt`, 32 bytes).
- Streaming import and visible catalog entry: PASS.
- FileProvider scope: instrumented test accepts `cache/open-4f16a9/` and rejects a sibling cache file.
- Open action: reached Android's resolver with a temporary content URI. No external text viewer was selected, so viewer receipt/content rendering is **not verified**.

## Manual matrix still required on a representative device

1. Install a signed release candidate and clear app data.
2. Complete first-run and unlock.
3. Open the picker and cancel; confirm the browser is still unlocked.
4. Select a small TXT from Downloads; confirm exact name and size.
5. Repeat with an image, PDF, provider-backed document, and a file larger than 1 MiB.
6. Remove a selected source before it is opened; expect a generic import failure and a healthy vault.
7. Open each imported type. Confirm the external viewer receives read-only content and the vault locks when the viewer transition backgrounds the app.
8. Return; expect the unlock screen when lifecycle lock occurred.
9. Lock manually and confirm `cache/open-4f16a9/` is empty best-effort.
10. Force-stop with a stale owned temp, relaunch, and confirm startup cleanup retries.

The temporary URI grant is not DRM. A viewer may copy, cache, index, or retain plaintext after receiving it.
