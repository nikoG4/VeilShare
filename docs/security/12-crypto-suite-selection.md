# Selección de suite criptográfica

## Qué se congela y qué no

Congelado:
- usar primitivas estándar;
- AEAD;
- KDF memory-hard para PIN;
- HKDF/domain separation;
- identity pinning;
- app E2E.

Pendiente:
- P-256 vs X25519/Ed25519;
- AES-GCM vs ChaCha20-Poly1305 para ciertas capas.

## Preferencia para archivos

AES-256-GCM es candidato fuerte por soporte de plataforma/proveedores. ChaCha20-Poly1305 también es válido si el provider seleccionado es más uniforme.

No mezclar algoritmos por target si los archivos deben ser interoperables.

## Hardware-backed tradeoff

P-256 puede integrarse mejor con Secure Enclave/Keystore en algunos escenarios.
X25519/Ed25519 tiene excelente diseño protocolario pero puede exigir claves software-wrapped.

La decisión se toma con una matriz:
- Android min SDK;
- iOS;
- JVM Windows/macOS/Linux;
- proveedor KMP;
- hardware key support;
- test vectors;
- performance.

## Regla

Una vez publicado `protocolVersion=1`, no cambiar suite en silencio. Negociación de versiones explícita para v2.
