# Ktor signaling server

## Objetivo

Servidor JVM pequeño y horizontalmente escalable si fuera necesario.

## Routes conceptuales

### WebSocket `/v1/ws`
Toda presence/signaling.

### GET `/v1/health`
Sin datos.

### POST `/v1/turn-credentials`
Devuelve credenciales efímeras si TURN activo.

## WS messages

Server:
- `challenge`
- `auth_ok`
- `peer_online`
- `peer_offline`
- `signal`
- `error`

Client:
- `auth`
- `lookup`
- `signal`
- `ping`

## Auth sin cuenta

Challenge response con identity key:
1. server nonce;
2. client firma transcript;
3. server verifica public key;
4. reference code debe derivar de public identity;
5. registra conexión.

## Scaling

MVP una instancia: map en memoria.

Multi-instance futuro:
- Redis pub/sub/presence ephemeral;
- no cambia protocolo;
- no guardar archivos.

## TLS

WSS obligatorio en producción aun cuando signaling no lleve file payload.

## CORS/Origin
Configurar si hay clientes web futuros; no abrir wildcard sin razón.
