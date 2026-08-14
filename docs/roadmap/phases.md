# Roadmap por fases

## Phase 0 — Foundation
- Gradle/KMP;
- modules/source sets;
- Compose;
- AdaptiveKt wrapper;
- DI;
- serialization;
- fake platform capabilities;
- CI.

## Phase 1 — Local vault skeleton
- blob store;
- manifests;
- import pipeline with fake crypto first;
- transaction journal;
- UI gallery.

## Phase 2 — Real cryptography
- provider;
- Argon2id;
- key hierarchy;
- real/decoy slots;
- secret stores;
- lock/session.

## Phase 3 — Native import & deletion
- Android picker/delete;
- iOS picker/delete;
- Desktop picker/delete;
- thumbnails/viewer.

## Phase 4 — Identity & contacts
- keypairs;
- fingerprint;
- reference code;
- QR;
- frequent contacts.

## Phase 5 — Signaling
- Ktor;
- auth challenge;
- presence;
- signaling;
- TURN credentials.

## Phase 6 — P2P transport
- Android;
- iOS;
- Desktop;
- loopback/integration.

## Phase 7 — Transfer protocol
- E2E session;
- offer/accept;
- chunks;
- ACK;
- resume;
- receive-to-vault.

## Phase 8 — Disguise & platform polish
- Android aliases;
- iOS alt icon;
- Desktop shortcut;
- screenshot/privacy;
- lifecycle.

## Phase 9 — Hardening
- fuzz;
- fault injection;
- migrations;
- store review;
- threat model review.
