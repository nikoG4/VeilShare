# Security test plan

## S1 — wrong PIN
- no desbloquea;
- no corrompe slots;
- timing comparable.

## S2 — decoy isolation
- FileKey de real no abre decoy;
- índices no se cruzan;
- importación a decoy no modifica real.

## S3 — tamper
Alterar:
- header;
- nonce;
- ciphertext;
- tag;
- manifest;
- chunk index.

Resultado: fail closed.

## S4 — nonce uniqueness
Test estadístico + property test por millones de chunks simulados/derivación.

## S5 — key rotation/wrapping
- cambio de PIN rewrap VaultKey;
- no recifra todos los GB de archivos;
- viejo PIN deja de funcionar.

## S6 — lock
- cache vacío;
- viewer cerrado;
- transfer state seguro;
- claves no accesibles desde object graph previsto.

## S7 — server compromise simulation
Con capturas de todo signaling:
- no reconstruir FileKey;
- no obtener filename;
- no falsificar contacto pinneado sin private key.

## S8 — identity change
Servidor devuelve otra clave para misma referencia/contact alias:
- warning;
- envío bloqueado.

## S9 — path traversal
Filenames recibidos nunca se usan como path local.

## S10 — fuzzing
- parsers de frames;
- manifests;
- resume bitmaps;
- malformed lengths.
