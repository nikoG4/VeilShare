package dev.veilshare.core.vault

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Internal, versioned encrypted snapshot format. Callers must lock all vault sessions
 * before export. Import writes ONLY to a new private staging directory; promotion,
 * credential verification and user-facing SAF flows belong to the Android application.
 *
 * Header: 8 magic bytes, 1 version byte, 12 random nonce bytes, followed by
 * AES-256-GCM encrypted ZIP bytes (including its authenticated end marker).
 */
internal object AndroidVaultBackupCodec {
    private val magic = byteArrayOf(0x56, 0x53, 0x42, 0x41, 0x43, 0x4b, 0x55, 0x50)
    private const val version = 1
    private const val maxEntries = 100_000
    private const val maxEntryBytes = 8L * 1024 * 1024 * 1024
    private const val maxTotalBytes = 64L * 1024 * 1024 * 1024
    private const val maxNameBytes = 512
    private val random = SecureRandom()

    fun newRecoveryKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher {
        require(key.size == 32) { "Recovery key must contain 256 bits" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(magic + byteArrayOf(version.toByte()) + nonce)
        }
    }

    fun export(root: File, destination: OutputStream, key: ByteArray) {
        require(root.isDirectory && !java.nio.file.Files.isSymbolicLink(root.toPath()))
        val files = root.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .toList()
        require(files.size <= maxEntries)
        require(files.sumOf { it.length() } <= maxTotalBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = DataOutputStream(destination)
        header.write(magic)
        header.writeByte(version)
        header.write(nonce)
        header.flush()
        // Closing the ZIP stream finalizes the GCM authentication tag.
        ZipOutputStream(CipherOutputStream(BufferedOutputStream(destination), cipher(Cipher.ENCRYPT_MODE, key, nonce))).use { zip ->
            for (file in files) {
                val name = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                require(name.toByteArray(Charsets.UTF_8).size <= maxNameBytes && file.length() <= maxEntryBytes)
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
            }
            // Explicit sentinel prevents accepting an otherwise valid but incomplete ZIP.
            zip.putNextEntry(ZipEntry(".veilshare-backup-end"))
            zip.closeEntry()
        }
    }

    /**
     * The stage MUST be newly created and empty. Authentication is verified before
     * returning; caller MUST delete stage on every failure, and must never promote
     * it without validating both vault credentials.
     */
    fun importToStage(source: InputStream, stage: File, key: ByteArray) {
        require(stage.isDirectory && stage.list()?.isEmpty() == true)
        require(!java.nio.file.Files.isSymbolicLink(stage.toPath()))
        val input = DataInputStream(BufferedInputStream(source))
        val headerMagic = ByteArray(magic.size)
        input.readFully(headerMagic)
        require(headerMagic.contentEquals(magic)) { "Invalid backup format" }
        val readVersion = input.readUnsignedByte()
        require(readVersion == version) { "Unsupported backup version" }
        val nonce = ByteArray(12)
        input.readFully(nonce)
        // Authenticate the COMPLETE ciphertext before trusting any ZIP entry. ZipInputStream
        // stops at the central directory and otherwise can skip the final GCM tag.
        // The temporary ZIP stays in private staging and is removed on every exit.
        val authenticatedZip = File(stage, ".veilshare-authenticated-zip.tmp")
        try {
            authenticatedZip.outputStream().buffered().use { plaintext ->
                val decryptor = cipher(Cipher.DECRYPT_MODE, key, nonce)
                val buffer = ByteArray(64 * 1024)
                var encryptedBytes = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    encryptedBytes += n
                    require(encryptedBytes <= maxTotalBytes + 128L * 1024 * 1024) {
                        "Backup exceeds encrypted size limit"
                    }
                    decryptor.update(buffer, 0, n)?.let { plaintext.write(it) }
                }
                // doFinal MUST execute: wrong key, truncation and tampering fail here.
                plaintext.write(decryptor.doFinal())
            }
            extractAuthenticatedZip(authenticatedZip, stage)
        } finally {
            authenticatedZip.delete()
        }
    }

    private fun extractAuthenticatedZip(authenticatedZip: File, stage: File) {
        var count = 0
        var total = 0L
        var endSeen = false
        val names = HashSet<String>()
        ZipInputStream(authenticatedZip.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= maxEntries + 1)
                val name = entry.name
                require(name.toByteArray(Charsets.UTF_8).size <= maxNameBytes)
                require(!entry.isDirectory && !name.startsWith('/') && !name.contains('\\') &&
                    name.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
                require(names.add(name)) { "Duplicate backup entry" }
                if (name == ".veilshare-backup-end") {
                    require(!endSeen && zip.read() == -1)
                    endSeen = true
                    require(zip.nextEntry == null) { "Entries after end marker" }
                    break
                }
                require(!endSeen)
                val target = File(stage, name)
                require(target.canonicalPath.startsWith(stage.canonicalPath + File.separator))
                require(target.parentFile.mkdirs() || target.parentFile.isDirectory)
                require(!java.nio.file.Files.isSymbolicLink(target.toPath()))
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        size += read
                        total += read
                        require(size <= maxEntryBytes && total <= maxTotalBytes)
                        output.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
        require(endSeen) { "Truncated backup" }
    }
}
