# Test strategy

## Pyramid

### common unit/property tests
Mayor volumen:
- codecs;
- key wrapping orchestration;
- state machines;
- chunk sets;
- migrations;
- contact pinning.

### platform contract tests
Cada actual debe pasar la misma suite abstracta:
- SecretStoreContract;
- ImportPicker fakes/bridges;
- CryptoProviderContract;
- PeerTransport loopback.

### integration
- Android emulator/device;
- iOS simulator/device;
- Desktop all OS if CI available.

### E2E
- Android ↔ Android
- Android ↔ iOS
- Android ↔ Desktop
- iOS ↔ Desktop
- Desktop ↔ Desktop

## Determinism

Protocol tests use seeded deterministic test provider.
Production CSPRNG never se seed con valores previsibles.
