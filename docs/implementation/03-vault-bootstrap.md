# Blueprint 03 — Bootstrap de bóvedas

## First setup

1. generar dos `VaultKey` si decoy está habilitado;
2. generar salts independientes;
3. derivar KEKs desde PINes;
4. crear descriptors;
5. envolver cada VaultKey;
6. escribir slots opacos;
7. crear índices cifrados vacíos;
8. fsync/commit;
9. recién entonces marcar onboarding complete.

## Atomicidad

Si falla slot 2:
- no dejar onboarding “completo” con una sola vault;
- cleanup o recovery consistente.

## Slot registry

Puede existir un archivo con lista de slot IDs si:
- no identifica roles;
- no incluye metadata sensible.

## Change PIN

1. unlock;
2. derive new KEK;
3. rewrap same VaultKey;
4. write new slot atomically;
5. remove old wrapper;
6. verify new unlock fixture.

No recifrar blobs.

## Acceptance

- setup con crash injection en cada step;
- slots indistinguibles estructuralmente;
- wrong PIN no produce partially unlocked state.
