# VeilShare Sharing UX Contract

Status: draft implementation contract for PR #6, stacked on PR #5.

## Goals

The sharing surface must make the safe path obvious without exposing protocol internals. A user should be able to tell whether they are waiting, verifying a peer, sending, receiving, completed, rejected, cancelled, or failed without understanding signaling, session keys, or transfer frames.

## Security boundary

Compose/UI MUST NOT receive or render:

- vault VMK, KEK, PIN, VaultId, or REAL/DECOY labels as sharing identity material;
- Ed25519 or X25519 private material;
- pending inbound handshake objects;
- handshake/session DATA keys or envelope keys;
- raw protocol envelopes.

The UI may receive only high-level state such as ReferenceCode, display alias, fingerprint, file metadata, progress, and terminal result.

ReferenceCode is routing metadata only. Entering or scanning a ReferenceCode MUST NOT establish trust by itself.

## Persona separation

The currently unlocked local persona maps to an opaque `LocalPersonaId`, which PR #5 maps through protected local state to a random `SharingContextId`. The UX MUST NOT reveal whether the active persona corresponds to REAL or DECOY and MUST NOT offer any cross-persona contact/presence view.

## Primary entry points

While a vault is unlocked the file browser exposes a single primary Share action. The first sharing surface offers two understandable choices:

1. **Send a file** — choose a file, select/enter a peer route, verify trust if needed, then send.
2. **Receive a file** — show this persona's current ReferenceCode and wait for a trusted inbound offer.

Advanced identity/contact controls belong under a secondary `Trusted contacts` surface rather than the first sharing screen.

## Sender state machine

`Preparing`
- optional chosen file summary;
- peer ReferenceCode field/action;
- no protocol terminology.

`Verification required`
- show peer fingerprint in grouped form;
- explicitly state that the routing code alone is not proof of identity;
- require an explicit out-of-band confirmation before pinning;
- later QR support may satisfy the same verification action.

`Connecting`
- non-terminal progress;
- Cancel remains available.

`Sending`
- filename;
- bytes sent / total bytes;
- progress indicator;
- Cancel remains available;
- never fake progress with timers.

`Completed`
- clear success state;
- Done returns to browser.

`Error` / `Cancelled`
- concise user-facing reason;
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

## Trusted contacts

A contact stores an alias plus pinned sharing identity/key and optional current ReferenceCode. UI fingerprint is always derived from the pinned public key, never trusted as persisted display text.

Required user operations:

- list contacts for the active sharing persona;
- add after explicit verification;
- rename alias;
- remove contact;
- handle identity/key mismatch as a high-friction re-verification, never automatic replacement.

## Verification

Phase 1: grouped fingerprint comparison.

Phase 2: QR representation containing only public verification material required by the high-level verification flow. QR scanning must still feed the same trust validation/pinning path; it must not bypass it.

## Layout

Compact:
- one-column cards;
- primary action full width where useful;
- file/peer metadata above destructive/accept actions.

Expanded:
- content width capped for readability;
- optional secondary trust/help panel;
- no stretching forms edge-to-edge.

## Accessibility and testing

Important actions/states get stable test tags. Tests must cover at least:

- Send entry and file selection;
- Receive entry and ReferenceCode display;
- verification-required state;
- Accept and Reject callbacks;
- Cancel while sending/receiving;
- Completed -> Done;
- Error -> close/retry;
- compact and expanded layouts.

## Non-goals for PR #6

- changing the wire format;
- changing HKDF/AEAD domains;
- exposing protocol messages in UI;
- implementing iOS native crypto/secure persistence;
- merging any earlier PR.

**DO NOT MERGE PR #1/#2/#3/#4/#5/#6.**
