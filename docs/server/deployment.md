# Deployment

## Components

```text
Internet
  ├─ Ktor signaling (WSS)
  └─ STUN/TURN service
```

## Ktor

- container;
- TLS terminated at reverse proxy or app;
- health endpoint;
- no persistent volume needed for MVP;
- no file upload path;
- resource limits.

## TURN

Bandwidth dominates cost when relay used.
Use:
- short-lived credentials;
- quotas;
- rate limit;
- metrics without content logging.

## Secrets

Server secrets:
- TURN credential secret;
- TLS/private infrastructure credentials.

Nunca claves de usuario.

## Metrics

Allowed:
- active connections count;
- signaling errors count;
- TURN credential count;
- aggregate bytes relay if infra exposes it.

Avoid user-level long-term analytics by default.
