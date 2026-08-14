# Desktop durability boundary

## Implemented write sequence

Catalog, slot, and journal replacements use the following sequence on Desktop:

1. Serialize a complete, versioned binary generation in memory.
2. Create a temp file in the authoritative file's directory.
3. Write all bytes through `FileChannel`.
4. Request `FileChannel.force(true)` for that temp file.
5. Require `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` in the same directory.
6. Delete a remaining temp on a controlled exception.

Blob finalization forces the complete VBL1 staging file and then requires a same-directory atomic move without replacement. Unsupported atomic move is an explicit failure; there is no non-atomic fallback.

## Authority and recovery

Only the authenticated primary catalog is logically authoritative. Temps are never promoted by scanning, filename similarity, successful parsing, or journal claims. After an exception around replacement, a fresh object graph reopens and authenticates the primary generation. It accepts only the complete old or complete new generation.

The journal is auxiliary evidence. Corrupt, ambiguous, foreign-namespace, or missing journal data never authorizes committed-blob deletion. Unknown and ambiguous blobs are preserved.

## Evidence levels

1. **Fresh object graph from the same root:** covered by persistent Desktop E2E tests.
2. **Deterministic JVM/filesystem exceptions:** covered before writes, during real partial temp writes, before replacement, after replacement, during physical blob deletion, and during journal cleanup.
3. **File and rename requests:** complete temp bytes are forced and an atomic rename is required. A successful return means those operations returned successfully to the JVM.
4. **Sudden power loss, kernel crash, filesystem bugs, and volatile storage-controller caches:** not simulated and not claimed safe.

Windows/JVM does not expose a portable, dependable directory-fsync primitive here. Consequently, `force(true)` does not prove persistence of the renamed directory entry after sudden power loss. The supported claim is:

> Deterministic faults and reconstruction from the same filesystem root accept only an authenticated old or new primary generation. Physical power loss was not simulated.
