package dev.veilshare.core.vault

import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SealedBytes
import dev.veilshare.core.model.BlobId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogCodecTest {
    private val codec = CatalogCodec()
    private val wrapped = SealedBytes(Nonce(ByteArray(12) { 7 }), ByteArray(48) { 9 })

    @Test fun `canonical round trip retains unicode folder and file metadata`() {
        val entries = listOf(
            VaultItem.Directory(VaultItemId("dir"), null, "ñandutí 中文 日本語 🚀"),
            VaultItem.File(VaultItemId("file"), VaultDirectoryId("dir"), "archivo 🚀", "text/plain", 42, BlobId("blob"), wrappedFileKey = wrapped)
        )
        val encoded = codec.encode(CatalogSnapshot(entries = entries))
        assertEquals(encoded.toList(), codec.encode(CatalogSnapshot(entries = entries)).toList())
        val decoded = codec.decode(encoded).entries
        assertEquals(entries[0], decoded[0])
        val file = decoded[1] as VaultItem.File
        assertEquals((entries[1] as VaultItem.File).copy(wrappedFileKey = null), file.copy(wrappedFileKey = null))
        assertEquals(wrapped.nonce.bytes.toList(), file.wrappedFileKey!!.nonce.bytes.toList())
        assertEquals(wrapped.ciphertext.toList(), file.wrappedFileKey!!.ciphertext.toList())
    }

    @Test fun `rejects malformed relationships and trailing data`() {
        assertFailsWith<VaultFormatException> { codec.encode(CatalogSnapshot(entries = listOf(VaultItem.Directory(VaultItemId("a"), VaultDirectoryId("missing"), "a")))) }
        assertFailsWith<VaultFormatException> { codec.decode(codec.encode(CatalogSnapshot()) + byteArrayOf(1)) }
    }
}
