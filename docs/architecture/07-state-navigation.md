# Estado, navegación y lifecycle

## Root states

```text
Booting
├─ NeedsOnboarding
├─ Locked
│  └─ DisguiseSurface
└─ Unlocked(VaultSession)
   ├─ Home
   ├─ Folder
   ├─ Viewer
   ├─ Import
   ├─ Contacts
   ├─ Send
   ├─ Receive
   └─ Settings
```

## Regla

Una `VaultSession` no es serializable ni persistible. Contiene handles/material sensible de sesión.

## Bloqueo

Triggers:
- timeout;
- app background más allá de política;
- pantalla bloqueada cuando platform callback exista;
- usuario pulsa lock;
- cambio de identidad crítica;
- memory pressure severa si la plataforma lo notifica;
- logout/reset.

`LockUseCase`:
1. cancelar viewers;
2. pausar/cancelar transferencias según política;
3. cerrar streams;
4. limpiar caches;
5. zeroize best-effort;
6. borrar referencias a VaultSession;
7. navegar a surface discreta/locked.

## Navigation

La navegación vive en common UI. Los deep links a funciones privadas deben estar deshabilitados o exigir unlock.

Nunca permitir:
`myapp://vault/file/123` → muestra contenido sin gate.
