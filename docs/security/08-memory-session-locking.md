# Memoria, sesión y bloqueo

## Sensitive types

Crear wrappers:
- `SensitiveBytes`;
- `SensitiveChars`;
- `VaultSession`.

Objetivo: reducir copias accidentales y evitar `toString()` útil.

```kotlin
class SensitiveBytes(...) : Closeable {
    override fun toString() = "SensitiveBytes(REDACTED)"
}
```

## JVM caveat

Zeroization best-effort:
- sobrescribir ByteArray;
- evitar String para PIN;
- minimizar copies;
- GC/JIT pueden impedir garantía absoluta.

Documentarlo.

## Session cache

Solo contiene:
- VaultKey/derived keys necesarias;
- índices descifrados;
- thumbnails RAM.

Se destruye al lock.

## Logs

Logger común con redaction:
- objetos sensibles no serializables;
- IDs opacos truncados si hace falta diagnóstico;
- release logs mínimos.

## Clipboard

No copiar PIN/keys.
Si usuario copia un código de referencia público, okay.
No copiar nombres privados sin acción explícita.

## Screenshots

Capability:
- Android: secure window cuando corresponda;
- iOS: detectar capture y cubrir contenido best-effort;
- Desktop: best-effort, sin prometer bloqueo universal.
