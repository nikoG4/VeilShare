# Jerarquía de claves

## Objetivo

Separar:
- secreto humano (PIN);
- secreto de bóveda;
- secreto por archivo;
- identidad;
- sesión de transferencia.

## Esquema

```text
PIN
 │
 ├─ salt aleatorio + parámetros KDF
 ▼
KEK = Argon2id(PIN, salt, params)
 │
 └── AEAD unwrap
        ▼
     VaultKey (VK) 256-bit aleatoria
        │
        ├─ deriva ManifestKey vía HKDF
        ├─ deriva IndexKey vía HKDF
        └─ envuelve FileKeys individuales

File
 └─ FileKey (FK) 256-bit aleatoria
       └─ AEAD chunks
```

## Por qué FileKey por archivo

- rotación granular;
- compartir sin usar VaultKey;
- borrar archivo eliminando manifest/wrapped key;
- reduce impacto de errores de nonce entre archivos.

## Biometría

No reemplaza PIN.

Opción:
- `VaultKey` tiene un segundo wrapper bajo un `BiometricWrappingKey` protegido por SecretStore;
- biometría solo desbloquea la bóveda real por política;
- desactivar biometría elimina ese wrapper.

## Recovery

Si se implementa:
- recovery secret de alta entropía;
- otro wrapper de VaultKey;
- no usar el mismo PIN;
- exportación consciente.

## Separación real/señuelo

Cada vault tiene:
- VaultKey distinta;
- salt KDF distinto;
- manifest distinto;
- biometric wrapper solo si corresponde.

## Domain separation

HKDF `info` explícito:
- `veilshare:v1:manifest`
- `veilshare:v1:index`
- `veilshare:v1:file-wrap`
- `veilshare:v1:transfer-session`

Nunca reutilizar salida sin `info` específico.
