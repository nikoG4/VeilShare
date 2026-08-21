# Receiver Import Contract V1

Date: 2026-08-15
Author: VeilShare Agent
Version: V1

## Overview

This document specifies the contract for integrating received chunks with the existing vault import pipeline.

**Critical Rule:**
- Never bypass existing import coordinator
- Never write directly to blob store
- Never write directly to catalog

## Import Pipeline

```
Network Stream (E2E encrypted)
    ↓
Decryption Layer (X25519 ephemeral)
    ↓
Validation (AEAD auth)
    ↓
Bounded Plaintext Buffer
    ↓
Encrypt with FileKey
    ↓
Persist to Blob Store (AtomicMove)
    ↓
Commit to Catalog (FileChannel.force)
    ↓
ACK (after durable)
```

## ImportSource Adapter

```kotlin
interface ImportSource {
    suspend fun source(input: InputStream): ImportResult
}
```

### Implementations

1. **Existing:** `FileImportSource`
2. **V1 New:** `NetworkImportSource`

### NetworkImportSource

```kotlin
class NetworkImportSource(
    private val stream: InputStream,
    private val fileSize: Long,
    private val mimeType: String?,
    private val displayName: String?
) : ImportSource {
    
    override suspend fun source(input: InputStream): ImportResult {
        // Read chunks
        // Verify AEAD
        // Decrypt with FileKey
        // Write to blob store
        // Commit catalog
        // Return result
    }
}
```

**Stream Handling:**
- Read in chunks (configurable chunk size)
- Verify AEAD per chunk
- Decrypt each chunk
- Write to blob store
- Commit after durable write

### ImportResult

```kotlin
sealed class ImportResult {
    data class Success(
        val fileId: FileId,
        val size: Long
    ) : ImportResult()
    
    data class Partial(
        val fileId: FileId?,
        val chunksReceived: Int,
        val reason: String
    ) : ImportResult()
    
    data class Failure(
        val reason: String,
        val fileId: FileId?
    ) : ImportResult()
}
```

## Chunk Processing

### Per-Chunk Flow

```kotlin
data class Chunk(
    val fileId: FileId,
    val chunkIndex: Int,
    val ciphertext: ByteArray,
    val authTag: ByteArray,
    val nonce: ByteArray
) {
    suspend fun process(fileKey: FileKey, chunkSize: Int): ChunkResult? {
        // Verify AEAD
        // Decrypt
        // Write to temp location
        // Commit to blob store
        // Return result
    }
}
```

### ChunkResult

```kotlin
sealed class ChunkResult {
    object OK : ChunkResult()
    
    data class Duplicate(val chunkIndex: Int) : ChunkResult()
    
    data class Corrupted(val authTag: ByteArray) : ChunkResult()
}
```

## Bounded Buffer

```kotlin
data class BoundedBuffer(
    val maxSize: Int = 1024 * 1024 * 1024, // 1GB
    val chunks: MutableList<Chunk> = mutableListOf()
) {
    suspend fun add(chunk: Chunk): ChunkResult {
        // Verify size
        // Decrypt
        // Check duplicate
        // Commit
        // Return result
    }
    
    suspend fun complete(): ImportResult? {
        // All chunks received?
        // Return success or failure
    }
}
```

## FileKey Management

### Key Selection

```kotlin
fun getFileKey(fileId: FileId): FileKey {
    // Derive from catalog
    // Or use existing file key
    // Or error (unknown file)
}
```

### Key Encryption

- **NEVER** send FileKey in protocol
- **NEVER** use FileKey for E2E encryption
- FileKey exists in vault before transfer
- Key encryption: separate from transfer crypto

### Key Derivation

```kotlin
fun deriveFileKey(
    fileId: FileId,
    vaultKey: VaultKey
): FileKey {
    // Use existing derivation
    // Or derive from VMK
    // Return FileKey
}
```

## Catalog Commit

### Commit Semantics

```kotlin
interface CatalogCommit {
    suspend fun commit(
        fileId: FileId,
        size: Long,
        mimeType: String?,
        displayName: String?,
        folder: String?
    ): Unit
}
```

**Critical Rules:**
- Commit only after durable write
- Exception during write: cleanup partial
- Exception ≠ rollback
- Catalog authoritative

### Commit Flow

```kotlin
suspend fun commitChunk(
    chunk: Chunk,
    catalogCommit: CatalogCommit
): ChunkResult {
    // Write to temp
    // Flush
    // Force
    // AtomicMove
    // Commit catalog
    // Return OK
}
```

## Error Handling

### Corrupted Chunk

```kotlin
fun handleCorruptedChunk(chunk: Chunk, result: ImportResult) {
    // Discard corrupted chunk
    // Log error
    // Don't retry (data corruption)
    // Continue with next chunk
}
```

### Missing Chunk

```kotlin
fun handleMissingChunk(transferId: TransferId, chunkIndex: Int): Boolean {
    // If gap is small: retry full transfer
    // If gap is large: fail transfer
    // Return: retry or fail
}
```

### Duplicated Chunk

```kotlin
fun handleDuplicateChunk(chunk: Chunk): ChunkResult {
    // Check if already committed
    // If committed: return Duplicate
    // If not committed: commit
    return if (committed) ChunkResult.Duplicate() else ChunkResult.OK()
}
```

## Semantics

### Pre-Commit Cancel

```kotlin
// Receiver cancels before commit
// No catalog entry created
// No blob in vault
// Discard partial chunks
```

### Post-Commit Cancel

```kotlin
// Receiver cancels after commit
// Catalog entry exists
// Blob in vault
// Exception ≠ rollback
// File exists in vault
```

### Corruption

```kotlin
// Corrupted file during transfer
// Other files unaffected
// Continue transfer
// Don't auto-delete
// File listed in catalog
// Open fails for corrupted
```

## Timeout Handling

```kotlin
fun handleTransferTimeout(): Unit {
    // Remove session from registry
    // Discard pending chunks
    // If not committed: cleanup partial
    // If committed: ignore
    // Log timeout
}
```

## Import Coordinator Integration

### Existing Coordinator

```kotlin
class ImportCoordinator(
    private val blobStore: BlobStore,
    private val catalog: CatalogDurable,
    private val fileKeyDeriver: FileKeyDeriver
) {
    suspend fun import(source: ImportSource): ImportResult {
        // Existing implementation
    }
}
```

### V1 Integration

```kotlin
fun importFromNetwork(
    stream: InputStream,
    metadata: FileMetadata,
    catalogCoordinator: CatalogCoordinator
): ImportResult {
    val source = NetworkImportSource(stream, metadata)
    return catalogCoordinator.import(source)
}
```

## Summary

**V1 Import Contract:**
- Use existing import pipeline
- Never bypass import coordinator
- Commit after durable write
- Idempotent delivery
- Handle duplicates gracefully
- Handle corruption gracefully
- Handle timeout gracefully

**NEVER:**
- Write directly to blob store
- Write directly to catalog
- Send FileKey in protocol
- Assume FileKey exists
- Skip encryption

---

**Next Action:** Start implementing common DTOs.
