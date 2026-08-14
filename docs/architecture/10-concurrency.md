# Concurrencia

## Coroutines

Common:
- structured concurrency;
- `CoroutineScope` por app/session/transfer;
- no GlobalScope.

## Session

`VaultSessionScope`
- se cancela al lock;
- viewers/imports dependientes se cancelan.

## Transfer

Cada transferencia:
- supervisor;
- bounded channel;
- sender window;
- disk writer;
- ACK processor.

## Race conditions críticas

- lock durante import;
- delete mientras share;
- rename mientras viewer;
- identity rotation durante transfer;
- app background durante handshake.

Usar locks/mutex sobre IDs lógicos, no sincronización global.

## Atomicity

Un FileId no puede estar simultáneamente:
- committed;
- deleting;
- importing.

State machine explícita.
