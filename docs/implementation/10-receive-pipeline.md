# Blueprint 10 — Recepción

## Steps

1. secure session authenticated;
2. receive offer;
3. user accepts;
4. create temp import transaction in target vault;
5. for each transfer chunk:
   - verify/decrypt transfer layer;
   - encrypt directly to receiver FileKey;
   - durable write;
   - mark range;
   - ACK;
6. verify total/final digest;
7. write encrypted manifest;
8. commit;
9. send final receipt.

## Lock behavior

Policy options:
- pause reception and close session;
- or continue only if receiving keys are session-scoped and vault writer can remain authorized.

MVP preference: pause on vault lock for simpler key hygiene, then resume.

## Duplicates

ACK already durable chunk without rewriting.

## Acceptance
- malicious filename;
- oversized metadata;
- unexpected file count;
- disk full;
- sender cancels;
- crash after write before ACK.
