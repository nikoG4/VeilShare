package dev.veilshare.core.vault

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Best-effort desktop durability primitive: force file bytes, then require same-filesystem atomic rename. */
internal object DesktopDurableFiles {
    fun writeAndForce(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val source = ByteBuffer.wrap(bytes)
            while (source.hasRemaining()) channel.write(source)
            channel.force(true)
        }
    }

    fun force(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    }

    fun replaceAtomic(temp: Path, target: Path) {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun promoteAtomic(temp: Path, target: Path) {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
    }
}
