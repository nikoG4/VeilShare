# Grafo de módulos recomendado

La granularidad debe permitir revisión de seguridad sin crear 40 módulos Gradle innecesarios.

```text
:shared:core-model
:shared:core-crypto
:shared:core-vault
:shared:core-identity
:shared:core-transfer
:shared:core-contacts
:shared:core-platform
:shared:ui-design
:shared:ui-features
:shared:app

:androidApp
:desktopApp
iosApp/          # wrapper Xcode/Swift mínimo

:server:signaling
```

## Responsabilidades

### `core-model`
- IDs;
- value objects;
- errores;
- serializers explícitos;
- versiones de formato.

No depende de UI, filesystem ni network.

### `core-crypto`
- contratos de AEAD/KDF/hash/key agreement;
- key hierarchy;
- wrapping;
- envelopes;
- test vectors.

No depende de UI.

### `core-vault`
- manifest;
- blob store;
- import/export pipelines;
- índices;
- folders/tags;
- lifecycle de objetos.

### `core-identity`
- device identity;
- fingerprint;
- reference code;
- verification state.

### `core-transfer`
- state machine;
- messages;
- chunking;
- resume;
- E2E session.

### `core-contacts`
- contactos frecuentes;
- pinning;
- identity-change alerts.

### `core-platform`
Contratos:
- secret store;
- file picker;
- original deletion;
- disguise;
- biometrics;
- screen protection;
- lifecycle;
- transport.

### `ui-design`
- wrapper de AdaptiveKt;
- tokens;
- componentes `VeilButton`, `VeilCard`, `VeilAdaptiveScaffold`, etc.

### `ui-features`
- screens;
- presenters/viewmodels;
- feature navigation.

### `shared:app`
- composición;
- DI;
- state root;
- wiring.

### apps
Solo entry points, manifiestos, recursos/entitlements, packaging y bridges nativos.

## Regla de dependencia

```text
ui-features ───────► domain/core
     │
     ▼
ui-design

core-vault ────────► core-crypto
core-transfer ─────► core-crypto
core-contacts ─────► core-identity
shared:app ────────► todos los contratos

platform implementations ─► core-platform contracts
```

`core-*` nunca depende de `ui-*`.
