# Secret stores por plataforma

## Contrato

El SecretStore protege claves auxiliares, wrappers biométricos e identidad cuando sea posible.

## Android

Usar Android Keystore:
- claves no exportables cuando la API lo permite;
- autenticación de usuario opcional;
- hardware-backed/StrongBox como mejora cuando disponible, sin asumir universalidad.

No almacenar grandes blobs en Keystore.

## iOS

Usar:
- Keychain;
- LocalAuthentication;
- Secure Enclave para tipos de clave compatibles.

El Secure Enclave no es almacenamiento de archivos; protege claves/operaciones.

## Desktop

No existe una API uniforme:
- Windows: DPAPI/CNG/Credential Manager según diseño;
- macOS: Keychain;
- Linux: Secret Service/libsecret cuando esté disponible.

Fallback controlado:
- si no existe secret store, cifrar key material con credencial del usuario;
- mostrar nivel de seguridad;
- no guardar clave maestra en plaintext.

## Identidad

Ideal:
- private identity key no exportable donde sea viable;
- public key exportable;
- fingerprint estable.

## Portabilidad

Hardware-bound keys complican restore/migración. Por eso VaultKey tiene wrappers y el formato no debe depender exclusivamente de una clave imposible de exportar sin recovery plan.
