package dev.veilshare.core.securestore

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Current-user Windows DPAPI protector. Non-Windows desktop fails closed. */
class WindowsDpapiStateProtector : SecureStateProtector {
    init {
        check(Platform.isWindows()) {
            "Secure desktop sharing-state persistence requires Windows DPAPI"
        }
    }

    override suspend fun protect(scope: SecureStateScope, plaintext: ByteArray): ByteArray {
        val entropy = scope.aad()
        return try {
            Crypt32Util.cryptProtectData(
                plaintext,
                entropy,
                WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
                "VeilShare secure sharing state",
                null,
            )
        } finally {
            entropy.fill(0)
        }
    }

    override suspend fun unprotect(scope: SecureStateScope, protectedBytes: ByteArray): ByteArray {
        val entropy = scope.aad()
        return try {
            Crypt32Util.cryptUnprotectData(
                protectedBytes,
                entropy,
                WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
                null,
            )
        } finally {
            entropy.fill(0)
        }
    }
}

class DesktopDirectoryStateStorage(
    private val root: Path,
    private val maxFileBytes: Long = ProtectedStateStore.DEFAULT_MAX_PROTECTED_BYTES.toLong(),
) : AtomicStateStorage {
    init {
        require(maxFileBytes > 0)
        Files.createDirectories(root)
    }

    override suspend fun read(fileName: String): ByteArray? {
        val target = resolve(fileName)
        if (!Files.exists(target)) return null
        val size = Files.size(target)
        require(size in 1..maxFileBytes) { "Secure state file size is invalid" }
        return Files.readAllBytes(target)
    }

    override suspend fun replaceAtomic(fileName: String, bytes: ByteArray) {
        require(bytes.isNotEmpty())
        require(bytes.size.toLong() <= maxFileBytes) { "Secure state file exceeds size limit" }
        val target = resolve(fileName)
        val temp = Files.createTempFile(root, ".$fileName.", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val source = ByteBuffer.wrap(bytes)
                while (source.hasRemaining()) channel.write(source)
                channel.force(true)
            }
            Files.move(
                temp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override suspend fun delete(fileName: String) {
        Files.deleteIfExists(resolve(fileName))
    }

    private fun resolve(fileName: String): Path {
        require(fileName.length in 1..80)
        require(fileName.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
            "Unsafe secure state filename"
        }
        val target = root.resolve(fileName).normalize()
        require(target.parent == root.normalize()) { "Secure state path escaped root" }
        return target
    }
}

object DesktopSecureStateFactory {
    fun windows(root: Path): ProtectedStateStore = ProtectedStateStore(
        storage = DesktopDirectoryStateStorage(root),
        protector = WindowsDpapiStateProtector(),
    )
}
