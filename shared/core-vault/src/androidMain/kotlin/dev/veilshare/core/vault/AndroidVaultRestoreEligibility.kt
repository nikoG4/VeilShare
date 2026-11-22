package dev.veilshare.core.vault

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Read-only preflight for the restore entry point. This deliberately does not
 * call AndroidVaultStorage.privateRoot(), because that method creates the root.
 *
 * Passing this check is NOT authorization to promote: the restore coordinator
 * must recheck under its exclusive session/write lock and use a filesystem
 * operation with proven no-replace semantics. Files.move(ATOMIC_MOVE) alone
 * does not provide that guarantee on every provider.
 */
internal object AndroidVaultRestoreEligibility {
    fun isEmptyUninitializedRoot(root: File): Boolean {
        val path = root.toPath()
        if (Files.isSymbolicLink(path)) return false
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            // A symlinked ancestor could redirect the future destination.
            return ancestorsAreRealDirectories(root.parentFile)
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
        if (!ancestorsAreRealDirectories(root.parentFile)) return false
        return try {
            Files.newDirectoryStream(path).use { entries -> !entries.iterator().hasNext() }
        } catch (_: Exception) {
            false
        }
    }

    private fun ancestorsAreRealDirectories(parent: File?): Boolean {
        var current = parent
        while (current != null) {
            val path = current.toPath()
            if (Files.isSymbolicLink(path)) return false
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
            current = current.parentFile
        }
        return true
    }
}
