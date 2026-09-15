# Android/Desktop Beta Runbook

Status: PR #7 beta-readiness work. This runbook deliberately does not cover iOS.

## 1. Signaling deployment

The signaling service is routing-only. It keeps presence/session routing in memory and does not persist filenames, file sizes, encrypted file payloads, vault material, fingerprints, or private keys.

Local development:

```bash
docker compose -f deploy/signaling/docker-compose.yml up --build
curl http://127.0.0.1:8080/healthz
```

Expected health response: `ok`.

For an Internet-facing beta, terminate TLS and expose the WebSocket as `wss://<host>/v1/ws`. Production/beta clients must not be configured with remote `ws://` endpoints.

Server configuration:

- `HOST` defaults to `0.0.0.0`.
- `PORT` defaults to `8080`.
- `/healthz` is an unauthenticated liveness endpoint and contains no user state.
- `/v1/ws` is the signaling WebSocket.

## 2. Client endpoint configuration

### Android

Build with a beta relay URL:

```bash
./gradlew :androidApp:assembleReleaseCheck \
  -Pveilshare.signalingUrl=wss://relay.example.com/v1/ws
```

`releaseCheck` is installable with the debug signing key but otherwise inherits release shrinking/debuggability behavior. It is intended for private beta validation, not store publication.

Debug builds may use the Android-emulator host bridge automatically:

`ws://10.0.2.2:8080/v1/ws`

Remote plain `ws://` is rejected even in the local-development policy.

### Desktop Windows

Set:

```powershell
$env:VEILSHARE_SIGNALING_URL = "wss://relay.example.com/v1/ws"
```

For an explicit localhost development session only:

```powershell
$env:VEILSHARE_ALLOW_INSECURE_LOOPBACK = "true"
$env:VEILSHARE_SIGNALING_URL = "ws://127.0.0.1:8080/v1/ws"
```

If no endpoint is configured in a packaged Desktop beta, the local vault remains usable and sharing is unavailable.

## 3. Build beta artifacts

Android installable beta APK:

```bash
./gradlew :androidApp:assembleReleaseCheck \
  -Pveilshare.signalingUrl=wss://relay.example.com/v1/ws
```

Windows portable distribution (run on Windows):

```powershell
./gradlew.bat :desktopApp:distZip
```

Signaling distribution:

```bash
./gradlew :server:signaling:distZip
```

The `Beta Readiness Validation` GitHub Actions workflow builds and retains these artifacts for the exact tested commit.

## 4. Trust bootstrap for two devices

For each unlocked persona, contacts and sharing identity remain persona-scoped.

1. Start device A and device B against the same signaling relay.
2. On A choose **Verificar contacto** and enter B's ReferenceCode.
3. Compare the full grouped fingerprint through an independent channel.
4. Confirm and save B on A.
5. Repeat in the opposite direction so B pins A.
6. Do not treat a ReferenceCode match as identity verification.

A changed identity for a previously associated routing code must always require explicit re-verification.

## 5. Physical E2E acceptance matrix

Run at least the following with one Android device and one Windows Desktop machine. Repeat the direction both ways where applicable.

### Baseline

- A and B unlock independent test vaults.
- Bilateral contact verification succeeds.
- A sends a small text file to B; B accepts; imported bytes equal source bytes.
- A sends a file larger than one transfer chunk; imported bytes equal source bytes.
- B sends Android -> Desktop and Desktop -> Android.
- Reject leaves no imported file.
- Local cancel during DATA terminates both sides without success UI.
- Receiver cancel during DATA terminates sender without success UI.

### Network loss

- Disconnect sender network before LOOKUP: operation fails; no fake success.
- Restore network and start a fresh send: transport reconnects and the same presence is re-registered.
- Disconnect during handshake: current attempt fails; a new attempt is required.
- Disconnect during DATA: current attempt fails/cancels; it must not be transparently resumed.
- Restore network and start a new transfer from the beginning.
- Leave an idle receiver waiting for more than two minutes; presence keepalive must keep it discoverable while the WebSocket remains healthy.
- Drop an idle receiver's transport, exit/re-enter **Recibir archivo**, and verify explicit presence refresh restores reachability.

### Lifecycle

- Android background/foreground outside the document picker locks as designed.
- Document picker transition does not falsely abandon the session.
- Lock during send/receive cancels sharing and clears active runtime state.
- Restart preserves trusted contacts/identity/reference-code state according to protected-store contracts.
- REAL and DECOY personas never show each other's contacts or presence.

## 6. Failure semantics

VeilShare intentionally does not implement transparent transfer resume in this beta baseline.

A WebSocket drop:

1. fails pending signaling requests;
2. invalidates the dead client session;
3. does not silently mark a transfer complete;
4. does not auto-resume authenticated DATA on a fresh transport;
5. permits a later explicit action to connect again and re-register the existing presence.

Healthy registered clients refresh their presence before the server's presence TTL expires.

## 7. Freeze gate before distributing a beta

Required green checks on the exact candidate commit:

- core/app/UI Desktop regression suite;
- signaling integration tests;
- Android `releaseCheck` APK build;
- Windows `distZip` build;
- signaling `distZip` build;
- signaling Docker image build;
- `git diff --check` against PR #6;
- physical two-device matrix recorded manually.

The CI gates can prove build/test reproducibility. The physical Android/Windows two-device matrix still requires real devices and a reachable relay.

**DO NOT MERGE PR #1/#2/#3/#4/#5/#6/#7 without explicit approval.**
