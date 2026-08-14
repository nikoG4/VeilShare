# Abstracciones de almacenamiento

## Separar BlobStore de MetadataStore

`BlobStore`
- bytes opacos;
- streaming;
- no entiende filenames.

`VaultRepository`
- entiende manifests;
- usa crypto;
- transacciones.

`PlatformStorageRoot`
- resuelve dónde viven blobs;
- nunca expone rutas a UI.

## API

```kotlin
interface BlobWriter {
    suspend fun write(bytes: ByteArray, offset: Int, length: Int)
    suspend fun commit(): BlobId
    suspend fun abort()
}

interface BlobReader {
    val size: Long
    suspend fun readAt(offset: Long, max: Int): ByteArray
    suspend fun close()
}
```

## Crash safety

Escritura:
- temporary opaque id;
- fsync/durable best-effort según plataforma;
- commit/rename atómico donde exista;
- journal.

## Quotas

Antes de importar:
- estimate space;
- no asumir que `freeSpace` es exacto;
- manejar ENOSPC durante write.
