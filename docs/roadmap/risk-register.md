# Risk register

| ID | Riesgo | Impacto | Mitigación |
|---|---|---|---|
| R1 | AdaptiveKt alpha cambia API | medio | wrapper ui-design |
| R2 | Argon2id KMP inconsistente | alto | provider contract + gate |
| R3 | WebRTC Desktop packaging | alto | transport abstraction |
| R4 | iOS background corta P2P | alto | chunk resume |
| R5 | Store rechaza apariencia engañosa | alto | distribución transparente |
| R6 | metadata leakage por logs | alto | redaction + checklist |
| R7 | nonce reuse bug | crítico | provider + tests |
| R8 | decoy implementado como UI fake | crítico concepto | dual vault ADR |
| R9 | flash deletion sobreprometida | medio | UX honesta |
| R10 | server abuse/TURN cost | medio-alto | quotas/rate limit |
