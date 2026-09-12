package dev.veilshare.core.transfer

/**
 * JVM/Android file-backed transfer source.
 *
 * Kept out of commonMain so native targets do not acquire a java.io dependency.
 */
class FileTransferSource(
    private val file: java.io.File,
    override val displayName: String = file.name,
    override val mimeHint: String? = null,
) : TransferSource {
    private var randomAccessFile: java.io.RandomAccessFile? = null

    override val fileSize: Long = file.length()

    override suspend fun readChunk(offset: Long, size: Int): ByteArray {
        require(offset >= 0)
        require(size >= 0)
        if (size == 0) return ByteArray(0)

        val handle = randomAccessFile ?: java.io.RandomAccessFile(file, "r").also {
            randomAccessFile = it
        }
        handle.seek(offset)

        val buffer = ByteArray(size)
        var totalRead = 0
        while (totalRead < size) {
            val read = handle.read(buffer, totalRead, size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return if (totalRead == size) buffer else buffer.copyOf(totalRead)
    }

    override suspend fun close() {
        randomAccessFile?.close()
        randomAccessFile = null
    }
}
