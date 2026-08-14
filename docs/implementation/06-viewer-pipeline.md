# Blueprint 06 — Viewer pipeline

## Goal

Visualizar sin persistir plaintext innecesario.

## Image
- decrypt chunks;
- decode from bounded buffer/stream;
- clear decoded cache on lock.

## Video
Need random/sequential access abstraction:
`VaultSeekableSource`.

No export full file just because decoder needs seek; implement chunk-backed read if framework permits.

## PDF/docs
Prefer in-app rendering that can consume bytes/stream.
If native viewer requires file URL:
- classify as platform exception;
- create explicit secure temp strategy;
- delete on close/lock;
- warn that absolute forensic erase is not guaranteed.

## Unsupported
Offer `Export and open with…` as explicit user action.

## Acceptance
- lock while viewer open;
- app background;
- large video;
- corrupt chunk stops playback, no partial silent continuation.
