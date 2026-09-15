package dev.veilshare.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransferControlMessagesTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Test
    fun `offer roundtrips with canonical metadata`() {
        val offer = TransferOffer(
            fileId = FileId("file-1"),
            displayName = "photo.jpg",
            mimeHint = "image/jpeg",
            sizeBytes = 123_456,
            totalChunks = 2,
        )

        val encoded = json.encodeToString(offer)
        val decoded = json.decodeFromString<TransferOffer>(encoded)

        assertEquals(offer, decoded)
        assertTrue(encoded.contains("photo.jpg"))
    }

    @Test
    fun `offer rejects unsafe path-like names before UI or vault`() {
        assertFailsWith<IllegalArgumentException> {
            TransferOffer(
                fileId = FileId("file-1"),
                displayName = "../secret.txt",
                sizeBytes = 1,
                totalChunks = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TransferOffer(
                fileId = FileId("file-1"),
                displayName = "folder\\secret.txt",
                sizeBytes = 1,
                totalChunks = 1,
            )
        }
    }

    @Test
    fun `offer rejects non-canonical or invalid size metadata`() {
        assertFailsWith<IllegalArgumentException> {
            TransferOffer(FileId("f"), " padded.txt ", sizeBytes = 1, totalChunks = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            TransferOffer(FileId("f"), "empty.bin", sizeBytes = 0, totalChunks = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            TransferOffer(FileId("f"), "bad.bin", sizeBytes = 1, totalChunks = 0)
        }
    }

    @Test
    fun `accept reject and failure roundtrip`() {
        val accept = TransferAccept(FileId("file-a"))
        assertEquals(accept, json.decodeFromString<TransferAccept>(json.encodeToString(accept)))

        val reject = TransferReject(FileId("file-a"), "user declined")
        assertEquals(reject, json.decodeFromString<TransferReject>(json.encodeToString(reject)))

        val failure = TransferFailure(
            transferIdHash = "abc123",
            code = TransferFailureCode.DECRYPTION_FAILED,
            details = "authentication failed",
        )
        assertEquals(failure, json.decodeFromString<TransferFailure>(json.encodeToString(failure)))
    }

    @Test
    fun `reject and failure reason fields are bounded and canonical`() {
        assertFailsWith<IllegalArgumentException> {
            TransferReject(FileId("file"), " reason ")
        }
        assertFailsWith<IllegalArgumentException> {
            TransferFailure(
                transferIdHash = "hash",
                code = TransferFailureCode.INTERNAL_ERROR,
                details = "x".repeat(SharingMetadataLimits.MAX_REASON_CHARS + 1),
            )
        }
    }
}
