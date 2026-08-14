# Principios arquitectónicos

## A1. Comportamiento compartido, integración nativa

Una capability debe tener:

```kotlin
// commonMain
interface SecretStore {
    suspend fun createWrappingKey(alias: SecretAlias, policy: AccessPolicy): SecretHandle
    suspend fun encrypt(handle: SecretHandle, plaintext: ByteArray, aad: ByteArray): SealedBytes
    suspend fun decrypt(handle: SecretHandle, ciphertext: SealedBytes, aad: ByteArray): ByteArray
}
```

y no:

```kotlin
// MAL
expect fun androidOrIosKeyStoreThing(context: Any): Any
```

El dominio no sabe qué es Keychain/Keystore.

## A2. Los formatos son estables

Los blobs cifrados y mensajes P2P llevan versión explícita:
- `vaultFormatVersion`;
- `fileFormatVersion`;
- `protocolVersion`.

Nunca inferir versión “por longitud”.

## A3. Seguridad por capas

1. permisos mínimos;
2. aislamiento del sandbox;
3. secret store del SO;
4. KDF;
5. key hierarchy;
6. AEAD;
7. bloqueo de sesión;
8. E2E app-level;
9. transporte cifrado;
10. verificación de identidad.

## A4. No plaintext temporal por comodidad

Prohibido:
- descifrar todo a `/tmp`;
- crear miniaturas públicas;
- copiar a cache externa y luego procesar.

Usar stream:
`encrypted input -> decrypt chunk -> viewer/decoder` cuando sea viable.

## A5. Protocol vs transport

`TransferProtocol` no importa WebRTC.

```text
TransferProtocol
  └─ PeerTransport
       ├─ WebRTC Android
       ├─ WebRTC iOS
       └─ WebRTC/other Desktop
```

## A6. UI compartida, capacidad nativa inyectada

Compose/AdaptiveKt recibe capabilities:
- picker;
- share/save;
- biometric;
- disguise;
- screen security;
- lifecycle.

No contiene `if (Platform.Android)` disperso.

## A7. Fail closed

Si:
- tag AEAD falla;
- manifest no autentica;
- fingerprint cambió;
- chunk final no coincide;
- secret store falla;

la acción se detiene. Nunca “intentar abrir igualmente”.

## A8. Auditabilidad

El código de seguridad debe ser aburrido:
- tipos explícitos;
- cero magia;
- pocos algoritmos;
- test vectors;
- no reflexiones;
- no serialización implícita inestable para material firmado.
