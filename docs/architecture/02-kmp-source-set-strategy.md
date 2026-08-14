# Estrategia KMP y source sets

## Objetivo

Mantener `commonMain` tan grande como sea correcto, no tan grande como sea artificial.

## Targets

```kotlin
kotlin {
    androidTarget()
    jvm("desktop")

    iosX64()
    iosArm64()
    iosSimulatorArm64()
}
```

La configuración exacta dependerá de versiones actuales de Kotlin/Compose/AGP, pero conceptualmente los targets son esos.

## Jerarquía

```text
commonMain
├── core model
├── domain/use cases
├── protocol
├── vault format
├── crypto API + portable primitives
├── persistence contracts
├── navigation/state
├── Compose UI
└── AdaptiveKt wrapper

androidMain
├── Android Keystore
├── BiometricPrompt
├── Photo Picker / SAF
├── launcher aliases
├── window/screenshot policy
├── Android lifecycle/background
└── WebRTC adapter Android

iosMain
├── Keychain / Secure Enclave
├── LocalAuthentication
├── Photos / document picker bridge
├── alternate icon bridge
├── iOS lifecycle/background handling
└── WebRTC adapter iOS

desktopMain
├── JVM filesystem
├── per-OS secret store bridge
├── desktop file picker
├── desktop window privacy best-effort
└── WebRTC/native transport adapter
```

## `mobileMain`

No crearlo por reflejo. Solo introducirlo si existe código real compartido Android+iOS que:
- no pertenece a desktop;
- usa dependencias disponibles en ambas plataformas;
- reduce duplicación significativa.

Ejemplo aceptable:
- lógica común de permisos representada sin tipos nativos.

Ejemplo no aceptable:
- poner interfaces vacías solo para “tener mobileMain”.

## `expect/actual` vs interfaces

Preferencia:
1. interfaz común + DI cuando la capacidad tiene estado, múltiples implementaciones o mocking;
2. `expect/actual` para funciones/valores pequeños, puros o de bootstrap.

### Buen candidato a interfaz
`PlatformFilePicker`, `SecretStore`, `PeerTransport`, `DisguiseController`.

### Buen candidato a expect/actual
`expect fun platformId(): PlatformId`
`expect fun secureRandomBytes(size: Int): ByteArray` solo si el proveedor cripto no lo resuelve de forma portable.

## Regla de contaminación

Ningún tipo de plataforma puede atravesar la frontera:

| Tipo nativo | Conversión antes de commonMain |
|---|---|
| Android `Uri` | `ImportHandle` |
| iOS `NSURL` | `ImportHandle` |
| JVM `Path` | `ImportHandle` |
| Android `Context` | capability inyectada |
| `UIViewController` | bridge interno iOS |
| WebRTC peer object | `PeerTransport` |

## commonTest

Debe contener:
- serialización;
- vault manifest;
- chunk frame;
- key hierarchy orchestration usando fake crypto;
- contact pinning;
- state machines;
- reanudación;
- migrations.

## Validación de jerarquía

Un PR que mueva lógica a `androidMain`/`iosMain`/`desktopMain` debe responder:

1. ¿Qué API impide que sea común?
2. ¿Podemos abstraer esa API y dejar el comportamiento en common?
3. ¿Hay un source set intermedio útil?
4. ¿Existe test común que garantice comportamiento equivalente?
