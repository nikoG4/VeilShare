# ADR-001 — KMP commonMain-first

Status: Accepted

## Decision
Toda lógica de producto portable vive en commonMain. Source sets de plataforma se limitan a integraciones inevitables.

## Consequences
+ máxima consistencia;
+ tests comunes;
+ menos divergencia.
- requiere diseñar buenas capabilities;
- puede necesitar wrappers.

## Rejected
Tres codebases nativas coordinadas manualmente.
