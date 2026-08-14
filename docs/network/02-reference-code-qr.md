# Código de referencia y QR

## Propósito

El código sirve para localizar un peer online.

No es:
- password;
- token secreto;
- prueba de identidad.

## Derivación recomendada

`referenceCode = Truncate(Base32(SHA-256(canonicalPublicIdentity)), N bits) + checksum`

N debe elegirse con margen suficiente. Preferencia: ~80 bits antes de checksum para minimizar colisiones/brute-force de locator.

Visual:
`M7QK-2P9D-V4TX-H8CN`

## Resolución sin DB

El cliente conectado registra:
- referenceCode;
- publicIdentity;
- proof of possession.

Servidor verifica que:
- code corresponde al public identity;
- challenge firmado es válido.

Luego mantiene:
`referenceCode -> websocket` en memoria.

## QR

QR contiene:
- protocol version;
- full public identity;
- full fingerprint;
- optional reference code.

Así el scan puede pinnear identidad sin confiar en resolución del servidor.

## Checksum

Detecta errores de tipeo.
No es MAC.
