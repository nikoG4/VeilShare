# Formato de bóveda

## Layout conceptual

```text
vault-root/
├── bootstrap/
│   ├── slot-8f3c.bin
│   └── slot-a12e.bin
├── blobs/
│   ├── 6a/6a947...vblob
│   ├── e4/e4b13...vblob
│   └── ...
├── manifests/
│   └── opacos...
└── journal/
    └── transacciones opacas...
```

Los nombres no deben incluir nombres originales.

## Bootstrap slot

Contiene en claro solo lo imprescindible:
- magic;
- format version;
- KDF id;
- KDF params;
- salt;
- nonce;
- ciphertext del wrapped VaultKey / descriptor;
- tag AEAD.

No contiene:
- `real`;
- `decoy`;
- nombre del usuario;
- cantidad de archivos.

## Descriptor cifrado

```kotlin
data class VaultDescriptor(
    val vaultId: VaultId,
    val createdAt: Long?,
    val policy: VaultPolicy,
    val indexPointer: BlobId,
    val persona: PersonaType // dentro del ciphertext
)
```

`persona` se conoce solo tras unlock.

## File manifest cifrado

Contiene:
- fileId;
- wrapped FileKey;
- original display name;
- MIME;
- size;
- chunk count;
- thumbnail pointer;
- folder;
- tags;
- integrity summary;
- timestamps;
- custom metadata.

## Journal

Importación:
`BEGIN -> CHUNKS -> MANIFEST -> VERIFY -> COMMIT`.

Crash antes de COMMIT:
- limpiar blobs huérfanos;
- no exponer item.

## Migrations

Cada objeto lleva `version`.
Migración debe ser:
- forward-only;
- transaccional;
- testeada con fixtures previos.
