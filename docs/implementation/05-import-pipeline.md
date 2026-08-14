# Blueprint 05 — Import pipeline

## API

```kotlin
class ImportUseCase(
    private val picker: ImportPicker,
    private val vault: VaultRepository,
    private val deletion: OriginalDeletion
)
```

Picker y deletion son platform capabilities; cifrado/commit es common.

## Pipeline por item

1. obtain `ImportHandle`;
2. query safe metadata;
3. allocate FileId/FileKey;
4. create temp blob;
5. stream chunks;
6. AEAD encrypt;
7. build encrypted manifest;
8. verify count/hash;
9. durable commit;
10. emit `Imported`;
11. optionally invoke delete original.

## Metadata query

Nunca confiar en:
- filename para path;
- provider-reported size como exacto;
- MIME para parser safety.

## Cancellation

Si coroutine cancel:
- close source;
- abort writer;
- journal cleanup;
- do not delete original.

## Batch

`Flow<ImportProgress>` con progreso por item y total.

## Acceptance

- 0 byte;
- 1 byte;
- 50MB;
- source throws mid-stream;
- disk full;
- user cancels;
- delete denied.
