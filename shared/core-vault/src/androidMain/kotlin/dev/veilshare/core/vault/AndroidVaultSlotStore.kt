package dev.veilshare.core.vault

import dev.veilshare.core.crypto.Argon2Parameters
import dev.veilshare.core.crypto.CryptoSuiteId
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.Salt
import dev.veilshare.core.crypto.SealedBytes
import dev.veilshare.core.model.VaultId
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.File

class AndroidVaultSlotStore(root: File, private val persistence: AndroidPersistenceOps = AndroidDurableFiles) : VaultSlotStore {
    private val dir = File(File(root, "bootstrap"), "slots")
    init { check(dir.mkdirs() || dir.isDirectory) }

    override suspend fun write(slotId: String, slot: VaultBootstrapSlot) {
        require(slotId.matches(Regex("[0-9a-f]{24}")))
        val target = File(dir, "$slotId.vslot")
        val temp = File.createTempFile(".slot-", ".tmp", dir)
        try {
            val bytes = ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { writeSlot(it, slot) }
                buffer.toByteArray()
            }
            persistence.writeAndSync(temp, bytes)
            persistence.replace(temp, target)
        } finally { temp.delete() }
    }

    override suspend fun all(): List<Pair<String, VaultBootstrapSlot>> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".vslot") }.orEmpty().mapNotNull { file ->
            try { file.name.removeSuffix(".vslot") to DataInputStream(file.inputStream()).use(::readSlot) }
            catch (_: VaultFormatException) { null }
            catch (_: IOException) { null }
            catch (_: IllegalArgumentException) { null }
        }

    private fun writeSlot(out: DataOutputStream, slot: VaultBootstrapSlot) {
        out.writeInt(0x56534C31); out.writeInt(slot.formatVersion.value); out.writeInt(slot.metadataVersion.value)
        out.writeByte(slot.cryptoSuite.ordinal); out.writeUTF(slot.vaultId.value)
        out.writeInt(slot.kdfParameters.memoryKiB); out.writeInt(slot.kdfParameters.iterations); out.writeInt(slot.kdfParameters.parallelism)
        bytes(out, slot.salt.bytes); sealed(out, slot.wrappedVaultKey); sealed(out, slot.encryptedDescriptor)
    }

    private fun readSlot(input: DataInputStream): VaultBootstrapSlot {
        if (input.readInt() != 0x56534C31) throw VaultFormatException("Bad slot magic")
        val format = VaultFormatVersion(input.readInt()); val metadata = VaultMetadataVersion(input.readInt())
        val suite = CryptoSuiteId.entries.getOrNull(input.readUnsignedByte()) ?: throw VaultFormatException("Unknown suite")
        val id = VaultId(input.readUTF()); val parameters = Argon2Parameters(input.readInt(), input.readInt(), input.readInt())
        val result = VaultBootstrapSlot(
            vaultId = id,
            formatVersion = format,
            metadataVersion = metadata,
            cryptoSuite = suite,
            kdfParameters = parameters,
            salt = Salt(bytes(input, 64)),
            wrappedVaultKey = sealed(input),
            encryptedDescriptor = sealed(input),
        )
        if (input.read() != -1) throw VaultFormatException("Trailing slot data")
        return result
    }

    private fun bytes(out: DataOutputStream, value: ByteArray) { out.writeInt(value.size); out.write(value) }
    private fun bytes(input: DataInputStream, max: Int): ByteArray { val size=input.readInt(); if(size !in 0..max) throw VaultFormatException("Unsafe slot length"); return ByteArray(size).also(input::readFully) }
    private fun sealed(out: DataOutputStream, value: SealedBytes) { bytes(out, value.nonce.bytes); bytes(out, value.ciphertext) }
    private fun sealed(input: DataInputStream) = SealedBytes(Nonce(bytes(input, 32)), bytes(input, 1024))
}
