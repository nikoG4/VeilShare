# Borrado de originales

## UX

Después de importación verificada:

```text
Archivo guardado de forma segura.

¿Eliminar el original?
[Eliminar original] [Conservar]
```

## Android

El borrado depende del origen/URI/permisos. La app solicita la operación adecuada y reporta resultado.

## iOS

Fotos pueden gestionarse mediante Photos con autorización adecuada. Archivos de providers externos pueden no permitir eliminación.

## Desktop

Si el usuario seleccionó un archivo local con permisos, se puede intentar delete.

## Lo que NO se promete

En flash/SSD:
- wear leveling;
- snapshots;
- backups;
- filesystem journaling;
- cloud sync;

pueden conservar copias físicas o remotas.

Texto correcto:
“Se eliminó el original según el sistema.”

Texto incorrecto:
“Se borró de forma irrecuperable.”

## Borrar de la bóveda

Eliminar:
1. referencia en índice;
2. wrapped FileKey;
3. blobs;
4. thumbnail;
5. journal.

La eliminación de la FileKey produce crypto-erasure lógico del contenido restante, aunque bytes físicos persistan.
