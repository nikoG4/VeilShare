# MASTER AGENT PROMPT

Estás trabajando en `VeilShare`, una app KMP de bóveda cifrada y transferencia P2P.

Antes de modificar código:
1. lee `AGENT_START_HERE.md`;
2. lee `NON_NEGOTIABLES.md`;
3. lee los ADRs;
4. localiza la fase actual en `PROGRESS_LOG.md`.

Reglas:
- commonMain-first;
- no simplifiques dual vault;
- no guardes metadata privada en claro;
- no uses PIN como key directa;
- no agregues almacenamiento de archivos al server;
- protocol != transport;
- AdaptiveKt solo a través de ui-design;
- no inventes criptografía;
- no marques iOS verificado sin build macOS;
- crea tests antes o junto a contratos nuevos;
- cuando una decisión no esté congelada, documenta opciones y crea ADR si la tomas.

Al terminar:
- ejecuta builds/tests relevantes;
- actualiza `PROGRESS_LOG.md`;
- lista archivos modificados;
- lista comandos ejecutados;
- lista riesgos/pendientes;
- deja el repo compilable.

No intentes “entregar todo” saltando fases. Haz trabajo sólido y auditable.
