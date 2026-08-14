# Fuentes oficiales / snapshot técnico — 2026-08-09

Este archivo sirve para que el agente no dependa de recuerdos de modelo. Antes de fijar versiones, volver a verificar.

## Kotlin Multiplatform
- Hierarchical project structure:
  https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html
- Expected and actual declarations:
  https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html
- Platform-specific APIs:
  https://kotlinlang.org/docs/multiplatform/multiplatform-connect-to-apis.html
- Compose Multiplatform:
  https://kotlinlang.org/compose-multiplatform/
- Adaptive layouts:
  https://kotlinlang.org/docs/multiplatform/compose-adaptive-layouts.html

## AdaptiveKt
- Official repository:
  https://github.com/nikoG4/AdaptiveKt
- README:
  https://github.com/nikoG4/AdaptiveKt/blob/main/README.md

Snapshot observado:
- alpha-stage;
- published version indicada: `0.1.0-alpha01`;
- commonMain-first direction;
- iOS target declarado, validación depende de tooling/macOS según repo.

## Crypto KMP candidate
- cryptography-kotlin:
  https://github.com/whyoleg/cryptography-kotlin

El repo declara una API Kotlin Multiplatform que envuelve proveedores nativos como CryptoKit/JCA/OpenSSL/WebCrypto. Evaluar versión actual y algoritmos antes de congelar ADR.

## Argon2id
- RFC 9106:
  https://www.rfc-editor.org/info/rfc9106/

## HKDF
- RFC 5869:
  https://www.rfc-editor.org/info/rfc5869/

## X25519
- RFC 7748:
  https://www.rfc-editor.org/info/rfc7748/

## ChaCha20-Poly1305
- RFC 8439:
  https://www.rfc-editor.org/info/rfc8439/

## Android security
- Android Keystore:
  https://developer.android.com/privacy-and-security/keystore
- Cryptography:
  https://developer.android.com/privacy-and-security/cryptography

## Apple security
- Secure Enclave:
  https://developer.apple.com/documentation/security/protecting-keys-with-the-secure-enclave
- Alternate icons:
  https://developer.apple.com/documentation/uikit/uiapplication/setalternateiconname(_:completionhandler:)

## Store policy
- Apple App Review Guidelines:
  https://developer.apple.com/app-store/review/guidelines/
- Google Play Deceptive Behaviour:
  https://support.google.com/googleplay/android-developer/answer/17006354

### Política relevante
Ambas tiendas exigen transparencia y desaconsejan/impiden funcionalidades ocultas o no documentadas. “Discreto” debe ser una feature declarada, no una forma de evadir review.
