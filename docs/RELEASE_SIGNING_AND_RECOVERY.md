# VeilShare: release signing and recovery safety

## Existing installations must be preserved

The Android application ID is `dev.veilshare.android`. The `releaseCheck` build has a **different** application ID (`dev.veilshare.android.releasecheck`) and uses debug signing. Its successful CI build is not a production release and does not establish update compatibility.

Android only accepts an in-place update when the application ID and signing certificate (or an Android-recognized signing lineage) are compatible. Before distributing a release to an existing installation, compare the **installed APK's certificate** against the proposed APK certificate using `apksigner verify --print-certs`, and verify the package name. Do not uninstall, clear data, replace the vault root, or change the package ID to resolve a mismatch. If the original signing key is unavailable, stop and obtain an explicit recovery/migration decision; an independently signed APK cannot access the previous app-private data.

## Stable release signing

`androidApp/build.gradle.kts` supports the following environment variables, all required together:

- `VEILSHARE_RELEASE_KEYSTORE_PATH`: local path to a persistent, securely retained keystore
- `VEILSHARE_RELEASE_STORE_PASSWORD`
- `VEILSHARE_RELEASE_KEY_ALIAS`
- `VEILSHARE_RELEASE_KEY_PASSWORD`

Store keystore bytes in an access-controlled GitHub Actions secret (for example, a base64-encoded keystore) and store passwords/alias in separate encrypted secrets. Decode the keystore into the runner's temporary directory, set `VEILSHARE_RELEASE_KEYSTORE_PATH` to that file, and delete it in an always-run cleanup step. Never commit the keystore, secret values, decoded files, or diagnostic logs exposing them. Provisioning these secrets is a repository-owner operation; **do not generate a replacement signing identity** without verifying the installed certificate first.

Without the full signing configuration, the release build is not evidence of a signed, update-compatible APK. The release workflow must fail closed rather than publish an unsigned or debug-signed artifact as production.

## Encrypted backup and restore are separate from signing

A vault backup must cover the full private root `noBackupFilesDir/v-8e61c4a0`, including REAL and DECOY slots, wrapped PIN material, catalogs, journal and encrypted blobs. The backup must preserve those bytes without decrypting core vault contents and add authenticated encryption under a fresh random 256-bit recovery key. The key must be shown only in a secure UI and kept outside preferences and the backup file.

Import must authenticate the entire ciphertext before extraction, reject malformed paths, symlinks and excessive sizes, and extract only to an isolated private staging directory. Before promoting the staged data, verify both vault credentials through the existing Android vault service. Promotion must require an empty destination and be atomic with rollback, never overwriting an existing vault. Expose export only from authenticated private settings, and restore only before initial setup, using SAF without storage permissions.

**Current implementation boundary:** the core backup codec and its tests do not themselves implement the SAF screens, session locking, staged credential checks or atomic promotion. Do not present the backup/restore feature as available until these are implemented and tested end to end.

A backup cannot retroactively grant a newly signed app access to data held by a differently signed installed app. Preserve the installed application and its data until the original signing identity and recovery path are verified.
