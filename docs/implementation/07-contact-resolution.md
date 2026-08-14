# Blueprint 07 — Resolución de contactos

## Add by code

1. validate format/checksum;
2. signaling lookup;
3. receive public identity;
4. recompute reference code;
5. reject mismatch;
6. compute full fingerprint;
7. show verification UI;
8. save encrypted contact.

## Existing frequent

Before send:
1. lookup by reference;
2. compare returned fingerprint with pinned;
3. mismatch => hard block;
4. match => continue.

## QR

QR contains full identity:
- validate version;
- compute fingerprint;
- derive code;
- save verified state.

## Alias

Alias is local plaintext only inside unlocked vault/contact store.

## Acceptance
- typo checksum;
- malicious server returns other key;
- same code with impossible collision simulation;
- identity rotation flow.
