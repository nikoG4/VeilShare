# PIN y KDF

## Requisito

Argon2id es la preferencia de protocolo para convertir PIN/password en KEK.

## Parámetros

No hardcodear parámetros de ejemplo sin benchmark.

En first-run:
- seleccionar perfil de memoria/tiempo aprobado;
- medir en dispositivo;
- almacenar parámetros por slot;
- objetivo: costo perceptible pero aceptable;
- mantener mínimo de seguridad.

## PIN corto

Un PIN de 4-6 dígitos tiene poca entropía. Argon2id eleva el costo pero no crea entropía.

Recomendación UX:
- permitir PIN largo/password;
- advertir sobre PIN muy corto;
- default 6+ dígitos si se usa modo numérico.

## Rate limiting local

Puede ayudar contra intento interactivo, pero un atacante con ciphertext offline puede ignorarlo. No venderlo como defensa principal.

## Normalización

Si password alfanumérico:
- definir Unicode normalization;
- congelarla en protocolo;
- tests cross-platform.

Si PIN numérico:
- almacenar como caracteres exactos;
- no parsear a Int (preserva ceros iniciales).

## Implementación

`PasswordKdf` en commonMain:

```kotlin
interface PasswordKdf {
    suspend fun derive(
        secret: SensitiveChars,
        salt: ByteArray,
        params: KdfParams,
        outputBytes: Int
    ): SensitiveBytes
}
```

La implementación puede ser específica si no hay proveedor KMP auditado.

## Prohibido

- SHA-256(PIN);
- MD5;
- cifrar el PIN;
- guardar hash rápido del PIN para “validarlo antes”;
- loguear tiempo/datos que permitan distinguir slots.
