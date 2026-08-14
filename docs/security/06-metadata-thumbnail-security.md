# Metadata y thumbnails

## Qué es sensible

- filename;
- extension;
- MIME;
- EXIF;
- dimensions;
- duration;
- thumbnails;
- folder;
- tags;
- sender;
- receiver;
- note;
- created/imported dates.

## Principio

El filesystem en reposo debe parecer blobs opacos.

## Thumbnails

Pipeline:
1. leer chunks cifrados;
2. descifrar región/necesario;
3. generar thumbnail;
4. cifrar thumbnail con key derivada o clave propia;
5. persistir únicamente ciphertext.

Cache RAM:
- LRU limitado;
- limpiar al lock.

## EXIF

Decisión por producto:
- preservar dentro del manifest cifrado;
- opción “strip metadata al exportar/compartir”.

Nunca extraer EXIF y guardarlo en DB en claro.

## Search

Índice de búsqueda:
- mantener descifrado solo durante sesión;
- o cifrar tokens/índice.

No construir un índice del SO con nombres privados.
