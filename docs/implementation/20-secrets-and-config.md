# Blueprint 20 — Secrets y configuración

## Client config

Puede contener:
- signaling URL;
- STUN URLs;
- protocol feature flags no sensibles.

No:
- TURN long-term password;
- server admin secret;
- private identity.

## Server config

Env/secrets manager:
- TLS references;
- TURN auth secret;
- rate limit settings.

## User secrets

Generated device-side:
- identity private keys;
- VaultKeys;
- FileKeys;
- recovery key.

Nunca viajan a config repo/server.

## Test config

Deterministic crypto provider solo en test source sets.
Build release debe fallar si detecta provider `TestCrypto`.
