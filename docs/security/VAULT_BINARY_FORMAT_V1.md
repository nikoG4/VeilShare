# VeilShare binary format V1 — normative design, not yet enabled

This document specifies `VEIL_CRYPTO_V1`. Android/Desktop are enabled after the Bouncy Castle provider/vector gate; iOS remains verification-pending until its cinterop provider passes the same fixtures.

## Blob header

All integers are unsigned big-endian. Parsers must reject unknown versions, unsupported suites, declared lengths over documented limits, truncation, duplicate/out-of-order chunk index and trailing unauthenticated bytes.

| Offset | Size | Field |
|---|---:|---|
| 0 | 4 | `VBL1` magic |
| 4 | 1 | format version (`1`) |
| 5 | 1 | crypto suite (`1` = VEIL_CRYPTO_V1) |
| 6 | 4 | chunk plaintext bytes (1,048,576) |
| 10 | 8 | declared plaintext length, or all ones when unknown |
| 18 | 4 | random per-file nonce prefix |
| 22 | 8 | number of chunks |

Each chunk is `[index:u64][plaintextLength:u32][ciphertext:plaintextLength+16]`. AAD is canonical concatenation of header fields through `numberOfChunks`, then `index` and `plaintextLength`. The final record has no plaintext and is an authenticated final marker.

## Nonces and limits

For ChaCha20-Poly1305 IETF, each 96-bit nonce is `prefix:u32 || index:u64`, big-endian; indices are unique in `0..2^64-2`. A FileKey is random and unique per file, so nonce reuse requires both a FileKey collision and same prefix/index, which the writer prohibits. Maximum declared plaintext is `Long.MAX_VALUE`; writer rejects overflow and any chunk count exceeding `(Long.MAX_VALUE + chunkSize - 1) / chunkSize`.

## Metadata

Filenames, MIME, folders, dates, wrapped FileKey and catalog data are payloads encrypted/authenticated under vault-derived metadata keys. Bootstrap plaintext contains only magic, opaque vault ID, version/suite/KDF identifier, bounded KDF parameters and salt. It contains no real/decoy label.
