# VeilShare Sharing UX Contract

Status: implemented baseline contract for PR #6, stacked on PR #5.

## Goals

The sharing surface must make the safe path obvious without exposing protocol internals. A user should be able to tell whether they are waiting, verifying a peer, sending, receiving, completed, rejected, cancelled, or failed without understanding signaling, session keys, or transfer frames.

## Security boundary

Compose/UI MUST NOT receive or render:

- vault VMK, KEK, PIN, VaultId, or REAL/DECOY labels as sharing identity material;
- Ed25519 or X25519 private material;
- peer public-key objects used by the trust store;
- pending inbound handshake objects;
- handshake/session DATA keys or envelope keys;
- raw protocol envelopes.

The UI may receive only high-level state such as ReferenceCode, display alias, fingerprint, file metadata, progress, verification reason, and terminal result.

ReferenceCode is routing metadata only. Entering a ReferenceCode MUST NOT establish trust by itself.

## Persona separation

The currently unlocked local persona maps to an opaque `LocalPersonaId`, which PR #5 maps through protected local state to a random `SharingContextId`. The UX MUST NOT reveal whether the active persona corresponds to REAL or DECOY and MUST NOT offer any cross-persona contact/presence view.

## Primary entry points

While a vault is unlocked the browser exposes two explicit actions:

1. **Enviar archivo** — choose a file, enter a peer ReferenceCode, verify trust if needed, then send.
2. **Recibir archivo** — show this persona's current ReferenceCode and wait for a trusted inbound offer.

Advanced contact management belongs in a secondary surface and is not required for the PR #6 freeze baseline.

## Sender state machine

`Preparing`
- optional chosen file summary;
- peer ReferenceCode field/action;
- no protocol terminology.

`VerificationRequired`
- show peer fingerprint in grouped form;
- distinguish a new peer from a routing code whose stored identity changed;
- explicitly state that the routing code alone is not proof of identity;
- require explicit out-of-band comparison before pinning/re-pinning;
- a new peer requires a local alias;
- an identity change preserves the existing contact alias;
- the candidate public key remains runtime-owned and never enters Compose;
- after verification the user must select the file again because the previous send attempt already relinquished and closed file ownership.

`Connecting`
- non-terminal progress;
- Cancel remains available.

`Sending`
- bytes sent / total bytes;
- progress indicator;
- Cancel remains available;
- never fake progress with timers.

`Completed`
- clear success state;
- Done returns to browser.

`Error` / `Cancelled`
- concise user-facing reason;
- key mismatch remains blocked and is never auto-accepted;
- Retry only if the previous ownership/state allows a fresh file selection;
- Done returns to browser.

## Receiver state machine

`Waiting`
- prominently show current ReferenceCode;
- explain that only already verified contacts may establish a trusted inbound session;
- Cancel returns to browser and does not rotate identity implicitly.

`Incoming`
- sender alias/identity display from the pinned local contact;
- filename and size;
- explicit Reject and Accept actions;
- dismiss/back MUST NOT imply Accept.

`Receiving`
- authenticated DATA progress;
- Cancel propagates to the runtime;
- vault import must not be reported complete until durable import succeeds.

`Completed`
- success only after vault import completed;
- Done returns to browser.

`Rejected` / `Cancelled` / `Error`
- terminal explanation;
- Done returns to browser.

## Verification baseline

PR #6 implements grouped manual fingerprint comparison. The runtime retains the untrusted `PeerIdentityCandidate`; UI receives only its fingerprint and verification reason. Confirmation invokes the core trust manager:

- new peer -> explicit `addVerified(..., MANUAL_FINGERPRINT)`;
- changed routed identity -> explicit `confirmIdentityChange(..., MANUAL_FINGERPRINT)`.

Neither path trusts a ReferenceCode, and neither automatically replaces a pinned key.

QR is a follow-up transport for public verification material. When added, scanning MUST feed the same explicit trust validation/pinning path; it must not bypass fingerprint/key checks.

## Layout

Compact:
- one-column cards;
- Send/Receive actions remain visible from the browser;
- primary action full width where useful;
- file/peer metadata above destructive/accept actions.

Expanded:
- content width capped for readability;
- Send/Receive actions remain visible from the browser toolbar;
- no stretching verification/forms edge-to-edge.

## Accessibility and testing

Important sharing entry and verification actions have stable test tags. Controller regression tests cover:

- sender entry/file ownership and completion;
- first-peer verification required -> explicit confirmation -> fresh preparation;
- changed identity confirmation using the existing alias;
- verification dismissal clears runtime pending trust;
- Receive entry and ReferenceCode state;
- Accept and Reject callbacks;
- Cancel while sending;
- Completed -> Done.

Full visual screenshot regression and QR verification are follow-up work, not security prerequisites for this baseline.

## Non-goals for PR #6

- changing the wire format;
- changing HKDF/AEAD domains;
- exposing protocol messages or key objects in UI;
- QR scanning/generation;
- full trusted-contact CRUD UI;
- implementing iOS native crypto/secure persistence;
- merging any earlier PR.

**DO NOT MERGE PR #1/#2/#3/#4/#5/#6.**
