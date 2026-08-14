# iOS implementation guide

## UI host

Compose Multiplatform compartido dentro del host iOS. Swift/SwiftUI/UIViewController mínimo para bridges que realmente lo necesiten.

## Secret store

- Keychain;
- Secure Enclave para claves compatibles;
- LocalAuthentication.

No exigir Secure Enclave para una primitiva que no soporte: usar wrapper architecture.

## Import

- Photos para media;
- document picker / security-scoped resources para Files/providers.

Convertir NSURL/asset a `ImportHandle` antes de common.

## Delete original

- Photos: operación de borrado con permisos/confirmación del sistema cuando corresponda;
- provider files: capability puede responder Unsupported.

## Icono discreto

UIKit soporta alternate app icons.
Diseñar un nombre de bundle/display neutro de publicación y no depender de cambiarlo dinámicamente.

## App Review

La funcionalidad de privacidad/discreción debe estar:
- documentada;
- accesible al reviewer;
- explicada en Notes for Review.

No incluir switches ocultos para evadir review.

## Background

iOS puede suspender una app. Por eso:
- protocolo reanudable;
- checkpoint frecuente;
- no afirmar que un DataChannel seguirá horas con app suspendida;
- receiver debe commit cada chunk durable antes del ACK.

## Screen capture

Implementar detección/cobertura best-effort; no prometer bloqueo universal de captura.
