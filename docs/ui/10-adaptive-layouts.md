# Layouts adaptativos

## Compact (< breakpoint definido por toolkit/wrapper)
Vault:
- top app bar;
- grid 2–3 columns;
- bottom nav.

Transfer:
- step-by-step full screen.

## Medium
- navigation rail;
- gallery más densa;
- details drawer.

## Expanded
- sidebar fija;
- content grid/list;
- preview panel;
- transfer sidebar.

## Input modes

Desktop:
- mouse hover;
- context menu;
- keyboard shortcuts;
- drag/drop.

Mobile:
- long press;
- touch targets;
- back gestures.

## AdaptiveKt

La feature pide:
`VeilAdaptiveScaffold(windowClass, ...)`.
No consulta breakpoints directos de AdaptiveKt.
