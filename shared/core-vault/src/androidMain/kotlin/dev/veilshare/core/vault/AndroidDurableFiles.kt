package dev.veilshare.core.vault

import android.system.Os
import java.io.File
import java.io.FileOutputStream

/** Android app-private primitive: sync file contents, then use same-filesystem POSIX rename. */
interface AndroidPersistenceOps {
    fun writeAndSync(file: File, bytes: ByteArray)
    fun sync(file: File)
    fun replace(temp: File, target: File)
}

object AndroidDurableFiles : AndroidPersistenceOps {
    override fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    override fun sync(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    override fun replace(temp: File, target: File) {
        Os.rename(temp.absolutePath, target.absolutePath)
    }
}
