# Blueprint 04 — Unlock engine

## API common

```kotlin
interface UnlockEngine {
    suspend fun unlock(credential: UnlockCredential): UnlockResult
}
```

## Steps

1. cargar slot headers;
2. validar versiones y límites de KDF antes de reservar memoria;
3. derivar candidate KEK;
4. intentar unwrap autenticado;
5. descifrar descriptor;
6. validar descriptor;
7. abrir encrypted index;
8. crear `VaultSession`;
9. limpiar KEK temporal.

## KDF DoS guard

Un archivo manipulado podría declarar 100 GB de Argon memory.
Definir límites máximos aceptables por versión.

## Multiple slots

No retornar cuál slot falló.
No short-circuit de forma que filtre rol si se evalúan múltiples candidatos.

## UnlockResult

- `Success(session)`;
- `InvalidCredential`;
- `CorruptedStorage`;
- `UnsupportedFormat`;
- `ResourceFailure`.

No usar `Exception("wrong real pin")`.

## Acceptance

- malformed headers;
- absurd KDF params;
- corrupted wrapper;
- real/decoy success;
- sensitive buffers closed.
