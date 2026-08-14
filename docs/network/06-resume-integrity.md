# Reanudación e integridad

## Estado persistido

Sender:
- transferId;
- peer fingerprint;
- files;
- accepted status;
- ACK ranges/bitmap;
- source vault ids.

Receiver:
- transferId;
- sender fingerprint;
- received durable ranges;
- target temp manifest;
- expected totals.

Estado sensible cifrado.

## Resume

1. reconectar;
2. autenticar peer;
3. comprobar mismo fingerprint;
4. `RESUME_REQUEST`;
5. receiver responde ranges;
6. sender reenvía faltantes;
7. complete solo tras verificación final.

## Bitmap vs ranges

Para archivos enormes:
- ranges comprimidos son mejores si pérdida es contigua;
- bitmap puede crecer.

Implementar una representación abstracta `ChunkSet` con encoding versionado.

## Integridad final

Receiver:
- todos los chunks presentes;
- todos tags validaron;
- size coincide;
- final digest/manifest coincide;
- commit atómico del archivo a vault.

Antes de eso aparece como “recibiendo”, no como archivo final.
