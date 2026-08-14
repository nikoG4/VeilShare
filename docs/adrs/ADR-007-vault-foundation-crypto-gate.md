# ADR-007 — Vault foundation and crypto release gate

Status: Accepted

The vault core has a portable crypto boundary (`PasswordKdf`, `AuthenticatedCipher`, `KeyWrapper`, and strong secret wrapper types). Bootstrap slots are structurally dual and role-neutral on disk; the vault role is only in the encrypted descriptor.

No production crypto provider is wired in this phase. The test provider proves orchestration only and is deliberately located in test sources. It must never be used by an app host. P1 remains the release gate: an audited Argon2id provider with Android, iOS and Desktop interoperability must be selected before persistence containing real user data is enabled.

Plaintext bootstrap data is limited to magic, opaque vault ID, format/KDF identifiers and KDF parameters/salt. Descriptor, catalog, filenames, MIME data, folder relationships and file metadata belong in authenticated encrypted payloads.
