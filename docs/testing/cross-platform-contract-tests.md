# Cross-platform contract tests

## Crypto
Para mismos inputs:
- serializer bytes iguales;
- hashes iguales;
- AEAD decrypt de fixture cruzado;
- HKDF outputs iguales.

## Vault
Fixture creado en JVM:
- abierto en Android/iOS test runner y viceversa.

## Transfer
Golden frames:
- encode/decode igual en todos;
- unknown fields/version handling.

## Reference code
Misma public identity → mismo code/checksum.

## Time
No firmar/hashear strings de fecha localizados.
Usar epoch/integers si se requiere en transcript.
