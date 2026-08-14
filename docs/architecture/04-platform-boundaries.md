# Fronteras de plataforma

## Capability model

Toda divergencia debe expresarse como capability.

```kotlin
interface PlatformCapabilities {
    val secretStore: SecretStore
    val importPicker: ImportPicker
    val originalDeletion: OriginalDeletion
    val biometricGate: BiometricGate
    val disguise: DisguiseController
    val screenPrivacy: ScreenPrivacy
    val lifecycle: AppLifecycle
    val peerTransportFactory: PeerTransportFactory
}
```

No es obligatorio tener una mega-interfaz en código; sirve como mapa.

## Fronteras inevitables

| Capacidad | Common | Android | iOS | Desktop |
|---|---|---|---|---|
| Cifrado de formato | sí | proveedor | proveedor | proveedor |
| KDF contract | sí | impl | impl | impl |
| Secret store | API | Keystore | Keychain/SE | SO/JVM |
| Picker | API/flow | Photo Picker/SAF | Photos/Files | chooser |
| Borrar original | policy | API Android | PhotoKit/provider | filesystem |
| Biometría | policy | BiometricPrompt | LocalAuthentication | opcional OS |
| Disfraz | model | aliases | alt icon | shortcuts |
| P2P protocol | sí | transport | transport | transport |
| Background | state machine | impl | impl limitada | impl |

## Error taxonomy común

No propagar excepciones nativas sin mapear:

```kotlin
sealed interface PlatformFailure {
    data object PermissionDenied
    data object UserCancelled
    data object Unsupported
    data object TemporarilyUnavailable
    data class Io(val code: String) : PlatformFailure
    data class Security(val code: String) : PlatformFailure
}
```

El `cause` nativo puede permanecer local para diagnóstico seguro, pero nunca contener secretos en logs.
