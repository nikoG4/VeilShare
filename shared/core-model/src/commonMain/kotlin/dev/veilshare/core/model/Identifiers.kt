package dev.veilshare.core.model

import kotlinx.serialization.Serializable

@Serializable @JvmInline value class VaultId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class FileId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class BlobId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class FolderId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class ContactId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class TransferId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class SessionId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class ReferenceCode(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class Fingerprint(val value: String) { init { require(value.isNotBlank()) } }

object FormatVersions { const val VAULT = 1; const val FILE = 1; const val PROTOCOL = 1 }
