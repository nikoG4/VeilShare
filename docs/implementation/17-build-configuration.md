# Blueprint 17 — Build configuration

## Flavors/modes

No crear “review flavor” que oculta funciones.
Sí se permiten:
- debug;
- release;
- internal;
con diferencias de endpoints/logging, no de concepto oculto.

## Config

Common:
- protocol versions;
- chunk defaults;
- timeouts.

Platform:
- bundle/package ids;
- entitlements;
- signing.

Secrets:
- server endpoint is not secret;
- TURN shared secret only server-side;
- no private user keys in build config.

## Reproducibility

Use Gradle wrapper.
Document JDK/Xcode/Android SDK minimums once verified.
