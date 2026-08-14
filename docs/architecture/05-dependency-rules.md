# Reglas de dependencias

## Reglas duras

1. Ninguna dependencia criptográfica nueva sin ADR.
2. Ninguna dependencia alpha/beta en core sin wrapper.
3. AdaptiveKt solo entra por `ui-design`.
4. Las librerías P2P no definen el protocolo.
5. Ktor server no comparte entidades persistence con el cliente.
6. No usar librerías de “encrypted shared preferences” como almacenamiento de archivos.
7. No usar base64 como “cifrado”.

## Version catalog

Centralizar:

```toml
[versions]
kotlin = "..."
compose = "..."
ktor = "..."
adaptiveKt = "0.1.0-alpha01"
cryptography = "..."

[libraries]
adaptive-core = { ... }
adaptive-components = { ... }
```

No fijar aquí versiones no verificadas en 2026 salvo AdaptiveKt snapshot documentado. Antes de iniciar Phase 0 el agente debe confirmar las versiones actuales compatibles.

## Dependencias permitidas por zona

### commonMain
- kotlinx.coroutines
- kotlinx.serialization
- Compose Multiplatform
- AdaptiveKt via wrapper module
- Ktor client solo si aplica a signaling
- crypto KMP seleccionada tras ADR

### platform source sets
- APIs nativas;
- bindings de WebRTC;
- Argon2/provider;
- secret store bridges.

## Supply chain

- lockfiles/verification metadata si el build lo soporta;
- checksum/Gradle dependency verification;
- revisar CVEs/avisos antes de release;
- no descargar binarios nativos en runtime.
