# Identidad y contactos

## Identidad por instalación

Generar al first-run:
- signing keypair;
- key agreement keypair si suite usa claves separadas;
- device instance id aleatorio.

Public identity descriptor:

```text
version
signingPublicKey
agreementPublicKey
deviceCapabilities
fingerprint
```

Capabilities no debe revelar datos innecesarios.

## Fingerprint

Hash canónico de public identity descriptor.
Mostrar en grupos legibles.

## Contact

```kotlin
data class TrustedContact(
    val id: ContactId,
    val alias: String,
    val referenceCode: String,
    val identityFingerprint: Fingerprint,
    val publicIdentity: PublicIdentity,
    val verification: VerificationState
)
```

Todo salvo reference lookup temporal se guarda cifrado localmente.

## TOFU vs QR

Primera conexión por código:
- TOFU posible con warning;
- QR recomendado para verificación.

Luego:
- pin fingerprint;
- cualquier cambio bloquea hasta re-verificación.

## Multi-device

En MVP cada dispositivo es una identidad.
Un “usuario” puede guardar:
- Juan iPhone;
- Juan PC.

Cuenta multi-device futura requiere un root identity/account model separado.
