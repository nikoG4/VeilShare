# Accesibilidad vs privacidad

## Objetivo

No romper lectores de pantalla arbitrariamente, pero no filtrar secretos desde locked state.

## Rules

- PIN fields secure;
- content descriptions de blobs privados solo cuando vault unlocked;
- disguise surface accesible como utilidad;
- warnings de identity change claros;
- focus traversal correcto en desktop/iOS.

## Risk

Accessibility services maliciosos en un dispositivo comprometido están fuera del threat model fuerte. No intentar deshabilitar accesibilidad globalmente.
