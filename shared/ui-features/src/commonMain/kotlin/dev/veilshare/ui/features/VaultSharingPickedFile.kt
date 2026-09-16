package dev.veilshare.ui.features

import dev.veilshare.core.vault.VaultHandle
import dev.veilshare.core.vault.VaultItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sharing source backed directly by the authenticated vault.
 *
 * Plaintext is never exported to disk. A tiny bounded in-memory channel bridges the
 * sequential VBL1 reader to Sharing V1's offset-based source contract. If the sender asks
 * to rewind, the producer is restarted and authenticated plaintext is discarded until the
 * requested offset.
 */
internal class VaultSharingPickedFile(
    private val vault: VaultHandle,
    private val file: VaultItem.File,
) : SharingPickedFile {
    override val displayName: String = file.displayName
    override val size: Long = file.size
    override val mimeType: String? = file.mimeType

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val readMutex = Mutex()
    private var producer: Job? = null
    private var chunks: Channel<ByteArray>? = null
    private var current = ByteArray(0)
    private var currentOffset = 0
    private var position = 0L
    private var closed = false

    override suspend fun readChunk(offset: Long, size: Int): ByteArray = readMutex.withLock {
        check(!closed) { "Vault sharing source is closed" }
        require(offset in 0..this.size) { "Invalid sharing read offset" }
        require(size > 0) { "Sharing read size must be positive" }
        if (offset == this.size) return@withLock ByteArray(0)

        if (chunks == null || offset < position) restartProducer()
        if (offset > position) discardUntil(offset)

        val requested = minOf(size.toLong(), this.size - offset).toInt()
        val output = ByteArray(requested)
        var written = 0
        while (written < requested) {
            if (currentOffset >= current.size) {
                wipeCurrent()
                current = receiveNextChunk() ?: break
                currentOffset = 0
            }
            val count = minOf(requested - written, current.size - currentOffset)
            current.copyInto(output, written, currentOffset, currentOffset + count)
            currentOffset += count
            written += count
            position += count
        }
        if (written == requested) output else output.copyOf(written).also { output.fill(0) }
    }

    private suspend fun discardUntil(target: Long) {
        while (position < target) {
            if (currentOffset >= current.size) {
                wipeCurrent()
                current = receiveNextChunk() ?: error("Vault file ended before requested offset")
                currentOffset = 0
            }
            val count = minOf((target - position).toIntSafe(), current.size - currentOffset)
            currentOffset += count
            position += count
        }
    }

    private suspend fun receiveNextChunk(): ByteArray? {
        val channel = chunks ?: return null
        return channel.receiveCatching().getOrNull()
    }

    private suspend fun restartProducer() {
        stopProducer()
        position = 0L
        currentOffset = 0
        current = ByteArray(0)
        val channel = Channel<ByteArray>(capacity = 2)
        chunks = channel
        producer = scope.launch {
            try {
                vault.readFile(file.id) { plain ->
                    try {
                        channel.send(plain)
                    } catch (cancelled: CancellationException) {
                        plain.fill(0)
                        throw cancelled
                    } catch (failure: Throwable) {
                        plain.fill(0)
                        throw failure
                    }
                }
            } finally {
                channel.close()
            }
        }
    }

    private suspend fun stopProducer() {
        producer?.cancelAndJoin()
        producer = null
        val channel = chunks
        chunks = null
        if (channel != null) {
            while (true) {
                val buffered = channel.tryReceive().getOrNull() ?: break
                buffered.fill(0)
            }
            channel.cancel()
        }
        wipeCurrent()
    }

    private fun wipeCurrent() {
        if (current.isNotEmpty()) current.fill(0)
        current = ByteArray(0)
        currentOffset = 0
    }

    override suspend fun close() {
        readMutex.withLock {
            if (closed) return@withLock
            closed = true
            stopProducer()
            scope.cancel()
        }
    }
}

private fun Long.toIntSafe(): Int = when {
    this <= 0L -> 0
    this >= Int.MAX_VALUE -> Int.MAX_VALUE
    else -> toInt()
}
