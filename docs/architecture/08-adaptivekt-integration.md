# Integración de AdaptiveKt

## Estado de la dependencia

Snapshot verificado en agosto de 2026:
- AdaptiveKt se declara alpha;
- versión publicada indicada: `0.1.0-alpha01`;
- enfoque commonMain-first;
- módulos de core, components, layout, feedback, navigation, forms y data.

Por estar alpha, **no** acoplar las features directamente a su API.

## Wrapper obligatorio

```text
AdaptiveKt
    │
    ▼
:shared:ui-design
    │
    ├─ VeilApp
    ├─ VeilScaffold
    ├─ VeilAdaptivePane
    ├─ VeilButton
    ├─ VeilCard
    ├─ VeilField
    ├─ VeilDialog
    ├─ VeilLoading
    ├─ VeilEmpty
    ├─ VeilError
    └─ VeilNavigation
         │
         ▼
    feature screens
```

## Por qué

Si AdaptiveKt cambia:
- package;
- parámetros;
- breakpoint API;
- component names;

solo cambia `ui-design`.

## Uso inicial sugerido

### Sí
- breakpoints;
- responsive container/grid;
- navigation mode;
- cards/surfaces;
- feedback states;
- forms;
- tokens.

### No delegar
- seguridad;
- unlock logic;
- file viewer internals;
- cryptographic status;
- transfer protocol;
- platform pickers.

## Breakpoints semánticos

No diseñar por “Android/iOS/Desktop”, sino por ancho/capacidad:

```text
Compact
- una columna
- bottom navigation o navegación mínima
- lista OR detalle

Medium
- navegación rail
- lista + preview según espacio

Expanded
- sidebar
- 2/3 panes
- drag/drop en desktop
```

## Security surface

El diseño discreto puede necesitar componentes fuera de AdaptiveKt para parecer una utilidad simple. Aun así, esa pantalla vive en common UI cuando sea posible; la plataforma solo controla icono/launcher.

## Test de aislamiento

Añadir una regla estática/convención:
- `feature` no puede importar `io.github.adaptivekt.*`;
- solo `ui-design` puede hacerlo.
