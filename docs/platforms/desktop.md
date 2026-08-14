# Desktop JVM implementation guide

## Targets

Compose Desktop sobre JVM:
- Windows;
- macOS;
- Linux.

## Storage

Vault root en directorio privado apropiado por SO.
Permisos filesystem restrictivos best-effort.

## Secret store

Bridge por OS:
- Windows: DPAPI/CNG/credential store según diseño final;
- macOS: Keychain;
- Linux: Secret Service.

Si no disponible, no caer a plaintext.

## Picker

Native file chooser/wrapper.
Convertir `Path` a `ImportHandle`.

## Large files

Desktop es caso fuerte:
- drag/drop;
- 10+ GB;
- background app abierta;
- múltiples transferencias.

Chunking y backpressure obligatorios.

## P2P

No asumir que la librería móvil funciona en JVM Desktop.
`PeerTransportFactory` permite backend distinto.

## Disguise

Desktop no puede prometer invisibilidad:
- nombre neutro;
- icono alternativo del instalador/shortcut;
- opcional renombrado de shortcut;
- proceso y paquete siguen siendo inspeccionables.

## Viewer

Para formatos no soportados internamente:
- export temporal solo con consentimiento;
- warning de que abrir en app externa produce plaintext fuera de vault.
