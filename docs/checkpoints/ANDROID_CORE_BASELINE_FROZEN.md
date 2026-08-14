# Android core baseline frozen — 2026-08-14

Desktop and Android use byte-compatible production slots, encrypted catalogs, Journal V2 records, opaque blob namespaces, and VBL1 blobs. Fixed synthetic physical trees are consumed in both directions, including a Desktop tree mutated by Android and reopened by Desktop.

Verified gates:

- Desktop D0-D8 and Android D0-D8;
- authenticated catalog authority and conservative namespace-aware recovery;
- real partial writes, sync failure, pre/post rename faults, physical delete failure, and journal cleanup failure;
- catalog, journal, slot, and referenced VBL1 corruption boundaries;
- REAL/DECOY and namespace isolation;
- cancellation on both sides of catalog commit;
- PIN rotation success and pre-rename failure;
- physical metadata/content canary scan;
- Desktop/Android artifact SHA-256 manifests and exact plaintext recovery.

Execution environment:

- Desktop JVM on Windows;
- Android Pixel 9 Pro XL API 35 emulator;
- no lower API image installed;
- no stable actual-process-death harness;
- no physical power-loss claim.

Decision: `ANDROID CORE BASELINE FROZEN`; application UI integration may begin in the next phase. UI is not part of this checkpoint.
