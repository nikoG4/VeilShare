# Local UI baseline

Status: Android and Desktop local UI baseline implemented (2026-08-14).

## Architecture

`LocalAppController` is the common presentation owner. Its explicit root state is
`Initializing`, `FirstRun`, `Locked`, `Unlocked`, or `Fatal`. It owns at most one
`VaultHandle`; locking cancels the active import, closes the handle, drops the
browser state, and requests cleanup of app-owned plaintext viewer files.

`VaultHandle` is a narrow core-vault facade. The UI never receives the VMK or a
FileKey and never writes catalogs, journals, or blobs directly. Platform services
compose only production crypto and namespaced physical stores:

- Desktop: `DesktopLocalVaultService` over the existing desktop stores.
- Android: `AndroidLocalVaultService` over `noBackupFilesDir` stores.

The authenticated durable catalog remains the logical authority. After an import
or delete reports an ambiguous failure/cancellation, presentation reloads and
authenticates the durable catalog instead of applying optimistic rollback. Active
recovery authenticates that catalog before building the referenced-blob set used
by GC.

## First run and unlock

First run accepts a principal and alternate code only during setup, requires
confirmation and distinct values, creates the persistent pair, clears input
buffers best-effort, and finishes locked. A partially created physical setup is
reported as incomplete and is never reset automatically.

Runtime unlock is one generic form. Both credentials produce the same screen
hierarchy and wording; only authenticated contents differ. Invalid credentials
use a neutral message. No session, PIN, VMK, decrypted catalog, or filename is
stored in Android saved state.

## Browser and transactions

The common browser uses logical IDs and catalog names only. It supports root and
folder navigation, breadcrumbs, persistent folder creation, streaming import with
progress/cancellation, authenticated open, explicit delete, code change, and
manual lock. Non-empty folder deletion is rejected by the core facade.

Import and delete always refresh from the authenticated catalog. Cancellation
before commit may leave no item; cancellation after catalog commit does not imply
rollback. UI never deletes physical blobs.

## Platform adapters

Android uses `ACTION_OPEN_DOCUMENT` and a `ContentResolver` stream. The encrypted
vault remains under `noBackupFilesDir`. External open decrypts into an opaque file
under one app-owned cache directory and grants a temporary FileProvider URI.
`FLAG_SECURE` is enabled for the Activity. A meaningful background transition
locks the session, except the transition caused by the active system picker.
Activity recreation does not persist the session and returns locked.

Desktop uses `JFileChooser`, `DesktopImportSource`, an opaque app data root, and
`Desktop.open`. Its neutral window title does not include filenames. Window close
disposes presentation and closes the session.

Both platforms clear only their own viewer cache on startup and lock. They never
scan or delete unrelated temporary files.

## Plaintext viewer limitation

External open necessarily materializes temporary plaintext. Cleanup is
best-effort; an external viewer can retain, copy, cache, or back up content, and a
crash can leave the owned cache file until the next startup. Desktop cannot
portably prevent screenshots. Android `FLAG_SECURE` reduces normal screenshots
and task previews but is not DRM.

## Lifecycle and future work

Desktop inactivity timeout and polished Android system-back folder navigation are
not part of this baseline. Manual lock, close/dispose, Android background lock,
and restart-locked behavior are implemented. Networking and iOS were untouched.
