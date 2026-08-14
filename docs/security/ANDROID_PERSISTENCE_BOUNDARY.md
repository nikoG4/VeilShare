# Android core persistence boundary

## Storage and primitives

Authoritative vault data is rooted under `Context.noBackupFilesDir`, which is app-private and excluded from Android Auto Backup. The fixed application subdirectory and every vault/blob identifier are opaque; REAL/DECOY labels and user filenames are not used as physical paths.

Catalog, slot, and journal generations follow:

1. serialize a complete versioned generation;
2. create a temp beside the authoritative file;
3. write through `FileOutputStream`;
4. flush and request `FileDescriptor.sync()`;
5. replace with same-filesystem `android.system.Os.rename()`;
6. remove a remaining temp after a controlled exception.

VBL1 writes remain staging until the complete authenticated terminal frame exists. Commit requests `sync()` for the staging file and then promotes it with `Os.rename()`. Temps are never promoted by scanning or parsing heuristics.

## Tested authority and faults

On API 35 instrumentation, Android D0-D8 covers pre-intent, post-intent, five real partial catalog writes, complete/synced pre-rename, post-catalog commit, physical delete failure, stale journal, journal-cleanup failure, and rename-complete-then-exception. Each scenario reconstructs crypto/stores/repository from the same app-private root three times.

Only the authenticated primary catalog determines whether an item exists. Journal data is auxiliary and namespace-scoped. Corrupt journal/catalog data never increases deletion authority. Unknown and ambiguous blobs are preserved.

Additional device tests cover catalog and journal corruption, corrupt/missing referenced blobs, REAL/DECOY ciphertext preservation, cancellation on both sides of catalog commit, failed PIN rotation before rename, and a raw physical canary scan. The canary scan found zero occurrences of its logical filename, MIME value, and plaintext content under the complete vault root; physical path components contained no REAL/DECOY labels.

Individual corrupt slots are isolated during enumeration: the malformed slot grants no session or recovery authority, while an independently valid sibling slot remains usable. Device tests cover authenticated-region mutation, truncation, and trailing data in both REAL/DECOY directions. Referenced VBL1 files are also physically truncated, appended, and header-corrupted; the target fails authenticated reading while sibling and cross-vault ciphertext remain unchanged.

## Physical Desktop/Android compatibility

Three fixed synthetic fixtures copy authoritative bytes without decoding or re-encoding them. Every internal slot, catalog, journal, and VBL1 entry has a SHA-256 assertion in its manifest.

- Desktop-produced tree consumed by Android: `CE4F3CA45356D0DF1D52034ADDEBB2B61E540B75AE216501F82F16DA49602E84`.
- Android-produced tree consumed by Desktop: `0CBA819F8DADDC1AE77167595E757C167ACC001F0EC17F8071FD71FD5FFB680F`.
- Desktop tree mutated by an Android production import and consumed again by Desktop: `F3DA11A6B134E1D30D555509ACBFF8593E04A6CF3CE7DB97EE0412FFDFF0A10E`.

The fixtures contain only synthetic PINs, plaintext, salts, keys, namespaces, and ciphertext generated for interoperability tests. They contain no absolute host/device paths or user material.

Blob reads bound the remaining `Long` length before converting the requested allocation to `Int`. Sparse files larger than 2 GiB are covered on Desktop and API 35, so a small read remains bounded rather than overflowing into a negative allocation.

## Claim boundary

Demonstrated:

- emulator filesystem operations on API 35;
- deterministic partial writes and sync/rename exceptions;
- fresh object graphs from the same physical root;
- exact ciphertext preservation for unaffected files;
- exact plaintext recovery;
- conservative authenticated recovery;
- physical artifact compatibility in both directions plus Desktop-Android-Desktop mutation;
- fourteen instrumented tests on the API 35 emulator, with no failures or skips.

Not demonstrated:

- actual OS process death at every fault boundary;
- sudden device power loss or kernel panic;
- flash translation layer/controller-cache behavior;
- behavior of every OEM filesystem;
- durable synchronization of the containing directory entry;
- actual runtime on API 23-26: this machine only has the API 35 system image and AVD.

The process-death claim remains limited to complete reconstruction of fresh object graphs from the same physical app-private root. Killing the instrumentation process also kills its controlling runner; no stable, timing-free two-process harness was available in this project, so actual OS process death is not claimed.

`FileDescriptor.sync()` and `Os.rename()` improve the requested persistence semantics but are not described as proof against physical power loss.

## Android core baseline — frozen (2026-08-14)

Within the boundaries above, D0-D8, corruption isolation, slot sibling isolation, VBL1 physical rejection, failed PIN rotation, opaque namespace isolation, canary scanning, and physical Desktop/Android interoperability are green. The remaining power-loss, OEM, lower-API runtime, and process-death limits are explicit environment/platform boundaries rather than unresolved logical transaction gaps.
