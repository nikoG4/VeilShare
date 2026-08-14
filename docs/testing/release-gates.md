# Release gates

## G0 Build
- Android build
- Desktop build
- iOS framework/app build on macOS

## G1 Common tests
100% green.

## G2 Crypto
- vectors green all targets;
- no TODO crypto;
- no debug keys.

## G3 Vault
- migration;
- import 0B/1B/large;
- crash recovery;
- real/decoy isolation.

## G4 Transfer
- all target pairs required for release;
- resume;
- TURN;
- corrupt frame.

## G5 Store/policy
- review notes;
- permissions strings;
- privacy manifest/data safety;
- no hidden review switch.

## G6 Security review
Manual checklist + dependency scan.

A platform no validada = no release para esa plataforma.
