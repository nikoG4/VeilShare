# Blueprint 19 — Workflow del desarrollador/agente

## Branch task

Cada cambio debe declarar:
- fase;
- docs relevantes;
- source sets tocados;
- security impact.

## Before commit

- format;
- tests;
- no secret grep patterns;
- dependency diff;
- docs.

## Commit examples

`feat(vault): add transactional encrypted import`
`feat(ios): bridge PhotoKit import handles`
`test(transfer): resume after receiver crash`
`docs(adr): select identity cipher suite`

## Agent checkpoint

Después de una tanda:
- actualizar progress;
- no dejar procesos dev server necesarios sin documentar;
- no borrar tests para hacer green.
