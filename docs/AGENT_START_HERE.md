# AGENT START HERE

## Misión

Implementar la aplicación descrita en este repositorio **sin reducir el concepto a una “bóveda simple”**.

La optimización principal no es “menos archivos” ni “menos módulos”. La optimización correcta es:

1. máxima reutilización segura en `commonMain`;
2. fronteras nativas pequeñas y explícitas;
3. seguridad verificable;
4. protocolo multiplataforma estable;
5. capacidad de reemplazar detalles de plataforma sin reescribir dominio/UI.

## Orden de lectura obligatorio

1. `NON_NEGOTIABLES.md`
2. `MASTER_SPEC.md`
3. `architecture/01-principles.md`
4. `architecture/02-kmp-source-set-strategy.md`
5. `architecture/03-module-graph.md`
6. `architecture/08-adaptivekt-integration.md`
7. `security/00-threat-model.md`
8. `security/01-key-hierarchy.md`
9. `security/03-real-decoy-vaults.md`
10. `network/04-p2p-transport-abstraction.md`
11. `network/05-transfer-protocol.md`
12. `platforms/cross-platform-capability-matrix.md`
13. `roadmap/phases.md`

## Reglas de ejecución

### Antes de programar
- Generar un inventario de módulos y source sets.
- Anotar librerías/versiones seleccionadas.
- Confirmar que cada dependencia existe para los targets que se declaran.
- Crear tests de contrato antes de los `actual`.
- No introducir una dependencia nativa en `commonMain`.

### Durante la implementación
- Mantener APIs de plataforma estrechas.
- No filtrar `Context`, `UIViewController`, `NSURL`, `java.io.File`, `Path`, etc. hacia dominio.
- Usar identificadores y streams/abstracciones propias.
- Nunca loguear PIN, claves, nombres privados de archivos, contenido, miniaturas ni bytes de archivo.
- No convertir PIN en clave con `SHA-256(pin)`.
- No guardar nombres/MIME/rutas privadas en texto plano.
- No usar el PIN señuelo como un `if` que simplemente cambia la lista visible.

### Al cerrar una fase
- Ejecutar tests en todos los targets disponibles.
- Actualizar `PROGRESS_LOG.md`.
- Dejar riesgos y limitaciones explícitos.
- Si iOS no puede validarse por falta de macOS, marcarlo `UNVERIFIED`, nunca `DONE`.

## Qué significa “commonMain-first”

No significa forzar APIs nativas a través de trucos. Significa:

- modelos, contratos, formatos y reglas en `commonMain`;
- implementación común cuando existe un proveedor realmente multiplataforma;
- `expect/actual` o interfaces inyectadas cuando una capacidad es nativa;
- source set intermedio solo si comparte una implementación real entre varios targets;
- evitar duplicación de comportamiento aunque haya duplicación inevitable de integración.

## Primera tarea sugerida

Crear el esqueleto Gradle/KMP, todos los contratos y tests de serialización/formato, pero **sin implementar todavía criptografía real**. El primer hito debe compilar Android, Desktop y el framework iOS y demostrar que las fronteras de plataforma están bien dibujadas.
