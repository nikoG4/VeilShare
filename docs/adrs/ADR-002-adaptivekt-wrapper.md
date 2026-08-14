# ADR-002 — AdaptiveKt detrás de wrapper

Status: Accepted

## Context
AdaptiveKt está en alpha en el snapshot inicial.

## Decision
Solo `ui-design` importa AdaptiveKt. Features importan componentes propios.

## Consequences
+ migración fácil;
+ diseño consistente;
- pequeña capa extra.
