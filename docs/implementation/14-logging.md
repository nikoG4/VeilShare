# Blueprint 14 — Logging y diagnóstico

## Structured logger

Fields permitidos:
- event code;
- component;
- platform;
- duration bucket;
- opaque truncated ids;
- error code.

Fields prohibidos:
- PIN;
- key bytes;
- filename;
- full path;
- MIME si puede revelar;
- contact alias;
- public key completa;
- ICE payload;
- decrypted metadata.

## Debug builds

Debug no significa permiso para loguear secretos.
Usar fixtures/test ids.

## Crash reports

Si se integra servicio futuro:
- scrubber;
- no breadcrumbs con filenames;
- opt-in según política.

## Diagnostic export

Puede crear un bundle con:
- versions;
- platform capabilities;
- error codes;
- redacted state.

Nunca vault content.
