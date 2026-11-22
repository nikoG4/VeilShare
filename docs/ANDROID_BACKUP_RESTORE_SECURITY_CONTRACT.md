# VeilShare backup/restore security contract (implementation gate)

Status: **design only — no backup or restore feature is enabled by this document**.

## Existing installations

Never uninstall, clear app data, replace the package ID, rotate the existing core encryption, or promote a restore over an existing vault. A release signed with a different certificate cannot update the installed app in place. The `releaseCheck` variant has a different application ID and uses debug signing; it is **not** a production migration path. Before producing an upgrade, compare the installed APK certificate digest and package ID with the intended release keystore. An export implemented later cannot retroactively recover an installation that was already deleted.

## Export invariants

* Only from an authenticated private settings surface, never from the decoy calculator or an unauthenticated screen. Lock all open vault handles, drain in-flight writes and stop background mutations before the snapshot; do not copy a live journal.
* Source: `AndroidVaultStorage.privateRoot(context)` (`noBackupFilesDir/v-8e61c4a0`). Include both REAL and DECOY wrapped PIN slots, catalogs, journal and encrypted blobs **as opaque bytes**, with no core decryption.
* Generate a fresh 32-byte recovery secret using `SecureRandom`; never derive the only encryption key from a numeric PIN and never persist the recovery secret in preferences, logs, analytics or the vault. Display it under `FLAG_SECURE` and require explicit acknowledgement that the user has stored it externally.
* Use a versioned, authenticated envelope with domain-separated header and per-chunk AEAD (e.g. AES-256-GCM with independently derived nonce/counter discipline). Authenticate file names, lengths, chunk indices, total counts, and an explicit final end-of-stream marker; reject wrong keys, truncated streams, appended bytes, reordered chunks and any authentication failure. Keep memory bounded for multi-GB backups.
* Strictly reject absolute paths, `..`, duplicate/case-fold-colliding names, symlinks, nonregular files, nested roots and oversized metadata. Enforce limits for file count, depth, name length, individual file size and total extracted bytes. Never follow filesystem links.
* Export only through Android Storage Access Framework `ACTION_CREATE_DOCUMENT`, without new storage permissions. Close descriptors and remove incomplete exports on cancellation/failure when possible; never claim success without a finalized authenticated stream.

## Restore invariants

* Expose `ACTION_OPEN_DOCUMENT` **only before initial setup** and only when the canonical vault root does not exist or is verifiably empty. Never replace or merge existing data.
* Decrypt and authenticate into a newly created private staging directory on the same filesystem. Reject malformed formats and all path/size violations before promotion. Do not extract into the live root.
* Verify both credential slots and vault readability with `AndroidLocalVaultService` constructed against staging, without modifying core cryptography. Require the corresponding user PINs to validate each slot; never treat archive authentication alone as proof of a usable vault.
* Recheck live-root emptiness immediately before an atomic same-filesystem rename. If promotion fails, leave the old state untouched and clean staging; if the filesystem cannot guarantee the required no-replace semantics, abort instead of attempting an unsafe overwrite.
* Do not expose staging data through exported components. Any dedicated backup activity must be `android:exported="false"`, and recovery-code screens must set `FLAG_SECURE`.

## Acceptance gate

Unit/instrumentation tests: round-trip both slots and their blobs; wrong recovery key; wrong REAL/DECOY PIN; changed header; changed chunk/tag; truncation at every framing boundary; trailing bytes; duplicate path; traversal; symlink; size/count limits; cancellation; concurrent writer; existing-root refusal; interrupted stage and failed promotion; restore availability only before setup. Validate on Android emulator and device with both vaults populated before enabling UI.

## Release signing gate

Stable keystore must be supplied out of band via GitHub Actions secrets, never committed. Configure `VEILSHARE_RELEASE_KEYSTORE_PATH`, `VEILSHARE_RELEASE_STORE_PASSWORD`, `VEILSHARE_RELEASE_KEY_ALIAS`, `VEILSHARE_RELEASE_KEY_PASSWORD` from a persistent keystore and protected CI environment. If unavailable, report **no signed release**; do not silently use the debug key. Never publish an APK as upgrade-compatible without checking the installed certificate and package ID.
