# Blueprint 09 — Envío

## Steps

1. select committed FileIds;
2. acquire read leases so delete waits/fails safely;
3. resolve recipient;
4. secure session;
5. send offer;
6. await accept;
7. for each file:
   - unwrap FileKey;
   - stream/decrypt vault chunk;
   - encrypt transfer chunk/session;
   - send respecting backpressure;
   - persist ACK state;
8. send complete;
9. wait final receipt;
10. release leases.

## Privacy

Offer sent only inside secure session.

## Resume

Sender stores:
- source FileId;
- peer fingerprint;
- transfer id;
- ACK state.

If source file deleted after pause, resume fails explicitly.

## Progress

Based on durable ACK bytes.

## Acceptance
- receiver rejects;
- receiver pauses;
- source lock;
- source file delete race;
- 20 GB simulated sparse/fixture.
