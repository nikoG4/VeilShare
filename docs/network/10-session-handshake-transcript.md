# Handshake E2E y transcript

## Objetivo

Evitar que signaling server pueda:
- sustituir peer sin alerta;
- modificar parámetros;
- forzar downgrade.

## Transcript canónico

Incluye:
- protocol version;
- sender identity fingerprint;
- recipient identity fingerprint;
- sender ephemeral key;
- recipient ephemeral key;
- sessionId;
- cipher suite id;
- capability flags relevantes.

Ambos firman/verifican el mismo byte sequence canónico.

## Derivación

`sharedSecret = ECDH(...)`
`transcriptHash = HASH(canonicalTranscript)`
`sessionKeys = HKDF(sharedSecret, salt=transcriptHash, info=...)`

## Directionality

Derivar keys separadas:
- A→B;
- B→A.

No usar una key bidireccional con contadores compartidos.

## Replay

SessionId aleatorio y ephemeral keys nuevos.
Guardar temporalmente session ids recientes si fuera necesario para detectar replay durante una sesión activa.
