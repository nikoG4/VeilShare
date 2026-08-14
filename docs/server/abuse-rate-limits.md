# Abuse prevention and rate limits

## Threats

- reference scanning;
- signaling spam;
- connection floods;
- TURN bandwidth theft;
- oversized signaling payloads.

## Controls

- challenge response;
- per-IP connection limits;
- per-identity rate limits;
- max frame size;
- max ICE candidate count;
- TTL sessions;
- TURN short credentials;
- quotas;
- exponential backoff.

## Privacy

Rate limiting should not force collection of extra personal data unless necessary.

## Server cannot trust clients

Validate:
- protocol version;
- lengths;
- reference code format;
- signed auth;
- target online;
- payload size.

The server does not need to parse encrypted transfer frames because it never receives them in normal P2P operation.
