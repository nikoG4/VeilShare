package dev.veilshare.core.securestore

/** Small deterministic binary codec for encrypted local sharing state. */
class BinaryStateWriter(initialCapacity: Int = 256) : AutoCloseable {
    private var buffer = ByteArray(initialCapacity.coerceAtLeast(32))
    private var size = 0
    private var closed = false

    fun writeInt(value: Int) {
        ensureOpen()
        ensureCapacity(4)
        buffer[size++] = (value ushr 24).toByte()
        buffer[size++] = (value ushr 16).toByte()
        buffer[size++] = (value ushr 8).toByte()
        buffer[size++] = value.toByte()
    }

    fun writeBytes(value: ByteArray, maxBytes: Int) {
        require(value.size <= maxBytes) { "Byte field exceeds max size" }
        writeInt(value.size)
        ensureCapacity(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    fun writeString(value: String, maxBytes: Int) {
        val encoded = value.encodeToByteArray()
        try {
            writeBytes(encoded, maxBytes)
        } finally {
            encoded.fill(0)
        }
    }

    fun writeNullableString(value: String?, maxBytes: Int) {
        if (value == null) {
            writeInt(-1)
            return
        }
        writeString(value, maxBytes)
    }

    fun toByteArray(): ByteArray {
        ensureOpen()
        return buffer.copyOf(size)
    }

    override fun close() {
        if (closed) return
        closed = true
        buffer.fill(0)
        size = 0
    }

    private fun ensureCapacity(additional: Int) {
        require(additional >= 0)
        val needed = size + additional
        require(needed >= size) { "Binary state size overflow" }
        if (needed <= buffer.size) return
        var next = buffer.size
        while (next < needed) {
            val grown = next.toLong() * 2L
            require(grown <= MAX_BUFFER_BYTES.toLong()) { "Binary state exceeds max buffer" }
            next = grown.toInt()
        }
        val replacement = buffer.copyOf(next)
        buffer.fill(0)
        buffer = replacement
    }

    private fun ensureOpen() {
        check(!closed) { "BinaryStateWriter is closed" }
    }

    private companion object {
        const val MAX_BUFFER_BYTES = 4 * 1024 * 1024
    }
}

class BinaryStateReader(private val bytes: ByteArray) {
    private var offset = 0

    fun readInt(): Int {
        requireRemaining(4)
        val value =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
        offset += 4
        return value
    }

    fun readBytes(maxBytes: Int): ByteArray {
        val length = readInt()
        require(length >= 0) { "Negative byte field length" }
        require(length <= maxBytes) { "Byte field exceeds max size" }
        requireRemaining(length)
        return bytes.copyOfRange(offset, offset + length).also { offset += length }
    }

    fun readString(maxBytes: Int): String {
        val encoded = readBytes(maxBytes)
        return try {
            encoded.decodeToString(throwOnInvalidSequence = true)
        } finally {
            encoded.fill(0)
        }
    }

    fun readNullableString(maxBytes: Int): String? {
        val saved = offset
        val length = readInt()
        if (length == -1) return null
        offset = saved
        return readString(maxBytes)
    }

    fun requireFinished() {
        require(offset == bytes.size) { "Unexpected trailing bytes in state record" }
    }

    private fun requireRemaining(count: Int) {
        require(count >= 0)
        require(offset <= bytes.size - count) { "Truncated binary state" }
    }
}
