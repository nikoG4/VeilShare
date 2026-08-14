# Protocolo WebSocket de signaling

## Frame envelope

```json
{
  "v": 1,
  "id": "msg-uuid",
  "type": "AUTH|LOOKUP|SIGNAL|PING",
  "payload": {}
}
```

## Limits

- max frame: definido bajo (ej. cientos de KiB, no MB);
- TTL de sesiones;
- no binary file frames.

## Challenge

```text
serverNonce
serverTimeBucket
protocolVersion
```

Client firma:
`domainTag || referenceCode || publicIdentityHash || serverNonce || protocolVersion`.

## Lookup

Request:
`referenceCode`

Response:
- offline; o
- public identity + rendezvous token efímero.

Client verifica code/fingerprint.

## Signal

Forward only si ambos están autenticados.
No persistir offline.
