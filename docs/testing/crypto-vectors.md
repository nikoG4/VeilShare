# Crypto test vectors

## Fuentes

Usar vectors oficiales de cada algoritmo cuando disponibles y fixtures propios cross-platform.

## Fixtures del proyecto

`test-vectors/v1/`:
- kdf;
- hkdf;
- aead;
- key wrap;
- vault slot;
- chunk;
- signed session transcript.

## Golden fixture policy

Un cambio que modifica bytes canónicos:
- rompe compatibilidad;
- requiere protocol/format version bump;
- ADR.

## Negative vectors

- wrong key;
- wrong aad;
- truncated tag;
- corrupted nonce;
- swapped chunk index;
- altered version.
