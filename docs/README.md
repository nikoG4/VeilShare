# VeilShare — paquete de especificación para agente

> **Nombre interno:** `VeilShare` es solamente un codename de ingeniería. No es el nombre obligatorio de la app publicada ni del launcher.

Este ZIP define con detalle una aplicación multiplataforma para guardar y compartir archivos/multimedia de forma privada, con bóveda local cifrada, bóveda señuelo, apariencia discreta, contactos frecuentes y transferencias P2P. El objetivo arquitectónico es **Kotlin Multiplatform (KMP) + Compose Multiplatform + AdaptiveKt**, con la regla de compartir en `commonMain` todo lo que sea razonablemente portable y aislar solo las fronteras que dependen de APIs nativas.

## Qué debe hacer un agente con este paquete

1. Leer `AGENT_START_HERE.md`.
2. Leer `NON_NEGOTIABLES.md` antes de escribir código.
3. Leer `MASTER_SPEC.md`.
4. Leer `architecture/02-kmp-source-set-strategy.md` y `architecture/08-adaptivekt-integration.md`.
5. Leer todo `security/` antes de implementar cifrado o desbloqueo.
6. Leer todo `network/` antes de implementar contactos, señalización o transferencias.
7. Usar los ADRs como decisiones congeladas.
8. Mantener `roadmap/definition-of-done.md` como puerta de salida de cada fase.
9. Si una decisión técnica cambia, crear un ADR nuevo; no editar silenciosamente el concepto para “hacerlo más fácil”.

## Principio rector

```text
commonMain por defecto
        │
        ├── modelo
        ├── dominio
        ├── cifrado de archivos (API común / proveedor portable)
        ├── formato de bóveda
        ├── protocolo de transferencia
        ├── contactos
        ├── navegación/estado
        ├── UI Compose + AdaptiveKt
        └── casos de uso
             │
             ├── androidMain → Android Keystore, Photo Picker/SAF, alias de launcher,
             │                 biometría, ciclo de vida y background Android
             │
             ├── iosMain     → Keychain/Secure Enclave, Photos/Files, icono alternativo,
             │                 biometría y restricciones de background iOS
             │
             └── desktopMain → almacenamiento/secret store del SO, file picker,
                               integración de escritorio y transporte nativo/JVM
```

## Alcance del MVP

El MVP no es “una carpeta con PIN”. Debe incluir:

- onboarding;
- selección de apariencia discreta;
- PIN real;
- PIN señuelo;
- dos bóvedas criptográficamente independientes;
- archivos y metadatos cifrados;
- importación de fotos, videos y documentos;
- pregunta posterior para borrar el original cuando la plataforma lo permita;
- bloqueo automático;
- contactos por código de referencia y QR;
- favoritos/frecuentes;
- identidad criptográfica por instalación/dispositivo;
- mini servidor Ktor únicamente para presencia/señalización y credenciales TURN;
- transporte P2P preferido;
- cifrado de aplicación adicional al transporte;
- transferencias por chunks, reanudables y verificables;
- UI adaptable a teléfono, tablet y escritorio;
- implementación común hasta donde sea posible sin fingir que todas las plataformas son iguales.

## No objetivos

- No prometer anonimato de red.
- No prometer borrado forense seguro de memoria flash.
- No prometer protección si el SO/dispositivo está completamente comprometido.
- No prometer “negación plausible” criptográfica perfecta: la bóveda señuelo es un mecanismo práctico de coacción/privacidad, no una prueba de inexistencia de otra bóveda.
- No ocultar funcionalidades a App Review o Google Play Review.
- No imitar exactamente iconos/nombres de Apple, Google, Samsung u operadores.

## Estructura de documentación

- `architecture/` — modularidad, source sets, dependencias, estado y AdaptiveKt.
- `security/` — amenazas, claves, formato cifrado, PINes, borrado, recuperación.
- `network/` — identidad, códigos, señalización, P2P y protocolo de transferencia.
- `platforms/` — decisiones Android, iOS y Desktop.
- `ui/` — flujos de pantalla y responsive/adaptive.
- `server/` — mini servidor Ktor.
- `testing/` — pruebas contractuales, fault injection y gates.
- `adrs/` — decisiones arquitectónicas.
- `roadmap/` — fases y Definition of Done.
- `prompts/` — prompts listos para agentes.
- `schemas/` — contratos serializables.
- `examples/` — ejemplos conceptuales.
- `references/` — snapshot de fuentes oficiales verificadas.
