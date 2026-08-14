# ADR-006 — Crypto provider boundary

Status: Accepted

## Decision
El core usa contratos/abstracciones y una librería KMP revisada donde sea adecuada. Secret stores y primitivas faltantes se implementan por plataforma.

## Guardrail
No implementar primitivas criptográficas propias.

## Candidate
`cryptography-kotlin` es un candidato por exponer una API KMP sobre proveedores nativos; la selección/versionado final se verifica en Phase 0.
