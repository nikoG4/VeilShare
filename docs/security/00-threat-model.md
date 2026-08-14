# Threat model

## Activos

- contenido de archivos;
- nombres y metadata;
- miniaturas;
- contactos;
- relación entre contactos;
- PIN real;
- PIN señuelo;
- VaultKeys;
- FileKeys;
- identidad privada;
- claves de sesión;
- historial de transferencias.

## Adversarios

### T1 — curioso casual con teléfono desbloqueado
Objetivo: no revelar de inmediato la existencia/contenido de la bóveda.

Mitigaciones:
- apariencia discreta;
- lock;
- vista de recientes protegida donde se pueda;
- sin nombres privados en notificaciones.

### T2 — persona que exige abrir la app
Mitigación práctica:
- PIN señuelo;
- bóveda señuelo funcional e independiente.

No se promete negar criptográficamente que exista otra estructura cifrada.

### T3 — ladrón con dispositivo bloqueado
Mitigaciones:
- sandbox;
- secret store;
- KDF;
- cifrado de blobs;
- auto-lock.

### T4 — atacante con copia del storage de app
Mitigaciones:
- Argon2id;
- VaultKey aleatoria;
- AEAD;
- metadata cifrada.

### T5 — servidor malicioso/comprometido
Debe poder:
- cortar servicio;
- observar metadata operativa mínima.

No debe poder:
- descifrar archivo;
- producir una transferencia autenticada como un contacto pinneado;
- aprender nombres de archivos.

### T6 — adversario de red
Mitigaciones:
- transporte seguro;
- app E2E;
- fingerprints.

No se promete ocultar IP si P2P directo.

### T7 — destinatario malicioso
Fuera de alcance:
una vez que el receptor descifra, puede copiar contenido. DRM no es objetivo.

### T8 — root/jailbreak/admin total + instrumentación
No se garantiza confidencialidad completa. Mitigaciones best-effort pueden elevar costo, pero no venderlo como invulnerable.

## No objetivos

- anti-forensics total;
- secure erase garantizado en flash;
- anonimato Tor;
- ocultar app a análisis de package/IPA;
- proteger contra malware con control de pantalla/teclado;
- prevenir foto externa de la pantalla.

## Riesgos de metadata

Aunque no haya archivo en servidor:
- IP;
- timestamps;
- volumen;
- tamaño aproximado;
- presencia;
- código de referencia consultado.

Reducir logs y padding puede ser feature futura.
