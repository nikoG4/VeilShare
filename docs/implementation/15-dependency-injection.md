# Blueprint 15 — Dependency injection

## Goal

Common domain testable sin framework DI obligatorio.

## AppEnvironment

```kotlin
data class AppEnvironment(
    val crypto: CryptoSuite,
    val platform: PlatformCapabilities,
    val vaultRepository: VaultRepository,
    val signaling: SignalingClient,
    ...
)
```

Puede usarse Koin u otro DI si es KMP y justificado, pero core constructors siguen explícitos.

## Scope

- App scope: provider/config.
- VaultSession scope: unlocked secrets/repositories.
- Transfer scope: secure channel/state.
- Screen scope: presenter/viewmodel.

Nunca singleton global de `VaultKey`.
