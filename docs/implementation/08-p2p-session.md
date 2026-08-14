# Blueprint 08 — Sesión P2P

## Layers

`SignalingClient` common Ktor client where possible.
`PeerTransport` platform adapter.
`SecurePeerSession` common.

## Connect

1. recipient resolved;
2. create transport;
3. exchange transport signaling;
4. transport open;
5. run app handshake;
6. verify signed transcript;
7. derive directional keys;
8. expose `SecureChannel`.

No file metadata before step 7.

## SecureChannel

```kotlin
interface SecureChannel {
    suspend fun send(message: ProtocolMessage)
    val incoming: Flow<ProtocolMessage>
}
```

Encryption/sequence replay protection internal.

## Sequence

Monotonic per direction.
Reject duplicate/out-of-window encrypted control frames unless protocol explicitly idempotent at higher layer.

## Acceptance
- MITM signaling;
- wrong recipient;
- downgraded protocol;
- reconnect produces new session keys.
