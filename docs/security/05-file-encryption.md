# Cifrado de archivos

## Modelo

Cada archivo:
- `FileKey` aleatoria 256-bit;
- chunks independientes;
- AEAD;
- AAD con identidad de archivo + índice + versión.

## Chunk

```text
ChunkRecord
- version
- fileId
- chunkIndex
- plaintextLength
- nonce
- ciphertext+tag
```

## Nonce

La regla crítica es **no reutilizar nonce con la misma key**.

Diseño simple y robusto:
- FileKey única por archivo;
- nonce aleatorio por chunk;
- nonce se almacena con chunk;
- generador CSPRNG del proveedor.

La alternativa nonce derivado por índice requiere una construcción cuidadosamente especificada; no implementarla improvisando.

## AAD

Ejemplo canónico:
`protocolVersion || fileId || chunkIndex || plaintextLength`.

La codificación debe ser binaria/canónica, no JSON ambiguo para bytes firmados.

## Tamaño de chunk

Configurable. Punto inicial:
- 1–4 MiB desktop/Wi‑Fi;
- menor si memoria móvil lo requiere.

El protocolo anuncia tamaño; no asumir igual en todas las transferencias.

## Integridad final

AEAD por chunk detecta corrupción local. Además guardar:
- hash del plaintext completo o árbol/manifest autenticado;
- tamaño exacto;
- cantidad de chunks.

El hash final sirve para confirmar reconstrucción, no reemplaza AEAD.

## Viewer

Descifrar bajo demanda. Para video grande:
- random access por chunk;
- cache temporal en RAM acotada;
- nunca exportar plaintext a storage salvo acción explícita.
