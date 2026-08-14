# Requisitos no negociables

Este documento existe específicamente para evitar que un agente “simplifique” el producto hasta perder las propiedades que lo definen.

## 1. Bóveda real y bóveda señuelo

**NO** implementar:

```kotlin
if (pin == decoyPin) showFakeFiles() else showRealFiles()
```

Debe existir separación criptográfica:
- salt distinto;
- material de claves distinto;
- descriptor/manifest distinto;
- índices distintos;
- archivos cifrados independientes;
- ninguna etiqueta en claro `REAL`/`DECOY`.

## 2. Todo contenido privado queda cifrado en reposo

Incluye:
- bytes de archivos;
- nombre original;
- extensión;
- MIME;
- fecha importada si se guarda;
- carpetas/tags;
- favoritos;
- miniaturas;
- notas;
- metadatos de transferencia que revelen contenido.

Se acepta en claro únicamente metadata operativa mínima que no revele contenido y esté justificada en un ADR.

## 3. PIN ≠ clave

El PIN alimenta una KDF resistente a fuerza bruta. La clave maestra de bóveda es aleatoria. El PIN únicamente protege/desbloquea material de clave.

## 4. No almacenar archivos en el servidor por defecto

El servidor:
- presencia online;
- negociación/señalización;
- entrega de mensajes efímeros;
- credenciales TURN de corta duración;
- rate limiting.

No:
- uploads;
- galería;
- thumbnails;
- backups;
- cola de archivos offline.

Una modalidad de relay temporal con almacenamiento sería una **feature futura y separada**, nunca una simplificación silenciosa del MVP.

## 5. Cifrado de aplicación aunque el transporte sea cifrado

WebRTC/TLS no reemplaza el cifrado E2E de la capa de aplicación.

## 6. Transferencias por chunks

No leer un archivo completo a RAM. Debe haber:
- chunking;
- hash/AEAD por chunk;
- progreso;
- ACK;
- reanudación;
- cancelación;
- integridad final.

## 7. Identidad y contactos

- cada instalación genera identidad;
- el código corto es un locator, no una autenticación;
- contactos frecuentes pinnean el fingerprint/clave completa;
- cambio de identidad produce advertencia explícita;
- QR permite verificación fuerte.

## 8. KMP real

`commonMain` debe contener la mayor parte del producto. Prohibido crear tres apps independientes que “comparten ideas”.

## 9. AdaptiveKt protegido por wrapper

No llenar features con imports directos de AdaptiveKt. La app expone `Veil*`/`App*` components propios. Ver `architecture/08-adaptivekt-integration.md`.

## 10. Distribución honesta

“Discreto” no significa “engañar al store reviewer”. Las funciones privadas/camufladas deben estar documentadas para revisión de tienda. No copiar identidad visual exacta de apps del sistema.

## 11. Borrado honesto

“Eliminar original” significa solicitar borrado mediante API de la plataforma. No afirmar “borrado irrecuperable” en flash/SSD.

## 12. No telemetría sensible

Por defecto:
- sin analytics de nombres;
- sin crashes con datos privados;
- sin screenshots automáticos;
- sin remote logging de excepciones que puedan contener paths/metadata privada.

## 13. Sin claves de servidor capaces de descifrar

El servidor nunca recibe:
- PIN;
- vault master key;
- file key en claro;
- session E2E key;
- recovery secret.

## 14. No degradar criptografía silenciosamente

Si Argon2id o el proveedor elegido no puede implementarse con calidad en un target:
- bloquear release de ese target;
- o crear ADR explícito para una alternativa equivalente.

No hacer fallback silencioso a SHA/PBKDF débil solo para “hacer compilar”.

## 15. Ningún “DONE” sin prueba

Una capacidad solo está `DONE` cuando cumple `roadmap/definition-of-done.md`.
