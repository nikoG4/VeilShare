# Blueprint 16 — Migrations

## Version axes

Separar:
- app version;
- vault format;
- file blob format;
- transfer protocol;
- server signaling protocol.

## Vault migration

1. unlock old;
2. snapshot/journal;
3. transform metadata/blobs if needed;
4. verify;
5. commit new version;
6. retain rollback info only as long as needed and encrypted.

## Protocol

Old peers:
- negotiate compatible protocol;
- or fail “update required”.

No intentar parsear v2 como v1.

## Tests

Keep golden fixtures for every released format.
