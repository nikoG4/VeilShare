# Servidor de señalización

## Filosofía

Stateless/ephemeral siempre que sea posible.

## Funciones

1. challenge;
2. presencia;
3. lookup online;
4. forward offer/answer/ICE;
5. credenciales TURN temporales;
6. rate limiting.

## No funciones

- archivos;
- chunks;
- previews;
- mensajes offline;
- contactos;
- PIN;
- keys privadas.

## WebSocket lifecycle

```text
CONNECT
  ↓
SERVER_CHALLENGE(nonce)
  ↓
CLIENT_AUTH(publicIdentity, referenceCode, signature)
  ↓
AUTH_OK
  ↓
presence map register
  ↓
SIGNAL frames
  ↓
DISCONNECT → remove presence
```

## Signal frame

```json
{
  "v": 1,
  "type": "offer|answer|ice|probe",
  "to": "REFERENCE",
  "sessionId": "opaque",
  "payload": "opaque/transport-specific"
}
```

El server puede ver signaling WebRTC por necesidad. No debe ver app E2E transfer manifest.

## Logs

Permitido:
- aggregate counts;
- error code;
- coarse latency.

Evitar:
- full reference codes;
- public keys completas en logs;
- ICE payloads;
- IP retention prolongada.
