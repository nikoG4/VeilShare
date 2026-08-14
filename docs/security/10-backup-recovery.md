# Backup y recuperación

## Default MVP

No habilitar backups automáticos del sandbox hasta decidir explícitamente su modelo.

## Riesgo

Si OS/cloud backup copia ciphertext pero no las claves necesarias:
- restore puede ser inútil.

Si copia wrappers demasiado permisivos:
- puede reducir seguridad.

## Opción futura: backup exportable

Formato:
- vault bundle cifrado;
- recovery key de alta entropía;
- manifest versionado;
- checksum;
- sin PIN de baja entropía como única protección del bundle portátil.

## Transferencia a nuevo dispositivo

Opción segura:
- pairing entre dispositivo viejo y nuevo;
- canal E2E;
- rewrap de VaultKey;
- verificación QR.

## Pérdida del PIN

Si no hay recovery:
- no existe “forgot PIN” del servidor.
- el servidor no puede recuperar contenido.

Debe mostrarse en onboarding.
